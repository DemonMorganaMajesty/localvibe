package com.localvibe.service.impl;

import com.localvibe.cache.CacheInvalidatePublisher;
import com.localvibe.cache.LocalCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 商品变更日志兜底同步任务
 *
 * 背景：canal 负责监听 binlog 驱动缓存失效，但如果 canal 客户端短暂不可用，
 * 变更日志表 tb_item_change_log 中未处理(is_processed=0)的记录就由本任务兜底补偿。
 * 每 30 秒扫描一次未处理日志，失效对应商品/库存缓存后标记已处理。
 *
 * @author 改造新增
 */
@Slf4j
@Component
public class ItemChangeLogSyncTask {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private LocalCacheManager localCacheManager;

    @Resource
    private CacheInvalidatePublisher cacheInvalidatePublisher;

    @Resource
    @Qualifier("openrestyRedisTemplate")
    private StringRedisTemplate openrestyRedisTemplate;

    /** 变更日志表是否存在（首次查询后缓存，避免每 30 秒重复报错刷屏） */
    private volatile Boolean tableExists;

    /**
     * 检查变更日志表是否存在（懒加载，表缺失时只提示一次）
     */
    private boolean checkTableExists() {
        if (tableExists != null) {
            return tableExists;
        }
        try {
            // 通过 JDBC 元数据判断表是否存在，不依赖具体数据库方言
            boolean exists = false;
            try (var connection = jdbcTemplate.getDataSource().getConnection();
                 var rs = connection.getMetaData().getTables(null, null, "tb_item_change_log", null)) {
                exists = rs.next();
            }
            tableExists = exists;
            if (!exists) {
                // 表不存在时仅提示一次，避免每 30 秒刷屏（建表后重启应用即可生效）
                log.warn("变更日志表 tb_item_change_log 不存在，兜底同步任务已跳过（可执行 resources/db 下的建表 SQL 后重启）");
            }
        } catch (Exception e) {
            tableExists = false;
            log.warn("检查 tb_item_change_log 表失败，按表不存在处理: {}", e.getMessage());
        }
        return tableExists;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void syncUnprocessedChangeLogs() {
        try {
            // 表不存在时直接跳过，避免持续报错刷屏
            if (!checkTableExists()) {
                return;
            }
            // 每次最多取 200 条未处理日志，避免一次处理过多
            List<Map<String, Object>> logs = jdbcTemplate.queryForList(
                    "select id, item_id from tb_item_change_log where is_processed = 0 limit 200");
            if (logs.isEmpty()) {
                return;
            }
            for (Map<String, Object> logRow : logs) {
                Long logId = ((Number) logRow.get("id")).longValue();
                Long itemId = ((Number) logRow.get("item_id")).longValue();
                String itemKey = "item:id:" + itemId;
                String stockKey = "stock:id:" + itemId;

                // 失效 Redis db6（openresty 链路）与本地 Caffeine，并通知其他进程
                openrestyRedisTemplate.delete(itemKey);
                openrestyRedisTemplate.delete(stockKey);
                localCacheManager.getItemCache().invalidate(itemKey);
                localCacheManager.getStockCache().invalidate(stockKey);
                cacheInvalidatePublisher.publish(itemKey);
                cacheInvalidatePublisher.publish(stockKey);

                // 标记已处理
                jdbcTemplate.update("update tb_item_change_log set is_processed = 1 where id = ?", logId);
            }
            log.info("变更日志兜底同步完成, 处理条数: {}", logs.size());
        } catch (Exception e) {
            log.warn("变更日志兜底同步异常（表不存在或连接问题，不影响主流程）: {}", e.getMessage());
        }
    }
}
