-- 秒杀抢购 Lua 脚本（RocketMQ 异步削峰版）
-- 与 seckillBlockQueue.lua 逻辑一致：Redis 内原子校验库存 + 一人一单 + 扣库存
-- 区别：不在 lua 内写 Redis Stream，抢购成功后由 Java 端发送 RocketMQ 消息，
--      消费者异步创建数据库订单，实现高并发下的削峰
--
-- 1.参数列表
-- 优惠券Id 参数用于拼接为库存key
local voucherId = ARGV[1]
-- 用户id 判断用户是否下过单
local userId = ARGV[2]

-- 2.数据key
-- 库存key 判断该秒杀券还有多少个 value为string 库存数量
local stockKey='seckill:stock:'..voucherId
-- 订单key判断该用户有没有已经下过单,一人一单不能多下,value为set 下过单的用户id
local orderKey='seckill:order:'..voucherId

-- 判断库存是否充足,不足返回1
if(tonumber( redis.call('get',stockKey) ) <=0) then
return 1
end

-- 判断是否重复下单, set里面是否有userId 有返回2(重复下单)
if(redis.call('sismember',orderKey,userId) ==1 ) then
return 2
end

-- 没有下过单 且 库存充足 扣除redis的库存（异步削峰：真正落库在消费者里完成）
redis.call('incrby',stockKey,-1)
-- 把userId加入进去set 防止重复下单
redis.call('sadd',orderKey,userId)
return 0
