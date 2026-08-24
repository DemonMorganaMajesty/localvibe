package com.localvibe.mq;

import cn.hutool.json.JSONUtil;
import com.localvibe.config.RocketMQConfig;
import com.localvibe.entity.VoucherOrder;
import com.localvibe.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;

/**
 * 秒杀订单消息消费者（RocketMQ）
 *
 * 消费 seckill-order-topic 中的订单消息，异步创建数据库订单。
 * 消费失败返回 RECONSUME_LATER，由 RocketMQ 自动重试，保证订单不丢失。
 */
@Slf4j
@Component
public class SeckillOrderMqConsumer implements InitializingBean, DisposableBean {

    @Resource
    private IVoucherOrderService voucherOrderService;
    //RocketMQ 原生推送模式消费者，broker 主动把消息推送给客户端。
    private DefaultMQPushConsumer consumer;

    //SpringBean 属性注入完成后执行afterPropertiesSet()，手动构建、启动消费者。
    @Override
    public void afterPropertiesSet() {
        /* 前端秒杀请求过来，Redis 预扣库存，生成VoucherOrder对象，发送消息到 RocketMQ；
        直接返回给用户下单成功。MQ 消费者异步消费消息，真正操作数据库：创建优惠券订单、扣数据库库存。
        削峰填谷，把高并发请求异步化，避免大量请求直接打数据库。
         */
        try {
            //指定消费组；
            DefaultMQPushConsumer c = new DefaultMQPushConsumer(RocketMQConfig.SECKILL_CONSUMER_GROUP);
            //设置 NameServer 地址，找到 Broker；
            c.setNamesrvAddr(RocketMQConfig.NAME_SERVER);
            //订阅该 topic 全部 tag 的消息。
            c.subscribe(RocketMQConfig.SECKILL_TOPIC, "*");
            // 将批量大小限制为1，避免批次内一条消息失败导致整批成功消息重复重试
            c.setConsumeMessageBatchMaxSize(1);

            //并发消费监听器。
            c.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
                for (MessageExt msg : msgs) {
                    try {
                        VoucherOrder voucherOrder = JSONUtil.toBean(
                                new String(msg.getBody(), StandardCharsets.UTF_8), VoucherOrder.class);
                        // 复用异步下单逻辑：幂等校验 + 乐观锁扣减 DB 库存 + 插入订单
                        voucherOrderService.createVoucherOrder(voucherOrder);
                        log.info("秒杀订单消费成功: orderId={}", voucherOrder.getId());
                    } catch (cn.hutool.json.JSONException e) {
                        // JSON 已损坏，重试不会改变消息内容，记录后丢弃，避免无限重试形成死信。
                        log.error("秒杀订单消息 JSON 解析失败，丢弃坏消息: msgId={}", msg.getMsgId(), e);
                        continue;
                    } catch (Exception e) {
                        // 业务异常才要求 Broker 稍后重新投递；当前批次只有一条消息，不会拖累其他消息。
                        log.error("秒杀订单消费失败，稍后自动重试: msgId={}", msg.getMsgId(), e);
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                //消费成功，Broker 更新 offset，这条消息不再投递。
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            c.start();
            this.consumer = c;
            //启动消费者异常只打 warn，不抛出异常，Spring 应用照样启动。
            log.info("RocketMQ 秒杀订单消费者启动成功, namesrv={}, topic={}, group={}",
                    RocketMQConfig.NAME_SERVER, RocketMQConfig.SECKILL_TOPIC, RocketMQConfig.SECKILL_CONSUMER_GROUP);
        } catch (Exception e) {
            // MQ 未就绪时不阻塞应用启动；MQ 恢复后重启应用即可消费积压消息
            log.warn("RocketMQ 秒杀订单消费者启动失败（应用照常启动）: {}", e.getMessage());
        }
    }

    //容器销毁时执行destroy()，关闭 consumer，优雅停机。
    @Override
    public void destroy() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}
