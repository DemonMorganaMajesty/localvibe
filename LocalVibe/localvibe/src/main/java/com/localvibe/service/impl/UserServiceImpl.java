package com.localvibe.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.localvibe.dto.LoginFormDTO;
import com.localvibe.dto.Result;
import com.localvibe.dto.UserDTO;
import com.localvibe.dto.UserInfoEditDTO;
import com.localvibe.entity.User;
import com.localvibe.entity.UserInfo;
import com.localvibe.mapper.UserMapper;
import com.localvibe.service.IUserInfoService;
import com.localvibe.service.IUserService;
import com.localvibe.utils.RegexUtils;
import com.localvibe.utils.UserHolder;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.localvibe.utils.RedisConstants.*;
import static com.localvibe.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Slf4j
@Service
/*extends ServiceImpl<UserMapper, User> mybatis-plus 实现单表的
增删改查 可以简化 Mapper的sql查询代码 非必须
 */
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    UserMapper userMapper;

    //数据存入redis 手动的序列化
    @Resource
    StringRedisTemplate stringRedisTemplate;

    //用户详情表(tb_user_info)服务，用于资料/积分/粉丝数维护
    @Resource
    IUserInfoService userInfoService;


    // 使用redis的验证码发送 登录  登录校验

    //发送验证码
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //检验发送来的手机号是否正确 正则表达式 Utils下
        boolean invalid=RegexUtils.isPhoneInvalid(phone);

        //如果手机号错误,返回错误信息
        if(invalid){
            return Result.fail("手机号格式错误");
        }
        //如果符合,生成验证码
            String code=RandomUtil.randomNumbers(6);

        //将验证码保存起来,保存到reids, 要和用户填写的验证码匹配
        /* 将手机号码作为key 验证码作为value(String),
        登陆时候需要保证登录的手机号和发验证码,手机号是同一个检验手机号
        是否一致:发短信用手机1,登录改为手机2+同一验证码 要排除这种情况
        用手机号作为key 直接就保证了是同一个手机号
        存redis时候命名要规范,"login:verification:"用于短信验证登录的
         手机号定义为常量  后面的是验证码的有效期
         */
        stringRedisTemplate.opsForValue().set(
                LOGIN_VERIFICATION_CODE_KEY +phone
                ,code,LOGIN_VERIFICATION_CODE_TTL, TimeUnit.MINUTES);

        //发送验证码 需要aliyun等平台 有点复杂 直接跳过
        log.debug("发送短信验证码成功,验证码:{}",code);

        //返回ok Result 数据格式
        return Result.success();
    }

    /*验证码登录/注册 登录后需要进行登录检验(utils中 拦截器去做
   只要有user不为空 那么就检验成功 放行:在threadLocal分配一个线程
   即可)
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //检验手机号是否一致:发短信用手机1,登录改为手机2+同一验证码 要排除这种情况
        String loginPhone=loginForm.getPhone();

        //优化2 使用手机号+密码登录 成功直接退出
        String loginPassword=loginForm.getPassword();
        if(loginPassword!=null){
            // 改造：先按手机号查询，区分"手机号未注册"与"密码错误"，避免误导用户
            User userByPhone=userMapper.selectUserByPhone(loginPhone);
            if(userByPhone==null)
                return Result.fail("该手机号未注册，请先注册或使用验证码登录");

            User user=userMapper.selectUserByPhoneAndPassword(loginPhone,loginPassword);

            if(user==null)
                return  Result.fail("密码错误，请重试");

            // 生成唯一的标识token
            String token=UUID.randomUUID().toString(true);
            //隐藏user的关键信息
            UserDTO userDTO=BeanUtil.copyProperties(user,UserDTO.class);

            Map<String, Object> rawMap = BeanUtil.beanToMap(userDTO);
            // 新建Map，把所有value统一转字符串，消除Long类型
            Map<String, String> strMap = new HashMap<>();
            for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
                strMap.put(entry.getKey(), entry.getValue().toString());
            }

            //把user成员变量 用前端的键值对map 表示出来 存入数据
            stringRedisTemplate.opsForHash().putAll(
                    LOGIN_USER_TOKEN_KEY +token,
                    strMap);

            //设置登录后数据的有效时间
            stringRedisTemplate.expire(LOGIN_USER_TOKEN_KEY +token,
                    LOGIN_USER_TOKEN_TTL,TimeUnit.MINUTES);
            //返回唯一的标识 token
            return Result.success(token);
        }

        //使用验证码登录 判断是不是该手机号发送的验证码 得到本地保存的验证码
        String redisCode=stringRedisTemplate.opsForValue().
                get(LOGIN_VERIFICATION_CODE_KEY +loginPhone);

        //前端传递来的验证码
        String loginCode=loginForm.getCode();

        //不能直接==(地址) 必须要调equals比较内容  调用equals的要非空
        if(redisCode==null)
        return Result.fail
                ("登录的手机号与发送验证码的手机号不一致,登录失败");


        if(!redisCode.equals(loginCode))
            return Result.fail("验证码错误,登录失败");

        // 验证码校验成功后立即删除，保证一次性使用，避免同一验证码重复登录
        stringRedisTemplate.delete(LOGIN_VERIFICATION_CODE_KEY + loginPhone);

        //验证码匹配成功 根据手机号查询用户
        String phone=loginForm.getPhone();

        //手机号注册过,查询到了用户
        //User user=userMapper.selectUserByPhone(phone);
        //mybatis-plus简化
        User user=query().eq("phone",phone).one();

        //没有查询到用户,注册一个用户,下面用的同一个user,要复用
        if(user==null){
            /*nickName 非空,注册的时候要自动分配,很多软件的初始化昵称的前缀
            都是相同的 抽象出来定义到utils的SystemConstants里user_,再
            使用hutTool 里面的工具 自动生成一些符号
             */
            //生成user 时候 可以在iml 内写个函数
            user=new User();
            user.setPhone(phone);
            user.setCreateTime(LocalDateTime.now());
            user.setUpdateTime(LocalDateTime.now());

            //随机生成昵称
            String nickName=USER_NICK_NAME_PREFIX+
                    RandomUtil.randomString(10);
            user.setNickName(nickName);

            //也可以直接传递 user 保存数据到数据库
            userMapper.insertByPhone(phone,nickName,LocalDateTime.now());
            //save(user);//向tb_user表插入数据 mybatis-plus
            // insertByPhone 自定义插入不会回填自增主键，重新查询拿到 id，否则登录态缺少 userId
            user = query().eq("phone", phone).one();
        }

        /*session(手动传递sessionId给cookie) redis 不行必须自动的
        传递唯一值(令牌token 作为唯一的标识,不能传递手机号,泄露隐私)
        sessionID 是随机的字符串 这里也传递随机的字符串
         */
        //toString(true); 生成的字符串没有下划线
        String token=UUID.randomUUID().toString(true);

        //uerDto 只保存 user的基本信息 给前端 隐藏敏感信息
        UserDTO userDTO=BeanUtil.copyProperties(user,UserDTO.class);

        /*把userDTO保存到redis中 redis的value 为Hash类型
        BeanUtil.beanToMap(userDTO) 把hash类型的uer成员变量
        转化为map的键值对 返回给前端
         */
        //Map<String,Object> rawMap =BeanUtil.beanToMap(userDTO);

        /*把所有值转为字符串，兼容StringRedisTemplate
        StringRedisTemplate全部使用字符串序列化器，只能存入字符串；
         */
       /* Map<String, String> strMap = new HashMap<>();
        for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
            strMap.put(entry.getKey(), entry.getValue().toString());
        }*/

           //使用另一种 方式把rawMap 中值Object-> String 兼容
      Map<String,Object>strMap=BeanUtil.beanToMap(
                userDTO,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor(
                                (fieldName,fieldValue)->{
                                    if(fieldValue==null)
                                        return "";
                                    return fieldValue.toString();
                                }));

        String tokenKey=LOGIN_USER_TOKEN_KEY +token;

        //把数据存入redis 返回给前端
        stringRedisTemplate.opsForHash().putAll(tokenKey,strMap);

        //给redis 的数据设置有效期(30min)
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TOKEN_TTL,
                TimeUnit.MINUTES);

        /*返回成功的信息 不需要返回登录的凭证jwt session是根据cookie的
        每一次session 有一个唯一的id id自动写入cookie(id作为登录凭证)
        每次会话都会带着id(登录凭证)找到session 进而找到user
         */
        return Result.success(token);
    }

    //登出：删除 Redis 登录态，使原 token 立即失效
    @Override
    public Result logout(String token) {
        if (StrUtil.isBlank(token)) {
            UserHolder.removeUser();
            return Result.success();
        }
        stringRedisTemplate.delete(LOGIN_USER_TOKEN_KEY + token);
        UserHolder.removeUser();
        return Result.success();
    }

    //实现签到的功能
    @Override
    public Result signUp() {
        //获取当前登录的用户信息
        UserDTO userDTO= UserHolder.getUser();
        //避免userDTo为null 得到的userId也为空 空指针异常 传入Redis
        if (userDTO == null || userDTO.getId()== null) {
            // 未登录直接返回，不执行Redis操作
            return Result.fail("用户未登录,请先登录");
        }
        //id
        Long userId=UserHolder.getUser().getId();

        //获取当前日期 的年和月 签到是根据月签到
        LocalDateTime now = LocalDateTime.now();
        String keySuffixYearAndMonth = now.format(DateTimeFormatter.
                ofPattern(":yyyy:MM"));

        //拼接key 实现存入redis 签到:id:年：月
        String key=USER_SIGNUP_KEY+userId+ keySuffixYearAndMonth;

        //获取今日是这个月的第几天 下标从1开始 存入的时候要减1
        int dayOfMonthIndex=now.getDayOfMonth();
        int offset=dayOfMonthIndex-1;

        //先判断今天是否已签到，已签到直接返回，避免重复签到/重复加积分
        Boolean signedTodayBit=stringRedisTemplate.opsForValue().getBit(key,offset);
        if(Boolean.TRUE.equals(signedTodayBit)){
            return Result.fail("今日已签到，请明天再来");
        }

        //当前签到 就是当天的 二进制为1(true) setbit key offset(idx) 值(0/1)
        stringRedisTemplate.opsForValue().setBit(key,offset,true);

        //改造：签到成功积分+1（tb_user_info 不存在则先创建默认行）
        UserInfo userInfo=userInfoService.getById(userId);
        if(userInfo==null){
            userInfo=new UserInfo();
            userInfo.setUserId(userId);
            userInfo.setFans(0);
            userInfo.setFollowee(0);
            userInfo.setCredits(1);
            userInfo.setLevel(false);
            userInfoService.save(userInfo);
        }else{
            int credits=(userInfo.getCredits()==null?0:userInfo.getCredits())+1;
            userInfoService.update().set("credits",credits)
                    .eq("user_id",userId).update();
        }
        return Result.success();
    }

    /*实现求出用户的连续签到天数:从最后一次签到的往前面倒着数直到1号,
    连续签到的次数(规定),不是求最长的 连续签到的天数, 也不是从前往后
    同时返回今天是否已签到，前端用于控制签到按钮与展示
     */
    @Override
    public Result findContinuiousSignUpCount() {
        //获取当前登录的用户信息
        UserDTO userDTO= UserHolder.getUser();
        //避免userDTo为null 得到的userId也为空 空指针异常 传入Redis
        if (userDTO == null || userDTO.getId()== null) {
            // 未登录直接返回，不执行Redis操作
            return Result.fail("用户未登录,请先登录");
        }
        //id
        Long userId=UserHolder.getUser().getId();

        //获取当前日期 的年和月 签到是根据月签到
        LocalDateTime now = LocalDateTime.now();
        String keySuffixYearAndMonth = now.format(DateTimeFormatter.
                ofPattern(":yyyy:MM"));
        //拼接key 实现存入redis 签到:id:年：月
        String key=USER_SIGNUP_KEY+userId+ keySuffixYearAndMonth;
        //获取今日是这个月的第几天 下标从1开始 存入的时候要减1
        int dayOfMonthIndex=now.getDayOfMonth();

        /*本月从1号到今天的所有的签到的记录:返回的是十进制数字(查询)
        把到现在为止的签到记录(10进制 解析出二进制,从后往前有几个连续的1)
        bitfeild key get u位数(u:无符号,位数就是从头部开始查询 几位数)
        数字(开始索引)
        查询出的结果是一个十进制的,但是为什么是一个集合,bitField可以同时
        实现多种功能,get set.. 一种功能得到的是一个结果,所以最后的结果是一个
        集合,只用get,集合就只需要判断第一个元素(idx 0)
         */
        List<Long> results = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.
                create().get(BitFieldSubCommands.BitFieldType.
                unsigned(dayOfMonthIndex)).valueAt(0));
        //判断是否有签到的结果(get,set..)
        if(results==null|| results.isEmpty()){
            Map<String,Object> empty=new HashMap<>();
            empty.put("count",0);
            empty.put("signedToday",false);
            return Result.success(empty);
        }

        Long num = results.get(0);
        //判断是否有get的结果 需要判断第一个元素(idx 0)
        if(num ==null || num ==0){
            Map<String,Object> zero=new HashMap<>();
            zero.put("count",0);
            zero.put("signedToday",false);
            return Result.success(zero);
        }

        //拿到到今天的打开结果,从后往前遍历 有多少个连续的1
        int count=0;
        boolean signedToday=false;
        while(num>0){
            //得到最低位的bit位数的值
            long result=num & 1;
            //已经找到第一个没有签到的天数 直接结束
            if(result==0)
                break;
            //最低位(今天)为1 说明今天已签到
            if(count==0)
                signedToday=true;
            //连续签到的次数++a,
            count++;
            // >>>无符号右移  >>有符号右移
            num >>>= 1;
        }
        Map<String,Object> result=new HashMap<>();
        result.put("count",count);
        result.put("signedToday",signedToday);
        return Result.success(result);
    }

    // 保存/更新用户资料(昵称/头像/介绍/城市/性别)，并同步刷新 redis 登录态
    @Override
    public Result updateUserInfo(UserInfoEditDTO dto, String token) {
        //获取当前登录的用户信息
        UserDTO userDTO= UserHolder.getUser();
        //避免userDTo为null 得到的userId也为空 空指针异常 传入Redis
        if (userDTO == null || userDTO.getId()== null) {
            return Result.fail("用户未登录,请先登录");
        }
        Long userId=userDTO.getId();

        // 1. 更新 tb_user：昵称/头像(有值才更新)
        boolean nickChanged=dto.getNickName()!=null && !dto.getNickName().isBlank();
        boolean iconChanged=dto.getIcon()!=null && !dto.getIcon().isBlank();
        if(nickChanged || iconChanged){
            update().eq("id",userId)
                    .set(nickChanged,"nick_name",dto.getNickName())
                    .set(iconChanged,"icon",dto.getIcon())
                    .update();
        }

        // 更新 tb_user_info：介绍/城市/性别/生日(不存在则创建)
        UserInfo userInfo=userInfoService.getById(userId);
        boolean needCreate=(userInfo==null);
        if(userInfo==null){
            userInfo=new UserInfo();
            userInfo.setUserId(userId);
            userInfo.setFans(0);
            userInfo.setFollowee(0);
            userInfo.setCredits(0);
            userInfo.setLevel(false);
        }
        if(dto.getIntroduce()!=null) userInfo.setIntroduce(dto.getIntroduce());
        if(dto.getCity()!=null) userInfo.setCity(dto.getCity());
        if(dto.getGender()!=null) userInfo.setGender(dto.getGender());
        if(dto.getBirthday()!=null) userInfo.setBirthday(dto.getBirthday());

        if(needCreate) userInfoService.save(userInfo);
        else userInfoService.updateById(userInfo);

        // 3. 同步刷新 redis 登录态中的昵称/头像，/user/me 立即返回最新值
        if(StrUtil.isNotBlank(token)){
            String key=LOGIN_USER_TOKEN_KEY+token;
            if(nickChanged) stringRedisTemplate.opsForHash().put(key,"nickName",dto.getNickName());
            if(iconChanged) stringRedisTemplate.opsForHash().put(key,"icon",dto.getIcon());
        }
        return Result.success();
    }

}

