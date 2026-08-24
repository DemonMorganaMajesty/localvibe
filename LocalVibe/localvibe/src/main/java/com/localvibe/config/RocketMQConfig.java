package com.localvibe.config;

/**
 * RocketMQ 配置常量
 *
 * RocketMQ 部署在 WSL 内（nameserver: 172.24.116.171:9876，broker 端口 10911），
 * 用于秒杀（优惠券抢购）场景的异步削峰：抢购请求先在校验+扣减 Redis 库存后
 * 立即返回，真正落库的订单创建通过消息队列异步完成。
 *
 * 说明：本配置为 Java 常量方式（不修改 yaml/properties），nameserver 地址与 WSL 内 rocketmq 一致。
 */
public class RocketMQConfig {

    /** RocketMQ NameServer 地址（WSL 内） */
    public static final String NAME_SERVER = "172.24.116.171:9876";

    /** 秒杀订单主题：生产者发送抢购成功的订单消息，消费者异步创建数据库订单 */
    public static final String SECKILL_TOPIC = "seckill-order-topic";

    /** 秒杀订单消息 tag */
    public static final String SECKILL_TAG = "seckill";

    /** 秒杀订单生产者组 */
    public static final String SECKILL_PRODUCER_GROUP = "seckill-order-producer";

    /** 秒杀订单消费者组（同一组内消费者负载均衡消费，保证一个订单只被处理一次） */
    public static final String SECKILL_CONSUMER_GROUP = "seckill-order-consumer";
}
