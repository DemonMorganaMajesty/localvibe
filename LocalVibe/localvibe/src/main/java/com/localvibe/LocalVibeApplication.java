package com.localvibe;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

//暴露代理对象
@EnableAspectJAutoProxy(exposeProxy = true)
//开启定时任务（商品变更日志兜底缓存同步等）
@EnableScheduling
@MapperScan("com.localvibe.mapper")
@SpringBootApplication
public class LocalVibeApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocalVibeApplication.class, args);
    }

}
