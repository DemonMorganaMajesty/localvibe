package com.localvibe.service;

import com.localvibe.dto.Result;
import com.localvibe.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IVoucherOrderService extends IService<VoucherOrder> {
    //抢秒杀优惠券 同步
    Result GetSeckillVoucher1(Long voucherId);

    //用于代理实现事务 同步创建订单的时候
    Result createVoucherOrder(Long voucherId);

    //抢秒杀优惠券 异步阻塞队列 发消息
    Result GetSeckillVoucher2(Long voucherId);

    /*用于代理实现事务 异步创建订单的时候 为什么同步异步传递的参数不同
    异步的时候 需要把订单的对象传递给阻塞队列
    弊端:阻塞队列的空间jvm有限可能会造成请求的丢失, 数据不一致问题,数据安全问题
    redis(数据是持久化的)宕机/.. 阻塞队列的订单还没有同步到数据库就会丢失
    解决方法:阻塞队列->消息队列(不依靠jvm内存,数据是持久化的,不会丢失)
     */
    void createVoucherOrder(VoucherOrder voucherOrder);
    //抢秒杀优惠券 异步消息队列 发消息
    Result GetSeckillVoucher3(Long voucherId);

    // 领取普通优惠券（type=0 直接落库，一人一单）
    Result claimVoucher(Long voucherId);

    // 我的优惠券：当前用户领取过的优惠券列表
    Result queryMyVouchers();

}
