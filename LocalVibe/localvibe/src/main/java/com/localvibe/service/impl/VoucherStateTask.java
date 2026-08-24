package com.localvibe.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localvibe.entity.SeckillVoucher;
import com.localvibe.entity.VoucherOrder;
import com.localvibe.mapper.VoucherOrderMapper;
import com.localvibe.service.ISeckillVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;


@Slf4j
@Component
public class VoucherStateTask {

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final int UNPAID_TIMEOUT_MINUTES = 30;
    private static final int BATCH_SIZE = 100;
    private static final String CLOSE_LOCK_KEY = "lock:task:voucher-order-close";
    private static final String REFUND_MARKER_PREFIX = "voucher:order:stock-refunded:";

    /**
     * 订单级幂等回补脚本：只有第一次执行时写入回补标记并增加 Redis 库存。
     * 后续定时任务或补偿扫描再次处理同一订单时，只返回 0，不会重复加库存。
     */
    private static final DefaultRedisScript<Long> REFUND_STOCK_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('SETNX', KEYS[1], '1') == 1 then " +
                            "redis.call('INCRBY', KEYS[2], 1); return 1; " +
                            "end; return 0;",
                    Long.class);

    @Scheduled(fixedDelay = 120_000, initialDelay = 120_000)
    public void cancelStaleUnpaidOrders() {
        RLock lock = redissonClient.getLock(CLOSE_LOCK_KEY);
        boolean locked = false;
        try {
            // 集群部署时只允许一个实例执行本轮任务，避免多实例重复回补库存
            locked = lock.tryLock();
            if (!locked) {
                return;
            }

            LocalDateTime deadline = LocalDateTime.now().minusMinutes(UNPAID_TIMEOUT_MINUTES);
            int totalCancelled = 0;
            while (true) {
                // 每次从第一页取一批，更新后继续取第一页，避免 offset 分页漏处理
                Page<VoucherOrder> page = new Page<>(1, BATCH_SIZE, false);
                QueryWrapper<VoucherOrder> query = new QueryWrapper<VoucherOrder>()
                        .eq("status", 1)
                        .lt("create_time", deadline)
                        .orderByAsc("create_time");
                List<VoucherOrder> orders = voucherOrderMapper.selectPage(page, query).getRecords();
                if (orders == null || orders.isEmpty()) {
                    break;
                }

                int batchCancelled = 0;
                for (VoucherOrder order : orders) {
                    // 条件更新保证同一订单只会被成功关闭一次
                    int updated = voucherOrderMapper.update(null, new UpdateWrapper<VoucherOrder>()
                            .eq("id", order.getId())
                            .eq("status", 1)
                            .set("status", 4)
                            .set("update_time", LocalDateTime.now()));
                    if (updated != 1) {
                        continue;
                    }

                    batchCancelled++;
                    // 订单关闭后，使用订单级幂等脚本回补秒杀券 Redis 预扣库存
                    refundSeckillStock(order);
                }

                totalCancelled += batchCancelled;
                if (orders.size() < BATCH_SIZE) {
                    break;
                }
            }
            if (totalCancelled > 0) {
                log.info("超时未支付订单关闭完成: {} 条", totalCancelled);
            }

            // 扫描已取消订单，补偿数据库状态已更新但 Redis 回补尚未完成的异常订单
            repairCancelledOrderStock();
        } catch (Exception e) {
            log.warn("超时订单关闭定时任务异常,{}", e.getMessage());
        } finally {
            // 只释放当前线程持有的锁，避免误释放其他实例的锁
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 补偿扫描已取消订单。
     *
     * 数据库状态更新和 Redis 操作不在同一个事务中，因此可能出现：
     * 数据库已经是已取消，但应用在回补 Redis 前宕机。通过订单级 Redis 标记
     * 保证重复扫描不会重复增加库存，补偿扫描则负责最终把遗漏订单补回来。
     */
    private void repairCancelledOrderStock() {
        Page<VoucherOrder> page = new Page<>(1, BATCH_SIZE, false);
        QueryWrapper<VoucherOrder> query = new QueryWrapper<VoucherOrder>()
                .eq("status", 4)
                .orderByAsc("update_time");
        List<VoucherOrder> cancelledOrders = voucherOrderMapper.selectPage(page, query).getRecords();
        if (cancelledOrders == null) {
            return;
        }
        for (VoucherOrder order : cancelledOrders) {
            refundSeckillStock(order);
        }
    }

    /**
     * 仅对秒杀券回补 Redis 预扣库存；普通券没有 Redis 预扣库存，不参与回补。
     */
    private void refundSeckillStock(VoucherOrder order) {
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(order.getVoucherId());
        if (seckillVoucher == null) {
            return;
        }
        String markerKey = REFUND_MARKER_PREFIX + order.getId();
        String stockKey = com.localvibe.utils.RedisConstants.SECKILL_STOCK_KEY + order.getVoucherId();
        Long refunded = stringRedisTemplate.execute(
                REFUND_STOCK_SCRIPT,
                java.util.Arrays.asList(markerKey, stockKey),
                markerKey, stockKey);
        if (Long.valueOf(1L).equals(refunded)) {
            log.info("已回补超时取消秒杀订单库存: orderId={}, voucherId={}",
                    order.getId(), order.getVoucherId());
        }
    }
}
