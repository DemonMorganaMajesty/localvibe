-- 1.参数列表
-- 优惠券Id 参数用于拼接为库存key 存变量的时候== (空格)值
local voucherId = ARGV[1]
-- 用户id 判断用户是否下过单
local userId = ARGV[2]

-- 2.数据key
-- 库存key 判断该秒杀券还有多少个 value为string 库存数量 要记得加:
local stockKey='seckill:stock:'..voucherId
-- 订单key判断该用户有没有已经下过单,一人一单不能多下,value为hashSet 下过单的用户id
-- 订单key 拼接的是voucherId 不是用户id  ..(拼接字符串)
local orderKey='seckill:order:'..voucherId

-- lua 脚本  没有多行注释/* */ 会报错 --[[ ]]  多行注释
--[[判断库存是否充足,不足返回1,还有库存再判断该用户是否已经下过单,保证一人一单
下过单直接返回2,没有下过单库存也还有,直接扣减redis中的库存(以前是在数据库,数据是
同步的,现在改为异步,把一个业务分割为每个部分(类比餐厅吃饭),提高并发性),扣除库存
并且将用户id 存入订单key的hashSet里,返回0 ]]

if(tonumber( redis.call('get',stockKey) ) <=0) then
-- 库存不足返回1
return 1
end

-- 判断是否重复下单, set里面是否有userId  有返回1(重复下单)
if(redis.call('sismember',orderKey,userId) ==1 ) then
return 2
end

-- 没有下过单 且 库存充足 扣除redis的库存
redis.call('incrby',stockKey,-1)
-- 把userId加入进去set
redis.call('sadd',orderKey,userId)
return 0



