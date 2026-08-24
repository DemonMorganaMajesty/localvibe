package com.localvibe.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.localvibe.cache.CacheInvalidatePublisher;
import com.localvibe.cache.LocalCacheManager;
import com.localvibe.entity.Voucher;
import com.localvibe.mapper.VoucherMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

/**
 * Canal 缓存同步客户端
 *
 * 架构位置：MySQL(WSL, binlog) -> Canal(WSL, 端口11111, 实例 information) -> 本项目(本类)
 * 作用：监听 MySQL 数据变更，驱动多级缓存的失效与同步：
 *  1. 删除 Redis 中对应的缓存键（db6 商品 测试/库存键 + db7 业务键）
 *  2. 通过 Redis Pub/Sub 通知所有 Tomcat 进程失效 Caffeine 本地缓存
 *
 * 说明：canal 服务端版本 1.1.8（WSL 内已配置，本类只做客户端连接，不改动服务端配置）
 *
 * MySQL(WSL) binlog → Canal服务端(WSL:11111) → 当前Java客户端消费binlog →
 * 删除Redis缓存key + Redis‑Pub/Sub广播消息 → 所有服务实例收到消息，清除本机Caffeine本地缓存。
 */
@Slf4j
@Component
public class CanalCacheSyncClient implements InitializingBean, org.springframework.beans.factory.DisposableBean {

    // ================== canal 服务端连接参数（WSL 内 canal，配置已就绪） ==================
    private static final String CANAL_HOST = "172.24.116.171";
    private static final int CANAL_PORT = 11111;
    /** canal 实例名：information（对应 canal_manager 中配置的实例，监听 WSL MySQL 3307） */
    private static final String CANAL_DESTINATION = "information";
    /** 订阅全部库表，具体业务按表名在本类内分发 */
    private static final String CANAL_FILTER = ".*\\..*";

    /** 应用业务缓存 Redis（db6） */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** openresty 链路共用 Redis（db6，见 OpenRestyRedisConfig） */
    @Resource
    @Qualifier("openrestyRedisTemplate")
    private StringRedisTemplate openrestyRedisTemplate;

    @Resource
    private CacheInvalidatePublisher cacheInvalidatePublisher;

    @Resource
    private LocalCacheManager localCacheManager;

    @Resource
    private VoucherMapper voucherMapper;

    // Canal 重复投递时使用该前缀记录已处理的 binlog 事件，避免重复删除缓存
    private static final String CANAL_EVENT_PROCESSED_KEY = "canal:cache:event:processed:";
    private static final long CANAL_EVENT_MARK_TTL_DAYS = 7L;

    //volatile 保证多线程可见性 , 和同步锁类似 但是不能保证原子性
    private volatile boolean running = true;
    private Thread workerThread;

    //bean 初始化完成，启动独立守护线程消费 canal 消息，不阻塞 Tomcat 主线程。
    @Override
    public void afterPropertiesSet() {
        //线程命名canal‑cache‑sync‑thread
        workerThread = new Thread(this::run, "canal-cache-sync-thread");
        //setDaemon(true) 设置为守护线程：如果主线程 tomcat 退出，这个线程自动跟着结束，不会阻止进程关闭。
        workerThread.setDaemon(true);
        //启动线程，开始消费 binlog；不会阻塞 SpringBoot 启动流程。
        workerThread.start();
    }

    // 容器销毁，关闭消费线程，优雅停止 canal 客户端。
    @Override
    public void destroy() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    /**
     * canal 消费主循环（断线自动重连，不影响应用启动）
     */
    private void run() {
        //断线自动重连
        while (running) {
            CanalConnector connector = null;
            try {
                //创建连接 创建单机canal 客户端连接，无用户名密码。
                connector = CanalConnectors.newSingleConnector(
                        // ip port 数据库
                        new InetSocketAddress(CANAL_HOST, CANAL_PORT),
                        CANAL_DESTINATION, "", "");
                connector.connect();
                //subscribe()订阅过滤规则。
                connector.subscribe(CANAL_FILTER);
                //rollback()回滚 offset，从头读取未 ack 的数据。
                connector.rollback();
                log.info("Canal 缓存同步客户端连接成功: {}:{}, destination={}",
                        CANAL_HOST, CANAL_PORT, CANAL_DESTINATION);

                while (running) {
                    /* 拉取一批 binlog，不会自动提交 offset。
                如果程序处理中途崩溃，没有调用ack(batchId)；重连之后，会重新拿到这一批 binlog，
                保证消息不丢失，但会带来重复消费问题。
                     */
                    Message message = connector.getWithoutAck(100);
                    long batchId = message.getId();
                    if (batchId == -1 || message.getEntries().isEmpty()) {
                        Thread.sleep(500);
                        continue;
                    }
                    //处理这批binlog数据
                    processEntries(message.getEntries());
                    //业务处理完成，手动ack，告诉canal服务端这批消息消费完成
                    connector.ack(batchId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                //任何异常，关闭连接，sleep10秒，外层while自动重连
                log.warn("Canal 客户端运行异常，10 秒后重连: {}", e.getMessage());
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } finally {
                //断开连接
                if (connector != null) {
                    try {
                        connector.disconnect();
                    } catch (Exception ignore) {
                        // 忽略断开异常
                    }
                }
            }
        }
    }

    /**
     * 处理一批 binlog 变更条目，按表名分发到对应的缓存失效逻辑
     */
    private void processEntries(List<CanalEntry.Entry> entries) {
        for (CanalEntry.Entry entry : entries) {
            // 只处理行数据变更 有这个类型才代表真实表数据增删改，忽略事务/心跳等条目
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) {
                continue;
            }
            CanalEntry.RowChange rowChange;
            try {
                //protobuf二进制字节数组 反序列化，解析行变更数据
                rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            } catch (Exception e) {
                log.error("解析 canal 变更数据失败", e);
                continue;
            }
            //拿到库名、表名、事件类型 INSERT / UPDATE / DELETE
            String schema = entry.getHeader().getSchemaName();
            String table = entry.getHeader().getTableName();
            CanalEntry.EventType eventType = rowChange.getEventType();
            //rowData 含beforeColumnsList修改前数据，afterColumnsList修改后数据。
            int rowIndex = 0;
            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                // Canal 在网络重连或 ack 丢失后可能重复投递同一批 binlog，先做事件级幂等判断
                if (markEventProcessed(entry, rowIndex++)) {
                    dispatch(schema, table, eventType, rowData);
                }
            }
        }
    }

