package com.localvibe.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.localvibe.dto.Result;
import com.localvibe.dto.UserDTO;
import com.localvibe.entity.Follow;
import com.localvibe.entity.UserInfo;
import com.localvibe.entity.User;
import com.localvibe.mapper.FollowMapper;
import com.localvibe.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.localvibe.service.IUserService;
import com.localvibe.service.IUserInfoService;
import com.localvibe.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.localvibe.utils.RedisConstants.USER_FOLLOWEES_KEY;


@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    IUserService userService;

    // 改造：用户详情表服务，用于维护粉丝/关注数量
    @Resource
    IUserInfoService userInfoService;

    /* 关注/取关 和点赞不一样,点赞是给一个帖子点赞后前端会高亮展示,关注/未关注
    没有关注的时候才会高亮展示,所以这里传过来的isFollow和点赞时候isLike的含义
    不同(isLike也没有作为参数传递到后端),
    followee:你关注的人 被关注者  follower:你的粉丝 关注者
    isFollow=true:登录用户准备关注用户  false:准备取关该用户
     */
    @Override
    public Result followOrUnfollow(Long followeeId, Boolean isFollow) {
        //获取登录者的信息
        UserDTO userDTO= UserHolder.getUser();
        //未登录
        if(userDTO==null)
            return Result.fail("用户未登录,请先登录");
        //登陆者的id
        Long userId=userDTO.getId();

        //isFollow==isBright 为true 没有关注 准备关注该用户
        if(isFollow){
            //要关注别人 就会多一跳数据库数据
            Follow follow =new Follow();
            //登录的用户的id 主动关注别人
            follow.setUserId(userId);
            //被关注的人 followee
            follow.setFollowUserId(followeeId);
            //plus
            boolean isSuccess=save(follow);
            //确保数据一致性
            if(isSuccess){
                //redis 要保存每个用户的关注的人,便于查询和找出登录者和被关注者的共同关注
                String key=USER_FOLLOWEES_KEY+userId;
                stringRedisTemplate.opsForSet().add(key,followeeId.toString());
                //同步更新被关注者的粉丝数 +1、自己的关注数 +1
                changeUserInfoCount(followeeId,"fans",1);
                changeUserInfoCount(userId,"followee",1);
            }
        }else{
            /*取关 删除数据库数据
            delete from tb_follow where user_id=? and follow_user_id=?
             */
            boolean isSuccess= remove(new QueryWrapper<Follow>()
                    .eq("user_id",userId)
                    .eq("follow_user_id",followeeId));
           //确保数据一致性
            if(isSuccess){
                //redis 要保存每个用户的关注的人,便于查询和找出登录者和被关注者的共同关注
                String key=USER_FOLLOWEES_KEY+userId;
                stringRedisTemplate.opsForSet().remove(key,followeeId.toString());
                //改造：同步更新被关注者的粉丝数 -1、自己的关注数 -1
                changeUserInfoCount(followeeId,"fans",-1);
                changeUserInfoCount(userId,"followee",-1);
            }
        }
        return Result.success((isFollow?"关注：":"取关：")
                +followeeId+"成功");
    }

    //维护 tb_user_info 的粉丝/关注数量(不存在则先创建默认行)
    private void changeUserInfoCount(Long targetUserId, String field, int delta){
        UserInfo info=userInfoService.getById(targetUserId);
        if(info==null){
            info=new UserInfo();
            info.setUserId(targetUserId);
            info.setFans(0);
            info.setFollowee(0);
            info.setCredits(0);
            info.setLevel(false);
            userInfoService.save(info);
        }
        int current="fans".equals(field)
                ?(info.getFans()==null?0:info.getFans())
                :(info.getFollowee()==null?0:info.getFollowee());
        int target=Math.max(0,current+delta);
        userInfoService.update().set(field,target).eq("user_id",targetUserId).update();
    }

    //判断登录的用户是否关注过 用户followeeId 在数据库查数据
    @Override
    public Result queryFolloweeId(Long followeeId){
        //获取登录者的信息
        UserDTO userDTO= UserHolder.getUser();
        //未登录
        if(userDTO==null)
            return Result.fail("用户未登录,请先登录");
        //登陆者的id
        Long userId=userDTO.getId();

        //查询数据 tb_follow where user_id=? and follow_user_id=?
        Long count=query().eq("user_id",userId)
                .eq("follow_user_id",followeeId).count();

        return Result.success(count>0);
    }

    //查询登录者和一个用户 的共同的关注
    @Override
    public Result queryCommonFollowees(Long objectUserId) {
        //拿到当前用户信息(登录者的id)
        UserDTO userDTO= UserHolder.getUser();
        //未登录
        if(userDTO==null)
            return Result.fail("用户未登录,请先登录");
        //登陆者的id
        Long hostUserId=userDTO.getId();

        //获取登陆者本机的key
        String hostKey=USER_FOLLOWEES_KEY+hostUserId;
        //获取用户的key
        String objectKey=USER_FOLLOWEES_KEY+objectUserId;

        //查询两个用户关注列表中的交集
        Set<String> commonIdString= stringRedisTemplate.opsForSet().
                intersect(hostKey,objectKey);
        //判断是否为空 没有共同的关注者 返回空集合
        if(commonIdString==null || commonIdString.isEmpty())
            return Result.success(Collections.emptyList());

        //String -> Long
        Set<Long> commonId=
        commonIdString.stream().map(id-> Long.valueOf(id))
                //(id->BeanUtil.copyProperties(id,Long.class))
                .collect(Collectors.toSet());
        //根据相同id 查询相同的用户
        List<User> commonUser=userService.listByIds(commonId);
        //隐藏信息 用户转为UserDTO
        Set<UserDTO>commonUserDTO=
                commonUser.stream().map(user->
                        BeanUtil.copyProperties(user,UserDTO.class))
                        .collect(Collectors.toSet());
        //返回
        return Result.success(commonUserDTO);
    }

    //查询某用户的粉丝列表(关注了 TA 的人)，返回基础用户信息
    @Override
    public Result queryFans(Long userId) {
        //tb_follow 中 follow_user_id = 该用户 的行，其 user_id 就是粉丝
        List<Follow> fans=query().eq("follow_user_id",userId).list();
        if(fans.isEmpty())
            return Result.success(Collections.emptyList());
        List<Long> fanIds=fans.stream().map(Follow::getUserId).collect(Collectors.toList());
        return Result.success(toUserDTOList(fanIds));
    }

    // 查询某用户关注的人列表，返回基础用户信息
    @Override
    public Result queryFollowees(Long userId) {
        //tb_follow 中 user_id = 该用户 的行，其 follow_user_id 就是 TA 关注的人
        List<Follow> follows=query().eq("user_id",userId).list();
        if(follows.isEmpty())
            return Result.success(Collections.emptyList());
        List<Long> followeeIds=follows.stream().map(Follow::getFollowUserId).collect(Collectors.toList());
        return Result.success(toUserDTOList(followeeIds));
    }

    //id 列表 -> 用户基础信息列表(隐藏敏感信息)
    private List<UserDTO> toUserDTOList(List<Long> ids){
        List<User> users=userService.listByIds(ids);
        return users.stream()
                .map(user->BeanUtil.copyProperties(user,UserDTO.class))
                .collect(Collectors.toList());
    }
}

