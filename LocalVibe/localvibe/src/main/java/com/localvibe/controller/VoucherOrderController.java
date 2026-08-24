package com.localvibe.controller;


import com.localvibe.dto.Result;
import com.localvibe.service.IVoucherOrderService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    IVoucherOrderService voucherOrderService;

    //抢优惠券 同步,所有数据同步更新,加锁处理多人抢票 一人抢多票
    @PostMapping("seckill/{id}")
    public Result seckillVoucher1(@PathVariable("id") Long voucherId) {
        return voucherOrderService.GetSeckillVoucher1(voucherId);
    }

    //抢优惠券 异步+阻塞队列,数据redis中间保存 数据库不需要同步更新库存的数据
    @PostMapping("seckillBlockQueue/{id}")
    public Result seckillVoucher2(@PathVariable("id") Long voucherId) {
        return voucherOrderService.GetSeckillVoucher2(voucherId);
    }

    //抢优惠券 异步+消息队列,数据redis中间保存 数据库不需要同步更新库存的数据
    @PostMapping("seckillMessageQueue/{id}")
    public Result seckillVoucher3(@PathVariable("id") Long voucherId) {
        return voucherOrderService.GetSeckillVoucher3(voucherId);
    }

    // 领取普通优惠券（type=0 直接落库，一人一单）
    @PostMapping("claim/{id}")
    public Result claimVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.claimVoucher(voucherId);
    }

    // 我的优惠券：当前用户领取过的优惠券列表（需登录）
    @GetMapping("/of/me")
    public Result queryMyVouchers() {
        return voucherOrderService.queryMyVouchers();
    }

}
