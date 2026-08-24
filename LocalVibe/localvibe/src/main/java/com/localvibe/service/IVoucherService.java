package com.localvibe.service;

import com.localvibe.dto.Result;
import com.localvibe.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IVoucherService extends IService<Voucher> {
    //查询所有秒杀券（首页秒杀专区，附带店铺信息） 按照销量排序
    Result querySeckillVoucherList();

    //查询有优惠券 的店铺
    Result queryVoucherOfShop(Long shopId);

    //添加秒杀优惠券
    void addSeckillVoucher(Voucher voucher);

    //添加优惠券
    void addVoucher(Voucher voucher);

}
