package com.localvibe.service.impl;

import cn.hutool.json.JSONUtil;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.localvibe.dto.Result;
import com.localvibe.entity.Voucher;
import com.localvibe.mapper.VoucherMapper;
import com.localvibe.entity.SeckillVoucher;
import com.localvibe.service.ISeckillVoucherService;
import com.localvibe.service.IVoucherService;
import com.localvibe.cache.CacheInvalidatePublisher;
import com.localvibe.cache.LocalCacheManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.List;

import static com.localvibe.utils.RedisConstants.SECKILL_STOCK_KEY;


//添加优惠券 一般是管理人员做 可以用postman模拟管理人员添加优惠券
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    //用于把优惠券直接 存入redis 实现订单秒杀的异步操作优化
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //多级缓存 L1 本地缓存管理(Caffeine) 与缓存失效消息发布
    @Resource
    private LocalCacheManager localCacheManager;

    @Resource
    private CacheInvalidatePublisher cacheInvalidatePublisher;

    /**
     * 首页秒杀专区：查询所有上架的秒杀券（含秒杀信息与店铺名称/图片）
     */
    @Override
    public Result querySeckillVoucherList() {
        List<Voucher> vouchers = getBaseMapper().querySeckillVouchers();
        System.out.println("DEBUG: querySeckillVouchers result size: " + (vouchers != null ? vouchers.size() : "NULL"));
        return Result.success(vouchers);
    }

    //查询 优惠券 caffine
    @Override
    public Result queryVoucherOfShop(Long shopId) {
        //多级缓存 L1：先查 Caffeine 本地缓存(店铺优惠券列表,键: voucher:shop:{shopId})
        String cacheKey="voucher:shop:"+shopId;
        String localJson=localCacheManager.getVoucherCache().getIfPresent(cacheKey);
        if(localJson!=null){
            return Result.success(JSONUtil.toList(localJson,Voucher.class));
        }

        // 查询数据库 优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 写回 L1 Caffeine(canal 监听 tb_voucher/tb_seckill_voucher 变更时统一失效)
        if(vouchers!=null && !vouchers.isEmpty()){
            localCacheManager.getVoucherCache().put(cacheKey,JSONUtil.toJsonStr(vouchers));
        }
        // 返回结果
        return Result.success(vouchers);
    }

    @Override
    public void addVoucher(Voucher voucher) {
        //mybatis-plus
        save(voucher);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        //保存秒杀优惠券的库存 用于异步实现订单秒杀
        stringRedisTemplate.opsForValue().set
                (SECKILL_STOCK_KEY+voucher.getId(),voucher.getStock().toString());

        //秒杀扣库存：只用 Redis，完全不碰 Caffeine，绝对不能把库存放本地缓存，多实例会超卖
        //新增/变更秒杀券后 失效店铺优惠券列表本地缓存(canal 也会兜底失效)
        localCacheManager.getVoucherCache().invalidateAll();
        cacheInvalidatePublisher.publish("voucher:shop:*");
    }
}
