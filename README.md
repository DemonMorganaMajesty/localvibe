# 城遇（LocalVibe）—— 社区电商平台 技术需求文档

> 版本：v2.0 | 更新日期：2026-08-24 | 项目状态：迭代中

---

## 一、项目概述

### 1.1 项目背景
城遇是一个结合 **本地生活探店** 与 **优惠券秒杀** 的社区电商平台。用户可发布探店笔记、关注达人、签到获取积分，并参与限时秒杀抢购优惠券。平台需应对瞬时高并发流量（如秒杀活动），同时保证数据一致性、缓存高效性和系统弹性。

### 1.2 项目目标
- 构建 **多级缓存体系**（OpenResty → Caffeine → Redis → MySQL），提升热点数据读取性能，降低数据库压力。
- 实现 **高并发秒杀** 能力：采用 Redis Lua 原子扣减 + RocketMQ 异步削峰，确保库存不超卖、订单不重复。
- 保证 **缓存最终一致性**：通过 Canal 订阅 binlog 主动失效缓存，解决多级缓存数据不一致问题。
- 提供 **社交互动功能**：探店笔记发布、关注 Feed 流、签到积分、基于 GEO 的商家检索。
- 确保 **幂等性** 与 **系统容错**：消息发送失败补偿、数据库唯一索引兜底、冷启动预热。

### 1.3 用户角色

| 角色 | 描述 |
|------|------|
| 普通用户 | 浏览店铺/笔记、签到、参与秒杀、发布探店笔记、关注/取消关注 |
| 商家管理员 | 发布/管理优惠券、查看秒杀订单、管理店铺信息 |
| 系统管理员 | 监控缓存命中率、MQ 堆积情况、Canal 运行状态、系统告警处理 |

---

## 二、业务需求

### 2.1 功能需求

| 编号 | 模块 | 需求描述 | 优先级 |
|------|------|----------|--------|
| FR-01 | 用户登录鉴权 | 支持手机号 + 验证码登录，JWT 令牌生成与刷新 | P0 |
| FR-02 | 店铺浏览与 GEO 检索 | 根据用户位置（经纬度）按距离搜索附近商家，支持分类筛选、分页 | P0 |
| FR-03 | 探店笔记发布 | 用户可上传图文/视频笔记，关联店铺，支持点赞、评论、分享 | P1 |
| FR-04 | 关注与 Feed 流 | 用户关注达人后，在个人 Feed 流中查看被关注者的笔记动态（按时间倒序） | P1 |
| FR-05 | 签到积分系统 | 每日签到获取积分，连续签到奖励递增，积分可用于兑换优惠券或参与秒杀资格 | P1 |
| FR-06 | 优惠券秒杀 | 商家发布限时秒杀优惠券，用户在规定时间内抢购，先到先得 | P0 |
| FR-07 | 秒杀订单管理 | 用户可查看自己的秒杀订单（待支付/已支付/已失效），支付超时自动释放库存 | P0 |
| FR-08 | 秒杀结果通知 | 秒杀成功/失败通过站内消息或推送通知用户 | P2 |
| FR-09 | 缓存预热与更新 | 系统启动时自动预热店铺分类、热门店铺、秒杀库存等信息；数据变更时实时或异步更新缓存 | P0 |
| FR-10 | 数据一致性保障 | 缓存与数据库最终一致，当缓存更新失败时有补偿机制（Canal 兜底） | P0 |

---

## 三、技术架构

