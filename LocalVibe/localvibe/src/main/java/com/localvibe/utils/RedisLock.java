package com.localvibe.utils;

import cn.hutool.Hutool;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;


//实现 分布式锁 mutex 的基础代码
public class RedisLock implements ILock {
    StringRedisTemplate stringRedisTemplate;
    String lockName;

    public RedisLock(String lockName,StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.lockName = lockName;
    }

    //key 的前缀
    public static final String LOCK_PREFIX_KEY="redis:lock";
    //给锁key 的value拼得复杂一点
    public static final String LOCK_PREFIX_UUID_VALUE=
            UUID.randomUUID().toString(true)+"-";

    //redis实现事务 的lua脚本 返回值是0/1(失败/成功)
    public static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static{
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        //去所有的类下面找文件
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        //设置脚本的类
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    //获取锁  传递锁存在的时间
    @Override
    public boolean getLock(long timeOutSecond) {
        //锁的key 前缀:业务(lockName):
        String key=LOCK_PREFIX_KEY+lockName;
        //key 的值value
        long threadId=Thread.currentThread().getId();
        String value=LOCK_PREFIX_UUID_VALUE+threadId;

        //setnx
        Boolean isSuccess=stringRedisTemplate.opsForValue().setIfAbsent
                (key,value,timeOutSecond,TimeUnit.SECONDS);

        /*自动拆箱 可能有空指针(null进行拆箱)会直接中断 抛出异常
        函数的返回值也不能是 Boolean 要是传递回去空指针没有判断 会出问题
        不能直接 return isSuccess(Boolean)
         */
        //BooleanUtil.isTrue(isSuccess);
        return Boolean.TRUE.equals(isSuccess);
    }

    /*释放锁  要保证是获取锁的对象(线程)来释放锁的
    只根据key来判断锁key: 同一个业务下 同一个对象是去抢同一把锁key 相同
    而同一个对象的多线程 threadId 是递增的,可能出现多个线程用同一个id的情况
    线程未执行完,锁过期了,锁给了其他的线程拿到了,等到自己完成释放锁就会出错
    value不安全 一个线程生成一个UUID 拼接起来 保证获取锁的值唯一 这样就
    可以根据值 来判断同一个业务同一个用户 是同一个线程抢到和释放锁 避免锁的误删
     */
    @Override
    public void unlock() {
        //锁的key 前缀:业务(lockName):
        String key=LOCK_PREFIX_KEY+lockName;
        //key 的值value
        long threadId=Thread.currentThread().getId();
        String value=LOCK_PREFIX_UUID_VALUE+threadId;

        /*执行脚本 看作一个事务 第一个是脚本 第二个是像传递的键keys
        第三个是向脚本传递的参数 argv
         */
        stringRedisTemplate.execute(UNLOCK_SCRIPT
                , Collections.singletonList(key),value);
    }

    /*  下面的不能 判断当前锁是不是自己线程的锁 和 释放锁 是一个事务
    redis要实现事务 最好通过写lua脚本 可以自动把一段代码看为一个事务

      @Override
    public void unlock() {
        //获取锁的 线程的 key
        String key=LOCK_PREFIX_KEY+lockName;
        //获取锁的线程的 value
        String value=LOCK_PREFIX_UUID_VALUE+Thread.currentThread().getId();
        //当前想要释放锁的 的value
        String valueT=stringRedisTemplate.opsForValue().get(key);

        //只能让获取锁的线程 来释放锁
        if(value.equals(valueT)){
          stringRedisTemplate.delete(key);
        }
    }
     */
}