    /**
     * 使用 Canal 日志文件名、日志位点和行号生成幂等键。
     * 同一 binlog 行重复消费时只执行一次缓存失效，避免无效 Redis/Caffeine IO。
     */
    private boolean markEventProcessed(CanalEntry.Entry entry, int rowIndex) {
        String eventKey = CANAL_EVENT_PROCESSED_KEY
                + entry.getHeader().getLogfileName() + ":"
                + entry.getHeader().getLogfileOffset() + ":" + rowIndex;
        Boolean first = stringRedisTemplate.opsForValue().setIfAbsent(
                eventKey, "1", CANAL_EVENT_MARK_TTL_DAYS, TimeUnit.DAYS);
        return Boolean.TRUE.equals(first);
    }

    /**
     * 表名 -> 缓存失效动作 的分发（新增/修改/删除统一按"数据已变化"处理）
     * 循环每一行，交给dispatch()按表分发。
     */
    private void dispatch(String schema, String table, CanalEntry.EventType eventType,
                          CanalEntry.RowData rowData) {
        try {
            switch (table) {
                case "tb_item" -> {
                    // 商品变更：失效 openresty 链路的 item:id:{id}（Redis db6）+ Caffeine
                    String id = getColumnValue(rowData, "id");
                    if (id != null) {
                        invalidateItem(id);
                    }
                }
                case "tb_item_stock" -> {
                    // 库存变更：失效 stock:id:{item_id}（Redis db6）+ Caffeine
                    String itemId = getColumnValue(rowData, "item_id");
                    if (itemId != null) {
                        invalidateStock(itemId);
                    }
                }
                case "tb_shop" -> {
                    // 店铺变更：失效 cache:shop:id{id}（Redis db7）+ Caffeine
                    String id = getColumnValue(rowData, "id");
                    if (id != null) {
                        invalidateShop(id);
                    }
                }
                case "tb_shop_type" -> {
                    // 店铺分类变更：失效全部分类缓存
                    invalidateShopType();
                }
                case "tb_voucher", "tb_seckill_voucher" -> {
                    // 优惠券变更只失效所属店铺列表，避免全量清空造成缓存雪崩
                    String shopId = getVoucherShopId(table, rowData);
                    if (shopId != null) {
                        invalidateVoucherShop(shopId);
                    } else {
                        // 无法解析关联店铺时才使用前缀兜底，保证缓存最终能够失效
                        localCacheManager.getVoucherCache().invalidateAll();
                        cacheInvalidatePublisher.publish("voucher:shop:*");
                    }
                }
                case "tb_user", "tb_user_info" -> {
                    /*
                     * 用户资料、积分和签到等正常业务也会更新 tb_user/tb_user_info。
                     * 这些业务接口会主动刷新 Redis 登录态，Canal 无法仅凭 binlog 区分
                     * “业务写库”和“外部直接改库”，因此这里不能无条件删除 token，
                     * 否则用户每次保存资料、签到成功后，下一次请求都会被强制要求重新登录。
                     * 直接改库造成的登录态短暂不一致交给 token TTL 兜底，真正登出由 /user/logout 负责。
                     */
                    log.debug("canal 用户表变更: {}.{}，保留当前 Redis 登录态，避免误踢已登录用户", schema, table);
                }
                case "tb_blog", "tb_blog_comments", "tb_follow", "tb_voucher_order", "tb_sign" -> {
                    // 笔记/评论/关注/订单/签到等表：不参与多级缓存 L1/L2 主动失效，仅记录调试日志
                    log.trace("canal 业务表变更(无需失效缓存): {}.{}", schema, table);
                }
                default -> log.trace("canal 未匹配的业务表: {}.{}", schema, table);
            }
        } catch (Exception e) {
            log.error("canal 缓存同步处理异常: {}.{}", schema, table, e);
        }
    }

