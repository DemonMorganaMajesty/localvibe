package com.localvibe.service;

import com.localvibe.dto.Result;
import com.localvibe.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IFollowService extends IService<Follow> {
    //关注/取关
    Result followOrUnfollow(Long followeeId, Boolean isFollow);

    //判断登录的用户是否关注过 用户followeeId
    Result queryFolloweeId(Long followeeId);

    //查询登录者和目标用户 的共同的关注
    Result queryCommonFollowees(Long objectUserId);

    // 改造：查询某用户的粉丝列表(关注了 TA 的人)
    Result queryFans(Long userId);

    // 改造：查询某用户关注的人列表
    Result queryFollowees(Long userId);
}
