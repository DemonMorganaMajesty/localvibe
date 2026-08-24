# 核心结论
**注入写【Service接口】，不要直接注入ServiceImpl实现类！**
```java
// ✅正确：注入接口
@Resource
private IBlogCommentsService blogCommentsService;

// ❌不推荐：直接注入实现类
@Resource
private BlogCommentsServiceImpl blogCommentsService;
```

> Spring IOC容器中，放入容器的是**实现类对象**；但是变量类型写**接口**。
> 面向接口编程，这是Spring开发的规范。

## 原理讲清楚
```java
// 接口
public interface IBlogCommentsService {}

// 实现类，@Service交给Spring管理
@Service
public class BlogCommentsServiceImpl implements IBlogCommentsService {}
```
1. `BlogCommentsServiceImpl`上加`@Service`，Spring把**这个实现类对象放进容器**，Bean名称默认：`blogCommentsServiceImpl`
2. 你写：`private IBlogCommentsService blogCommentsService;`
- @Autowired：**按接口类型去容器查找**，找到实现类的实例，完成注入。
- @Resource：先匹配bean名称，找不到再按接口类型查找，拿到实现类实例。

> 变量的**引用类型是接口**，实际引用的对象是实现类实例，多态。

## 为什么不建议直接注入实现类？
1. **违背面向接口编程思想**
   以后如果要换另一套实现，新增`BlogCommentsRedisServiceImpl implements IBlogCommentsService`。
   如果Controller写死`BlogCommentsServiceImpl`，所有地方都要改代码；
   注入接口，只需要控制哪个实现类交给Spring，业务代码不动。

2. 不利于单元测试
   写单元测试可以很方便mock接口；如果直接注入实现类，mock麻烦。

3. 符合MP项目、黑马点评全部源码写法，面试就按这个说。

## 那变量名怎么写？
```java
//接口：IBlogCommentsService
@Resource
private IBlogCommentsService blogCommentsService;
```
变量名 `blogCommentsService`，习惯去掉I前缀。
> @Resource优先按bean名字匹配：容器bean名字是`blogCommentsServiceImpl`。
> 这里变量名和bean名字不一致，所以@Resource会降级走**按类型匹配**，完全没问题。

> 注意：只有**同一个接口多个实现类**的时候，才会出现冲突报错，单个实现类随便写。

### 多实现类场景演示
```java
@Service("dbImpl")
public class BlogCommentsServiceImpl implements IBlogCommentsService {}

@Service("redisImpl")
public class BlogCommentsRedisImpl implements IBlogCommentsService {}
```

✅@Resource 指定name（依然注入接口）
```java
@Resource(name = "dbImpl")
private IBlogCommentsService blogCommentsService;
```

✅@Autowired + @Qualifier（依然注入接口）
```java
@Autowired
@Qualifier("redisImpl")
private IBlogCommentsService blogCommentsService;
```

❌绝对不要写成：
```java
// 不要写实现类做变量类型
@Resource
private BlogCommentsServiceImpl blogCommentsService;
```

## 面试口述话术
> 我们开发的时候注入的是Service接口，不是ServiceImpl实现类。
> 实现类上加@Service交给SpringIOC管理，容器存的是实现类实例；变量声明为接口，利用多态。
> 好处面向接口编程，替换实现、单元测试mock更方便。
> 只有一个实现类，@Autowired、@Resource都可以注入；多个实现类，需要指定Bean名称。

## 补充一个高频面试追问：
> 既然注入接口，接口不能new，Spring是怎么做的？
> Spring在容器中保存的是实现类的实例，变量声明为接口类型，父类引用指向子类对象，Java多态。

### 顺带提：Mapper
Mapper也是一样，注入**Mapper接口**，不要写实现类。Mybatis动态生成Mapper接口的代理对象放到IOC。
```java
@Resource
private BlogCommentsMapper blogCommentsMapper;
```

要不要我顺带讲下 `@Service`、`@Mapper`注解的作用？