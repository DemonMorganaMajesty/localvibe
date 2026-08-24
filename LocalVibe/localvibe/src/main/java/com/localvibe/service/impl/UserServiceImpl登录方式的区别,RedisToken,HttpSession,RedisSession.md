# 两套登录方案对比

> ①**现在在用：Redis-Token登录（上线代码）**
> ②**注释掉的代码：原生HttpSession登录（单机版本）**

## 流程总览

### ✅ Redis‑Token（你现在项目用）

1. 登录成功后端生成**UUID token字符串**返回给前端
2. 前端把token存到localStorage/sessionStorage，**每次请求放在请求头携带**
3. 拦截器拿到token，去Redis Hash读取用户DTO，存入`UserHolder(ThreadLocal)`
4. Redis设置TTL过期；退出登录直接删除Redis中的token key

> 验证码也存在Redis，key=`login:verification:{phone}`

### ✅ HttpSession（注释掉的旧代码）

1. 登录成功，把`UserDTO`放入`HttpSession`对象，Tomcat自动生成`JSESSIONID`，通过**Cookie自动返回浏览器**
2. 浏览器后续请求自动携带Cookie(JSESSIONID)，服务器根据JSESSIONID找到本机Session对象，取出用户信息放入ThreadLocal
3. 验证码直接存入HttpSession对象。

## 核心差异点


| 对比维度         | Redis‑Token登录（当前代码）                                                | 原生HttpSession登录（注释代码）                                         |
| ---------------- | --------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| **会话存储位置** | 独立Redis（所有服务实例共享）                                               | **Tomcat本机内存，单机隔离**                                            |
| **凭证传递方式** | 后端返回token字符串，前端手动放请求头；适配**前后端分离、APP、小程序**      | 浏览器自动Cookie携带JSESSIONID；**不适合小程序、APP**，强依赖Cookie机制 |
| **集群/分布式**  | ✅支持多实例部署，Nginx负载均衡随便转发，不会丢登录态                       | ❌集群会出现**session不共享问题**；请求落到不同服务器登录直接失效       |
| **验证码存储**   | Redis，key绑定手机号，防止换手机号复用验证码                                | 存在本机Session对象；绑定当前会话，换浏览器会话验证码失效               |
| **过期控制**     | Redis TTL控制过期；可以做滑动过期（访问刷新TTL）                            | tomcat容器配置session过期时间；服务重启session全部丢失                  |
| **服务重启**     | Redis持久化，重启服务登录态不丢失                                           | 服务重启，内存session全部清空，全部用户掉线                             |
| **登出销毁**     | 直接删除Redis中的token key，立刻失效                                        | 调用`session.invalidate()`销毁本机session对象                           |
| **更新用户信息** | 可以直接修改Redis hash中的nickName/icon（你的updateUserInfo就做了这个逻辑） | 只能在当前服务实例修改session对象；集群场景修改不会同步到其他节点       |
| **适用场景**     | 前后端分离、分布式、微服务、多实例部署（你的项目）                          | 传统服务端渲染（thymeleaf）、单机项目，不适合集群                       |

## HttpSession致命缺陷（面试必问）

1. **集群session不共享**
   用户登录打到A服务器，session保存在A内存；下一次Nginx转发到B服务器，B机器没有这个session，判定未登录直接拦截。

> 解决方案：ip_hash粘滞、tomcat会话复制、spring‑session把session存redis；但原生HttpSession默认不支持分布式。

2. **强依赖Cookie**，小程序、APP无法自动携带JSESSIONID，前后端分离项目很难用。
3. 服务器重启，内存session全部销毁，所有用户全部掉线。

## Redis‑Token（你项目）的优势

1. 会话数据全部放Redis，**所有服务实例共享会话，天然支持分布式集群**，适配Nginx负载均衡。
2. token交给前端自行存储（localStorage），放在请求头传递，适配H5、小程序、APP，**不依赖浏览器Cookie**。
3. 用户信息存储为Redis Hash，**可以局部更新字段**，比如修改昵称头像，直接修改hash中某个field，不用覆盖整个对象（`updateUserInfo`就是这个逻辑）。
4. 服务重启，Redis数据还在，用户不会掉线；支持滑动TTL刷新登录有效期。

## 两套代码细节小区别

1. **验证码校验**

- Redis‑Token：验证码key=手机号；**就算换浏览器，同一个手机号验证码也生效**。
- HttpSession：验证码绑定会话；换浏览器，就算同一个手机号，验证码直接失效。

2. **密码登录逻辑**
   两套都支持手机号密码登录；

- Session版本直接把UserDTO放入session对象；
- Redis‑Token版本：把UserDTO转为HashMap存入Redis Hash，返回token。

3. **拦截器逻辑不同**

- Redis‑Token拦截器：从请求头拿token，查询Redis。
- HttpSession拦截器：不需要拿请求头，自动读取Cookie的JSESSIONID，获取session对象。

## 面试官高频追问

> Q：既然HttpSession这么方便，为什么黑马点评要改成Redis‑Token？
> A：
>
> 1. 传统HttpSession存服务器本机内存，集群多实例部署会出现session不共享，请求转发到别的机器登录状态丢失。
> 2. 前后端分离项目，小程序、APP无法自动携带Cookie，JSESSIONID传递麻烦。
> 3. 使用Redis存储会话，所有服务节点共享一份会话，服务重启登录态不丢失，适配分布式架构。

> Q：Redis‑Token和SpringSession有什么区别？
> A：SpringSession底层也是把HttpSession全部存入Redis，API还是HttpSession；而我们手写Redis‑Token是完全自己实现会话逻辑，不用Servlet的Session接口，更适合前后端分离项目。

> Q：Redis‑Token方案存在什么问题？
> A：每一次请求都要访问Redis，多一层网络IO；可以搭配Caffeine本地缓存做一层会话缓存，减少Redis访问压力。

## 总结

- **注释掉的HttpSession：适合单机、传统网页，不适合分布式/前后端分离。**
- **当前Redis‑Token代码：适合你的LocalVibe项目，支持多实例、前后端分离，是项目里的亮点。**

> 简历写项目的时候，可以写：**手写Redis‑Token分布式会话，替代原生HttpSession，解决集群环境会话共享问题，适配前后端分离架构。**