### 3.1 整体架构图
┌─────────────────────────────────────────────────────────────────┐
│ 前端（Vue/小程序） │
└─────────────────────────────────────────────────────────────────┘
│
▼
┌─────────────────────────────────────────────────────────────────┐
│ OpenResty（L0 网关层） │
│ - 静态资源缓存（店铺分类、热门店铺等） │
│ - 限流/防刷（秒杀入口） │
└─────────────────────────────────────────────────────────────────┘
│
▼
┌─────────────────────────────────────────────────────────────────┐
│ Spring Boot 应用集群（L1 + L2 缓存） │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │ Caffeine 本地缓存（L1） —— JVM 堆内缓存 │ │
│ └──────────────────────────────────────────────────────────┘ │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │ Redis 哨兵集群（L2） —— 集中式缓存 │ │
│ │ - 店铺详情、分类、GEO 坐标、秒杀库存、用户签到等 │ │
│ └──────────────────────────────────────────────────────────┘ │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │ 业务逻辑层（Controller → Service → DAO） │ │
│ │ - 秒杀核心（Lua 脚本） │ │
│ │ - RocketMQ 生产者/消费者 │ │
│ │ - Canal 客户端（监听 binlog） │ │
│ └──────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
│
┌────────────────────┼────────────────────┐
▼ ▼ ▼
┌──────────┐ ┌──────────┐ ┌──────────┐
│ MySQL │ │ RocketMQ │ │ Canal │
│ (持久层) │ │ (消息队列)│ │ (binlog) │
└──────────┘ └──────────┘ └──────────┘

text

### 3.2 技术栈选型明细

| 层级 | 技术 | 版本 | 选型理由 |
|------|------|------|----------|
| 后端框架 | Spring Boot 3.x + Spring MVC | 3.2+ | 成熟稳定，支持异步处理与 AOP |
| ORM | MyBatis-Plus | 3.5+ | 简化 CRUD，支持分页、逻辑删除 |
| 数据库 | MySQL 8.0 | 8.0.33+ | 支持事务、行锁、binlog 格式 ROW |
| 缓存（远程） | Redis 哨兵模式 | 7.0+ | 高可用，自动故障转移，支持 Lua 脚本、GEO、Bitmap |
| 缓存（本地） | Caffeine | 3.1+ | 高性能 JVM 缓存，支持 TTL、最大容量 |
| 网关缓存 | OpenResty | 1.21.4.1 | 基于 Nginx + Lua，可实现高级缓存策略 |
| 消息队列 | RocketMQ | 4.9.5+ | 高吞吐、低延迟，支持事务消息、死信队列 |
| 数据同步 | Canal | 1.1.8+ | 监听 MySQL binlog，实现缓存失效、数据同步 |
| 分布式锁（备选） | Redisson | 3.26+ | 提供可重入锁，用于非秒杀场景的并发控制 |
| 序列化 | Jackson | 2.15+ | 高效 JSON 序列化 |
| 连接池 | HikariCP | 5.0+ | 高性能数据库连接池 |
| 日志 | Logback + SLF4J | - | 统一日志框架 |

---

## 四、核心模块详细设计

### 4.1 秒杀模块

#### 4.1.1 整体流程
用户发起秒杀请求
│
▼
Lua 脚本（Redis 单线程原子执行）：

校验活动是否有效（时间范围）

校验库存是否充足

校验用户是否已抢购（一人一单）

预扣库存（decr）

记录用户抢购资格（setbit / set）

返回成功/失败
│
├────── 失败 ────► 直接返回“已抢光/已参与”
│
▼ 成功
发送 RocketMQ 消息（秒杀成功事件）
│
├────── 发送失败 ──► 触发补偿：回滚 Redis 库存 + 移除资格标记，返回“系统繁忙”
│
▼ 消息正常发送
立即返回“抢购中，请稍后查看订单”
│
▼ 消费者异步处理

创建订单（状态：待支付）

扣减数据库库存（乐观锁/行锁）

订单支付超时（如 15 分钟）自动取消，恢复库存（由定时任务或延迟消息处理）

text

#### 4.1.2 Lua 脚本设计（原子操作）

