package com.localvibe.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;


@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_voucher")
public class Voucher implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 商铺id
     */
    private Long shopId;

    /**
     * 代金券标题
     */
    private String title;

    /**
     * 副标题
     */
    private String subTitle;

    /**
     * 使用规则
     */
    private String rules;

    /**
     * 支付金额
     */
    private Long payValue;

    /**
     * 抵扣金额
     */
    private Long actualValue;

    /**
     * 优惠券类型
     */
    private Integer type;

    /**
     * 优惠券类型
     */
    private Integer status;
    /**
     * 库存
     */
    @TableField(exist = false)
    private Integer stock;

    /**
     * 生效时间
     */
    @TableField(exist = false)
    private LocalDateTime beginTime;

    /**
     * 失效时间
     */
    @TableField(exist = false)
    private LocalDateTime endTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;


    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 所属店铺名称（联表查询附加字段，非表字段）
     */
    @TableField(exist = false)
    private String shopName;

    /**
     * 所属店铺图片（联表查询附加字段，非表字段）
     */
    @TableField(exist = false)
    private String shopImages;

    /**
     * 所属店铺区域（联表查询附加字段，非表字段，用于前端展示地理位置）
     */
    @TableField(exist = false)
    private String shopArea;

    /**
     * 领取记录订单id（联表查询附加字段，非表字段）
     */
    @TableField(exist = false)
    private Long orderId;

    /**
     * 领取时间（联表查询附加字段，非表字段）
     */
    @TableField(exist = false)
    private LocalDateTime orderTime;


}
