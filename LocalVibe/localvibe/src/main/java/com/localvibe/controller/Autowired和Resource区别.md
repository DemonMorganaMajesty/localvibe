# @Resource vs @Autowired
> 版本说明：
- `@Resource`：**JSR‑250 Java官方规范**，`jakarta.annotation.Resource`，JDK自带，不属于Spring。
- `@Autowired`：**Spring专属注解**，`org.springframework.beans.factory.annotation.Autowired`。

## 核心区别一句话
1. **@Resource：先按Bean名称匹配，名称找不到，再退回去按类型匹配**
2. **@Autowired：优先按类型匹配；类型匹配多个bean时，再结合变量名做名称筛选**

> Bean名称：Spring容器中Bean的id/name，默认是类名首字母小写，例如 `IBlogCommentsService` 的实现类 `BlogCommentsServiceImpl`，bean名称默认：`blogCommentsServiceImpl`。

---

## 示例代码
```java
// 方式1 @Resource
@Resource
private IBlogCommentsService blogCommentsService;

// 方式2 @Autowired
@Autowired
private IBlogCommentsService blogCommentsService;
```

### 场景1：接口只有**一个实现类**（你现在HmDp项目绝大多数情况）
✅两者效果完全一样，都能注入成功，随便写。
黑马点评很多地方混用。

### 场景2：一个接口**有多个实现类**（重点，坑就在这里）
```java
public interface IUserService {}

@Component("userServiceImplDb")
public class UserServiceImplDb implements IUserService {}

@Component("userServiceImplRedis")
public class UserServiceImplRedis implements IUserService {}
```
容器里面有**两个Bean，同一个接口类型**。

#### ① @Resource 情况
```java
// 变量名 = userServiceImplDb
@Resource
private IUserService userServiceImplDb;
```
`@Resource`拿**变量名**去匹配Bean名称，匹配到`userServiceImplDb`，注入成功。

如果变量名不对：
```java
// 变量名叫 userService，容器没有叫userService的bean
@Resource
private IUserService userService;
```
> 先按名字找：找不到 → 再按类型找，发现有2个同类型Bean → **直接报错NoUniqueBeanDefinitionException**

> @Resource可以手动指定name：强制指定bean名字
```java
@Resource(name = "userServiceImplDb")
private IUserService userService;
```

#### ② @Autowired 情况
`@Autowired`优先按**类型**找，找到2个，不知道选哪个，报错。
解决：搭配`@Qualifier("bean名字")`指定bean名称
```java
@Autowired
@Qualifier("userServiceImplDb")
private IUserService userService;
```

> 小细节：@Autowired有`required=false`属性，找不到bean不会抛异常；@Resource没有这个属性。
```java
@Autowired(required = false)
private XXX xxx;
```

## 对比表格
| 特性 | @Resource(jakarta) | @Autowired(Spring) |
|---|---|---|
| 来源 | Java标准，JSR‑250 | Spring框架专属 |
| 匹配顺序 | **1. Bean名称 → 2.类型** | **1.类型 → 2.配合@Qualifier指定名称** |
| 指定bean | `@Resource(name="xxx")` | `@Autowired + @Qualifier("xxx")` |
| required属性 | ❌没有 | ✅`@Autowired(required=false)` |
| 多实现类 | 依赖变量名或name属性 | 需要搭配@Qualifier |

## ✅实际项目怎么选（你的hmdp/localvibe项目）
1. **绝大多数情况：接口只有一个实现类**
   两者随便用。
- 黑马点评原始代码大量使用`@Resource`；
- 很多公司习惯用`@Autowired`。

> 你的代码：
```java
@Resource
private IBlogCommentsService blogCommentsService;
```
变量名 `blogCommentsService`，对应实现类bean名称`blogCommentsServiceImpl`，这里靠类型匹配成功。

2. **出现同一个接口多个实现类的时候**
- 想用`@Resource`：要么变量名和bean名字一致，要么写`@Resource(name="xxx")`
- 想用`@Autowired`：必须加上`@Qualifier("beanName")`

## ⚠️面试高频坑点
1. 很多人记反：误以为`@Resource`优先类型，**错！优先名字！**
2. `@Resource`是Java规范，不是Spring，切换别的DI框架也能用；`@Autowired`脱离Spring直接失效。
3. SpringBoot3 包名注意：`@Resource`是`jakarta.annotation.Resource`，不要再导入旧的`javax.annotation`。

## 面试背诵简短话术
> @Resource是Java标准注解，优先按Bean名称匹配，名称匹配失败才按类型；可以用name属性指定bean。
> @Autowired是Spring专属，优先按类型注入；同类型多个Bean时，配合@Qualifier指定bean名称；支持required=false。
> 业务中接口只有一个实现类，两者效果一致；多实现类场景二者都需要显式指定Bean名字。

### 补充小拓展：构造器注入（现在Spring官方推荐）
现在Spring官方推荐**构造函数注入**，不推荐字段注入（@Resource/@Autowired写在字段上）。
```java
// 构造器注入，Spring4.3之后，只有一个构造函数可以省略@Autowired
@RestController
public class BlogCommentsController {
    private final IBlogCommentsService blogCommentsService;

    public BlogCommentsController(IBlogCommentsService blogCommentsService) {
        this.blogCommentsService = blogCommentsService;
    }
}
```
> 面试官有可能追问：字段注入的缺点，构造器注入好处，需要我给你整理吗？