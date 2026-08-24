package com.localvibe.controller;


import com.localvibe.dto.Result;
import com.localvibe.entity.Voucher;
import com.localvibe.service.IVoucherService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;


@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    /**
     * 新增普通券
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    //添加优惠券在 管理员添加/postman申请
    @PostMapping
    public Result addVoucher(@RequestBody Voucher voucher) {
        voucherService.addVoucher(voucher);
        return Result.success(voucher.getId());
    }

    /**
     * 新增秒杀券
     * @param voucher 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    //添加优惠券在 管理员添加/postman申请
    @PostMapping("seckill")
    public Result addSeckillVoucher(@RequestBody Voucher voucher) {
        voucherService.addSeckillVoucher(voucher);
        return Result.success(voucher.getId());
    }

    /**
     * 查询所有秒杀券（首页秒杀专区展示，美食优惠券抢购入口）
     * @return 秒杀券列表
     */
    @GetMapping("/seckill/list")
    public Result querySeckillVoucherList() {
        return voucherService.querySeckillVoucherList();
    }

    /**
     * 查询店铺的优惠券列表
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }
}
