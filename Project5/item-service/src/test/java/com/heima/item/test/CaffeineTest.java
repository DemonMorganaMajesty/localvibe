package com.heima.item.test;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;

import java.time.Duration;

public class CaffeineTest {

    /*
      基本用法测试
     */
    @Test
    void testBasicOps() {
        // 创建缓存对象
        Cache<String, String> cache = Caffeine.newBuilder().build();

        // 存数据
        cache.put("girlFriend", "迪丽热巴");

        // 取数据，不存在则返回null
        String girlFriend = cache.getIfPresent("girlFriend");
        System.out.println("girlFriend =" + girlFriend);

        // 取数据，不存在则去数据库查询 传递的是函数
        String defaultGirlFriend = cache.get("defaultGirlfriend", key -> {
            // 这里可以去数据库根据 key查询value (直接返回模拟查询)
            return "柳岩";
        });
        System.out.println("defaultGirlFriend = " + defaultGirlFriend);
    }

    /*
     基于大小设置驱逐策略： 缓存上限是1个
     */
    @Test
    void testEvictByNum() throws InterruptedException {
        // 创建缓存对象
        Cache<String, String> cache = Caffeine.newBuilder()
                // 设置缓存大小上限为 1
                .maximumSize(1)
                .build();
        // 存数据
        cache.put("girlFriend1", "柳岩");
        cache.put("girlFriend2", "范冰冰");
        cache.put("girlFriend3", "迪丽热巴");
        // 延迟10ms，给清理缓存线程一点时间
        Thread.sleep(10L);
        // 获取数据
        System.out.println("girlFriend1: " + cache.getIfPresent("girlFriend1"));
        System.out.println("girlFriend2: " + cache.getIfPresent("girlFriend2"));
        System.out.println("girlFriend3: " + cache.getIfPresent("girlFriend3"));
    }

    /*
     基于时间设置驱逐策略： 时间一过清理
     */
    @Test
    void testEvictByTime() throws InterruptedException {
        // 创建缓存对象
        Cache<String, String> cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(1)) // 设置缓存有效期为 10 秒
                .build();
        // 存数据
        cache.put("girlFriend", "柳岩");
        // 获取数据
        System.out.println("girlFriend: " + cache.getIfPresent("girlFriend"));
        // 休眠一会儿
        Thread.sleep(1200L);
        System.out.println("girlFriend: " + cache.getIfPresent("girlFriend"));
    }
}
