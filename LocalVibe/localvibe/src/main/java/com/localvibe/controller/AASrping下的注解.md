# Spring注解完整总结（面试速记版）
> Bean：被Spring IOC容器管理的对象。
> 创建Bean有**两套完全不同方式**；之后是注入、读取配置、事务、生命周期、MVC。

## 一、方式1：类上注解，Spring自动帮你new对象（@Component家族）
写在**类上面**，Spring扫描类，自动实例化放入IOC。
|注解|用途|
|---|---|
|`@Component`|通用组件，所有普通Bean|
|`@Service`|业务层实现类，`@Component`派生，仅语义区分|
|`@Repository`|DAO数据访问层（MyBatis一般用@Mapper）|
|`@Controller`|MVC控制器|
|`@RestController`|接口控制器 = `@Controller + @ResponseBody`|

> 共同点：都是`@Component`衍生，底层功能一样，只是用来区分分层，提高可读性。

## 二、方式2：@Bean 写在方法上，自己new对象交给Spring
1. 一般放在`@Configuration`配置类的方法上。
2. **对象由程序员手动new，return返回，注册成为IOC的Bean**。
3. 使用场景：
    - 第三方类，改不了源码，不能加`@Component`（RedisTemplate、LettuceConnectionFactory）
    - 需要同一个类创建**多个不同配置实例**（你的项目两个StringRedisTemplate，db6、db7）
4. `@Bean(name="bean名字")`可以指定Bean名称。

> `@Configuration`：标记配置类，会CGLIB代理；类内部调用本类@Bean方法，直接拿容器单例，不会重复创建对象。

> ✔对比记忆
- `@Service`：类上，Spring帮new，自己写的业务类用。
- `@Bean`：方法上，自己new对象，第三方组件、多实例场景用。
> 两者产出都是IOC容器的Bean，注入的时候没有区别。

## 三、依赖注入：把容器中的Bean注入到变量
|注解|特点|
|---|---|
|`@Resource`|Java原生，**优先按bean名称匹配，其次按类型**|
|`@Autowired`|Spring专属，**优先按类型匹配**|
|`@Qualifier("beanName")`|配合上面两个，指定Bean名称，解决**同类型多个Bean冲突**|

> 坑：同一个类型容器有多个Bean，不写`@Qualifier`直接注入直接报错。

> 注意：手动new出来的对象（`new LoginInterceptor1()`）**不归Spring管**，类内部`@Resource/@Autowired`不会生效，需要构造方法传参。

## 四、读取yml配置文件
1. `@Value("${xxx}")`：写在字段上，读取简单字符串、数字；适合少量零散配置。
2. `@ConfigurationProperties`：批量把yml绑定到实体类，适合大批量配置。

## 五、事务相关 Service层重点
`@Transactional` Spring声明式事务，底层AOP动态代理。
- 只能加在**public方法**上才生效。
- 常用：`@Transactional(rollbackFor = Exception.class)`，所有异常都回滚。
- 失效场景（面试高频）
    1. private/protected方法
    2. 当前类内部`this.方法()`调用，不走代理对象
    3. 异常被try‑catch吃掉，没有抛出
    4. mysql不是InnoDB引擎

> 原则：查询方法不加事务；写操作加事务。

## 六、生命周期（Bean初始化、销毁）
1. 注解版本
- `@PostConstruct`：Bean属性注入完成后执行（初始化）
- `@PreDestroy`：Bean销毁前执行（释放资源）
2. 接口版本（你Canal代码用到）
- `InitializingBean` → `afterPropertiesSet()` 初始化
- `DisposableBean` → `destroy()` 销毁

> Canal项目：afterPropertiesSet启动消费线程；destroy关闭线程释放资源。

## 七、MVC Web相关
1. `WebMvcConfigurer`：**接口不是注解**，扩展SpringMVC功能。
    - `addInterceptors()`：注册拦截器，`order()`控制执行顺序，数字越小优先级越高。
    - `addCorsMappings()`：配置跨域。
2. 拦截器：preHandle从小到大执行；postHandle/afterCompletion从大到小反向执行。
3. ThreadLocal：存当前登录用户，请求结束必须清理，防止线程池复用产生错乱、内存泄漏。

## 八、三层架构职责
1. **Controller**：接收http请求，接收参数，返回结果，不写业务逻辑。
2. **Service @Service**：核心业务、事务、Redis、MQ、业务校验，组合多次数据库操作。
3. **Mapper @Mapper**：只做数据库CRUD。

## 九、你项目典型案例回顾
1. OpenRestyRedisConfig
    - `@Configuration`配置类；`@Value`读取redis配置；`@Bean`手动创建db6的StringRedisTemplate；注入时搭配`@Qualifier("openrestyRedisTemplate")`区分db7默认模板。
2. InterceptorConfig
    - 实现`WebMvcConfigurer`；addInterceptors注册两个拦截器；order控制顺序；手动new的拦截器不会自动注入Bean。
3. CanalCacheSyncClient
    - InitializingBean启动线程消费binlog；DisposableBean关闭资源。
4. Service层
    - Impl加`@Service`；写操作加上`@Transactional(rollbackFor = Exception.class)`。

## 面试简短背诵话术
1. 创建Bean两种方式：类上加`@Component/@Service`由Spring实例化；方法上加`@Bean`自己new对象交给Spring，多用于第三方组件和多实例。
2. 注入：`@Resource`优先byName，`@Autowired`优先byType，多个同类型Bean配合`@Qualifier`指定名字。
3. `@Transactional`实现事务，注意几种事务失效场景，只作用public方法。
4. Bean生命周期可以用注解`@PostConstruct/@PreDestroy`或者接口InitializingBean、DisposableBean。
5. WebMvcConfigurer接口用来扩展MVC，注册拦截器、配置跨域，order控制拦截器执行顺序。

如果你需要，我可以再压缩成一页超短版本，适合写笔记。