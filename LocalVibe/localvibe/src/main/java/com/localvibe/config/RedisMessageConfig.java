package com.localvibe.config;

import com.localvibe.cache.CacheInvalidateListener;
import com.localvibe.cache.CacheInvalidatePublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis 消息监听配置（缓存失效同步用）
 *
 * 注册一个监听 localvibe:cache:invalidate 频道的容器，
 * 实现多 Tomcat 进程间 Caffeine 本地缓存的同步失效（配合 CanalClient 使用）。
 */
@Configuration
public class RedisMessageConfig {

    @Bean
    public RedisMessageListenerContainer cacheInvalidateListenerContainer(
            RedisConnectionFactory connectionFactory,
            CacheInvalidateListener cacheInvalidateListener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(cacheInvalidateListener,
                new ChannelTopic(CacheInvalidatePublisher.CACHE_INVALIDATE_CHANNEL));
        return container;
    }
}
