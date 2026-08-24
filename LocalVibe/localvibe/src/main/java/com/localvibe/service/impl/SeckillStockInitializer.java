package com.localvibe.service.impl;

import com.localvibe.entity.SeckillVoucher;
import com.localvibe.service.ISeckillVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

import static com.localvibe.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * 秒杀库存预热组件
 *
 * 场景：秒杀券由管理员录入（tb_seckill_voucher 表），而异步秒杀（RocketMQ 削峰）的
 * 库存预扣发生在 Redis 的 seckill:stock:{voucherId} 键上。应用启动时把数据库中
 * 未同步过的秒杀券库存写入 Redis，避免 lua 脚本因库存键缺失而报错。
 *
 * 说明：只做"键不存在才写入"，不覆盖运行中已扣减的库存，也不会修改数据库。
 */
@Slf4j
@Component
public class SeckillStockInitializer implements ApplicationRunner {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<SeckillVoucher> seckillVouchers = seckillVoucherService.list();
            int synced = 0;

            for (SeckillVoucher seckillVoucher : seckillVouchers) {
                String key = SECKILL_STOCK_KEY + seckillVoucher.getVoucherId();
                // 已存在(运行中扣减过)的库存键不覆盖  不存在才需要 存入redis
                if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(key))) {
                    stringRedisTemplate.opsForValue().set(key, String.valueOf(seckillVoucher.getStock()));
                    synced++;
                }
            }
            log.info("秒杀库存预热完成: 共 {} 张秒杀券, 本次同步 {} 个库存键", seckillVouchers.size(), synced);
        } catch (Exception e) {
            // tb_seckill_voucher 表未就绪或 Redis 异常时不影响应用启动
            log.warn("秒杀库存预热失败（表不存在或连接异常，不影响启动）: {}", e.getMessage());
        }
    }
}
