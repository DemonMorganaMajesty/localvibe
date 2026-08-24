package com.localvibe.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 商品实体（多级缓存核心：对应 localvibe 业务库 tb_item 表）
 * 该实体是 openresty(lua) 多级缓存链路的数据源：nginx:81 -> openresty:8085 -> redis -> tomcat(本项目)
 * </p>
 *
 * @author 改造新增
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_item")
public class Item implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 商品id（openresty lua 中 item:id:{id} 缓存的键）
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 商品标题
     */
    private String title;

    /**
     * 商品名称
     */
    private String name;

    /**
     * 价格（单位：分）
     */
    private Long price;

    /**
     * 商品图片
     */
    private String image;

    /**
     * 类目名称（美食主题下为 火锅/甜品 等）
     */
    private String category;

    /**
     * 品牌名称
     */
    private String brand;

    /**
     * 规格（JSON 字符串）
     */
    private String spec;

    /**
     * 商品状态：1-正常，2-下架，3-删除
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
