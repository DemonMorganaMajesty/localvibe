package com.localvibe.utils;


import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/*实现全局唯一Id的生成器 一共64位 1位标志位0 31位时间戳位(当前时间到起始时间
的差值,单位s) 32位序列号位(自增长count,生成的id的个数)
 利用redis的自增长  这里设置的是天一个key(时间戳位),也可以统计每天id的数目
 一般用来为数量很多的设置:用户的订单id
 */
@Component
public class RedisIdWorker {
    //开始的时间戳定义为常量
    public static final long BEGIN_TIMESTAMP=1767225600L;
    //序列号位数定义为常量
    public static final int COUNT_BITS=32;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    //创建唯一的id 根据redis 的自增的原理
    public long createId(String keyPrefix){
        //生成当前时间戳
        LocalDateTime now=LocalDateTime.now();

        //toEpochSecond(时区偏移)：把这个日期时间，按照你传入的时区，
        // 换算成从 1970‑01‑01 00:00:00 UTC 开始到此刻的秒数，返回 long 时间戳。
        long secondNow=now.toEpochSecond(ZoneOffset.UTC);
        //与基准时间 的差值
        long diffTime=secondNow-BEGIN_TIMESTAMP;

        //得到当前的date:年月日 格式是20260412
        String date=now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        /*生成序列号 自增长 设计key的值:keyPrefix(同一业务不变),实际上
        key的变化是根据date来的 一天一个key 也可以设置一月一个key..
        "increment:"+keyPrefix+":"+date
        这是在redis的键 每天一个键,值是这一天所创建的id的个数 count
        怎么得每个唯一标识的id:dates转为秒-起始的秒(前31位) cout(后32位)
        调用函数得到 什么业务的唯一标识
         */
        long count=stringRedisTemplate.opsForValue().
                increment("increment:"+keyPrefix+":"+date);

        /*时间戳放在高位(位运算),每条生成的id数目放在低位(| +都可)
        diffTime << 32：时间偏移左移 32 位，放到 long 的高 31 位，低 32 位全部补 0。
      | count：或运算，把 count 放到低 32 位。（count 是 32 位以内，不会冲突）

      diffTime        → 0000 0000 ... 时间数值 (31bit)
      diffTime <<32   → 时间数值 后面补上32个0
      count           → 0000... count(32bit)
      按位或 |        → 合并成完整64位long全局ID
         */
        return diffTime<<COUNT_BITS | count;
    }

    //设置一个秒数的初始值 运行得到后 记录下来
    public static void main(String[] args) {
        LocalDateTime time=LocalDateTime.of(2026,1,1,0,0,0);
        long second=time.toEpochSecond(ZoneOffset.UTC);

        System.out.println("second="+second);
    }
}
