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
@TableName("tb_blog_comments")
public class BlogComments implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 评论用户昵称（非数据库字段）
     */
    @TableField(exist = false)
    private String nickName;

    /**
     * 评论用户头像（非数据库字段）
     */
    @TableField(exist = false)
    private String icon;

    /**
     * 帖子标题（非数据库字段）
     */
    @TableField(exist = false)
    private String blogTitle;

    /**
     * 帖子图片（非数据库字段）
     */
    @TableField(exist = false)
    private String blogImages;

    /**
     * 当前用户是否点赞过该评论（非数据库字段）
     */
    @TableField(exist = false)
    private Boolean isLike;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户id
     */
    private Long userId;

    /**
     * 探店id
     */
    private Long blogId;

    /**
     * 关联的1级评论id，如果是一级评论，则值为0
     */
    private Long parentId;

    /**
     * 回复的评论id
     */
    private Long answerId;

    /**
     * 回复的内容
     */
    private String content;

    /**
     * 点赞数
     */
    private Integer liked;

    /**
     * 状态，0：正常，1：被举报，2：禁止查看
     */
    private Boolean status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


}
