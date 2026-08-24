package com.localvibe.utils;

import cn.hutool.core.bean.BeanUtil;
import com.localvibe.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.localvibe.utils.RedisConstants.LOGIN_USER_TOKEN_KEY;
import static com.localvibe.utils.RedisConstants.LOGIN_USER_TOKEN_TTL;

public class LoginInterceptor2 implements HandlerInterceptor {

    /*第一个拦截器进行所有请求的拦截,第二个拦截器只需要进行是否有登录请求的拦截判断
    只要有登录那么就直接放行,没有就拦截
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //是否要拦截 threadLocal没有用户就需要拦截
        if(UserHolder.getUser()==null){
            //拦截未登录的用户 返回未登录的状态
            response.setStatus(401);
            //未登录的直接放行
            return false;
        }
        //有用户就放行
        return true;
    }


}
