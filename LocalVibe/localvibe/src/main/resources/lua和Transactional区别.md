# Lua原子性(只管redis 消息队列异步补偿使得mysql redis一致)vs @Transactional 事务(只管mysql)

核心一句话：
**Lua 是 Redis 服务端单线程执行，多条命令不会被别的请求插队；`@Transactional`是数据库事务，只管MySQL，管不到Redis。两者管的是两套完全不同的存储。**

## 1、Lua脚本为什么具备原子性

Redis是**单线程模型**，执行Lua脚本的时候：

> 整个Lua脚本里面所有Redis命令，作为一个整体，执行期间不会处理其他客户端的任何命令。

- 要么全部执行完；
- 要么报错全部不执行；
- 中间不会插入别的请求的Redis操作。

✅原子性：**针对Redis的多条操作，不会出现“执行一半，被别的请求改了数据”。**

示例秒杀lua逻辑：

```lua
--1.查库存
--2.判断用户是否已经抢过券
--3.库存扣减
--4.标记用户已抢购
```

这4步在Redis服务器上一气呵成，别的并发请求插不进来，**防止Redis层面超卖**。

> ⚠️注意：Lua原子性**不代表回滚**。脚本中间报错，前面已经执行的命令不会回滚，只是停止继续往下执行。不是数据库那种完整事务回滚。

## 2、那我加上`@Transactional`为什么不行？

`@Transactional` 是 **MySQL数据库的事务**，只接管MySQL的DML操作（insert/update/delete），**完全管不到Redis**。

### 错误思路：Java代码 + @Transactional

```java
@Transactional
public void seckill(Long voucherId, Long userId){
    //①读Redis库存
    //②判断库存
    //③Redis扣库存
    //④Redis标记用户已抢券

    //⑤MySQL扣库存、生成订单
}
```

`@Transactional`只能保证⑤MySQL这一堆操作是事务。
**①‑④全部是Redis操作，不受Spring事务控制！**

### 并发灾难（不加Lua，只用@Transactional）

线程A、线程B同时进来：

1. 线程A读Redis库存=1
2. **线程B同时读Redis库存=1（A还没扣Redis）**
3. A扣Redis库存变成0
4. B扣Redis库存变成‑1
5. 然后才进入MySQL事务。

👉**Redis这边已经超卖了！MySQL事务救不了Redis并发问题。**

> `@Transactional`管MySQL，管不了Redis，Java代码中Redis多条命令之间会被其他线程插队。

## 3、两者的分工（秒杀场景）

1. **Lua脚本：负责Redis侧并发防护**
   Redis的库存、一人一单标记，多条Redis命令原子执行，挡住大部分并发请求，**在Redis层就拦截掉非法请求，不让打到数据库**。
2. **@Transactional（createVoucherOrder里面）：负责MySQL数据库**
   Lua放行之后，到数据库这一层：扣DB库存、插入订单，保证数据库层面的一致性，防止数据库超卖。

> 两者是搭档，不是替代品：
> Lua解决Redis并发；@Transactional解决MySQL事务。**不能拿一个去替代另一个。**

## 4、高频面试追问：既然Lua原子，那Lua脚本执行一半报错，会回滚吗？

> Redis Lua**没有回滚能力！**
> 脚本前面几条命令执行成功，中间某一行报错，已经执行成功的命令不会撤销，直接终止脚本。
> 所以写秒杀Lua，要**先做全部判断，最后才做修改操作**，先判断，后修改，避免出现“一半成功一半失败”。

## 5、再对比：为什么Java写Redis多条命令，就算加锁也不如Lua？

Java代码：

```java
// 伪代码
Integer stock = redisTemplate.get("stock");
if(stock>0){
    redisTemplate.decr("stock");
}
```

这是**多条网络IO来回**：读Redis →回到Java判断 →再发请求写Redis。
两次Redis请求中间存在网络间隙，别的线程可以插队。

Lua脚本是：**把逻辑发给Redis服务器，服务器本地一次性跑完，没有中间网络间隙。**

## 极简背诵版面试话术

> `@Transactional`是MySQL的事务，只能保证数据库操作原子性，对Redis无效。
> 如果在Java写多条Redis命令，命令之间会有网络往返，并发下其他请求可以插队，会出现超卖。
> Redis执行Lua脚本是服务端单线程完整执行，脚本内部所有Redis命令不会被其他客户端打断，实现Redis操作的原子性，用来做预校验挡并发。
> 但是Lua没有回滚，需要先判断再修改；Lua和@Transactional是配合使用，Lua保护Redis，事务保护MySQL。

### 补充坑

- Lua只管Redis；Lua执行成功，后面Java代码、MySQL挂掉，Redis数据不会自动回滚。

> 所以我们才引入消息队列，做异步补偿，保证Redis和MySQL最终一致性。
