package com.heima.item.canal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.heima.item.pojo.Item;
import com.heima.item.pojo.ItemStock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig {
    //商品的进程缓存 工具
    @Bean
    public Cache<Long,Item> itemCache(){
        return Caffeine.newBuilder().initialCapacity(100).
                maximumSize(10000).build();
    }

    //库存 的进程缓存工具
    @Bean
    public Cache<Long, ItemStock> itemStockCahe(){
        return Caffeine.newBuilder().initialCapacity(100).
                maximumSize(10000).build();
    }

}

