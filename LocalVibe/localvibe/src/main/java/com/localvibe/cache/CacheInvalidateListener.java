package com.localvibe.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;

/**
 * 缓存失效消息监听器（Redis Pub/Sub）
 *
 * 收到其他 Tomcat 进程（或 canal 同步客户端）发布的缓存失效消息后，
 * 失效本进程 Caffeine 本地缓存中对应的键，保证多实例部署下的缓存一致性。
 *
 * Redis Pub/Sub 消息订阅监听器，多级缓存架构里用来解决多实例本地缓存一致性问题。
 * 你的项目缓存链路：OpenResty(Nginx Lua缓存) → Caffeine(JVM本地缓存) → Redis → MySQL + Canal
 */
@Slf4j
@Component
public class CacheInvalidateListener implements MessageListener {

    @Resource
    private LocalCacheManager localCacheManager;

    //本地部署的 多台tomcat/ 多个端口都会收到消息 从而删除 caffeine本地缓存 避免脏数据
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String cacheKey = new String(message.getBody(), StandardCharsets.UTF_8);
        log.debug("收到缓存失效消息, key: {}", cacheKey);
        if (cacheKey.endsWith("*")) {
            /*前缀失效消息（如 cache:shopType:* / voucher:shop:*）：按前缀失效所有本地缓存
            删除 Caffeine 里面所有以此开头的 key。适合批量更新一类数据。
             */
            localCacheManager.invalidateByPrefix(cacheKey.substring(0, cacheKey.length() - 1));
        } else {
            localCacheManager.invalidateAll(cacheKey);
        }
    }
}