    // ==================== 各表缓存失效逻辑 ====================

    /** 失效商品缓存：Redis db6 item:id:{id} + 本地 Caffeine + 进程间通知
     *
     * 删除 Redis 远程缓存 key。
     * 删除当前这台机器的 Caffeine 本地缓存。
     * Redis 发布消息；项目中其他所有实例订阅这个 channel，收到消息后清除本机 Caffeine。
     * */
    private void invalidateItem(String id) {
        String key = "item:id:" + id;
        openrestyRedisTemplate.delete(key);
        localCacheManager.getItemCache().invalidate(key);
        cacheInvalidatePublisher.publish(key);
        log.info("canal 失效商品缓存: {}", key);
    }

    /** 失效库存缓存：Redis db6 stock:id:{itemId} + 本地 Caffeine + 进程间通知 */
    private void invalidateStock(String itemId) {
        String key = "stock:id:" + itemId;
        openrestyRedisTemplate.delete(key);
        localCacheManager.getStockCache().invalidate(key);
        cacheInvalidatePublisher.publish(key);
        log.info("canal 失效库存缓存: {}", key);
    }

    /** 失效店铺缓存：Redis db6 cache:shop:id{id} + 本地 Caffeine + 进程间通知
     *
     * 删除 Redis 远程缓存 key。
     * 删除当前这台机器的 Caffeine 本地缓存。
     * Redis 发布消息；项目中其他所有实例订阅这个 channel，收到消息后清除本机 Caffeine。
     * */
    private void invalidateShop(String id) {
        String key = "cache:shop:id" + id;
        stringRedisTemplate.delete(key);
        localCacheManager.getShopCache().invalidate(key);
        cacheInvalidatePublisher.publish(key);
        log.info("canal 失效店铺缓存: {}", key);
    }

    /** 失效店铺分类缓存（Redis db6 三种结构 + 本地 Caffeine + 进程间通知） */
    private void invalidateShopType() {
        String prefix = "cache:shopType:";
        stringRedisTemplate.delete(prefix + "string");
        stringRedisTemplate.delete(prefix + "list");
        stringRedisTemplate.delete(prefix + "hash");
        localCacheManager.getShopTypeCache().invalidateAll();
        cacheInvalidatePublisher.publish("cache:shopType:*");
        log.info("canal 失效店铺分类缓存");
    }

    /** 根据优惠券变更行解析所属店铺 id，秒杀券通过 voucher_id 查询主券表 */
    private String getVoucherShopId(String table, CanalEntry.RowData rowData) {
        if ("tb_voucher".equals(table)) {
            return getColumnValue(rowData, "shop_id");
        }
        String voucherId = getColumnValue(rowData, "voucher_id");
        if (voucherId == null) {
            return null;
        }
        Voucher voucher = voucherMapper.selectById(Long.valueOf(voucherId));
        return voucher == null || voucher.getShopId() == null ? null : voucher.getShopId().toString();
    }

    /** 精准失效某个店铺的优惠券列表缓存 */
    private void invalidateVoucherShop(String shopId) {
        String key = "voucher:shop:" + shopId;
        localCacheManager.getVoucherCache().invalidate(key);
        cacheInvalidatePublisher.publish(key);
        log.info("canal 精准失效店铺优惠券缓存: {}", key);
    }

    /**
     * 删除直接修改用户表后仍存在的登录态 token。
     * 登录态没有按 userId 建立反向索引，因此这里使用 Redis SCAN，避免 KEYS 阻塞 Redis。
     */
    private void invalidateUserTokens(String userId) {
        if (userId == null) {
            return;
        }
        stringRedisTemplate.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match("login:userToken:*").count(100).build())
                .forEachRemaining(key -> {
                    Object id = stringRedisTemplate.opsForHash().get(key, "id");
                    if (userId.equals(id)) {
                        stringRedisTemplate.delete(key);
                    }
                });
        log.info("canal 失效用户登录态: userId={}", userId);
    }

    /**
     * 从变更行中取指定列的值（优先取变更后的值，便于拿到最新 id）
     * 优先拿变更后 after 的值；拿不到，就拿 before 的值。
     */
    private String getColumnValue(CanalEntry.RowData rowData, String column) {
        Map<String, String> after = toColumnMap(rowData.getAfterColumnsList());
        if (after.containsKey(column)) {
            return after.get(column);
        }
        return toColumnMap(rowData.getBeforeColumnsList()).get(column);
    }

    private Map<String, String> toColumnMap(List<CanalEntry.Column> columns) {
        return columns.stream()
                // canal 1.1.8 的 Column 无 isValid 字段，仅过滤空列名即可
                .filter(column -> column.getName() != null && !column.getName().isEmpty())
                .collect(Collectors.toMap(CanalEntry.Column::getName, CanalEntry.Column::getValue));
    }
}
