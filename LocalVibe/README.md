# LocalVibe 社区探店电商高并发项目
一款基于 **多级缓存 + Lua秒杀 + RocketMQ异步削峰 + Canal缓存同步** 的高性能社区电商平台，主打探店社交+优惠券秒杀业务，解决高并发流量击穿、缓存不一致、秒杀超卖等典型业务问题。

## 📘 项目简介
项目实现 **用户登录鉴权、探店笔记发布、关注Feed流、签到积分、商家地理位置检索、优惠券秒杀抢购** 等核心业务。

针对高并发场景做了全套架构优化：
- 搭建 **nginx静态资源->OpenResty + Caffeine(tomcat本地缓存) + Redis 多级缓存架构(不是数据都满足)**
- 秒杀库存链路：`Redis Lua -> RocketMQ -> MySQL`；caffeine->redis->mysql主要用于缓存关键的数据：店铺优惠券；OpenResty 主要缓存店铺的分类和店铺数据,其余业务查询链路大多是：`Redis -> MySQL
- 基于 **Lua脚本原子执行** 完成秒杀库存预扣、资格校验，杜绝超卖
- 依托 **RocketMQ** 实现秒杀流量异步削峰、订单异步落库
- 通过 **Canal监听Binlog** 实现缓存最终一致性兜底
- 利用 Redis 高级数据结构实现 Feed流、签到、附近商家等社交能力

## 💻 技术栈
- **后端框架**：SpringBoot3、SpringMVC、MyBatis‑Plus
- **数据库**：MySQL 8.0
- **缓存体系**：Redis、Lua脚本、Caffeine本地缓存、Redis哨兵
- **中间件**：RocketMQ、Canal、Redis Pub/Sub
- **网关层**：OpenResty（WSL2 / 虚拟机部署）

## ✨ 核心功能与架构亮点
### 1. 高并发秒杀模块（核心亮点）
- **Lua脚本原子操作**：在Redis单线程中一次性完成库存校验、库存预扣、一人一单资格判断，从根源避免超卖与重复抢购
- **RocketMQ异步削峰**：秒杀请求前置快速响应，将耗时的订单创建、数据库落库异步化，大幅提升接口吞吐
- **消息失败补偿机制**：MQ异步发送失败时，自动回补Redis预扣库存、清理用户抢购标记，避免库存丢失、数据脏写
- **数据库兜底防护**：基于用户+优惠券联合唯一索引，杜绝并发重复下单

### 2. 四级多级缓存架构 & 缓存一致性
架构层级：`OpenResty(L0网关缓存) → Caffeine(L1本地缓存) → Redis(L2远程缓存) → MySQL(持久层)`

- **L0 OpenResty网关缓存**：部署在WSL虚拟机，缓存店铺、笔记等热点静态数据，前置拦截高并发请求，减轻后端Tomcat压力
- **L1 Caffeine本地缓存**：JVM高性能缓存，无网络IO，响应速度极高
- **L2 Redis缓存**：统一存储集群热点数据

#### 缓存一致性整套方案
1. **业务主流程**：写库后主动删除Redis缓存、清空本机Caffeine缓存
2. **集群同步**：通过 Redis Pub/Sub 广播失效消息，统一清除所有服务节点本地缓存
3. **兜底保障**：Canal监听MySQL Binlog，异步删除缓存，防止业务代码漏写缓存更新逻辑
4. **精准缓存失效**：解析Binlog获取ShopId，精准删除对应缓存Key，摒弃全量清空缓存，彻底规避缓存雪崩
5. **冷启动优化**：项目启动通过 `@PostConstruct` 预热热点数据，避免首次请求大批量击穿数据库

### 3. 社交业务模块
- **Feed关注流**：基于Redis ZSet实现收件箱模式，以发布时间为score，采用max‑offset滚动分页，解决动态分页漏读、重复读取问题
- **签到积分系统**：通过Redis Bitmap位图结构按月存储用户签到数据，极小内存开销实现连续签到统计、积分发放
- **附近商家检索**：基于Redis GEO实现商家坐标存储、距离排序、范围检索

### 4. 业务幂等设计
- Lua脚本前置拦截重复秒杀请求
- Canal Binlog事件幂等去重，避免重复删除缓存产生无效IO
- RocketMQ消费业务幂等，适配MQ至少一次投递特性
- 数据库唯一索引 + 异常补偿，保障数据最终一致性

## 📂 项目部署目录规范
所有 **环境配置、启动脚本、中间件配置、OpenResty自定义配置** 统一托管在项目 `deploy` 目录，开箱即用、可移植性强：
openresty:wget https://openresty.org/download/openresty-1.21.4.1.tar.gz
tar -zxvf openresty-1.21.4.1.tar.gz
cd openresty-1.21.4.1

./configure
make
sudo make install


redis下载:sudo apt install redis-server -y
sudo systemctl start redis-server
sudo systemctl enable redis-server

canal:# 下载安装包
wget https://github.com/alibaba/canal/releases/download/canal-1.1.8/canal.deployer-1.1.8.tar.gz

# 解压
mkdir -p canal
tar -zxvf canal.deployer-1.1.8.tar.gz -C canal

# 启动
cd canal/bin
sh startup.sh

rocketmq:# 下载
wget https://archive.apache.org/dist/rocketmq/4.9.5/rocketmq-all-4.9.5-bin-release.zip

# 解压
unzip rocketmq-all-4.9.5-bin-release.zip
cd rocketmq-all-4.9.5-bin-release

# 启动NameServer
nohup sh bin/mqnamesrv &

# 启动Broker
nohup sh bin/mqbroker -n localhost:9876 &

