package com.localvibe.service;

import com.localvibe.dto.Result;
import com.localvibe.entity.BlogComments;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IBlogCommentsService extends IService<BlogComments> {

    // 发表评论：插入评论并让帖子评论数+1，返回最新评论数
    Result addComment(Long blogId, String content);

    // 分页查询某帖子的评论列表（补充用户昵称头像）
    // 分页查询某帖子的评论列表（补充用户昵称头像，sortMode: latest=按时间/hot=按点赞最热）
    Result queryCommentsOfBlog(Long blogId, Integer pageNumber, Integer pageSize, String sortMode);

    // 评论点赞/取消点赞（返回 {liked, isLike}）
    Result likeComment(Long commentId);

    // 删除自己的评论（帖子评论数-1）
    Result deleteComment(Long id);

    // 我的评论：我评论过的帖子列表（补充帖子标题/首图）
    Result queryMyComments();
}