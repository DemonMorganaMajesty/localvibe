package com.localvibe.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.localvibe.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    public  final StringRedisTemplate stringRedisTemplate;

    //建立一个线程池  用于缓存击穿
    public static final ExecutorService CACHE_REBUILD_EXECUTOR
            = Executors.newFixedThreadPool(10);


    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //不设置过期时间,普通的在redis存数据
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key,
                JSONUtil.toJsonStr(value),time,unit);
    }

    //设置逻辑过期时间
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit unit){
        //使用RedisData 带expire字段 设置逻辑过期时间
        RedisData redisDataValue=new RedisData();
        //unit 单位不一定是s 单位转化
        redisDataValue.setExpireTime(LocalDateTime.now().plusSeconds
                (unit.toSeconds(time)));
        redisDataValue.setData(value);

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisDataValue));
    }

    /*定义的是统一的方法类,所以类型不能写死 可是使用Object/泛型
    Object要强转 最好用泛型 <T,idType>定义泛型 id也不确定类型,前缀也有可能不同
    封装的时候还要进行数据库的查询,写为泛型后去哪里查,需要调用者返回函数式编程
    Function<idType(参数类型),T(函数返回值类型)> dbSelect
     */
    public <T,idType> T selectByIdWithPassThrough
    (String keyPrefix, idType id, Class<T>type,
     Function<idType,T> dbSelect,Long time, TimeUnit unit) {
        //先去缓存中查询数据 没有再去数据库查询
        /*选择什么作为键: 业务:店铺的编号   值可以是String / hash
        最好选择hash string使用json 格式存数据的时候有一些没有用的数据
        要保存浪费空间,而且 String的json不能修改单个的字段的值 时候
        这里使用string 来演示 hash在登录的时候演示了
         */
        //计算cache和数据库查询时间
        // long start = System.currentTimeMillis();

        //存入redis 的键
        String key=keyPrefix+id;

        //不一定是某个具体的类 shop  shopJson->json
        String json=
                stringRedisTemplate.opsForValue().get(key);

        /*判断缓存中有没有查询到 是否为空 !=null 也可以
        isNotBlank:不为null 不为"" 不为空格 不为等制表符 换行符
         */
        if(StrUtil.isNotBlank(json)){
            //cache中查到了数据 json转为java对象返回
            T TObject=JSONUtil.toBean(json,type);
            return TObject;
        }

        /*数据库中不存在的数据null 也保存到了redis(保存为"") 所以要单独判断
        但是shopJson 也可能为null 数据库中数据不存在且数据没有存到redis,
        isNotBlank 放下来的是 shopJson=null "" 为null调用equals会报错
        "".equals(shopJson) StrUtil.equals("",shopJson) 都可以
         */
        if("".equals(json))
            return null;

        //缓存中没有查到数据 直接去数据库里面查数据
        //使用 mybatis-plus简单语句的查询 调用者传递函数过来
        //Shop shop=shopMapper.selectById(id); 改为泛型这样写不行
        //Shop shop=getById(id);
        T TObject=dbSelect.apply(id);

        //数据库中没有该数据 返回错误
        if(TObject==null){
            //数据不存在 将null(存活时间2min)写入redis 解决缓存穿透等问题
            stringRedisTemplate.opsForValue().set(key,"",
                    CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //数据库中有该数据,不为null 但是缓存中没有,把数据存入到缓存中
        stringRedisTemplate.opsForValue().set(key,
                JSONUtil.toJsonStr(TObject),time,unit);

        //调用上面的函数 this.setWithLogicExpire(key,TObject,time,unit);

        //返回成功的响应
        return TObject;
    }


    /**
     * 互斥锁 + 空值缓存：同时处理缓存击穿和缓存穿透。
     * 缓存未命中时只有拿到锁的线程查询数据库，其余线程等待后重试。
     */
    public <T, idType> T selectWithMutexAndCacheThrough(
            String keyPrefix, idType id, Class<T> type, Function<idType, T> dbSelect,
            Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) return JSONUtil.toBean(json, type);
        if ("".equals(json)) return null;

        String lockKey = LOCK_SHOP_KEY + id;
        if (!getLock(lockKey)) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            return selectWithMutexAndCacheThrough(keyPrefix, id, type, dbSelect, time, unit);
        }
        try {
            String latest = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(latest)) return JSONUtil.toBean(latest, type);
            if ("".equals(latest)) return null;
            T value = dbSelect.apply(id);
            if (value == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
            return value;
        } finally {
            unlock(lockKey);
        }
    }

    /**
     * 逻辑过期 + 空值缓存：同时处理缓存击穿和缓存穿透。
     * 有效数据直接返回；逻辑过期返回旧数据并异步重建；缓存未命中时加锁回源。
     */
    public <T, idType> T selectWithLogicExpireAndCacheThrough(
            String keyPrefix, idType id, Class<T> type, Function<idType, T> dbSelect,
            Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if ("".equals(json)) return null;

        if (StrUtil.isBlank(json)) {
            String lockKey = LOCK_SHOP_KEY + id;
            if (!getLock(lockKey)) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                return selectWithLogicExpireAndCacheThrough(keyPrefix, id, type, dbSelect, time, unit);
            }
            try {
                String latest = stringRedisTemplate.opsForValue().get(key);
                if ("".equals(latest)) return null;
                if (StrUtil.isNotBlank(latest)) return parseLogicExpire(latest, type);
                T value = dbSelect.apply(id);
                if (value == null) {
                    stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                setWithLogicExpire(key, value, time, unit);
                return value;
            } finally {
                unlock(lockKey);
            }
        }

        T value = parseLogicExpire(json, type);
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) return value;

        String lockKey = LOCK_SHOP_KEY + id;
        if (getLock(lockKey)) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    T fresh = dbSelect.apply(id);
                    if (fresh == null) {
                        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    } else {
                        setWithLogicExpire(key, fresh, time, unit);
                    }
                } finally {
                    unlock(lockKey);
                }
            });
        }
        return value;
    }

    // 统一解析 RedisData 中的泛型业务对象
    private <T> T parseLogicExpire(String json, Class<T> type) {
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject jsonData = (JSONObject) redisData.getData();
        return jsonData == null ? null : JSONUtil.toBean(jsonData, type);
    }

    //使用逻辑过期 解决缓存击穿问题
    public <T,idType> T selectWithLogicExpire(String keyPrefix
            ,idType id,Class<T> type,Function<idType,T> dbSelect
            ,Long time, TimeUnit unit) {
        //先去缓存中查询数据 没有再去数据库查询
        /*选择什么作为键: 业务:店铺的编号   值可以是String / hash
        最好选择hash string使用json 格式存数据的时候有一些没有用的数据
        要保存浪费空间,而且 String的json不能修改单个的字段的值 时候
        这里使用string 来演示 hash在登录的时候演示了
         */
        //计算cache和数据库查询时间
        // long start = System.currentTimeMillis();

        //存入redis 的键
        String key=keyPrefix+id;

        String json=
                stringRedisTemplate.opsForValue().get(key);

        //为空返回null 使用逻辑过期处理缓存穿透 不需要在redis存""
        if(StrUtil.isBlank(json)){
            return null;
        }

        /*数据在redis中存在,需要反序列化得到先数据对象(RedisData类型)
        再得到shop的类型 并且判断过期时间
         */
        RedisData redisDataObject =JSONUtil.toBean
                (json,RedisData.class);

        //得到泛型类型的反序列化字节码
        JSONObject jsonData =(JSONObject) redisDataObject.getData();
        //得到泛型对象
        T TObject=JSONUtil.toBean(jsonData,type);
        //过期时间
        LocalDateTime expireTime= redisDataObject.getExpireTime();

        //判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //没有过期 直接返回旧的信息
            return TObject;
        }

        //缓存过期了 就需要重建缓存 但是不管有没有获取成功锁 都要返回旧的shop信息
        //获取互斥锁
        String lockKey=LOCK_SHOP_KEY+id;
        //获取锁
        boolean isLock=getLock(lockKey);

        //获取锁成功 再次判断redis 缓存是否过期 过期了就要重建redis缓存
        if(isLock&&expireTime.isBefore(LocalDateTime.now())){
            //过期了 开启一个独立的线程去实现缓存重建 该次任然返回
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //先查询数据库 然后把数据写进redis
                    T TObjectAgain=dbSelect.apply(id);
                    //缓存重建:重新把数据库的数据读到缓存 带有expire
                    this.setWithLogicExpire(key,TObjectAgain,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });

        }

        return TObject;
    }

    //获取锁 互斥锁解决缓存击穿的问题  set nx 实现获取锁
    public boolean getLock(String key){
        //key 的值任意即可  过期的时间测试的设置为s
        Boolean sign=stringRedisTemplate.opsForValue().setIfAbsent
                (key,"1",LOCK_SHOP_TTL,TimeUnit.SECONDS);

        /*为什么不直接返回sign: 拆箱装箱可能出现空指针异常 工具包将null
        false 都变为false 返回
         */
        return BooleanUtil.isTrue(sign);
    }
    //释放锁
    public void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}
