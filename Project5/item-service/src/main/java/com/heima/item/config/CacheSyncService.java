package com.heima.item.config;

import com.heima.item.pojo.Item;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CacheSyncService {

    //实现发消息的接口 可以不要
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 双写统一入口：写数据库后，必须调这个发消息同步缓存
     *
     * @param id   商品id
     * @param type 操作类型：insert/update/delete
     */
    public void sync(Long id, String type) {
        String topic = "item." + type.toLowerCase();
        stringRedisTemplate.convertAndSend(topic, String.valueOf(id));
        log.info("【双写同步】发送 Redis 消息 [{}], id={}", topic, id);
    }
}