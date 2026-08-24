package com.localvibe;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 城遇(local-vibe) 集群第二节点启动类（端口 8086）
 *
 * 双开集群负载均衡说明：
 *   LocalVibeApplication      -> server.port = 8084（主节点，application.yaml）
 *   LocalVibeApplicationSlave -> server.port = 8086（从节点，本类启动参数覆盖）
 * 配合 WSL openresty 的 upstream tomcat-cluster（8084 + 8086，一致性 hash）
 * 实现两台 Tomcat 的负载均衡。
 *
 * 注意：命令行参数 --server.port 优先级高于 application.yaml，可安全覆盖端口；
 * 其余配置（Redis/Canal/RocketMQ）与主节点完全一致。
 *
 * @author 改造新增
 */
@EnableAspectJAutoProxy(exposeProxy = true)
//开启定时任务（商品变更日志兜底缓存同步等）
@EnableScheduling
@MapperScan("com.localvibe.mapper")
@SpringBootApplication
public class LocalVibeApplicationSlave {

    public static void main(String[] args) {
        // 在原有启动参数后追加 --server.port=8086，实现与主节点(8084)双开集群
        String[] finalArgs = new String[args.length + 1];
        System.arraycopy(args, 0, finalArgs, 0, args.length);
        finalArgs[args.length] = "--server.port=8086";
        SpringApplication.run(LocalVibeApplicationSlave.class, finalArgs);
    }
}