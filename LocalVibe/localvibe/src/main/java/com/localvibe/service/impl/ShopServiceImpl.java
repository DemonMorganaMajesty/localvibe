package com.localvibe.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localvibe.dto.Result;
import com.localvibe.entity.Shop;
import com.localvibe.mapper.ShopMapper;
import com.localvibe.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.localvibe.utils.CacheClient;
import com.localvibe.utils.RedisData;
import com.localvibe.utils.SystemConstants;
import com.localvibe.cache.CacheInvalidatePublisher;
import com.localvibe.cache.LocalCacheManager;
import com.localvibe.utils.RedisConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.localvibe.utils.RedisConstants.*;


@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    ShopMapper shopMapper;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    CacheClient cacheClient;

    //多级缓存 L1 本地缓存管理(Caffeine) 与缓存失效消息发布
    @Resource
    LocalCacheManager localCacheManager;

    //缓存 失效发布器
    @Resource
    CacheInvalidatePublisher cacheInvalidatePublisher;

    //建立一个线程池
    public static final ExecutorService CACHE_REBUILD_EXECUTOR
            =Executors.newFixedThreadPool(10);



    //解决缓存穿透 和 缓存击穿问题
    @Override
    public Result selectById(Long id){
        /*只处理缓存穿透的问题:数据库中为null的数据 在redis存为""
        也可以把selectByIdWithCacheThrough
        返回值设置为 shop 从而实现统一
         */
        //return selectByIdWithCacheThrough(id);
        /*使用CacheClient封装好的工具类 解决缓存穿透的问题
        调用函数idT->getById(idT) 也可 this::getById
         */
        /*Shop shop=cacheClient.selectByIdWithPassThrough
        (CACHE_SHOP_ID_KEY,id,Shop.class,idT->getById(idT),
                CACHE_SHOP_TTL,TimeUnit.MINUTES);*/

        /*使用互斥锁 解决缓存击穿：缓存redis过期, +缓存穿透(不用锁也可解决)
         */
        //Shop shop=selectWithMutex(id);

        //常用 使用逻辑过期处理缓存穿透问题(不会出现缓存穿透的问题)
        //Shop shop=selectWithLogicExpire(id);

        //多级缓存 L1：先查 Caffeine 本地缓存(进程内,键与redis一致)
        String localJson=localCacheManager.getShopCache().getIfPresent(CACHE_SHOP_ID_KEY+id);
        if(StrUtil.isNotBlank(localJson)){
            return Result.success(JSONUtil.toBean(localJson,Shop.class));
        }
        //使用当前业务类自己的组合方法处理店铺缓存
        //Redis 命中时返回旧数据并异步重建；Redis 未命中时加锁回源，空值写入短 TTL
        Shop shop = selectWithLogicExpireAndCacheThrough(id);
        if(shop==null)
            return Result.fail("店铺不存在");
        //写回 L1 Caffeine(键与redis一致,canal/变更时统一失效)
        localCacheManager.getShopCache().put(CACHE_SHOP_ID_KEY+id,JSONUtil.toJsonStr(shop));
        return Result.success(shop);
    }

    //有缓存穿透的情况: 数据库中也没有数据 向redis存入"" 有时间 防止直接查询数据库
    @Override
    public Result selectByIdWithCacheThrough(Long id) {
        //先去缓存中查询数据 没有再去数据库查询
        /*选择什么作为键: 业务:店铺的编号   值可以是String / hash
        最好选择hash string使用json 格式存数据的时候有一些没有用的数据
        要保存浪费空间,而且 String的json不能修改单个的字段的值 时候
        这里使用string 来演示 hash在登录的时候演示了
         */
        //计算cache和数据库查询时间
        // long start = System.currentTimeMillis();

        //存入redis 的键
        String key=CACHE_SHOP_ID_KEY+id;

        String shopJson=
        stringRedisTemplate.opsForValue().get(key);

        /*判断缓存中有没有查询到 是否为空 !=null 也可以
        isNotBlank:不为null 不为"" 不为空格 不为等制表符 换行符
         */
        if(StrUtil.isNotBlank(shopJson)){
            //cache中查到了数据 json转为java对象返回
            //log.info("🟢 缓存命中! key: {}, 耗时: {}ms", key, System.currentTimeMillis() - start);
            Shop shop=JSONUtil.toBean(shopJson,Shop.class);
            return Result.success(shop);
        }

        /*数据库中不存在的数据null 也保存到了redis(保存为"") 所以要单独判断
        但是shopJson 也可能为null 数据库中数据不存在且数据没有存到redis,
        isNotBlank 放下来的是 shopJson=null "" 为null调用equals会报错
        "".equals(shopJson) StrUtil.equals("",shopJson) 都可以
         */
        if("".equals(shopJson))
            return Result.fail("店铺信息不存在");

        //log.info("🟢 数据库查询! key: {}, 耗时: {}ms", key, System.currentTimeMillis() - start);
        //缓存中没有查到数据 直接去数据库里面查
        //Shop shop=getById(id); mybatis-plus简单语句的查询
        Shop shop=shopMapper.selectById(id);

        //数据库中没有该数据 返回错误
        if(shop==null){
            //数据不存在 将null(存活时间2min)写入redis 解决缓存穿透等问题
            stringRedisTemplate.opsForValue().set(key,"",
                    CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺不存在,查询错误");
        }

        //数据库中有该数据,但是缓存中没有,把数据存入到缓存中
        stringRedisTemplate.opsForValue().set(key,
                JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //返回成功的响应
        return Result.success(shop);
    }

    /* 有缓存穿透的情况: 数据库中也没有数据 向redis存入"" 有时间 防止直接查询数据库
   使用互斥锁在缓存穿透的基础上(上一个函数)  解决缓存击穿问题
     */
    public Shop selectWithMutexAndCacheThrough(Long id){
        //先去缓存中查询数据 没有再去数据库查询
        /*选择什么作为键: 业务:店铺的编号   值可以是String / hash
        最好选择hash string使用json 格式存数据的时候有一些没有用的数据
        要保存浪费空间,而且 String的json不能修改单个的字段的值 时候
        这里使用string 来演示 hash在登录的时候演示了
         */
        //计算cache和数据库查询时间
        // long start = System.currentTimeMillis();

        //存入redis 的键
        String key=CACHE_SHOP_ID_KEY+id;

        String shopJson=
                stringRedisTemplate.opsForValue().get(key);

        /*判断缓存中有没有查询到 是否为空 !=null 也可以
        isNotBlank:不为null 不为"" 不为空格 不为等制表符 换行符
         */
        if(StrUtil.isNotBlank(shopJson)){
            //cache中查到了数据 json转为java对象返回
            //log.info("🟢 缓存命中! key: {}, 耗时: {}ms", key, System.currentTimeMillis() - start);
            Shop shop=JSONUtil.toBean(shopJson,Shop.class);
            return shop;
        }

        /*数据库中不存在的数据null 也保存到了redis(保存为"") 所以要单独判断
        但是shopJson 也可能为null 数据库中数据不存在且数据没有存到redis,
        isNotBlank 放下来的是 shopJson=null "" 为null调用equals会报错
        "".equals(shopJson) StrUtil.equals("",shopJson) 都可以
         */
        if("".equals(shopJson))
            return null;

        //假设缓存过期了 需要进行缓存的重建:从数据库写数据到缓存
        //获取互斥锁  可以改为循环 避免一直递归栈溢出
        String lockKey=LOCK_SHOP_KEY+id;
        try {
            boolean sign=getLock(lockKey);

            //是否获取了锁
            if(!sign){
                //没有获取锁 直接返回重试
                Thread.sleep(50);
                //重试获取锁 递归
                return selectWithMutexAndCacheThrough(id);
            }

            //获取锁了 检查redis有没有命中
            String shopJsonAgain =stringRedisTemplate.opsForValue().get(key);

            //StrUtil.isNotBlank(shopJson)
            //缓存已有有效数据，直接返回，不用查库
            if (StrUtil.isNotBlank(shopJsonAgain)) {
                return JSONUtil.toBean(shopJsonAgain, Shop.class);
            }
            //缓存已经存入空值，数据库无数据，直接返回null
            if ("".equals(shopJsonAgain)) {
                return null;
            }

            //缓存中没有查到数据 直接去数据库里面查
            //Shop shop=getById(id); mybatis-plus简单语句的查询
            Shop shop=shopMapper.selectById(id);

            //休眠模拟重建的缓存的时间
            //Thread.sleep(200);

            //数据库中没有该数据 返回错误
            if(shop==null){
                //数据不存在 将null(存活时间2min)写入redis 解决缓存穿透等问题
                stringRedisTemplate.opsForValue().set(key,"",
                        CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            //数据库中有该数据,但是缓存中没有,把数据存入到缓存中
            stringRedisTemplate.opsForValue().set(key,
                    JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

            //返回成功的响应
            return shop;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unlock(lockKey);
        }
    }

    //获取锁 互斥锁解决缓存击穿的问题
    public boolean getLock(String key){
        //key 的值任意即可  过期的时间测试的设置为s
        Boolean sign=stringRedisTemplate.opsForValue().setIfAbsent
                (key,"1",LOCK_SHOP_TTL,TimeUnit.SECONDS);

        /*为什么不直接返回sign: 拆箱装箱可能出现空指针异常 工具包将null
        false 都变为false 返回
         */
        //Boolean.TRUE.equals(sign);
        return BooleanUtil.isTrue(sign);
    }
    //释放锁
    public void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    /*利用逻辑过期处理缓存击穿 怎么实现逻辑过期:要设置expire时间
    但是如果直接在Shop设置 会改变原代码,所以把expire设置到了RedisData
    重建缓存
     */
    public void saveShopWithLogicExpireTime(Long id,Long expireTime) throws InterruptedException {
        //查询店铺的数据
        Shop shop=getById(id);
        //模拟缓存重建的时间
        //Thread.sleep(200);

        //设置逻辑过期时间
        //redisDataShop=shop +expire
        RedisData redisDataShop=new RedisData();
        redisDataShop.setData(shop);
        //当前时间 + 过期时间
        redisDataShop.setExpireTime(LocalDateTime.now()
                .plusSeconds(expireTime));
        //写入redis
        stringRedisTemplate.opsForValue().
                set(CACHE_SHOP_ID_KEY+id,JSONUtil.toJsonStr(redisDataShop));
    }

    /**
     * 店铺详情缓存：逻辑过期 + 空值缓存的组合处理。
     *
     * 逻辑过期解决热点店铺缓存击穿：过期时先返回旧数据，再异步重建。
     * 空值缓存解决缓存穿透：数据库不存在的店铺写入短 TTL 空字符串。
     * Redis 未命中时使用互斥锁，避免多个线程同时回源数据库。
     */
    public Shop selectWithLogicExpireAndCacheThrough(Long id) {
        String key = CACHE_SHOP_ID_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 空值缓存命中：数据库不存在该店铺，直接返回，避免重复查询数据库
        if ("".equals(shopJson)) {
            return null;
        }

        // Redis 未命中：加锁回源，同时写入逻辑过期数据或短 TTL 空值
        if (StrUtil.isBlank(shopJson)) {
            String lockKey = LOCK_SHOP_KEY + id;
            if (!getLock(lockKey)) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                return selectWithLogicExpireAndCacheThrough(id);
            }
            try {
                // 获取锁后再次检查，避免重复回源
                String latestJson = stringRedisTemplate.opsForValue().get(key);
                if ("".equals(latestJson)) {
                    return null;
                }
                if (StrUtil.isNotBlank(latestJson)) {
                    return parseShopLogicExpire(latestJson);
                }

                Shop shop = getById(id);
                if (shop == null) {
                    stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                saveShopLogicExpireValue(key, shop, CACHE_SHOP_TTL, TimeUnit.SECONDS);
                return shop;
            } finally {
                unlock(lockKey);
            }
        }

        Shop shop = parseShopLogicExpire(shopJson);
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime == null || expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }

        // 逻辑过期只返回旧值，缓存重建放入线程池，避免阻塞当前请求
        String lockKey = LOCK_SHOP_KEY + id;
        if (getLock(lockKey)) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    Shop freshShop = getById(id);
                    if (freshShop == null) {
                        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    } else {
                        saveShopLogicExpireValue(key, freshShop, CACHE_SHOP_TTL, TimeUnit.SECONDS);
                    }
                } finally {
                    unlock(lockKey);
                }
            });
        }
        return shop;
    }

    // 写入店铺逻辑过期数据，统一使用 RedisData 格式
    private void saveShopLogicExpireValue(String key, Shop shop, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 解析 RedisData 中的店铺对象
    private Shop parseShopLogicExpire(String json) {
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        return data == null ? null : JSONUtil.toBean(data, Shop.class);
    }

    //使用逻辑过期 只解决缓存击穿(热点过期)的问题
    public Shop selectWithLogicExpire(Long id) {
        //先去缓存中查询数据 没有再去数据库查询
        /*选择什么作为键: 业务:店铺的编号   值可以是String / hash
        最好选择hash string使用json 格式存数据的时候有一些没有用的数据
        要保存浪费空间,而且 String的json不能修改单个的字段的值 时候
        这里使用string 来演示 hash在登录的时候演示了
         */
        //计算cache和数据库查询时间
        // long start = System.currentTimeMillis();

        //存入redis 的键
        String key=CACHE_SHOP_ID_KEY+id;

        String shopJson=
                stringRedisTemplate.opsForValue().get(key);

        //为空返回null 使用逻辑过期处理缓存穿透 不需要在redis存""
        if(StrUtil.isBlank(shopJson)){
            return null;
        }

        /*数据在redis中存在,需要反序列化得到先数据对象(RedisData类型)
        再得到shop的类型 并且判断过期时间
         */
        RedisData redisDataShop=JSONUtil.toBean
                (shopJson,RedisData.class);
        //得到Shop 类型的反序列化字节码
        JSONObject shopJsonData =(JSONObject)redisDataShop.getData();
        //得到店铺对象
        Shop shop=JSONUtil.toBean(shopJsonData,Shop.class);
        //过期时间
        LocalDateTime expireTime=redisDataShop.getExpireTime();

        //判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //没有过期 直接返回旧的信息
            return shop;
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
                    //缓存重建:重新把数据库的数据读到缓存 带有expire
                    this.saveShopWithLogicExpireTime(id,30L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });

        }

        return shop;
    }

    //修改商店的信息 数据库和缓存都要修改 当作一个事务进行修改
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        //先要更新数据库(OI 慢)  后删除缓存
        //mybatis-plus 简单sql语句的简写 调用修改shop的sql
        updateById(shop);

        //店铺的id
        Long id=shop.getId();
        if(id==null)
            return  Result.fail("店铺id不能为空,更新失败");

        //key
        String key=CACHE_SHOP_ID_KEY+shop.getId();

        //删除缓存(L2 redis)
        stringRedisTemplate.delete(key);

        //多级缓存 L1：失效本地 Caffeine 并发布消息通知其他 Tomcat 进程(canal 也会兜底)
        localCacheManager.getShopCache().invalidate(key);
        cacheInvalidatePublisher.publish(key);

        return Result.success("修改成功");
    }

    // 按店铺类型分页查询：默认按销量热度，用户选择距离范围后才使用 Redis GEO
   @Override
    public Result queryShopByType(Integer typeId, Integer pageNumber, Double x, Double y, String distanceRange) {
        int current = pageNumber == null || pageNumber < 1 ? 1 : pageNumber;

        // 所有店铺分类统一支持两种模式：默认按热度，选择距离范围后使用 Redis GEO
        if (StrUtil.isBlank(distanceRange) || x == null || y == null) {
            // 未选择距离或定位不可用时，按销量降序返回，保证十个分类都能正常展示商家
            Page<Shop> page = query().eq("type_id", typeId).orderByDesc("sold")
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.success(page.getRecords());
        }

        // 距离范围由前端显式选择，避免页面打开时自动定位改变默认排序
        double minDistanceKm = 0D;
        double maxDistanceKm = 5D;
        switch (distanceRange) {
            case "1km" -> maxDistanceKm = 1D;
            case "2km" -> maxDistanceKm = 2D;
            case "5km" -> maxDistanceKm = 5D;
            case "over5km" -> minDistanceKm = 5D;
            default -> {
                Page<Shop> page = query().eq("type_id", typeId).orderByDesc("sold")
                        .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
                return Result.success(page.getRecords());
            }
        }

        int begin = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = GEOGRAPHY_SHOPTYPE_KEY + typeId;
        double searchRadiusKm = "over5km".equals(distanceRange) ? 50D : maxDistanceKm;

        // Redis GEO 按距离升序查询，再按用户选择的范围和页码截取
        GeoResults<RedisGeoCommands.GeoLocation<String>> resultsAll =
                stringRedisTemplate.opsForGeo().search(key, GeoReference.fromCoordinate(x, y),
                        new Distance(searchRadiusKm * 1000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                                .includeDistance().sortAscending().limit(1000));
        if (resultsAll == null || resultsAll.getContent().isEmpty()) {
            return Result.success(new ArrayList<>());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> results = resultsAll.getContent();
        // Lambda 中使用 final 局部变量，避免 switch 赋值后的变量无法被捕获
        final double selectedMinDistanceKm = minDistanceKm;
        final double selectedMaxDistanceKm = maxDistanceKm;
        final String selectedDistanceRange = distanceRange;
        results.removeIf(result -> {
            double distanceKm = result.getDistance().getValue() / 1000D;
            return "over5km".equals(selectedDistanceRange)
                    ? distanceKm < selectedMinDistanceKm
                    : distanceKm >= selectedMaxDistanceKm;
        });
        if (results.size() <= begin) return Result.success(new ArrayList<>());

        List<Long> shopIds = new ArrayList<>(end - begin);
        Map<String, Distance> distanceMap = new HashMap<>(results.size());
        results.stream().skip(begin).limit(end - begin).forEach(result -> {
            String shopId = result.getContent().getName();
            shopIds.add(Long.valueOf(shopId));
            distanceMap.put(shopId, result.getDistance());
        });

        String idStr = StrUtil.join(",", shopIds);
        List<Shop> shops = query().in("id", shopIds)
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            Distance distance = distanceMap.get(shop.getId().toString());
            if (distance != null) shop.setDistance(distance.getValue());
        }
        return Result.success(shops);
    }
}
