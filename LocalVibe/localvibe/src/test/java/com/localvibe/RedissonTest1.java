package com.localvibe;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

/* 1.可重入锁 封装在redisson 创建RLock的对象:
可重入锁:单个线程抢到锁后,需要调用多个方法来,每个方法不能都去枪锁
而是把这个线程抢到的锁 让每个方法都可以使用,如何实现 key hk hv(count)
不能直接判断锁是否存在,而是方法2要拿锁的时候要判断 该锁是不是线程的锁
用次数控制, 该锁重入了几次,几个方法重入几次,每次进入一个方法hv++,退出
hv-- 直到hv=0的时候 释放锁 一把锁锁住的是整个线程,不是每个方法结束
就释放锁,每进入一个方法就获取锁,
lua脚本的原理在resource下
2.锁的可重试:尝试获取锁,判断锁的ttl(剩余存活时间 -1永不过期(未设置为null)
,-2key不存在/过期已经删除  >0过期的时间) ttl为null(-1 项目结束后会释放锁)
leasetime(redisson中的过期时间)=ttl 如果leasetime不为-1 那么返回true
直接结束 抢到锁 leasetime=-1 开启WatchDog 看门狗(递归) 不停的更新有效期
(实现永不过期) ttl不为null,判断ttl是否>0 剩余有效期<0 return false枪锁失败
ttl>0 可以重新抢锁,订阅别人释放锁的信息,等待别人释放锁再获取,一边等待要一边判断
等待时间是否超时,时间超时抢锁失败,没有超时,循环获取锁
3.超时释放锁：尝试释放锁,判断是否成功,成功发送释放的信息让别人来抢,并且
取消看门狗(递归,刷新过期时间),不成功那么记录异常即可,不用管发生了什么
4.主从一致性:一般分布式会部署到多台电脑上,那么就会有多个redis,通常会设置
一个redis master(主节点) 多个redis slave(从节点) 主从之间很容易出现数据
不一致的情况,解决:不设置主从 把每个都当作节点(每个节点可设置主从),
枪锁的时候同时向所有的节点(不同reids) 枪锁(连锁 在每个节点都抢到才算抢锁成功)
 */

@Slf4j
@SpringBootTest
public class RedissonTest1 {
    @Resource
    RedissonClient redissonClient;

    public RLock rLock;

    @BeforeEach
    public void setup(){
        rLock = redissonClient.getLock("order");
    }

    @Test
    void method1() {
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
    void method2() {
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
/*
1. 获取锁 Lua 脚本（lock.lua）
lua
local key = KEYS[1];          -- 锁的key
local threadId = ARGV[1];    -- 线程唯一标识
local releaseTime = ARGV[2]; -- 锁的自动释放时间

-- 判断锁是否不存在
if(redis.call('exists', key) == 0) then
    -- 不存在，获取锁，存入hash结构，当前线程计数=1
    redis.call('hset', key, threadId, '1');
    -- 设置锁过期时间
    redis.call('expire', key, releaseTime);
    return 1; -- 获取锁成功，返回1
end;

-- 锁已存在，判断持有线程是否是当前线程（可重入）
if(redis.call('hexists', key, threadId) == 1) then
    -- 重入次数 +1
    redis.call('hincrby', key, threadId, '1');
    -- 刷新锁过期时间
    redis.call('expire', key, releaseTime);
    return 1; -- 重入加锁成功
end;

-- 锁存在且不是当前线程持有，加锁失败
return 0;
2. 释放锁 Lua 脚本（unlock.lua）
lua
local key = KEYS[1];          -- 锁的key
local threadId = ARGV[1];    -- 线程唯一标识
local releaseTime = ARGV[2]; -- 锁自动释放时间

-- 判断锁持有者是否为当前线程，不是直接返回，不操作
if (redis.call('HEXISTS', key, threadId) == 0) then
    return nil;
end;

-- 是自己的锁，重入计数 -1
local count = redis.call('HINCRBY', key, threadId, -1);

-- 减完后计数>0：还有重入，只刷新过期时间，不删锁
if (count > 0) then
    redis.call('EXPIRE', key, releaseTime);
    return nil;
else
    -- 计数=0，所有重入全部执行完毕，直接删除锁
    redis.call('DEL', key);
    return nil;
end;
 */