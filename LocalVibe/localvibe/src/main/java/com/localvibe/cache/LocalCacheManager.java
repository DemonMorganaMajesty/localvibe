package com.localvibe.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 本地缓存管理器（Caffeine 实现）
 *
 * 多级缓存架构中的 JVM 一级缓存（L1）：
 * 浏览器 -> nginx:81 -> openresty:8085(lua 本地缓存) -> Redis -> Tomcat(Caffeine) -> MySQL
 *
 * Caffeine 各缓存与 Redis 缓存键保持一致，canal/binlog 变更或业务更新时，
 * 通过 Redis Pub/Sub（见 CacheInvalidatePublisher/CacheInvalidateListener）实现
 * 多 Tomcat 进程间的本地缓存同步失效。
 */
@Slf4j
@Component
public class LocalCacheManager {

    /**
     * 商品缓存：键为 item:id:{id}（与 openresty lua 的 Redis 键一致）
     * 30 秒过期兜底 + canal/binlog 变更主动失效
     */
    private Cache<String, String> itemCache;

    /**
     * 商品库存缓存：键为 stock:id:{id}（与 openresty lua 的 Redis 键一致）
     */
    private Cache<String, String> stockCache;

    /**
     * 店铺缓存：键为 cache:shop:id{id}（与业务 Redis 键一致）
     */
    private Cache<String, String> shopCache;

    /**
     * 店铺分类缓存：键为 cache:shopType:*（与业务 Redis 键一致）
     */
    private Cache<String, String> shopTypeCache;

    /**
     * 店铺优惠券列表缓存：键为 voucher:shop:{shopId}
     */
    private Cache<String, String> voucherCache;

    @PostConstruct
    public void init() {
        // 商品/库存：热点数据，容量大、过期短，依赖 canal 主动失效
        itemCache = Caffeine.newBuilder()
                .maximumSize(20_000)
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .build();
        stockCache = Caffeine.newBuilder()
                .maximumSize(20_000)
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .build();
        // 店铺/分类/优惠券：变更不频繁，容量适中
        shopCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .build();
        shopTypeCache = Caffeine.newBuilder()
                .maximumSize(1_000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build();
        voucherCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .build();
        log.info("Caffeine 本地缓存初始化完成（多级缓存 L1）");
    }

    public Cache<String, String> getItemCache() { return itemCache; }
    public Cache<String, String> getStockCache() { return stockCache; }
    public Cache<String, String> getShopCache() { return shopCache; }
    public Cache<String, String> getShopTypeCache() { return shopTypeCache; }
    public Cache<String, String> getVoucherCache() { return voucherCache; }

    /**
     * 按缓存键失效所有本地缓存（进程间同步：Redis Pub/Sub 收到消息后调用）
     * 多个缓存共用同一套键前缀规范，逐个 invalidate 开销很小，保证一致性
     *
     * @param key 缓存键，如 item:id:10001
     */
    public void invalidateAll(String key) {
        itemCache.invalidate(key);
        stockCache.invalidate(key);
        shopCache.invalidate(key);
        shopTypeCache.invalidate(key);
        voucherCache.invalidate(key);
        log.debug("Caffeine 本地缓存已失效, key: {}", key);
    }

    /**
     * 按前缀失效本地缓存（用于列表类缓存，如 cache:shopType:*）
     *
     * @param prefix 键前缀
     */
    public void invalidateByPrefix(String prefix) {
        itemCache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
        stockCache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
        shopCache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
        shopTypeCache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
        voucherCache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
        log.debug("Caffeine 本地缓存已按前缀失效, prefix: {}", prefix);
    }

    /**
     * 清空全部本地缓存（一般用于系统初始化或手工刷新）
     */
    public void clearAll() {
        itemCache.invalidateAll();
        stockCache.invalidateAll();
        shopCache.invalidateAll();
        shopTypeCache.invalidateAll();
        voucherCache.invalidateAll();
        log.info("Caffeine 本地缓存已全部清空");
    }
}
