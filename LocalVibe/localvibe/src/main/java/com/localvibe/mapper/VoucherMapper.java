package com.localvibe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import com.localvibe.entity.Voucher;
import org.apache.ibatis.annotations.Param;

import java.util.List;


public interface VoucherMapper extends BaseMapper<Voucher> {

    /**
     * 查询所有秒杀券（首页秒杀专区，附带秒杀信息与店铺信息）
     */
    @Select("SELECT v.id, v.shop_id, v.title, v.sub_title, v.rules, v.pay_value, v.actual_value, v.type, " +
            "sv.stock, sv.begin_time, sv.end_time, s.name AS shop_name, s.images AS shop_images, " +
            "s.area AS shop_area " +
            "FROM tb_voucher v " +
            "LEFT JOIN tb_seckill_voucher sv ON v.id = sv.voucher_id " +
            "LEFT JOIN tb_shop s ON v.shop_id = s.id " +
            "WHERE v.type = 1 AND v.status = 1")
    List<Voucher> querySeckillVouchers();

    /**
     * 查询当前用户领取过的优惠券列表（附带店铺信息与秒杀时间/领取时间）
     */
    List<Voucher> queryMyVouchers(@Param("userId") Long userId);

    List<Voucher> queryVoucherOfShop(@Param("shopId") Long shopId);
}
