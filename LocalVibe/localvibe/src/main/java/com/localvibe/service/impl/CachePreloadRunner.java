package com.localvibe.service.impl;

import cn.hutool.json.JSONUtil;
import com.localvibe.cache.LocalCacheManager;
import com.localvibe.entity.Shop;
import com.localvibe.entity.ShopType;
import com.localvibe.service.IShopService;
import com.localvibe.service.IShopTypeService;
import com.localvibe.utils.CacheClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.localvibe.utils.RedisConstants.*;

/**
 * 缓存热启动组件（Redis 预热）
 *
 * 场景：店铺分类、店铺、店铺地理坐标(GEO) 属于"一般不会经常改变"的数据。
 * 应用启动时将它们一次性写入 Redis(db7) 与 Caffeine(L1)，
 * 避免冷启动后第一个用户请求直接打到 MySQL，提高首屏响应速度。
 *
 * 预热内容：
 *  1. 店铺分类(10个) -> Redis hash/list/string 三种结构 + Caffeine
 *  2. 店铺(全部)     -> Redis cache:shop:id{id}(逻辑过期格式) + Caffeine
 *  3. 店铺坐标       -> Redis GEO geography:shopType:{typeId}（附近店铺距离查询用）
 *
 * 一致性说明：canal 监听 tb_shop / tb_shop_type 变更时仍会主动失效上述缓存，
 * 热启动只是"提前把数据库数据刷进缓存"，不改变既有的失效机制。
 *
 * @author 改造新增
 */
@Slf4j
@Component
public class CachePreloadRunner implements ApplicationRunner {

    @Resource
    private IShopTypeService shopTypeService;

    @Resource
    private IShopService shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private LocalCacheManager localCacheManager;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            preloadShopType();
            preloadShop();
            preloadShopGeo();
            log.info("Redis 热启动预热完成（店铺分类/店铺/地理坐标）");
        } catch (Exception e) {
            // 预热失败只记录日志，不影响应用正常启动
            log.warn("Redis 热启动预热失败（表未就绪或 Redis 异常，不影响启动）: {}", e.getMessage());
        }
    }

    /**
     * 预热店铺分类：Redis 三种结构(cache:shopType:hash/list/string) + Caffeine L1
     */
    private void preloadShopType() {
        // 按 sort 升序查询全部分类（与 selectByRedisHash 的查询顺序一致）
        List<ShopType> shopTypes = shopTypeService.query().orderByAsc("sort").list();
        if (shopTypes == null || shopTypes.isEmpty()) {
            log.warn("热启动：tb_shop_type 无数据，跳过分类预热");
            return;
        }
        // hash 结构：field=分类id value=分类JSON
        Map<String, String> hashMap = new HashMap<>(shopTypes.size());
        // list 结构：按 sort 顺序保存的 JSON 列表
        List<String> listValues = new ArrayList<>(shopTypes.size());
        for (ShopType shopType : shopTypes) {
            String json = JSONUtil.toJsonStr(shopType);
            hashMap.put(shopType.getId().toString(), json);
            listValues.add(json);
        }
        //redis 三种的数据类型 String(json) hash list  都存进去了
        stringRedisTemplate.opsForHash().putAll(CATHE_SHOPTYPE_HASH_KEY, hashMap);
        stringRedisTemplate.delete(CATHE_SHOPTYPE_LIST_KEY);
        stringRedisTemplate.opsForList().rightPushAll(CATHE_SHOPTYPE_LIST_KEY, listValues);

        // string 结构：整体 JSON 列表
        stringRedisTemplate.opsForValue().set(CATHE_SHOPTYPE_STRING_KEY, JSONUtil.toJsonStr(shopTypes));
        // 写回 Caffeine L1（键与 redis hash 键保持一致，canal 变更时统一失效）
        localCacheManager.getShopTypeCache().put(CATHE_SHOPTYPE_HASH_KEY, JSONUtil.toJsonStr(shopTypes));
        log.info("热启动：店铺分类已预热 {} 个 -> Redis + Caffeine", shopTypes.size());
    }

    /**
     * 预热店铺：Redis cache:shop:id{id}(逻辑过期格式) + Caffeine L1
     */
    private void preloadShop() {
        List<Shop> shops = shopService.list();
        if (shops == null || shops.isEmpty()) {
            log.warn("热启动：tb_shop 无数据，跳过店铺预热");
            return;
        }
        int cached = 0;
        for (Shop shop : shops) {
            String key = CACHE_SHOP_ID_KEY + shop.getId();
            // 已存在(运行中已写入)的键不覆盖，避免打断运行中的缓存状态
            // 不存在的要设置 预启动
            if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(key))) {
                // 与 CacheClient.selectWithLogicExpire 使用同一逻辑过期格式
                cacheClient.setWithLogicExpire(key, shop, CACHE_SHOP_TTL, TimeUnit.SECONDS);
                cached++;
            }
            // L1 Caffeine 直接回填（读取路径与 selectById 的 L1 键一致）
            localCacheManager.getShopCache().put(key, JSONUtil.toJsonStr(shop));
        }
        log.info("热启动：店铺已预热 {} 家（本次写入 Redis {} 个键）", shops.size(), cached);
    }

    /**
     * 预热店铺地理坐标：Redis GEO geography:shopType:{typeId}（附近店铺按距离查询用）
     */
    private void preloadShopGeo() {
        List<Shop> shops = shopService.list();
        if (shops == null || shops.isEmpty()) {
            return;
        }
        int geoCount = 0;
        for (Shop shop : shops) {
            // x=经度 y=纬度，member 存店铺 id（与 queryShopByType 的读取约定一致）
            if (shop.getX() == null || shop.getY() == null || shop.getTypeId() == null) {
                continue;
            }
            String key = GEOGRAPHY_SHOPTYPE_KEY + shop.getTypeId();
            stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
            geoCount++;
        }
        log.info("热启动：店铺地理坐标已预热 {} 条 -> Redis GEO", geoCount);
    }
}