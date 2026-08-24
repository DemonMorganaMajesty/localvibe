package com.localvibe.controller;


import cn.hutool.core.bean.BeanUtil;
import com.localvibe.dto.LoginFormDTO;
import com.localvibe.dto.Result;
import com.localvibe.dto.UserDTO;
import com.localvibe.entity.User;
import com.localvibe.entity.UserInfo;
import com.localvibe.service.IUserInfoService;
import com.localvibe.service.IUserService;
import com.localvibe.utils.UserHolder;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;


/*import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;*/


@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;


    /* 使用session的验证码发送 登录  登录校验
     public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        // TODO 发送短信验证码并保存验证码
        return userService.sendCode(phone,session);
        //return Result.fail("功能未完成");
    }
    @PostMapping("/login")
    //@RequestBody 绑定json格式的参数
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        // TODO 实现登录功能
        return userService.login(loginForm,session);
        //return Result.fail("功能未完成");
    }
     //登录时候需要登录检验 拦截器会拦截请求request 里有本次登录的用户
    @GetMapping("/me")
    public Result me(){
        // TODO 获取当前登录的用户并返回
        //UserDTO:把User的敏感信息屏蔽掉
        UserDTO user=UserHolder.getUser();
        return Result.success(user);
        //return Result.fail("功能未完成");
    }
     */

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    /*@RequestParam("phone") 用来绑定参数  session一次会话,自动分派
    用于将生成的验证码 保存到 session中 便于和用户的验证码 比较
     */
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        // TODO 发送短信验证码并保存验证码
        return userService.sendCode(phone,session);
        //return Result.fail("功能未完成");
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    //@RequestBody 绑定json格式的参数
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        // TODO 实现登录功能
        return userService.login(loginForm,session);
        //return Result.fail("功能未完成");
    }

    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(@RequestHeader(value = "authorization", required = false) String token){
        return userService.logout(token);
    }

    //登录时候需要登录检验 拦截器会拦截请求request 里有本次登录的用户
    @GetMapping("/me")
    public Result me(){
        // TODO 获取当前登录的用户并返回
        //UserDTO:把User的敏感信息屏蔽掉
        UserDTO user=UserHolder.getUser();
        return Result.success(user);
        //return Result.fail("功能未完成");
    }

    //查询用户信息
    // 改造：保存/更新当前登录用户的资料(昵称/头像/介绍/城市/性别等)
    @PutMapping("/info")
    public Result updateInfo(@RequestBody com.localvibe.dto.UserInfoEditDTO dto,
                             @RequestHeader(value = "authorization", required = false) String token) {
        return userService.updateUserInfo(dto, token);
    }
    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.success();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.success(info);
    }

    // 根据id查询用户的信息
    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId){
        // 查询详情
        User user = userService.getById(userId);
        if (user == null) {
            return Result.fail("用户不存在,查询失败");
        }
        //隐藏私密信息
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回
        return Result.success(userDTO);
    }

    //实现签到功能,当前用户当天签到(一般是按照月份签到)
    @PostMapping("/signUp")
    public Result signUp(){
        return userService.signUp();
    }

    /*实现求出用户的连续签到天数:从最后一次签到的往前面倒着数直到1号,
    连续签到的次数(规定),不是求最长的 连续签到的天数, 也不是从前往后
     */
    @GetMapping("/signUp/count")
    public Result findContinuiousSignUpCount(){
        return userService.findContinuiousSignUpCount();
    }

}
