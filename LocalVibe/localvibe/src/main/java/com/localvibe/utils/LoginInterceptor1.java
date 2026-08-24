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

//@Component
public class LoginInterceptor1 implements HandlerInterceptor {

    StringRedisTemplate stringRedisTemplate;
    public LoginInterceptor1(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }
    /*前置拦截器  检验多种Controller的登录信息,下发信息给Controller 设置用户的信息
    将不为空的user(不拦截) 分配一个线程 就可以进行后续的请求
     */
    //使用token的拦截器 代码
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取token
        String token=request.getHeader("authorization");
        //token为空
        if(token==null){
            //token不存在表示未登录 也不用拦截 直接放行
            return true;
        }

        /*根据token 获取redis的用户信息,由键key(登录时候生成的token)
        取值(hash类型)所以取出来的也是一个map 键值对
         */
        String key=LOGIN_USER_TOKEN_KEY+token;
        Map<Object,Object> userInformationMap =
        stringRedisTemplate.opsForHash().entries(key);

        //用户不存在 拦截
        if(userInformationMap.isEmpty()){
            //响应未授权
            response.setStatus(401);
            return false;
        }

        //将user的成员对象的map 转换为userDTO 对象 不忽略错误
        UserDTO userDTO=
        BeanUtil.fillBeanWithMap(userInformationMap,
                new UserDTO(),false);

        //把UserDTO 存储到ThreadLocal
        UserHolder.saveUser(userDTO);

        /*刷新user的有效期(重新设置一遍有效时间即可)
        为什么要刷新:用户登录的时候 数据有效时间30min 但是登录后不退出
        数据的有效时间应该是永久的 等价于 用户每一次请求前要刷新一遍时间
        只要用户一直在操作 就要刷新
         */
        stringRedisTemplate.expire(key,
                LOGIN_USER_TOKEN_TTL, TimeUnit.MINUTES);
        return true;
    }

    //后置拦截器 用来移除threadLocal 的user用户,释放线程
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
/*  使用session 时候拦截器代码
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
            //得到session
            HttpSession session=request.getSession();

            //获取session的用户 得到的是UserDTO:屏蔽了敏感信息
            UserDTO userDto= (UserDTO) session.getAttribute("user");

            //用户不存在 拦截
            if(userDto==null){
                //响应未授权
                response.setStatus(401);
                return false;
            }

            //将sesison 的用户保存到 threadLocal 线程池里
            UserHolder.saveUser(userDto);

            return true;
        }

        //后置拦截器 用来移除threadLocal 的user用户,释放线程
        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
            UserHolder.removeUser();
        }*/
