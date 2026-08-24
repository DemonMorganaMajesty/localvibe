package com.localvibe.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * <p>
 * 商品库存实体（对应 localvibe 业务库 tb_item_stock 表）
 * openresty lua 中 stock:id:{id} 缓存的键，秒杀/抢购时的库存来源
 * </p>
 *
 * @author 改造新增
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_item_stock")
public class ItemStock implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 商品id，关联 tb_item 表
     */
    @TableId(value = "item_id", type = IdType.INPUT)
    private Long itemId;

    /**
     * 商品库存
     */
    private Integer stock;

    /**
     * 商品销量
     */
    private Integer sold;

}
