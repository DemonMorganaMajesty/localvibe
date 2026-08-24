package com.localvibe.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.localvibe.cache.CacheInvalidatePublisher;
import com.localvibe.cache.LocalCacheManager;
import com.localvibe.dto.Result;
import com.localvibe.entity.Item;
import com.localvibe.entity.ItemStock;
import com.localvibe.mapper.ItemMapper;
import com.localvibe.mapper.ItemStockMapper;
import com.localvibe.service.IItemService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 商品服务实现（多级缓存核心链路）
 * </p>
 *
 * 多级缓存读取顺序（对应 openresty lua 中 item:id: / stock:id: 键）：
 * L1 Caffeine（JVM 本地，进程内）
 *  -> L2 Redis db6（与 openresty 共用，跨进程）
 *  -> L3 MySQL（最终数据源）
 *
 * 数据变更时的缓存一致性：
 * 1. 本服务更新时：删除 Redis 键 + 失效本地 Caffeine + Redis Pub/Sub 通知其他进程
 * 2. canal 监听 binlog：删除 Redis 键 + 通知所有进程失效 Caffeine（见 CanalCacheSyncClient）
 * 3. tb_item_change_log 定时任务：canal 异常时的兜底同步（见 ItemChangeLogSyncTask）
 *
 * @author 改造新增
 */
@Slf4j
@Service
public class ItemServiceImpl extends ServiceImpl<ItemMapper, Item> implements IItemService {

    /** openresty lua 约定缓存前缀（与 WSL 中 item.lua 保持一致） */
    private static final String ITEM_CACHE_KEY_PREFIX = "item:id:";
    private static final String STOCK_CACHE_KEY_PREFIX = "stock:id:";

    /** Redis L2 过期时间：商品 30 分钟，库存 10 分钟（canal 会主动失效，TTL 仅兜底） */
    private static final long ITEM_REDIS_TTL_SECONDS = 30 * 60;
    private static final long STOCK_REDIS_TTL_SECONDS = 10 * 60;

    @Resource
    private ItemStockMapper itemStockMapper;

    @Resource
    private LocalCacheManager localCacheManager;

    @Resource
    private CacheInvalidatePublisher cacheInvalidatePublisher;

    /** openresty 链路共用 Redis（db6） */
    @Resource
    @Qualifier("openrestyRedisTemplate")
    private StringRedisTemplate openrestyRedisTemplate;

    /** tb_item_change_log 兜底同步用 */
    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public Result queryItemById(Long id) {
        String key = ITEM_CACHE_KEY_PREFIX + id;

        // L1：Caffeine 本地缓存
        String json = localCacheManager.getItemCache().getIfPresent(key);
        if (StrUtil.isNotBlank(json)) {
            return Result.success(JSONUtil.toBean(json, Item.class));
        }

        // L2：Redis db6（与 openresty lua 共用，键名一致）
        json = openrestyRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            // 回填 L1，供后续请求命中 JVM 缓存
            localCacheManager.getItemCache().put(key, json);
            return Result.success(JSONUtil.toBean(json, Item.class));
        }

        // L3：MySQL（最终数据源）
        Item item = getById(id);
        if (item == null) {
            return Result.fail("商品不存在");
        }
        // 写回 L2 + L1，形成完整缓存链路
        json = JSONUtil.toJsonStr(item);
        openrestyRedisTemplate.opsForValue().set(key, json, ITEM_REDIS_TTL_SECONDS, TimeUnit.SECONDS);
        localCacheManager.getItemCache().put(key, json);
        return Result.success(item);
    }

    @Override
    public Result queryItemStockById(Long id) {
        String key = STOCK_CACHE_KEY_PREFIX + id;

        // L1：Caffeine 本地缓存
        String json = localCacheManager.getStockCache().getIfPresent(key);
        if (StrUtil.isNotBlank(json)) {
            return Result.success(JSONUtil.toBean(json, ItemStock.class));
        }

        // L2：Redis db6
        json = openrestyRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            localCacheManager.getStockCache().put(key, json);
            return Result.success(JSONUtil.toBean(json, ItemStock.class));
        }

        // L3：MySQL
        ItemStock itemStock = itemStockMapper.selectById(id);
        if (itemStock == null) {
            return Result.fail("商品库存不存在");
        }
        json = JSONUtil.toJsonStr(itemStock);
        openrestyRedisTemplate.opsForValue().set(key, json, STOCK_REDIS_TTL_SECONDS, TimeUnit.SECONDS);
        localCacheManager.getStockCache().put(key, json);
        return Result.success(itemStock);
    }

    @Override
    @Transactional
    public Result updateItem(Item item) {
        if (item == null || item.getId() == null) {
            return Result.fail("商品id不能为空,更新失败");
        }
        // 1.先更新数据库（多级缓存场景下数据库永远是最终数据源）
        updateById(item);

        // 2.记录变更日志，供定时任务兜底同步缓存（canal 异常时的保障）
        //   容错：变更日志表未创建时不阻断商品更新，缓存失效仍照常执行
        try {
            jdbcTemplate.update(
                    "insert into tb_item_change_log(item_id, change_type, is_processed) values(?, 'UPDATE', 0)",
                    item.getId());
        } catch (Exception e) {
            log.debug("变更日志表 tb_item_change_log 未就绪，跳过日志记录（不影响主流程）: {}", e.getMessage());
        }

        // 3.失效各级缓存（Redis L2 + 本地 L1 + 进程间通知）
        invalidateItemCache(item.getId());
        return Result.success("修改成功");
    }

    /**
     * 失效商品+库存缓存（删除 Redis、失效本地 Caffeine、通知其他进程）
     */
    private void invalidateItemCache(Long id) {
        String itemKey = ITEM_CACHE_KEY_PREFIX + id;
        String stockKey = STOCK_CACHE_KEY_PREFIX + id;

        openrestyRedisTemplate.delete(itemKey);
        openrestyRedisTemplate.delete(stockKey);

        localCacheManager.getItemCache().invalidate(itemKey);
        localCacheManager.getStockCache().invalidate(stockKey);

        cacheInvalidatePublisher.publish(itemKey);
        cacheInvalidatePublisher.publish(stockKey);
        log.info("商品变更，已失效多级缓存: itemKey={}, stockKey={}", itemKey, stockKey);
    }
}
