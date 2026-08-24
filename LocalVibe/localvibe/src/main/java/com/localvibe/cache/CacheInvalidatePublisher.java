package com.localvibe.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 缓存失效消息发布器（Redis Pub/Sub） 只删除 caffeine
 *
 * 用途：当一个 Tomcat 进程（或 canal 监听）发现数据变更后，
 * 发布一条包含缓存键的消息，其他 Tomcat(本地多port部署) 进程收到后同步失效各自的
 * Caffeine 本地缓存，实现 JVM 进程间的缓存一致性。
 */
@Slf4j
@Component
public class CacheInvalidatePublisher {

    /**
     * 缓存失效通知频道
     */
    public static final String CACHE_INVALIDATE_CHANNEL = "localvibe:cache:invalidate";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发布一条缓存失效消息
     *
     * @param cacheKey 需要失效的缓存键（与 Caffeine/Redis 中的键一致）
     */
    public void publish(String cacheKey) {
        try {
            stringRedisTemplate.convertAndSend(CACHE_INVALIDATE_CHANNEL, cacheKey);
            log.debug("已发布缓存失效消息, key: {}", cacheKey);
        } catch (Exception e) {
            // Redis 异常不影响主流程，仅记录日志（本进程已自行失效）
            log.warn("发布缓存失效消息失败, key: {}", cacheKey, e);
        }
    }
}
