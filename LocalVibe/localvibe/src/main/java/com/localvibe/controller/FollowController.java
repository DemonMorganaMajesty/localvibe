package com.localvibe.controller;


import com.localvibe.dto.Result;
import com.localvibe.service.IFollowService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    IFollowService followService;


    //关注/取关  followee:你关注的人 被关注者 follower:你的粉丝 关注者
    @PutMapping("/{id}/{isFollow}")
    public Result followOrUnfollow(@PathVariable("id") Long followeeId,
                         @PathVariable("isFollow") Boolean isFollow){
        return followService.followOrUnfollow(followeeId,isFollow);
    }

    //判断登录的用户是否关注过 用户followeeId
    @GetMapping("/or/not/{id}")
    public Result queryFolloweeId(@PathVariable("id") Long id){
        return followService.queryFolloweeId(id);
    }

    //查询登录者和一个发帖者 的共同的关注
    @GetMapping("/common/{id}")
    public Result queryMutualFollowees(@PathVariable("id") Long objectUserId){
        return  followService.queryCommonFollowees(objectUserId);
    }

    // 改造：查询某用户的粉丝列表(关注了 TA 的人)
    @GetMapping("/fans/{id}")
    public Result queryFans(@PathVariable("id") Long userId){
        return followService.queryFans(userId);
    }

    // 改造：查询某用户关注的人列表
    @GetMapping("/followees/{id}")
    public Result queryFollowees(@PathVariable("id") Long userId){
        return followService.queryFollowees(userId);
    }
}
