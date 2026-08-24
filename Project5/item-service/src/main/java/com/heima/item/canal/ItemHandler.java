package com.heima.item.canal;

import com.github.benmanes.caffeine.cache.Cache;
import com.heima.item.config.RedisHandler;
import com.heima.item.pojo.Item;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.A;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.javatool.canal.client.annotation.CanalTable;
import top.javatool.canal.client.handler.EntryHandler;

//监控哪一张表
//@CanalTable("tb_item")
@Component
@Slf4j
//数据库增删改 相应的数据就会传递到这里 实现监控
public class ItemHandler implements EntryHandler<Item> {
    /*redisHandler 封装了redis缓存 stringRedis的函数
        操作redis缓存
          */
    @Autowired
    RedisHandler redisHandler;
    //操作 jvm缓存
    @Autowired
    private Cache<Long,Item> itemCache;

    @PostConstruct
    public void init() {
        log.info("=== Canal ItemHandler 已初始化 ===");
    }

    //添加
    @Override
    public void insert(Item item) {
        //写数据到缓存 jvm缓存和redis缓存 不负责nginx的本地缓存
        log.info("=== Canal insert 触发, item={} ===", item);
        //jvm缓存
        itemCache.put(item.getId(),item);

        /*redisHandler 封装了redis缓存 stringRedis的函数
        操作redis缓存
         */
        redisHandler.saveItem(item);
    }
    //删除
    @Override
    public void delete(Item item) {
        //jvm缓存
        itemCache.invalidate(item.getId());
        log.info("=== Canal delete 触发, item={} ===", item);
        //redis
        redisHandler.deleteItem(item.getId());
    }
    //更新
    @Override
    public void update(Item itemBefore, Item itemAfter) {
        //jvm缓存
        itemCache.put(itemAfter.getId(),itemAfter);
        log.info("=== Canal update 触发, item={} ===", itemAfter);
        //redis
        redisHandler.saveItem(itemAfter);
    }
}