/*  使用session的验证码发送 登录  登录校验
//发送验证码
@Override
public Result sendCodeBySession(String phone, HttpSession session) {
    //检验发送来的手机号是否正确 正则表达式 Utils下
    boolean invalid=RegexUtils.isPhoneInvalid(phone);

    //如果手机号错误,返回错误信息
    if(invalid){
        return Result.fail("手机号格式错误");
    }
    //如果符合,生成验证码
    String code=RandomUtil.randomNumbers(6);

    //将验证码保存起来,保存到session,要和用户填写的验证码匹配
    session.setAttribute("code",code);

         将手机号码 也保存起来, 登陆时候需要保证登录的手机号和发验证码的手机号是同一个
        检验手机号是否一致:发短信用手机1,登录改为手机2+同一验证码 要排除这种情况

    session.setAttribute("phone",phone);

    //发送验证码 需要aliyun等平台 有点复杂 直接跳过
    log.debug("发送短信验证码成功,验证码:{}",code);

    //返回ok Result 数据格式
    return Result.success();
}

验证码登录/注册 登录后需要进行登录检验(utils中 拦截器去做
只要有user不为空 那么就检验成功 放行:在threadLocal分配一个线程
即可)

@Override
public Result loginBySession(LoginFormDTO loginForm, HttpSession session) {
    //检验手机号是否一致:发短信用手机1,登录改为手机2+同一验证码 要排除这种情况
    String loginPhone=loginForm.getPhone();

        //优化2 使用手机号+密码登录 成功直接退出
        String loginPassword=loginForm.getPassword();
        if(loginPassword!=null){
            User user=userMapper.selectUserByPhoneAndPassword(loginPhone,loginPassword);
            if(user!=null){
                //把用户保存到session中  保存DTO:只返回基本信息,不返回敏感信息
                session.setAttribute("user",
                        BeanUtil.copyProperties(user, UserDTO.class));

                return Result.success("使用密码登录成功");
            }
        }

    //使用验证码登录
    String sessionPhone= session.getAttribute("phone").toString();

    //不能直接==(地址) 必须要调equals比较内容  调用equals的要非空
    if(loginPhone==null || !loginPhone.equals(sessionPhone))
        return Result.fail
                ("登录的手机号与发送验证码的手机号不一致,登录失败");

    //比较用户发送的验证码是否和session中的一致
    String sendCode =loginForm.getCode();
    String sessionCode= session.getAttribute("code").toString();

    //不一致返回错误信息 调equals要非空
    if(sessionCode==null ||!sessionCode.equals(sendCode))
        return Result.fail("验证码错误");

    //验证码匹配成功 根据手机号查询用户
    String phone=loginForm.getPhone();

    //手机号注册过,查询到了用户
    //User user=userMapper.selectUserByPhone(phone);
    //mybatis-plus简化
    User user=query().eq("phone",phone).one();

    //没有查询到用户,注册一个用户,下面用的同一个user,要复用
    if(user==null){
            nickName 非空,注册的时候要自动分配,很多软件的初始化昵称的前缀
            都是相同的 抽象出来定义到utils的SystemConstants里user_,再
            使用hutTool 里面的工具 自动生成一些符号

        //生成user 时候 可以在iml 内写个函数
        user=new User();
        user.setPhone(phone);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());

        //随机生成昵称
        String nickName=USER_NICK_NAME_PREFIX+
                RandomUtil.randomString(10);
        user.setNickName(nickName);

        //也可以直接传递 user 保存数据到数据库
        userMapper.insertByPhone(phone,nickName,LocalDateTime.now());
        //save(user);//向tb_user表插入数据 mybatis-plus
    }

    //把用户保存到session中  保存DTO:只返回基本信息,不返回敏感信息
    session.setAttribute("user",
            BeanUtil.copyProperties(user, UserDTO.class));

        返回成功的信息 不需要返回登录的凭证jwt session是根据cookie的
        每一次session 有一个唯一的id id自动写入cookie(id作为登录凭证)
        每次会话都会带着id(登录凭证)找到session 进而找到user

    return Result.success("使用验证码登录成功");
}*/
