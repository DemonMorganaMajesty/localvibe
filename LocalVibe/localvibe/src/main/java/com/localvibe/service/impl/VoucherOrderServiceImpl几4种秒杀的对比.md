# 四套秒杀完整分析：作用、优缺点、存在意义

公共底层：`void createVoucherOrder(VoucherOrder voucherOrder)`，扣库存、一人一单、生成订单，**4套全部复用，只写一份数据库逻辑**。

## 版本1：GetSeckillVoucher1 【同步秒杀】

**技术：Redisson分布式锁，无lua、无队列，请求全程同步阻塞**

### 作用

前端请求过来，**整个下单逻辑全部在http请求线程做完**：判断优惠券库存、加分布式锁、判断是否已经抢过券、扣数据库库存、生成订单，全部做完再返回给前端。

### 优点

- 代码最简单，好理解，没有中间件依赖。

### 缺点

1. 高并发下HTTP线程被DB操作占满，tomcat线程池打满，大量请求阻塞；
2. 大量请求直接打数据库，DB压力巨大；
3. 秒杀接口响应慢，用户等待时间长。

### 定位

项目最开始原始版本，用来理解秒杀最基础流程。

---

## 版本2：GetSeckillVoucher2 【Lua + JVM内存阻塞队列 ArrayBlockingQueue】

配套：`SECKILL_SCRIPT`、`voucherOrderBlockingDeque`、`VoucherOrderBlockQueueRunnable`、`@PostConstruct initial()`、`handleVoucherOrderBlockQueue()`

### 作用

1. 请求进来，**先执行Lua脚本在Redis做预校验：库存、一人一单**；
2. Lua校验通过，组装订单对象，放到**JVM内存队列**，立刻返回前端“抢购成功”；
3. 项目启动就开启后台单线程，循环从队列取出任务，异步执行数据库下单；
4. handleVoucherOrderBlockQueue还加Redisson锁，防止Redis宕机，Lua失效，出现超卖。

> 核心思想：**把慢的DB操作剥离http线程，实现异步削峰**。

### 优点

- http请求很快返回，Tomcat线程快速释放；
- Redis挡住大部分非法请求，DB压力下降。

### 致命缺点（面试必说）

1. **内存队列，服务重启任务直接丢失**；
2. 集群部署：每个服务实例自己持有自己的队列，请求落在哪个实例，任务就在哪个实例，实例挂掉任务全丢；
3. 没有重试、死信，消费异常任务丢失；
4. `proxy`成员变量多线程并发覆盖，线程安全bug。

### 定位

学习“异步削峰”思想，**不能上生产**，演示手写简易消息队列的弊端。

---

## 版本4：Redis Stream版本（整套废弃，你刚贴的一大段）

配套：`SECKILL_ORDER_EXECUTOR_MESSAGE_QUEQUE`、`VoucherOrderMessageQueueRunnable`、`handlePendingList()`、stream消费逻辑、PendingList处理；`@PostConstruct ini()`被注释。

### 作用

> 想解决版本2 JVM内存队列的任务丢失问题，**拿Redis做消息队列**。

1. 秒杀接口Lua校验通过，把订单消息写入Redis Stream；
2. 后台线程用`XREADGROUP`消费stream消息；
3. 业务处理完成执行ACK；处理失败消息留在PendingList；
4. 单独`handlePendingList()`循环处理pending里未确认的异常消息，实现消息重试。

### 优点

- 消息存在Redis，服务重启消息不会丢，解决JVM内存队列丢失问题；
- 自带消费组、ack、pending失败队列，原生具备消息可靠性。

### 缺点（为什么废弃，换成RocketMQ）

1. Redis本质是缓存，不是MQ；大量秒杀消息占用Redis内存，挤压缓存业务；
2. Redis Stream功能简陋：没有死信队列、消息轨迹、重试次数、监控；
3. 主从切换、集群环境下消息可靠性维护成本高；
4. 自己手写消费、pending处理，大量业务代码，容易写bug。

### 定位

**学习Redis‑Stream特性，演示用Redis实现消息队列的利弊，仅留参考，不运行。**

---

## 版本3：GetSeckillVoucher3 【Lua + RocketMQ，最终版本】

配套：`SECKILL_ROCKETMQ_SCRIPT`、`SeckillOrderMqProducer`；消费逻辑抽离独立类`SeckillOrderMqConsumer`，不在当前Service。

### 作用

1. 请求进来执行RocketMQ专用Lua脚本，Redis预校验库存、一人一单；
2. Lua校验通过，发送消息到RocketMQ，直接返回前端抢购成功；
3. RocketMQ消费者独立服务/独立线程监听消息，异步执行下单；
4. MQ发送失败时，**降级直接同步调用下单逻辑，保证消息不丢业务不丢**。

### 优点（解决前面三套全部痛点）

1. MQ持久化消息，服务重启消息不丢失；支持集群；
2. RocketMQ自带：重试机制、死信队列、消息轨迹、监控控制台；
3. 削峰填谷，流量洪峰缓冲，保护数据库；
4. Redis只做校验，消息交给专门中间件，职责分离，不侵占Redis缓存资源。

### 小缺点

需要额外部署RocketMQ中间件。

### 定位

**项目最终生产可用版本，写简历、面试主要讲这套。**

---

# 公共组件作用

1. `private IVoucherOrderService proxy=null;`

> 拿到当前serviceAop代理对象，用来调用带事务的`createVoucherOrder`；版本2、版本3降级共用，**存在线程安全bug，面试可以主动提这个问题+修复方案**。

2. `@PreDestroy stopAllConsumeThread()`

> 容器关闭时优雅关闭两套后台线程池（版本2JVM队列、版本4RedisStream），避免线程泄露。

3. `createVoucherOrder(VoucherOrder voucherOrder)`

> 数据库操作核心：扣减库存、判断用户是否已经下单、插入订单；版本2、4、3全部复用，不用重复写数据库代码。

# 面试背诵极简话术

> 一开始写同步版本，所有逻辑在http线程，数据库压力太大。
> 然后改成JVM内存队列异步削峰，但是服务重启任务丢失，集群无法使用。
> 接着尝试Redis‑Stream，把消息存Redis解决丢失，但是Redis不适合做MQ，侵入缓存，还要自己手写大量重试pending逻辑。
> 最后选用RocketMQ专业消息队列，完成最终版本，同时做发送失败降级。

如果你要，我可以帮你压缩成简历项目描述。
