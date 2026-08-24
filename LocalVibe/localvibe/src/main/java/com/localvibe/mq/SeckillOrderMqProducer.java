package com.localvibe.mq;

import cn.hutool.json.JSONUtil;
import com.localvibe.config.RocketMQConfig;
import com.localvibe.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.Message;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;

/**
 * 秒杀订单消息生产者（RocketMQ）
 *
 * 高并发抢购削峰流程：
 * 请求 -> Redis lua 校验/扣库存（秒级返回） -> 发送本消息 -> 消费者异步创建订单
 * 生产者启动失败时不阻塞应用启动，发送失败由调用方决定降级策略。
 */
@Slf4j
@Component
public class SeckillOrderMqProducer {
    //原生 RocketMQ DefaultMQProducer，不是 rocketmq‑starter 封装，手动管理生产者生命周期。
    private DefaultMQProducer producer;
    //多线程可见性，防止主线程、业务线程看到started变量的缓存值，保证状态对所有线程实时可见。
    private volatile boolean started = false;

    @PostConstruct
    public void init() {
        try {
            //生成者组
            DefaultMQProducer p = new DefaultMQProducer(RocketMQConfig.SECKILL_PRODUCER_GROUP);
            //设置 NameServer 地址，找到 Broker；
            p.setNamesrvAddr(RocketMQConfig.NAME_SERVER);
            //发送超时3秒
            p.setSendMsgTimeout(3000);
            //同步发送失败，内部重试2次
            p.setRetryTimesWhenSendFailed(2);
            //异步发送失败重试2次
            p.setRetryTimesWhenSendAsyncFailed(2);
            p.start();
            this.producer = p;
            this.started = true;
            log.info("RocketMQ 秒杀订单生产者启动成功, namesrv={}, group={}",
                    RocketMQConfig.NAME_SERVER, RocketMQConfig.SECKILL_PRODUCER_GROUP);
        } catch (Exception e) {
            // MQ 未就绪时不阻塞应用启动，发送时返回 false 由业务降级
            log.warn("RocketMQ 秒杀订单生产者启动失败（应用照常启动，秒杀将降级处理）: {}", e.getMessage());
        }
    }

    /**
     * 异步发送普通 RocketMQ 消息。
     *
     * 秒杀请求已经在 Redis Lua 中完成资格确认，不需要事务消息或半消息；
     * 普通消息成功写入 Broker 后会持久化，生产者服务宕机也不影响消费者继续处理。
     * 使用 SendCallback 避免同步 send 长时间占用 Tomcat 业务线程。
     *
     * @param voucherOrder 抢购成功的订单（含 id/userId/voucherId）
     * @param failureCallback 发送失败时的 Redis 预扣补偿逻辑
     */
    public void sendSeckillOrderAsync(VoucherOrder voucherOrder, Runnable failureCallback) {
        if (!started || producer == null) {
            log.warn("RocketMQ 生产者未就绪，秒杀订单消息发送失败: orderId={}", voucherOrder.getId());
            failureCallback.run();
            return;
        }
        try {
            Message message = new Message(
                    RocketMQConfig.SECKILL_TOPIC,
                    RocketMQConfig.SECKILL_TAG,
                    voucherOrder.getId().toString(),
                    JSONUtil.toJsonStr(voucherOrder).getBytes(StandardCharsets.UTF_8));
            producer.send(message, new SendCallback() {
                @Override
                public void onSuccess(SendResult result) {
                    log.info("秒杀订单消息异步发送成功: orderId={}, sendStatus={}",
                            voucherOrder.getId(), result.getSendStatus());
                }

                @Override
                public void onException(Throwable e) {
                    log.error("秒杀订单消息异步发送失败: orderId={}", voucherOrder.getId(), e);
                    failureCallback.run();
                }
            });
        } catch (Exception e) {
            log.error("秒杀订单消息发送异常: orderId={}", voucherOrder.getId(), e);
            failureCallback.run();
        }
    }

    @PreDestroy
    public void destroy() {
        if (producer != null) {
            producer.shutdown();
        }
    }
}
