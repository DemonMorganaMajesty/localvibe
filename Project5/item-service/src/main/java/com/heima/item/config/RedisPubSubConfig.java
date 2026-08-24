package com.heima.item.config;


import com.github.benmanes.caffeine.cache.Cache;
import com.heima.item.pojo.Item;
import com.heima.item.service.IItemService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RedisPubSubConfig {
    @Autowired
    private Cache<Long, Item> itemCache;

    @Autowired
    private IItemService itemService;

    @Autowired
    private RedisHandler redisHandler;

    @Bean
    public RedisMessageListenerContainer redisContainer(RedisConnectionFactory connectionFactory) {
        //spring 提供的redis 消息监听的容器负责订阅频道、接收消息
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        //redis工厂连接,自动的注入
        container.setConnectionFactory(connectionFactory);

        /*订阅 item.update 频道
        PatternTopic spring Data Redis 提供的类，表示要订阅的 Redis 频道
        new PatternTopic("item.update") 订阅更新的频道
         */
        container.addMessageListener((message, pattern) -> {
            //getBody()得到是 序列化好的byte[]消息内容 getChannel()频道名字
            String id = new String(message.getBody());
            log.info("=== 收到 Redis 消息 [update]，更新 JVM 缓存，id={} ===", id);

            //查数据库最新数据 状态不为3(只有1 /2) 根据id查  one查一条
            Item item = itemService.query().ne("status", 3).
                    eq("id", Long.valueOf(id)).one();
            if (item != null) {
                // 更新 JVM 缓存
                itemCache.put(item.getId(), item);
                // 更新 Redis 缓存
                redisHandler.saveItem(item);
                log.info("=== JVM 缓存和 Redis 缓存已更新，id={} ===", id);
            }
        }, new PatternTopic("item.update"));

        // 订阅 item.delete 频道
        container.addMessageListener((message, pattern) -> {
            String id = new String(message.getBody());
            log.info("=== 收到 Redis 消息 [delete]，删除 JVM 缓存，id={} ===", id);

            // 删除 JVM 缓存
            itemCache.invalidate(Long.valueOf(id));
            //删除 Redis 缓存
            redisHandler.deleteItem(Long.valueOf(id));
        }, new PatternTopic("item.delete"));

        // 订阅item.insert 频道
        container.addMessageListener((message, pattern) -> {
            String id = new String(message.getBody());
            log.info("=== 收到 Redis 消息 [insert]，新增 JVM 缓存，id={} ===", id);

            // 查数据库
            Item item = itemService.query().ne("status", 3).eq("id", Long.valueOf(id)).one();
            if (item != null) {
                // 新增 JVM 缓存
                itemCache.put(item.getId(), item);
                // 新增 Redis 缓存
                redisHandler.saveItem(item);
            }
        }, new PatternTopic("item.insert"));

        return container;
    }
}
