package com.localvibe;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

/*使用Redisson连锁处理分布式锁的主从一致性问题
分布式 需要集群的redis(多台电脑上redis 或者一台电脑部署多个redis集群)
在wsl 上配置多份文件并 不需要开启集群模式,这样会使数据获取锁失败

启动测试方法  所有的分布式集群(都是一个节点 都会同时获得锁);
1.Redis Cluster（服务端集群，你关了 cluster-enabled no）
是 Redis 服务自身的分片集群，把 16384 个槽分给多台 Redis，
需要服务端开启 cluster-enabled yes 才能组成集群。
你现在 6380/6381/6382 都是独立单机 Redis，互相之间完全不通信、不共享数据。
Redisson MultiLock（客户端多锁，和服务端是否集群无关）

2.MultiLock 只是客户端层面同时向多个独立 Redis 实例分别申请同一个名称的锁，
只有全部实例都加锁成功才算拿到锁。
底层逻辑：分别连接每一个 Redis，单独执行 SET lock key NX EX；
对 Redis 服务没有任何集群要求，哪怕每个 Redis 都是孤立单机也能正常运行；
只要求客户端能连通所有 Redis 端口即可。
 */
@Slf4j
@SpringBootTest
public class RedissonTest2{

    @Resource
    RedissonClient redissonClient_6380;
    @Resource
    RedissonClient redissonClient_6381;
    @Resource
    RedissonClient redissonClient_6382;

    public RLock rLock;

    @BeforeEach
    public void setup(){
        //创建单
        RLock rLock1 = redissonClient_6380.getLock("order");
        RLock rLock2 = redissonClient_6381.getLock("order");
        RLock rLock3 = redissonClient_6382.getLock("order");

        //创建连锁 不管用哪一个都可以 最后都是把锁放进集合
        rLock=redissonClient_6381.getMultiLock(rLock1,rLock2,rLock3);

    }

    @Test
    void method1() throws InterruptedException {
        // 尝试获取锁
        boolean isLock = rLock.tryLock();
        if (!isLock) {
            log.error("获取锁失败 ... 1");
            return;
        }
        try {
            log.info("获取锁成功 ... 1");
            method2();
            log.info("开始执行业务 ... 1");
        } finally {
            log.warn("准备释放锁 ... 1");
            rLock.unlock();
        }
    }
    void method2() throws InterruptedException {
        // 尝试获取锁
        boolean isLock = rLock.tryLock();
        if (!isLock) {
            log.error("获取锁失败 ... 2");
            return;
        }
        try {
            log.info("获取锁成功 ... 2");
            log.info("开始执行业务 ... 2");
        } finally {
            log.warn("准备释放锁 ... 2");
            rLock.unlock();
        }
    }

}