package com.localvibe.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        //创建配置
        Config config=new Config();
        //配置地址
        config.useSingleServer().setAddress("redis://172.24.116.171:6379")
                //.setAddress("redis://127.0.0.1:6379")
                .setPassword("Cjx200621")
                .setSslEnableEndpointIdentification(false)
                .setConnectTimeout(3000); // 3秒连不上直接报错，不卡死;

        //创建RedissionClient 对象
        return Redisson.create(config);
    }

    @Bean
    public RedissonClient redissonClient_6380(){
        //创建配置
        Config config=new Config();
        //配置地址
        config.useSingleServer().setAddress("redis://172.24.116.171:6380")
                .setPassword("Cjx200621")
                .setSslEnableEndpointIdentification(false)
                .setConnectTimeout(3000); // 3秒连不上直接报错，不卡死;

        //创建RedissionClient 对象
        return Redisson.create(config);
    }
    @Bean
    public RedissonClient redissonClient_6381(){
        //创建配置
        Config config=new Config();
        //配置地址
        config.useSingleServer().setAddress("redis://172.24.116.171:6381")
                .setPassword("Cjx200621")
                .setSslEnableEndpointIdentification(false)
                .setConnectTimeout(3000); // 3秒连不上直接报错，不卡死;

        //创建RedissionClient 对象
        return Redisson.create(config);
    }
    @Bean
    public RedissonClient redissonClient_6382(){
        //创建配置
        Config config=new Config();
        //配置地址
        config.useSingleServer().setAddress("redis://172.24.116.171:6382")
                .setPassword("Cjx200621")
                .setSslEnableEndpointIdentification(false)
                .setConnectTimeout(3000); // 3秒连不上直接报错，不卡死;

        //创建RedissionClient 对象
        return Redisson.create(config);
    }
}
