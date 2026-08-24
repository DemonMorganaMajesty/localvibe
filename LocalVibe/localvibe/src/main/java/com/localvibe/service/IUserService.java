package com.localvibe.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.localvibe.dto.LoginFormDTO;
import com.localvibe.dto.Result;
import com.localvibe.dto.UserInfoEditDTO;
import com.localvibe.entity.User;
import jakarta.servlet.http.HttpSession;


public interface IUserService extends IService<User> {
    //验证码
    Result sendCode(String phone, HttpSession session);
    //登录
    Result login(LoginFormDTO loginForm, HttpSession session);

    //登出：删除当前 token 对应的 Redis 登录态
    Result logout(String token);
    //签到
    Result signUp();

    // 改造：保存/更新用户资料(昵称/头像/介绍/城市/性别等)，token 用于同步刷新 redis 登录态
    Result updateUserInfo(UserInfoEditDTO dto, String token);

    /*实现求出用户的连续签到天数:从最后一次签到的往前面倒着数直到1号,
    连续签到的次数(规定),不是求最长的 连续签到的天数, 也不是从前往后
     */
    Result findContinuiousSignUpCount();
}
