package com.localvibe;

import com.localvibe.entity.Shop;
import com.localvibe.service.impl.ShopServiceImpl;
import com.localvibe.utils.CacheClient;
import com.localvibe.utils.RedisIdWorker;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.localvibe.utils.RedisConstants.CACHE_SHOP_ID_KEY;

@SpringBootTest
class LocalVibeApplicationTests {
    @Resource
    ShopServiceImpl shopService;

    //实现缓存穿透和击穿的工具类包装算法
    @Resource
    CacheClient cacheClient;

    //测试生成的唯一的全局ID
    @Resource
    RedisIdWorker redisIdWoker;
    //线程池
    public ExecutorService executorService
            = Executors.newFixedThreadPool(60);


    //再redis添加数据的 逻辑过期时间
    @Test
    public void testSaveRedisData() throws InterruptedException {
       /* 直接调用 id,time  key=keyPrefix+id
       shopService.saveShopWithLogicExpireTime(1L,10L);
        */
        //使用工具类封装实现
        Shop shop=shopService.getById(1L);
        cacheClient.setWithLogicExpire(CACHE_SHOP_ID_KEY+1L,
                shop,10L, TimeUnit.SECONDS);
    }

    @Test
    public void testIdWoker() throws InterruptedException {
        //一共有30个线程 相当于一个计数器
        CountDownLatch latch=new CountDownLatch(30);
        //线程执行的任务
        Runnable task=()->{
            for(int i=0;i<100;i++){
                long id=redisIdWoker.createId("order");
                System.out.println("id:"+id);
            }
            //每个线程执行完后 --
            latch.countDown();
        };
        //线程执行
        long begin=System.currentTimeMillis();
        for(int i=0;i<30;i++)
            executorService.submit(task);
        latch.await();
        long end=System.currentTimeMillis();
        System.out.println("time="+(end-begin));
    }
}
