package com.localvibe.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localvibe.dto.Result;
import com.localvibe.dto.UserDTO;
import com.localvibe.entity.Blog;
import com.localvibe.entity.User;
import com.localvibe.service.IBlogService;
import com.localvibe.service.IUserService;
import com.localvibe.utils.SystemConstants;
import com.localvibe.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.List;


@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;
    @Resource
    private IUserService userService;

    //保存发布的笔记,要保存到redis(向粉丝推送feed笔记)和数据库
    @PostMapping
    public Result saveBlogAndFeedToFollowers(@RequestBody Blog blog) {
        return blogService.saveBlogAndFeedToFollowers(blog);
    }

    // 改造：更新自己的笔记(仅作者可操作)
    @PutMapping("/update")
    public Result updateBlog(@RequestBody Blog blog) {
        return blogService.updateBlog(blog);
    }

    // 改造：删除自己的笔记(仅作者可操作)。路径不使用 /blog/{id}，避免与 openresty 详情缓存路由冲突
    @DeleteMapping("/delete/{id}")
    public Result deleteBlog(@PathVariable("id") Long id) {
        return blogService.deleteBlog(id);
    }

    /*为帖子点赞/取消点赞 只对blog实体加上isLike判断是否已经点过,并且返回给前端
    要实现一个用户对一个帖子点一个赞,点赞过的前端显示高亮且不能再点赞/在点就是取消点赞
    存在数据库内存消耗太大,放在redis中 key是帖子的id,value是hashSet
    存放点赞过的用户的id 数据库中保存该帖子点赞的总数
     */
    //点赞/取消点赞
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
      /*  // 修改点赞数量 有漏洞
        blogService.update()
                .setSql("liked = liked + 1").eq("id", id).update();*/
        return blogService.likeBlog(id);
    }

    // 分页查询当前用户发布的帖子，默认 5 条一页
    @GetMapping("/of/me")
    public Result queryMyBlogPage(
            @RequestParam(value = "pageNumber", defaultValue = "1") Integer pageNumber,
            @RequestParam(value = "pageSize", defaultValue = "5") Integer pageSize
    ) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("用户未登录,请先登录");
        }
        int current = pageNumber == null || pageNumber < 1 ? 1 : pageNumber;
        int size = pageSize == null || pageSize < 1 ? SystemConstants.DEFAULT_PAGE_SIZE
                : Math.min(pageSize, SystemConstants.MAX_PAGE_SIZE);
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId())
                .orderByDesc("create_time")
                .page(new Page<>(current, size));
        return Result.success(page.getRecords(), page.getTotal());
    }

    //按照热度的分页查询 分页查询是比较每一个贴子的点赞总数,先排序再分页
    @GetMapping("/hot")
    public Result queryHotBlogPage(@RequestParam(value = "pageNumber", defaultValue = "1") Integer pageNumber) {
     return blogService.queryHotBlogPage(pageNumber);
    }

    //根据id查询笔记
    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long blogId){
        return blogService.queryBlogById(blogId);
    }

    /*实现单个帖子按点赞时间排序,不同用户给单个帖子点赞,数据库只有点赞的总数
    具体哪些人点赞了不知道,redis 点赞的value是hashSet 唯一无序,list 不唯一
    可排序, 所以最好用sortedSet 唯一 可排序 但是需要给每一个sk指定一个值(score)
    这是排序的依据,所以直接把点赞的时间当作score,这样以前存在reids的数据也要该
    hashSet->sortedSet
     */
    //按照时间的先后顺序查询所有的点赞的列表(人) 前5个
    @GetMapping("/likes/{id}")
    public Result queryLikeListByTime(@PathVariable("id") Long blogId){
        return blogService.queryLikeListByTime(blogId);
    }

    // 我的点赞列表：我点赞过的帖子（需登录）
    @GetMapping("/likes/of/me")
    public Result queryMyLikedBlogs() {
        return blogService.queryMyLikedBlogs();
    }

    /*根据用户查询 用户发的帖子 查询一些 分页查询 前端的请求是按照?拼接的使用
    RequestParam注解,有些变量非必须可以去掉  {id}使用PathVariable绑定
     */
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "pageNumber", defaultValue = "1") Integer pageNumber,
            @RequestParam("id") Long id
    ) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", id)
                .page(new Page<>(pageNumber, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.success(records);
    }

    /*redis的SortedSet实现滚动分页查询的,实现粉丝接收关注者发送的笔记
     发送的笔记要按照时间排序,最新的在最上面(倒序),不能按照角标排顺序
     因为随时有可能有新的笔记插入进来,角标会变,所以不能使用list,
     */
    //滚动查询 来自关注者发布的笔记推送 关注者的笔记推送是先发送到收件箱redis
    @GetMapping("of/follow")
    public Result scrollQueryBlogFromFollowee(
            @RequestParam("lastId") Long max,
            @RequestParam(value ="offset",defaultValue = "0")
            Integer offset
    ){
        return blogService.scrollQueryBlogFromFollowee(max,offset);
    }
}
