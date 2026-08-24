# @Service 和 @Component 的区别，结合你两段代码
## 回顾你的两个类
1）黑马点评业务类：`BlogCommentServiceImpl`
```java
@Service
public class BlogCommentServiceImpl implements IBlogCommentService {
    // 处理业务逻辑：CRUD、点赞、fillComment等
}
```

2）定时任务类：`BlogLikeReconcileTask`
```java
@Component
public class BlogLikeReconcileTask {
    // @Scheduled 定时对账任务
}
```

---

## 底层本质
`@Service`、`@Repository`、`@Controller` **全部都是 `@Component` 的衍生注解**。
```java
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Component  // @Service 源码上面就标注了 @Component
public @interface Service {
}
```
- `@Component`：通用组件，Spring把类交给容器管理。
- `@Service`：语义化，专门标记**业务 Service层**。
> 功能上两者**Spring效果一模一样，都能注入、都能被扫描、都支持@Scheduled**；**差别只在阅读语义，功能无区别**。

### 简单一句话
- `@Service` = `@Component` + **语义说明：这是业务业务层Service**
- `@Component` = 通用，不属于标准三层（controller/service/repository）就用它。

---

## 为什么你的代码这么写
### ① BlogCommentServiceImpl → @Service
这个是标准的业务Service层：
- 实现接口 `IBlogCommentService`
- 对外提供业务方法：查询评论、新增评论、点赞、填充用户信息
- Controller会注入这个Service调用业务方法。

按照Spring开发规范：**业务逻辑层，统一用@Service，见名知意**。
> 你把它改成`@Component`，代码照样跑，但是不符合代码阅读习惯。

### ② BlogLikeReconcileTask → @Component
这个类**不是业务Service**：
- 不实现业务接口
- Controller不会注入调用它
- 它只是一个后台定时任务组件，放定时调度逻辑，不属于标准Service业务层。

不属于 Controller / Service / Mapper，通用组件，就用 `@Component`。

> 如果你强行给定时任务写`@Service`，程序**不会报错，可以正常跑定时任务**，但是语义不对，别人看代码会疑惑：这不是业务Service，为啥加@Service。

---

## 四个注解快速记忆
|注解|用途|
|---|---|
|`@Controller`|web控制器，接收前端请求|
|`@Service`|业务层，写业务逻辑|
|`@Repository`|Mapper/DAO层（Mybatis一般不用，Mapper用@Mapper）|
|`@Component`|通用组件：定时任务、工具类、监听器，不属于上面三层就用它|

> 注意：`@Scheduled` 只要求类被Spring管理，`@Component/@Service`都生效，**跟是哪个注解无关**。

### 举反例
```java
// 这样写定时任务，功能完全正常，但是不推荐，语义混乱
@Service
public class BlogLikeReconcileTask {
    @Scheduled(...)
    public void reconcileBlogLikeCounts(){}
}
```
能跑，但是团队阅读代码会困惑：这个类没有业务接口，不是业务服务，为什么@Service。

---

## 面试标准回答
> @Service是@Component的派生注解，底层都代表将类注册到Spring容器。
> @Service用于业务Service层，表达语义；@Component用于通用组件，例如定时任务、工具类，不属于三层架构的业务类。
> 两者功能完全等价，主要用于代码可读性，区分不同职责的类。

## 小补充
1. Mapper接口，我们用`@Mapper`，**不是@Component/@Repository**，Mybatis自己处理。
2. 工具类如果需要注入成员变量(StringRedisTemplate等)，用`@Component`；纯静态工具类不需要Spring管理，什么注解都不加。

### 总结你的项目
- 处理业务CRUD、给Controller调用 → **@Service**
- 定时任务、监听、普通组件，不对外提供业务接口 → **@Component**