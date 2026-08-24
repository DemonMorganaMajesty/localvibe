package com.heima.item.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ChangeLogPoller {
    //spring 管理的jdbc工具 实现sql语句的书写 数据库的操作
    @Autowired
    private JdbcTemplate jdbcTemplate;
    //redis
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //定时任务 每隔3s 秒执行一次
    @Scheduled(fixedRate = 3000)
    public void poll() {
        // 查未处理的变更(),is_processed = 0 每次50条
        List<Map<String, Object>> logs = jdbcTemplate.queryForList(
                "SELECT id, item_id, change_type FROM tb_item_change_log " +
                        "WHERE is_processed = 0 LIMIT 50"
        );
        //没有未处理的数据 直接返回
        if(logs==null||logs.isEmpty())
            return;

        //处理数据
        for (Map<String, Object> record : logs) {
            /*集合record 的k v 就是一个字段+数据 Number是数据类型:所有数字的父类
            longValue()是将Object 转化为Long 类型  也可直接转化为Long 就不用这个函数了
             */
            Long logId = ((Number) record.get("id")).longValue();
            Long itemId = ((Number) record.get("item_id")).longValue();
            String type = (String) record.get("change_type");


            //给redis发消息，走你现有的 Redis 订阅逻辑
            stringRedisTemplate.convertAndSend("item." + type.toLowerCase(), String.valueOf(itemId));

            //标记已处理
            jdbcTemplate.update("UPDATE tb_item_change_log SET is_processed = 1 WHERE id = ?", logId);

            //记录日志
            log.info("【触发器兜底】{} id={}", type, itemId);
        }
    }
}