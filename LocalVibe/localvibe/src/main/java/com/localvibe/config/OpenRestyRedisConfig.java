package com.localvibe.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * OpenResty 链路专用 Redis 配置
 *
 * openresty(lua) 与 Redis 数据同步约定使用 db6（见 WSL 中 /usr/local/openresty/nginx/lua/item.lua），
 * 而应用自身业务缓存使用 application.yaml 中配置的 database 7。
 * 本配置在不修改 yaml 的前提下，额外构建一个指向 db6 的 StringRedisTemplate，
 * 用于商品/库存多级缓存的 L2 读写，保证 openresty 与本项目共用同一份 Redis 缓存数据。
 *
 * 说明：host/port/password 直接复用 yaml 中已有的 redis 配置，仅数据库号改为 6。
 */
@Configuration
public class OpenRestyRedisConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    @Value("${spring.data.redis.password}")
    private String password;

    /**
     * 与 openresty lua 共用的 Redis 模板（db6）
     */
    @Bean(name = "openrestyRedisTemplate")
    public StringRedisTemplate openrestyRedisTemplate() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(host);
        config.setPort(port);
        config.setPassword(password);
        // openresty lua 约定：商品/库存缓存放在 db6
        config.setDatabase(6);

        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        return new StringRedisTemplate(factory);
    }
}
