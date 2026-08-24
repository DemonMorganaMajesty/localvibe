package com.localvibe.controller;


import com.localvibe.dto.Result;
import com.localvibe.entity.BlogComments;
import com.localvibe.service.IBlogCommentsService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/*
@RestController=@Controller + @ResponseBody。
将类注册为 Spring 控制器；
所有方法返回值自动序列化为 JSON 响应给前端，不用跳转页面。

@RequestMapping("/blog‑comments")
类级别路径前缀，该类下所有接口 URL 都会拼接 /blog-comments。

@PostMapping：对应 HTTP POST，新增 / 提交，发表评论
@GetMapping：对应 HTTP GET，查询数据
@PutMapping：对应 HTTP PUT，做状态修改（点赞 / 取消点赞）
@DeleteMapping：对应 HTTP DELETE，删除资源
 */
@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    //jakarta 提供的依赖注入注解，把IBlogCommentsService的实现类注入。
    //@Resource 优先按 bean 名称匹配；@Autowired 优先按类型匹配。
    @Resource
    private IBlogCommentsService blogCommentsService;

    /*发表评论（需登录）：body {blogId, content}
   @RequestBody 解析请求体 JSON，把前端 POST 传来的 json 自动封装成 BlogComments 对象。
   POST/PUT可用改注解,  get没有请求体
     */
    @PostMapping
    public Result addComment(@RequestBody BlogComments body) {
        return blogCommentsService.addComment(body.getBlogId(), body.getContent());
    }

    // 查询某帖子的评论列表（游客可看，默认5条一页，sortMode: latest=最新时间（默认）/hot=最热）
    @GetMapping("/of/blog/{blogId}")
    public Result queryCommentsOfBlog(
            /* @PathVariable("blogId") 获取 URL 路径{blogId}占位符中的值。
            示例：GET /blog-comments/of/blog/10 → blogId=10。

            @RequestParam获取 URL?后面的查询参数。 value：前端参数名
            GET /blog-comments/of/blog/10?pageNumber=2&pageSize=5&sortMode=hot
            blogId=10 → @PathVariable
            ?后面的可省略  pageNumber、pageSize、sortMode → @RequestParam
            这两个一般 增删改查都可用
             */
            @PathVariable("blogId") Long blogId,
            @RequestParam(value = "pageNumber", defaultValue = "1") Integer pageNumber,
            @RequestParam(value = "pageSize", defaultValue = "5") Integer pageSize,
            @RequestParam(value = "sortMode", defaultValue = "latest") String sortMode
    ) {
        return blogCommentsService.queryCommentsOfBlog(blogId, pageNumber, pageSize, sortMode);
    }

    // 评论点赞/取消点赞（需登录）  更新update
    @PutMapping("/like/{id}")
    public Result likeComment(@PathVariable("id") Long id) {
        return blogCommentsService.likeComment(id);
    }

    // 删除自己的评论（需登录）
    @DeleteMapping("/{id}")
    public Result deleteComment(@PathVariable("id") Long id) {
        return blogCommentsService.deleteComment(id);
    }

    // 我的评论列表（需登录）
    @GetMapping("/of/me")
    public Result queryMyComments() {
        return blogCommentsService.queryMyComments();
    }
}