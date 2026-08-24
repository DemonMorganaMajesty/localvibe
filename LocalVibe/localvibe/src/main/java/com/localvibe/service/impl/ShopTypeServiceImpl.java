package com.localvibe.service.impl;

import cn.hutool.json.JSONUtil;
import com.localvibe.entity.ShopType;
import com.localvibe.mapper.ShopTypeMapper;
import com.localvibe.cache.LocalCacheManager;
import com.localvibe.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.localvibe.utils.RedisConstants.*;


@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    //多级缓存 L1 本地缓存管理(Caffeine)
    @Resource
    LocalCacheManager localCacheManager;

    /*这里的函数返回类型是集合 也可以返回Result 商品的种类一般不多内存不大
    所以可以不用设置有效的期限
     */
    //把店铺的分类 写入到redis 缓存
    @Override
    public List<ShopType> selectByRedisString() {
        /*把value 保存为String 类型:所有数据都直接是String的序列化形式
        但是返回前端 需要都转化为 集合的形式
         */
        String key=CATHE_SHOPTYPE_STRING_KEY;

        //判断redis 里面有没有数据
        String shopTypeJson=stringRedisTemplate.opsForValue().get(key);

        //redis中保存有数据 直接返回集合
        if(shopTypeJson!=null){
            return JSONUtil.toList(shopTypeJson,ShopType.class);
        }

        /*redis中没有数据 去数据库查找数据直接返回集合 按照sort的类型升序排序
        sort 数字代表种类
         */
        List<ShopType> shopTypeList=this.query().
                orderByAsc("sort").list();

        //数据库中数据为空
        if(shopTypeList.isEmpty()){
            return null;
        }

        //把数据库中的数据 写入redis缓存
        stringRedisTemplate.opsForValue().set(key,
                JSONUtil.toJsonStr(shopTypeList));

        //设置有效期限 非必须 因为种类不多 内存不大 所占空间比较少
        //stringRedisTemplate.expire(key,CACHE_SHOPTYPE_TTL, TimeUnit.DAYS);

        return shopTypeList;
    }

    //redis value存入的数据类型为list
    @Override
    public List<ShopType> selectByRedisList() {
         /*把value 保存为List 类型:每个店铺的数据都直接是一个list元素
         */
        String key=CATHE_SHOPTYPE_LIST_KEY;

        //判断redis 里面有没有数据
        List<String> shopTypeListRedis=stringRedisTemplate.opsForList().
                range(key,0,-1);

        /*redis中有数据 因为是按照list保存的 都会把所有的商品分类都
        传递到redis 不会出现只把一些数据库的数据传递到redis 所以
        shopTypeListRedis 不为空就可以直接返回了
         */
        if(!shopTypeListRedis.isEmpty()&&shopTypeListRedis.size()>0 ){
          List<ShopType> shopTypeList=new ArrayList<>();

          //把缓存里的数据保存shopTypeList进去  返回给controller
          for(String s:shopTypeListRedis)
              shopTypeList.add(JSONUtil.toBean(s,ShopType.class));

          //直接返回集合
          return shopTypeList;
        }

        /*redis中没有数据 去数据库查找数据直接返回集合 按照sort的类型升序排序
        sort 数字代表种类
         */
        List<ShopType> shopTypeList=this.query().
                orderByAsc("sort").list();
        //没有数据直接返回null
        if(shopTypeList.isEmpty()||shopTypeList.size()==0)
            return null;

        //把数据库的数据ShopType类型转化为string json(redis保存的格式)
        shopTypeListRedis=new ArrayList<>();
        for(ShopType shopType:shopTypeList){
            shopTypeListRedis.add(JSONUtil.toJsonStr(shopType));
        }

        //在reids 以键值对 key=String value=list的格式写入数据
        stringRedisTemplate.opsForList().rightPushAll
                (key,shopTypeListRedis);

        /*stringRedisTemplate.opsForList().set(key,shopTypeListRedis);
        不行 set的第二个数据的格式是 <String,String,String> 的泛型
        key listKey listValue
         */
        //设置有效期限 非必须 因为种类不多 内存不大 所占空间比较少
        //stringRedisTemplate.expire(key,CACHE_SHOPTYPE_TTL, TimeUnit.DAYS);
        return shopTypeList;
    }

    //redis的 value的数据类型为hash  其他仅为示范 这个作为应用
    @Override
    public List<ShopType> selectByRedisHash() {
        /*把value 保存为hash 类型:每个店铺的数据都直接是一个hK-hv元素
         */
        String key=CATHE_SHOPTYPE_HASH_KEY;

        //多级缓存 L1：先查 Caffeine 本地缓存(进程内,键与redis一致)
        String localJson=localCacheManager.getShopTypeCache().getIfPresent(CATHE_SHOPTYPE_HASH_KEY);
        if(localJson!=null){
            return JSONUtil.toList(localJson,ShopType.class);
        }

        // key hashKey hashValue 的三元键值对
        HashOperations<String,String,String> keyHashOperations=
                stringRedisTemplate.opsForHash();

        //得到key 的hashKey hashValue map集合
       Map<Object,Object>valueHashMapObject= stringRedisTemplate.
                opsForHash().entries(key);

       //Map<String,String>valueHashMapString= keyHashOperations.entries(key);

       //最终的返回的集合
       List<ShopType> shopTypeList=new ArrayList<>();
       //如果map非空 将hv转化为ShopType 直接返回数据
        if(!valueHashMapObject.isEmpty()){
            for(Object hashValue:valueHashMapObject.values()){
                shopTypeList.add(JSONUtil.toBean
                        ((String) hashValue,ShopType.class));
            }

            /*redis的hashset 排序,这只是在给前端返回的结果排序,redis内任然无序
            redis 的sortedSet才可以实现redis数据库的排序
             */
            shopTypeList.sort((o1,o2)->
                    Long.compare(o1.getId(),o2.getId()));
            return shopTypeList;
        }

        /*redis中没有数据 去数据库查找数据直接返回集合 按照sort的类型升序排序
        sort 数字代表种类
         */
        shopTypeList=this.query().orderByAsc("sort").list();
        //没有数据直接返回null
        if(shopTypeList.isEmpty()||shopTypeList.size()==0)
            return null;

        //把数据库的数据ShopType类型转化为string json(redis保存的格式)
        Map<String,String> shopTypeMapRedis =new HashMap<>();
        for(ShopType shopType:shopTypeList){
            shopTypeMapRedis.put(shopType.getId().toString()
                    ,JSONUtil.toJsonStr(shopType));
        }

        //在redis 以键值对 key=String value=hash的格式写入数据
        stringRedisTemplate.opsForHash().putAll(key,shopTypeMapRedis);

        //设置有效期限 非必须 因为种类不多 内存不大 所占空间比较少
        //stringRedisTemplate.expire(key,CACHE_SHOPTYPE_TTL, TimeUnit.DAYS);

        //写回 L1 Caffeine(键与redis一致,canal/变更时统一失效)
        localCacheManager.getShopTypeCache().put(key,JSONUtil.toJsonStr(shopTypeList));

        return shopTypeList;
    }

}
