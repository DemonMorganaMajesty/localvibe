package com.localvibe.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.localvibe.dto.Result;
import com.localvibe.entity.SeckillVoucher;
import com.localvibe.entity.VoucherOrder;
import com.localvibe.mapper.VoucherOrderMapper;
import com.localvibe.service.ISeckillVoucherService;
import com.localvibe.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.localvibe.dto.UserDTO;
import com.localvibe.entity.Voucher;
import com.localvibe.mapper.VoucherMapper;
import com.localvibe.utils.RedisIdWorker;
import com.localvibe.utils.UserHolder;
import com.localvibe.mq.SeckillOrderMqProducer;
import static com.localvibe.utils.RedisConstants.SECKILL_STOCK_KEY;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
/*import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;*/
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;



//多表操作 最好加事务 要进行处理多线程并发等问题 优惠券一般一人只能抢一张,所也要限制
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    ISeckillVoucherService seckillVoucherService;

    @Resource
    RedisIdWorker redisIdWorker;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    RedissonClient redissonClient;

    //RocketMQ 生产者(异步秒杀削峰:lua 校验扣库存后,发送消息异步创建订单)
    @Resource
    SeckillOrderMqProducer seckillOrderMqProducer;

    @Resource
    VoucherMapper voucherMapper;

    //多并发(多个人同时枪) 会出现问题 同步抢优惠券 全部请求打 DB，并发能力差
    @Override
    public Result GetSeckillVoucher1(Long voucherId) {
        if (UserHolder.getUser() == null) return Result.fail("用户未登录");
        //查询优惠券的id
        SeckillVoucher seckillVoucher=
                seckillVoucherService.getById(voucherId);
        if(seckillVoucher==null)
            return Result.fail("优惠券不存在,查询失败");

        //判断优惠券的抢购时间 秒杀时间有没有开始
        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now()))
            return Result.fail("优惠券的抢购时间(秒杀时间)还没有开始");

        //判断优惠券的抢购时间 秒杀时间有没有结束
        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now()))
            return Result.fail("优惠券的抢购时间(秒杀时间)已经结束");

        //判断优惠券的库存
        long stock=seckillVoucher.getStock();
        if(stock<=0)
            return  Result.fail("优惠券库存不足");

        /*要加悲观锁: 对函数加 synchronized createVoucherOrder 有问题这里的锁是this
        也就是voucherOrderServiceImpl的一个对象,多个用户来抢锁 那么就会导致
        只有一个人会抢到锁(每个用户一个this 唯一),只能进行单线程,不能直接对函数加锁
         要实现并行:对用户的id加锁,每个用户一个id 一个锁,不同对象不同锁,既可以实现
         多用户并行抢购,也可以保证一个用户抢一张,
        long userId= UserHolder.getUser().getId(); 底层是new出来的,要实现
        对单个用户唯一,只把id的值作为锁
        为什么不在create函数内部加锁,这样就是先释放锁后提交事务有问题
        必须要先提交事务后释放锁 所以必须把create 封装在一个函数内
          */
        Long userId=UserHolder.getUser().getId();
        /*必须要等到 事务提交 写进数据库 再释放锁 事务要生效是spring做了代理
        只有create加了Transactional 这个两个函数应该是同一个事务,手动使用代理
        把两个函数 变为统一事务(默认是this调用的create函数)
        这种枷锁的方式也只能处理同一个用户的多个请求(线程)都全部到达同一个服务器
        (非分布式),如果部署到多台的服务器上(分布式 不同的进程),那么不同的服务器jvm
        不同 就会导致锁不同 锁失效了
         */
        /*synchronized (userId.toString().intern()) {
            //拿到当前对象的代理对象
            IVoucherOrderService proxy= (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }*/

        /*使用redis 的分布式锁解决 同一用户分布式(不同jvm下 同一用户同时抢单,
        保证是同一把锁)  用户不同 锁的key也不同 所以要拼上业务+id 一个用户一把锁
        不同用户锁(mysql乐观锁)不同才可以实现多个用户同时抢,库存充足即可买,
        但是避免不了一个用户发多次请求抢单,所以需要redisson(redis)限制多请求锁
         */
    /*    //使用手写 RedisLock 创建和获取锁  getLock里会自动拼接业务的前缀
        RedisLock redisLock=new RedisLock(":order:"+userId,stringRedisTemplate);
        boolean isRedisLock=redisLock.getLock(1200);
*/
        //使用RedissonConfig 下的RedissonLock 创建锁对象
        RLock redissonLock=redissonClient.getLock("redisson:lock:order:"+userId);
       /* 有三个参数(获取锁失败重试获取锁的最大的等待时间,存活时间,单位)
        无参 默认获取锁失败就直接返回(-1),30s,Second*/

        //redisson tryLock 锁住同一用户多请求下多单
        boolean isRedissonLock= false;
        try {
            isRedissonLock = redissonLock.tryLock(10,30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if(!isRedissonLock)
        //if(!isRedisLock)
            //获取锁失败(已经有线程抢到了),
            return Result.fail("一个人只能下一单,不允许重复下单");

        //获取锁成功
        try {
            //获取代理对象
            IVoucherOrderService proxy= (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
          /*  //释放锁
            redisLock.unlock();*/

            // 只有当前线程持有锁才释放
            if (redissonLock.isHeldByCurrentThread()) {
                redissonLock.unlock();
            }
        }
    }

    @Transactional
    //同步创建订单 可以单独写在一个函数
    public Result createVoucherOrder(Long voucherId){
         /*一种优惠券一般一人只能抢一张,所以也要限制 但是不能直接的限制,
        count开始都是0 ,一个用户多线程请求,那么所有的线程都可以创建一个订单
        还是会出现一人有拿到了多个优惠券,所以必须加锁:乐观锁(不能对本身就不存在的加)
        所以只能创建悲观锁:synchronized
         */
        //根据用户id 和优惠券id 进行查询订单  有订单直接返回
        long userId= UserHolder.getUser().getId();

        /*mysql乐观锁的优化：先查询存值,买的时候当前的值和查询的值要一样
        这段时间没有别人买,解决多人并发问题,优化为只要买的时候库存够就行
         */
        long count=query().eq("user_id",userId).
                eq("voucher_id",voucherId).count();
        if(count>0)
            return Result.fail("该用户已经使用过该种优惠券");

      /* 抢购成功 优惠券库存-1 不是对优惠券 对象直接-- 而是操作数据库 plus
      多线程并发的时候会出现问题(多线程交叉执行 超卖,库存100 卖出去110..),需要加锁：
      1.悲观锁:每一个线程都一起抢锁,只有有锁的人才可以抢优惠券,多线程
      直接变为只能单线程,运行的效率会变低
      2.乐观锁:逻辑加锁,在买优惠券的时候,判断查询到的值,和数据库中的值是否
      一致(sql查询),一致就说明在该线程查询和抢票的时候没有其他线程,不一致
      就直接重试/异常,保证了数据的一致性,但是100个线程同时抢200张，假设
      一个线程抢到了,数据库库存-1,其他线程抢票发现数据被改,本来还可以抢票
      ,却被抛出异常也有问题,判断数据是否存在,不能使用乐观锁,只能使用悲观锁
      3.乐观锁改进:数据一致的判断-> 数据是否还有库存,只要还有库存,直接就可以
      抢
        boolean isSuccess=seckillVoucherService.update().
                setSql("stock=stock-1")
                .eq("voucher_id",voucherId)
                .eq("stock",stock); //乐观锁
                .update();*/
        boolean isSuccess=seckillVoucherService.update().
                setSql("stock=stock-1")
                .eq("voucher_id",voucherId)
                .gt("stock",0) //乐观锁优化 stock>0
                .update();

        if(!isSuccess){
            //购买失败
            return Result.fail("优惠券库存不足");
        }

        //为什么这里可以直接把数据写给对象:这时候对象(订单)还没有存入数据库
        //创建订单：订单表买东西
        VoucherOrder voucherOrder=new VoucherOrder();
        //向该用户拿到的优惠券的 存入订单的id
        long orderId= redisIdWorker.createId("order");
        voucherOrder.setId(orderId);

        //向这张优惠券 存入用户的id(每次请求都会放入线程池,在线程池里面取)
        voucherOrder.setUserId(userId);

        //向订单 加上优惠券的id
        voucherOrder.setVoucherId(voucherId);

        //把订单加入数据库  调用的是voucherOrder的函数
        save(voucherOrder);

        //同步抢购成功:同步记录 Redis 一人一单集合,防止后续(含异步路径)重复领取
        stringRedisTemplate.opsForSet().add("seckill:order:" + voucherId, String.valueOf(userId));

        //返回订单的id
        return Result.success(orderId);

    }
//---------------------------------------------------------------------

    //实现异步+阻塞队列 获取消息
    //redis实现事务 的lua脚本 返回值是Long 自己规定的泛型
    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT =new DefaultRedisScript<>();
        //去所有的类下面找文件
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckillBlockQueue.lua"));
        //设置脚本的类
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //阻塞队列 数组实现的单端阻塞队列
    public BlockingQueue<VoucherOrder> voucherOrderBlockingDeque
            =new ArrayBlockingQueue<>(1024*1024);
    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR_BLOCK_QUEQUE =
            Executors.newSingleThreadExecutor();
    /*runnable 执行线程任务,要多久执行呢,只要一开启application就有可能
    发送请求,所有要在初始化之后就立马执行线程的run任务
     */
    private class VoucherOrderBlockQueueRunnable implements Runnable{
        @Override
        public void run() {
            /*streamConsumeRunning.get()为true 在application关闭前改为false
            不会造成无限的循环抛错
             */
            while(streamConsumeRunning.get()){
               //获取队列的订单信息
                try {
                    VoucherOrder voucherOrder=voucherOrderBlockingDeque
                            .take(); //获取并删除队列的头部,有元素才开始
                    //单独写函数处理
                    handleVoucherOrderBlockQueue(voucherOrder);
                } catch (InterruptedException e) {
                    // 应用关闭时主动中断 take，退出消费者线程，不把正常停机记录为业务异常
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // 单条订单处理失败不能逃出 Runnable，否则线程池工作线程会退出，后续订单无人消费
                    log.error("阻塞队列订单处理异常，继续消费后续订单", e);
                }
            }
        }
    }

    //拿到主线程(抢单的代理对象)
    private IVoucherOrderService proxy=null;
    //处理订单的函数  主要是防止redis挂掉 异步处理不需要给前端返回值(已经给过了)
    private void handleVoucherOrderBlockQueue(VoucherOrder voucherOrder) {
        /*获取用户id 只能从voucherOrder 不能从userHodler,因为处理
        订单的下单 是异步的 重新开启了一个线程
         */
        Long userId=voucherOrder.getUserId();

        //使用RedissonConfig 下的RedissonLock 创建锁对象
        RLock redissonLock=redissonClient.getLock("redisson:lock:order:"+userId);

        //redisson tryLock 锁住同一用户多请求下多单
        boolean isRedissonLock= false;
        try {
            isRedissonLock = redissonLock.tryLock(10,30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        if(!isRedissonLock) {
            /*获取锁失败(已经有线程抢到了) redis出现的不同情况给前端已经
            返回了信息,这里就不需要再给前端返回信息,这里加锁是因为防止redis
            挂了 多请求发给数据库导致一人多单
             */
            log.error("不允许重复下单");
            return;
        }
        try {
            /*获取代理对象 不能直接取当前线程的代理对象,新开的线程不是用来
            处理抢单的 只是用来处理下单的
           IVoucherOrderService proxy= (IVoucherOrderService) AopContext.currentProxy();*/
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 只有当前线程持有锁才释放
            if (redissonLock.isHeldByCurrentThread()) {
                redissonLock.unlock();
            }
        }
    }
    @PostConstruct
    private void initial(){
        SECKILL_ORDER_EXECUTOR_BLOCK_QUEQUE.
                submit(new VoucherOrderBlockQueueRunnable());
    }

    // 抢优惠券 异步+阻塞队列 发消息
    @Override
    public Result GetSeckillVoucher2(Long voucherId) {
        if (UserHolder.getUser() == null) return Result.fail("用户未登录");
        /*获取用户及id 不能直接使用普通long,redis里的value是string
        long 没有toString 函数 +"" 最好直接定义为Long 有toString函数
         */
        Long userId=UserHolder.getUser().getId();

        //数据库校验一人一单(Redis 状态可能因清理/换库丢失,以数据库为准)
        if (isUserClaimed(userId, voucherId)) {
            return Result.fail("已经领取过该优惠券,请勿重复领取");
        }

        //执行lua脚本 根据返回的结果判断:1库存不足 2已经下过单了 0抢购成功
        Long result=
        stringRedisTemplate.execute(
                SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(),userId.toString());

        //判断是否为0
        int r=result.intValue();
        if(r!=0){
            return Result.fail(r==1?"库存不足":"已经购买,不能重复下单");
        }

        //正常情况返回0 抢购成功 保存到阻塞队列(待执行的队列)
        //拿到每个订单的 订单Id 根据Id生成的算法
        long orderId= redisIdWorker.createId("seckill:order");

        //return Result.success(orderId);

        /*抢单成功,将优惠券id,用户id,订单id,封装后存入阻塞队列,开启线程任务
         来从阻塞队列里面获取信息,实现异步下单,抢单只是在redis执行,真正
         的下单开启另外一个线程单独执行,数据最后存入数据库,把一个大的业务
         分成多模块,可以实多并发,每个模块单独执行,互不打扰,也能更快的给出反馈,
         */
        //创建订单：订单表买东西 把订单对象放入阻塞队列
        VoucherOrder voucherOrder=new VoucherOrder();
        //向该用户拿到的优惠券的 存入订单的id
        voucherOrder.setId(orderId);
        //向这张优惠券 存入用户的id(每次请求都会放入线程池,在线程池里面取)
        voucherOrder.setUserId(userId);
        //向订单 加上优惠券的id
        voucherOrder.setVoucherId(voucherId);
        //获取主线程代理对象(抢单的线程的代理对象)
        proxy= (IVoucherOrderService) AopContext.currentProxy();
        //存入阻塞队列
        voucherOrderBlockingDeque.add(voucherOrder);

        //抢单完成直接返回  新线程执行异步下单会自动运行
        return Result.success(orderId);
    }

//----------------------------------------------------------------------------------------------
    //第四种
    //redis手动实现 异步+消息队列处理  收消息
    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR_MESSAGE_QUEQUE =
            Executors.newSingleThreadExecutor();
    //消息队列名称
    String messageQueueName="messageQueue.stream.orders";
    /*runnable 执行线程任务,要多久执行呢,只要一开启application就有可能
    发送请求,所有要在初始化之后就立马执行线程的run任务
     */
    //redis stream 消息队列版本已被 rocketmq 异步秒杀取代,该线程不再启动(代码保留供参考)
    //@PostConstruct
    private void ini(){
        SECKILL_ORDER_EXECUTOR_MESSAGE_QUEQUE.execute(new VoucherOrderMessageQueueRunnable());
    }
    //异步的时候 一开启apllication 就要执行线程 必须在结束前关闭
    private final AtomicBoolean streamConsumeRunning =
            new AtomicBoolean(true);

    private class VoucherOrderMessageQueueRunnable implements Runnable{
        @Override
        public void run() {
            while(streamConsumeRunning.get()){
                try {
                    /*获取消息队列的订单信息  读 返回的是消息的集合(一次可以读多条消息)
                    xreadgroup group g1 c1 count 1 block 2s Streams 队列名 >(依次读)
                    MapRecord<key类型,hk类型,hv类型> key是消息的id(*自动生成) key的value是hash
                    */
                    List<MapRecord<String,Object,Object>> list=
                       stringRedisTemplate.opsForStream()
                           .read(Consumer.from("group1","consumer1"),
                           StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                           StreamOffset.create(messageQueueName, ReadOffset.lastConsumed()));
                    //判断消息是否读取失败
                    if(list==null||list.isEmpty()){
                        //消息获取失败,说明没有消息,继续循环
                        continue;
                    }
                    /*解析消息 因为一次只读一条 所以集合里只有一条 直接取就行
                     MapRecord<key类型,hk类型,hv类型> key是消息的id(*自动生成) key的value是hash
                     */
                    MapRecord<String, Object, Object> message = list.get(0);
                    //解析消息的时候不需要 key(队列名字) 只需要消息的值 值为hash
                    Map<Object,Object> hashValue=message.getValue();
                    //把hashValue 发过来的消息(一个对象的多个成员变量) 转化为订单的对象
                    VoucherOrder voucherOrder= BeanUtil.
                            fillBeanWithMap(hashValue, new VoucherOrder(), true);
                    //获取成功 可以下单
                    createVoucherOrder(voucherOrder);

                    //ack确认消息 sack 队名 g1 id(生成的很长一串的 *)
                    stringRedisTemplate.opsForStream().
                            acknowledge(messageQueueName,"group1",message.getId());

                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    //消息队列获取异常 没有确认的消息 会被存入PendingList()
                    handlePendingList();
                }
            }
        }
    }
    //处理未被确认 存进PendingList()的信息
     public void handlePendingList(){
         while(streamConsumeRunning.get()){
             try {
                    /*获取pendingList的订单信息  读 返回的是消息的集合(一次可以读多条消息)
                    xreadgroup group g1 c1 count 1 block 2s Streams 队列名 0(处理未确认的)
                    每次读从未被确认的消息里面读
                    MapRecord<key类型,hk类型,hv类型> key是消息的id(*自动生成) key的value是hash
                    */
                 List<MapRecord<String,Object,Object>> list=
                         stringRedisTemplate.opsForStream()
                          .read(Consumer.from("group1","consumer1"),
                          StreamReadOptions.empty().count(1), StreamOffset.
                           create(messageQueueName, ReadOffset.from("0")));

                 //判断消息是否读取失败
                 if(list==null||list.isEmpty()){
                     //消息获取失败,说明pendingList没有异常消息,直接退出
                     break;
                 }
                    /*解析消息 因为一次只读一条 所以集合里只有一条 直接取就行
                     MapRecord<key类型,hk类型,hv类型> key是消息的id(*自动生成) key的value是hash
                     */
                 MapRecord<String, Object, Object> message = list.get(0);
                 //解析消息的时候不需要 key(队列名字) 只需要消息的值 值为hash
                 Map<Object,Object> hashValue=message.getValue();
                 //把hashValue 发过来的消息(一个对象的多个成员变量) 转化为订单的对象
                 VoucherOrder voucherOrder= BeanUtil.
                         fillBeanWithMap(hashValue, new VoucherOrder(), true);
                 //获取成功 可以下单
                createVoucherOrder(voucherOrder);

                 //ack确认消息 sack 队名 g1 id(生成的很长一串的 *)
                 stringRedisTemplate.opsForStream().
                         acknowledge(messageQueueName,"group1",message.getId());

             } catch (Exception e) {
                 log.error("处理订单异常",e);
                 /*pendingList获取异常时候还出现了异常 还需要递归调用自己吗
                 不用递归调用handlePendingList(); 出现的异常信息任然在pendingList
                 直接下次循环处理即可,一直递归很难跳出去
                 */

                 //休眠降低 处理pendingList 异常未确认消息的频率
                 try {
                     Thread.sleep(20);
                 } catch (InterruptedException ex) {
                     throw new RuntimeException(ex);
                 }
             }
         }
    }
//--------------------------------------------------------------

    //异步创建订单  2 3 共用
    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //??????UserHolder ??????, ???voucherOrder??
        long userId= voucherOrder.getUserId();

        //??????????(?????)
        long count=query().eq("user_id",userId).
                eq("voucher_id",voucherOrder.getVoucherId()).count();
        if(count>0) {
            // 补偿 lua 已扣减的 Redis 库存(重复领取时数据库未扣库存)
            stringRedisTemplate.opsForValue().increment(SECKILL_STOCK_KEY + voucherOrder.getVoucherId(), 1);
            log.info("??????,??????, orderId={}", voucherOrder.getId());
            return;
        }

        //???????(???? stock>0,???)
        boolean isSuccess=seckillVoucherService.update().
                setSql("stock=stock-1")
                .eq("voucher_id",voucherOrder.getVoucherId())
                .gt("stock",0) //????? stock>0
                .update();

        if(!isSuccess){
            log.error("????, orderId={}", voucherOrder.getId());
            return;
        }

        //????????  ????voucherOrder???
        try {
            save(voucherOrder);
        } catch (DuplicateKeyException e) {
            // ??????:?????????(user_id,voucher_id) -> ???????,
            // ???????????,???????????(MyBatis-Plus ?????????)
            seckillVoucherService.update().
                    setSql("stock=stock+1").
                    eq("voucher_id",voucherOrder.getVoucherId()).
                    update();
            // 补偿 lua 已扣减的 Redis 库存(数据库唯一约束兜底重复领取)
            stringRedisTemplate.opsForValue().increment(SECKILL_STOCK_KEY + voucherOrder.getVoucherId(), 1);
            log.info("???????????,?????, orderId={}", voucherOrder.getId());
        }
    }


    //RocketMQ 异步秒杀 使用的lua脚本(与seckillBlockQueue.lua逻辑一致,不写redis stream,由rocketmq消息队列削峰)
    public static final DefaultRedisScript<Long> SECKILL_ROCKETMQ_SCRIPT;
    static{
        SECKILL_ROCKETMQ_SCRIPT =new DefaultRedisScript<>();
        //去所有的类下面找文件
        SECKILL_ROCKETMQ_SCRIPT.setLocation(new ClassPathResource("seckillRocketMQ.lua"));
        //设置脚本的返回结果的类
        SECKILL_ROCKETMQ_SCRIPT.setResultType(Long.class);
    }

    //抢优惠券 异步+消息队列(rocketmq 实现) 发消息,实现高并发下的削峰
    @Override
    public Result GetSeckillVoucher3(Long voucherId) {
        if (UserHolder.getUser() == null) return Result.fail("用户未登录,请先登录");
         /*获取用户及id 不能直接使用普通long,redis里的value是string
        long 没有toString 函数 +"" 最好直接定义为Long 有toString函数
        long->String  String.valueOf():把其他类型的变量转为字符串
         */
        Long userId=UserHolder.getUser().getId();
        //数据库校验一人一单(Redis 状态可能因清理/换库丢失,以数据库为准)
        if (isUserClaimed(userId, voucherId)) {
            return Result.fail("已经领取过该优惠券,请勿重复领取");
        }
        //拿到每个订单的 订单Id 根据Id生成的算法
        long orderId= redisIdWorker.createId("seckill:order");

        //执行lua脚本 根据返回的结果判断:1库存不足 2已经下过单了 0抢购成功
        //lua内完成:库存校验+一人一单校验+redis扣库存(原子操作,支撑高并发)
        Long result=
                stringRedisTemplate.execute(
                        SECKILL_ROCKETMQ_SCRIPT, Collections.emptyList(),
                        voucherId.toString(),userId.toString());

        //判断是否为0
        int r=result.intValue();
        if(r!=0){
            return Result.fail(r==1?"库存不足":"已经购买,不能重复下单");
        }

        //抢单成功:构建订单对象(此时还未落库) 发送到RocketMQ消息队列,由消费者异步创建订单
        VoucherOrder voucherOrder=new VoucherOrder();
        //向该用户拿到的优惠券的 存入订单的id
        voucherOrder.setId(orderId);
        //向这张优惠券 存入用户的id
        voucherOrder.setUserId(userId);
        //向订单 加上优惠券的id
        voucherOrder.setVoucherId(voucherId);

        //获取主线程代理对象(抢单的线程的代理对象),MQ发送失败时需要降级同步下单
        proxy= (IVoucherOrderService) AopContext.currentProxy();

        //使用异步普通消息发送，立即释放 Tomcat 业务线程；失败时回补 Redis 预扣状态
        seckillOrderMqProducer.sendSeckillOrderAsync(
                voucherOrder, () -> compensateSeckillPreDeduction(voucherOrder));

        //抢单完成直接返回  消费者线程异步下单
        return Result.success(orderId);
    }

    /**
     * RocketMQ 异步发送失败时补偿 Redis Lua 已完成的预扣。
     * 只有消息明确发送失败才执行，避免 Redis 库存和用户重复购买集合残留脏数据。
     */
    private void compensateSeckillPreDeduction(VoucherOrder voucherOrder) {
        stringRedisTemplate.opsForValue().increment(
                SECKILL_STOCK_KEY + voucherOrder.getVoucherId(), 1);
        stringRedisTemplate.opsForSet().remove(
                "seckill:order:" + voucherOrder.getVoucherId(),
                voucherOrder.getUserId().toString());
        log.warn("RocketMQ 消息发送失败，已补偿秒杀预扣状态: orderId={}", voucherOrder.getId());
    }

    // 数据库校验该用户是否已领取过该优惠券(一人一单)
    private boolean isUserClaimed(Long userId, Long voucherId) {
        return query().eq("user_id", userId).eq("voucher_id", voucherId).count() > 0;
    }

    // 领取普通优惠券（type=0）：校验登录/存在/一人一单后直接落库
    @Override
    public Result claimVoucher(Long voucherId) {
        // 写入数据库的操作
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null || userDTO.getId() == null) {
            return Result.fail("用户未登录,请先登录");
        }
        Voucher voucher = voucherMapper.selectById(voucherId);
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }
        if (voucher.getStatus() != null && voucher.getStatus() != 1) {
            return Result.fail("优惠券已下架");
        }
        if (voucher.getType() != null && voucher.getType() == 1) {
            return Result.fail("该优惠券为秒杀券,请点击立即抢");
        }
        if (isUserClaimed(userDTO.getId(), voucherId)) {
            return Result.fail("您已领取过该优惠券,请勿重复领取");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.createId("order"));
        voucherOrder.setUserId(userDTO.getId());
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setStatus(1); // 已领取/未使用
        save(voucherOrder);
        return Result.success(voucherOrder.getId());
    }

    // 我的优惠券：当前用户领取过的优惠券列表(含券信息/店铺/秒杀时间/领取时间)
    @Override
    public Result queryMyVouchers() {
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null || userDTO.getId() == null) {
            return Result.fail("用户未登录,请先登录");
        }
        List<Voucher> vouchers = voucherMapper.queryMyVouchers(userDTO.getId());
        return Result.success(vouchers == null ? Collections.emptyList() : vouchers);
    }

    // 各种方法实现 秒杀抢单的 线程的关闭函数
    @PreDestroy
    public void stopAllConsumeThread() {
       /* // 关闭Stream消费循环
        streamConsumeRunning.set(false);
        // 关闭线程池
        SECKILL_ORDER_EXECUTOR_MESSAGE_QUEQUE.shutdownNow();
        SECKILL_ORDER_EXECUTOR_BLOCK_QUEQUE.shutdownNow();
        log.info("订单消费线程池已停止");*/
        // 1. 标记循环停止，让消费线程主动退出循环
        streamConsumeRunning.set(false);
        // 2. 先优雅关闭：等待2秒，让当前正在执行的Redis读取、订单处理走完
        SECKILL_ORDER_EXECUTOR_MESSAGE_QUEQUE.shutdown();
        SECKILL_ORDER_EXECUTOR_BLOCK_QUEQUE.shutdown();
        try {
            // 给2秒时间，等待阻塞的xReadGroup完成本次阻塞周期（你设置的block 2s）
            if (!SECKILL_ORDER_EXECUTOR_MESSAGE_QUEQUE.awaitTermination(2, TimeUnit.SECONDS)) {
                // 2秒后仍未退出，再强制中断
                SECKILL_ORDER_EXECUTOR_MESSAGE_QUEQUE.shutdownNow();
            }
            if (!SECKILL_ORDER_EXECUTOR_BLOCK_QUEQUE.awaitTermination(2, TimeUnit.SECONDS)) {
                SECKILL_ORDER_EXECUTOR_BLOCK_QUEQUE.shutdownNow();
            }
        } catch (InterruptedException e) {
            // 等待期间本线程被中断，直接强制关闭
            SECKILL_ORDER_EXECUTOR_MESSAGE_QUEQUE.shutdownNow();
            SECKILL_ORDER_EXECUTOR_BLOCK_QUEQUE.shutdownNow();
        }
        log.info("订单消费线程池已停止");
    }


}
