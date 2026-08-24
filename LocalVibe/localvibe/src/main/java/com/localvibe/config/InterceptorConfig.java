package com.localvibe.config;

import com.localvibe.utils.LoginInterceptor1;
import com.localvibe.utils.LoginInterceptor2;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class InterceptorConfig implements WebMvcConfigurer {

  /*  // 将拦截器交给Spring管理，生成Bean
    @Autowired
    LoginInterceptor1 loginInterceptor;
*/
    /* InterceptorConfig 交给了 Spring管理  StringRedisTemplate
   没有 所以加上Resource注解 自动创建一个 对象
     */
    @Resource
    StringRedisTemplate stringRedisTemplate;

/* 拦截器的执行顺序:默认是先add的先执行,最好手动为order(id)排序,数字越小的
越先执行
 */

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //主要拦截的是用户的登录检验的  发验证码 注册 登录/..直接放行
        //也可以直接注入 就要给类LoginInterceptor加@Component注解

        registry.addInterceptor(new LoginInterceptor1(stringRedisTemplate)).
                addPathPatterns("/**").order(0);

        registry.addInterceptor(new LoginInterceptor2())
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        // 游客模式：只放开"浏览类"只读接口（用户/店铺/笔记），
                        // 点赞、关注、发布、签到等写操作仍需登录（万不得已时才跳登录页）
                        "/blog/hot",
                        "/blog/{id}",
                        "/blog/likes/{id}",
                        "/blog/of/user",
                        "/blog-comments/of/blog/*",
                        "/user/*",
                        "/user/info/*",
                        "/follow/*",
                        "/user/code",
                        "/user/login"
                ).order(1);

    }

   /* // 👇 添加跨域配置
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true);
    }*/

}