```lua
-- 参数: KEYS[1]=秒杀库存Key, KEYS[2]=用户抢购标记Key, ARGV[1]=用户ID, ARGV[2]=活动ID
local stock_key = KEYS[1]
local user_key = KEYS[2]
local user_id = ARGV[1]
local activity_id = ARGV[2]

-- 1. 检查库存
local stock = redis.call('get', stock_key)
if not stock or tonumber(stock) <= 0 then
    return 0  -- 库存不足
end

-- 2. 检查用户是否已抢购（利用 set 或 string 位图）
local has_bought = redis.call('sismember', user_key, user_id)
if has_bought == 1 then
    return -1  -- 重复抢购
end

-- 3. 预扣库存
redis.call('decr', stock_key)
-- 4. 记录用户抢购资格
redis.call('sadd', user_key, user_id)
-- 5. 返回成功
return 1
4.1.3 库存一致性保障
环节	保障措施
Redis 预扣	Lua 原子操作，杜绝并发超扣
MQ 消息发送失败	同步回滚 Redis 库存，并删除抢购标记
数据库扣减	使用乐观锁（版本号）或行锁 SELECT ... FOR UPDATE，防止超卖
消息消费失败	重试机制（最多 3 次），若仍失败转入死信队列，人工介入或补偿
支付超时	定时任务扫描待支付订单，超时取消并恢复库存（同时通知 Redis 增加库存）
数据库唯一索引	(user_id, activity_id) 唯一索引，防止 MQ 重复消费导致重复订单
4.2 多级缓存架构
4.2.1 缓存层级与职责
层级	实现	缓存内容	TTL	更新策略
L0	OpenResty（Nginx + Lua）	店铺分类列表、热门店铺摘要、首页推荐	1 分钟	主动清除（通过 Admin 管理接口）+ 被动过期
L1	Caffeine（应用内）	店铺详情、笔记详情、用户信息	5 分钟（可配置）	写入时主动失效 + Redis Pub/Sub 广播失效
L2	Redis 哨兵集群	所有热点数据（店铺、分类、GEO、库存、签到、Feed）	视业务而定（库存短，GEO 长）	写入时主动删除或更新；Canal 兜底删除
4.2.2 缓存一致性方案
业务流程更新缓存：

写操作 → 更新 MySQL → 删除 Redis 对应 key → 广播 Redis Pub/Sub 消息（携带 key 或类名）

所有应用实例订阅消息，收到后删除本地 Caffeine 对应缓存

Canal 兜底机制：

Canal 监听 MySQL binlog（ROW 格式），解析出变更的表和主键 ID

根据表名映射到对应的缓存 key 前缀，发送删除命令到 Redis

同时也可通过 Redis Pub/Sub 广播给所有实例清本地缓存

作用：解决业务代码遗漏删除、或删除操作失败时的数据不一致问题

缓存穿透 / 击穿防护：

空值缓存（key 存在但 value 为 null，TTL 较短）

布隆过滤器（可选项，用于过滤无效 ID）

4.2.3 冷启动预热
实现 ApplicationRunner 或 @PostConstruct，在服务启动时加载：

所有店铺分类列表 → Redis / Caffeine

前 100 热门店铺详情 → Redis / Caffeine

所有秒杀活动库存 → Redis

所有商家 GEO 坐标 → Redis（GEO 数据结构）

预热完成后，服务才对外暴露（可通过健康检查控制）

4.3 社交功能模块
4.3.1 Feed 流（关注动态）
存储结构：为每个用户维护一个 ZSet（收件箱），key = feed:user:{userId}，score = 发布时间戳，member = 笔记 ID。

发布笔记：查询该用户的粉丝列表，向每个粉丝的 ZSet 中写入该笔记 ID（写扩散，适合粉丝数 ≤ 1000 的场景，粉丝数多时可改用读扩散）。

滚动分页查询：

客户端传入 maxScore（上一页最小时间戳）和 offset（偏移量，解决同一时间点多条记录）

使用 ZREVRANGEBYSCORE feed:user:{userId} maxScore -inf WITHSCORES LIMIT offset count

返回笔记 ID 列表，再批量查询笔记详情（注意缓存）

4.3.2 签到积分
存储结构：Redis Bitmap，key = sign:{userId}:{yearMonth}，offset = 日（1-31），value = 1 表示已签到。

签到操作：

调用 SETBIT sign:{userId}:{yearMonth} dayOffset 1

若未签到，则增加积分，并记录连续签到天数（可另存连续签到计数）。

连续签到统计：使用 BITFIELD 或 BITPOS 计算从月初到昨日的连续签到天数。

积分发放：原子性增加积分（INCR），并可配合 Redis 分布式锁防止并发重复发放。

4.3.3 附近商家检索（GEO）
存储结构：Redis GEO，key = shop:geo，member = shopId，经度/纬度。

添加/更新：GEOADD shop:geo longitude latitude shopId

查询：GEOSEARCH shop:geo FROMLONLAT lng lat BYRADIUS radius m|km WITHDIST，并按距离排序。

结合缓存：商家详细信息从 Redis Hash 或 Caffeine 中获取，GEO 仅返回 ID 列表。

4.4 幂等与并发安全设计
场景	方案
秒杀抢购	Lua 脚本原子判断用户资格+预扣库存，杜绝重复抢购
RocketMQ 消费	消费者使用唯一键（如订单号）做幂等表，或利用数据库唯一索引防重
点赞/关注	使用 Redis Set 记录状态（如 like:note:{noteId}），操作前检查 set 成员，操作使用 SADD 原子
缓存清理	Canal 消费 binlog 时使用主键去重（同一个主键在短时间内多次更新，只清理一次）
订单支付回调	使用状态机，仅允许待支付→已支付，重复回调时校验状态，不重复处理
五、数据存储设计
5.1 MySQL 核心表结构（简略）
user：用户表

shop：店铺表（含经纬度、分类、评分等）

shop_category：分类表

note：探店笔记（关联 shop、user）

note_like：笔记点赞关系

follow：关注关系

voucher：优惠券表（含秒杀信息、库存、开始/结束时间）

voucher_order：秒杀订单表（user_id, voucher_id, status, create_time, pay_time）

sign_record：签到记录（可按月分表或只存汇总）

5.2 Redis 缓存 Key 设计规范
业务	Key 格式	数据结构	TTL	说明
店铺详情	shop:detail:{shopId}	String (JSON)	1h	热点
店铺分类列表	shop:category:all	List/String	10min	变化少
秒杀库存	seckill:stock:{voucherId}	String (整数)	活动结束	预扣库存
用户抢购标记	seckill:user:{voucherId}	Set (userId)	活动结束	一人一单
GEO 坐标	shop:geo	GEO	永久	更新时维护
签到	sign:{userId}:{yearMonth}	Bitmap	永久	按自然月
Feed 流	feed:user:{userId}	ZSet (noteId, score=time)	7天	时间线
缓存失效广播	cache:invalidate:channel	Pub/Sub	-	发布 key 名称
本地缓存版本号	cache:version:{key}	String	5min	用于本地缓存对比
六、接口设计（核心）
6.1 秒杀抢购接口
text
POST /api/seckill/order
Content-Type: application/json

Request:
{
    "voucherId": 1001
}

Response:
HTTP/1.1 200 OK
{
    "code": 0,
    "msg": "抢购成功，订单处理中",
    "data": {
        "orderId": "1234567890"   // 若异步生成，可为空
    }
}
失败响应：
{
    "code": -1,
    "msg": "库存不足 / 已抢购 / 活动未开始"
}
6.2 附近商家搜索
text
GET /api/shop/nearby?lng=118.78&lat=32.06&radius=3000&category=美食&page=1&size=10

Response:
{
    "code": 0,
    "data": {
        "total": 30,
        "list": [
            {
                "shopId": 10001,
                "name": "肯德基",
                "distance": 120.5,   // 米
                "address": "...",
                "avgScore": 4.5,
                "category": "美食"
            }
        ]
    }
}
6.3 Feed 流查询
text
GET /api/feed?maxScore=1692345600000&offset=0&count=10
Authorization: Bearer <JWT>

Response:
{
    "code": 0,
    "data": {
        "notes": [...],   // 笔记列表（含图片、内容、店铺信息）
        "nextMaxScore": 1692345000000,
        "nextOffset": 3
    }
}
七、部署与运维
7.1 环境依赖与版本
组件	版本	部署方式
MySQL	8.0.33	独立服务器或云 RDS
Redis 哨兵	7.0.12	3 个节点（1 主 2 从）+ 3 个哨兵
RocketMQ	4.9.5	Nameserver + Broker（可集群）
Canal	1.1.8	单机或集群（与 MySQL 同机）
OpenResty	1.21.4.1	WSL2 / 虚拟机，也可独立物理机
Spring Boot 应用	3.2+	多实例部署（JAR 包）
7.2 配置管理
所有配置文件（.yml / .properties）存放于项目 deploy/config 目录。

敏感信息（数据库密码、Redis 密码、密钥）通过环境变量或配置中心（如 Apollo）管理。

启动脚本统一放置于 deploy/scripts。

7.3 监控与告警
应用监控：Spring Boot Actuator + Micrometer + Prometheus + Grafana

重点关注：接口响应时间、QPS、错误率、线程池状态、缓存命中率

中间件监控：

RocketMQ 控制台（Dashboard）查看消息堆积、消费延迟

Redis 监控（RedisInsight 或 INFO 命令）

Canal 监控（查看延迟、解析错误）

日志：使用 ELK（或 Loki）集中式日志，错误日志自动告警

7.4 容灾与降级
Redis 故障：哨兵自动切换，切换期间部分功能降级（如秒杀直接返回失败，查询走 MySQL）

RocketMQ 故障：秒杀降级为同步创建订单（直接操作 MySQL，避免流量冲击）

Canal 故障：不影响业务主流程，缓存一致性降级为主动删除（业务代码删除），后续恢复后再同步

数据库故障：应用层熔断，返回友好提示

八、测试要求
8.1 单元测试
覆盖核心服务类（秒杀、缓存操作、GEO、签到）

使用 Mockito 隔离外部依赖

8.2 集成测试
秒杀全链路测试（模拟并发 1000 用户，验证库存正确）

缓存一致性测试（修改数据库后验证缓存是否失效）

MQ 消息发送/消费测试（包括重试和死信）

8.3 性能测试
使用 JMeter 模拟秒杀场景（QPS 5000），观察系统指标

缓存预热前后响应时间对比

九、版本规划
版本	新增功能	预计时间
v2.1	秒杀活动报名提醒、秒杀结果推送	Q4 2026
v2.2	积分兑换商城	Q1 2027
v2.3	商家端数据分析（秒杀效果、用户画像）	Q2 2027
v2.4	多语言支持 + 海外商家 GEO 扩展	Q3 2027
十、附录
A. 设计决策记录
决策点	选择	理由
秒杀库存存储	Redis + Lua，不依赖 MySQL	性能高，避免数据库行锁瓶颈
消息队列选型	RocketMQ 替代 RabbitMQ	高吞吐、支持事务消息、延迟消息
缓存一致性	Canal 监听 binlog 做兜底	无需业务代码侵入，保证最终一致性
本地缓存广播	Redis Pub/Sub	轻量级、实时性好
签到存储	Bitmap	内存占用极小，且支持位运算统计连续签到
B. 风险与应对
风险	影响	应对措施
Redis 哨兵主从切换期间服务不可用	秒杀失败、查询降级	客户端配置重试策略，快速失败返回提示
RocketMQ 消息大量堆积	订单延迟落库，用户体验差	监控告警，扩容消费者；限流控制生产速度
Canal 解析 binlog 延迟	缓存不一致时间拉长	设置缓存 TTL 较短（如 5 分钟），保证最终一致
秒杀活动开始时流量突刺	缓存、MQ 压力过大	网关层限流（OpenResty + Redis 计数器）
本地缓存 Caffeine 过大导致 GC	应用响应变慢	设置最大容量（如 1000 条），合理 TTL
