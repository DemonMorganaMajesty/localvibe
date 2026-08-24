package com.localvibe.service;

import com.localvibe.dto.Result;
import com.localvibe.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IBlogService extends IService<Blog> {
    //保存发布的笔记,要保存到redis(向粉丝推送笔记)和数据库
    Result saveBlogAndFeedToFollowers(Blog blog);

    //按照热度的分页查询 分页查询是比较每一个贴子的点赞总数,先排序再分页
    Result queryHotBlogPage(Integer pageNumber);

    //分页查询客户发的所有的帖子
    Result queryBlogById(Long id);

    //给帖子点赞
    Result likeBlog(Long id);

    //按照时间的先后顺序查询所有的点赞的列表(人) 前5个
    Result queryLikeListByTime(Long blogId);

    //滚动查询 来自关注者发布的笔记推送 关注者的笔记推送是先发送到收件箱redis
    Result scrollQueryBlogFromFollowee(Long max, Integer offset);

    // 改造：更新自己的笔记(标题/正文/图片/关联店铺)，仅笔记作者可操作
    Result updateBlog(Blog blog);

    // 改造：删除自己的笔记(同步清理点赞/粉丝收件箱/详情缓存)，仅笔记作者可操作
    Result deleteBlog(Long blogId);

    // 改造：我的点赞列表，我点赞过的帖子(Redis 反向索引，首次查询自动同步历史点赞)
    Result queryMyLikedBlogs();
}
