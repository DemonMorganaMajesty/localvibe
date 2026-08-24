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

/*
@Data
Lombok注解自动生成：getter、setter、toString、无参构造、equals、hashCode 方法。

@EqualsAndHashCode(callSuper = false)
生成equals()和hashCode()方法
callSuper=false：不调用父类的 equals/hashCode。
因为当前类没有继承其他业务父类，如果写 true 会去调用 Object 的，一般实体写 false。
如果你的 Blog 继承了别的类，就要改成 true。

@Accessors(chain = true)
链式 setter。普通 set 返回 void，开启后 set 方法返回this对象。
Blog blog = new Blog()
        .setTitle("探店")
        .setShopId(1001L)
        .setContent("好吃");

@TableName("tb_blog")
MyBatis‑Plus  注解
指定数据库表名。
实体类名Blog，数据库表叫tb_blog，名字不一致，必须标注，否则 MP 找不到表。
 */

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_blog")
public class Blog implements Serializable {
    /*JVM 序列化 / 反序列化时校验版本号。1L 是手动指定版本号。
    需要进行 反/序列化 必须要implements Serializable
     */
    private static final long serialVersionUID = 1L;
    /**
     *
     * value="id"：数据库主键列名
     * type = IdType.AUTO：数据库自增主键，插入数据时 id 由 mysql 自动生成，代码不用设置 id。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /**
     * 商户id
     */
    private Long shopId;
    /**
     * 用户id
     */
    private Long userId;
    /**
     * 用户图标 exist=false  代表数据库中没有这个字段
     * 只用来做业务返回，查询数据库、插入数据库的时候 MyBatis‑Plus 会忽略这个字段。
     */
    @TableField(exist = false)
    private String icon;
    /**
     * 用户姓名
     */
    @TableField(exist = false)
    private String name;
    /**
     * 是否点赞过了 实现一个用户对一个帖子点一个赞
     */
    @TableField(exist = false)
    private Boolean isLike;

    /**
     * 标题
     */
    private String title;

    /**
     * 探店的照片，最多9张，多张以","隔开
     */
    private String images;

    /**
     * 探店的文字描述
     */
    private String content;

    /**
     * 点赞数量
     */
    private Integer liked;

    /**
     * 评论数量
     */
    private Integer comments;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


}
