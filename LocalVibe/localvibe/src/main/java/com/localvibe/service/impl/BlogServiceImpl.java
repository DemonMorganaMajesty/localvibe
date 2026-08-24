package com.localvibe.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localvibe.dto.Result;
import com.localvibe.dto.ScrollResult;
import com.localvibe.dto.UserDTO;
import com.localvibe.entity.Blog;
import com.localvibe.entity.Follow;
import com.localvibe.entity.BlogComments;
import com.localvibe.entity.User;
import com.localvibe.mapper.BlogMapper;
import com.localvibe.mapper.BlogCommentsMapper;
import com.localvibe.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.localvibe.service.IFollowService;
import com.localvibe.service.IUserService;
import com.localvibe.utils.SystemConstants;
import com.localvibe.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.localvibe.utils.RedisConstants.*;


//redis中点赞人的保存 value hashSet->sortedSet 实现单个帖子按点赞时间排序
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    //注入接口,spring 会自动生成实现类,不能注入实现类
    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private BlogCommentsMapper blogCommentsMapper;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    //保存发布的笔记,要保存到redis(向粉丝推送feed笔记)和数据库
    @Override
    public Result saveBlogAndFeedToFollowers(Blog blog) {
        // 获取登录用户
        UserDTO userDTO = UserHolder.getUser();
        //避免userDTo为null 得到的userId也为空 空指针异常 传入Redis
        if (userDTO == null || userDTO.getId()== null) {
            // 未登录直接返回，不执行Redis操作
            return Result.fail("用户未登录,请先登录");
        }
        Long userId=userDTO.getId();

        //笔记 保存发送者的信息
        blog.setUserId(userId);
        // 保存探店博文 简单sql保存save直接 plus生成
        boolean isSuccess=save(blog);
        if(!isSuccess)
            return Result.fail("笔记发布失败");

        /*查询笔记作者(登录者)的所有的粉丝 follow表中有
        user_id:主动去关注别人的人,user的关注者是follow_usr_id
        follow_user_id:其他user关注的人,follow_user_id的粉丝是user
        查询数据:根据user_id找关注的人,根据follow_user_id找粉丝
        select * from tb_follow where follow_user_id=?()
         */
        List<Follow> followers=followService.query().
                eq("follow_user_id",userId).list();
        //实现笔记的推送 遍历每一个
        for(Follow follower:followers){
            //获取粉丝的id(user_id)
            Long followerId=follower.getUserId();
            /*推送,把笔记推送到收件箱(redis) key 粉丝Id value
            sortedSet score存的是推送的时间
             */
            String key= USER_FOLLOWERS_FEED_BLOG_KEY+followerId;
            //key  value score
            stringRedisTemplate.opsForZSet().
                    add(key,blog.getId().toString(),
                            System.currentTimeMillis());
        }
        return Result.success(blog.getId());
    }


    // 更新自己的笔记，仅作者可操作；成功后清理帖子详情缓存
    @Override
    public Result updateBlog(Blog blog) {
        //获取当前登录的用户信息
        UserDTO userDTO= UserHolder.getUser();
        if (userDTO == null || userDTO.getId()== null) {
            return Result.fail("用户未登录,请先登录");
        }
        Long userId=userDTO.getId();
        //笔记id不能为空
        if(blog.getId()==null){
            return Result.fail("笔记id不能为空");
        }
        //查询原笔记 校验归属
        Blog old=getById(blog.getId());
        if(old==null){
            return Result.fail("笔记不存在或已被删除");
        }
        if(!old.getUserId().equals(userId)){
            return Result.fail("只能修改自己发布的笔记");
        }
        //只允许更新标题/正文/图片/关联店铺
        update().eq("id",blog.getId())
                .set("shop_id",blog.getShopId())
                .set("title",blog.getTitle())
                .set("content",blog.getContent())
                .set("images",blog.getImages())
                .set("update_time",LocalDateTime.now())
                .update();
        //清理帖子详情三级缓存(redis 键由 lua 读取，删除后回源重建；nginx 本地缓存短TTL自动过期)
        stringRedisTemplate.delete("localvibe:blog:id:"+blog.getId());
        return Result.success();
    }

    //删除自己的笔记，仅作者可操作；同步清理点赞集合/粉丝收件箱/详情缓存
    @Override
    public Result deleteBlog(Long blogId) {
        //获取当前登录的用户信息
        UserDTO userDTO= UserHolder.getUser();
        if (userDTO == null || userDTO.getId()== null) {
            return Result.fail("用户未登录,请先登录");
        }
        Long userId=userDTO.getId();
        //查询原笔记 校验归属
        Blog old=getById(blogId);
        if(old==null){
            return Result.fail("笔记不存在或已被删除");
        }
        if(!old.getUserId().equals(userId)){
            return Result.fail("只能删除自己发布的笔记");
        }

        //删除笔记
        boolean isSuccess=removeById(blogId);
        if(!isSuccess){
            return Result.fail("笔记删除失败");
        }

        //清理该帖子的评论记录(数据库)
        blogCommentsMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BlogComments>()
                .eq("blog_id", blogId));
        //清理点赞集合(redis)
        stringRedisTemplate.delete(BLOG_LIKED_KEY+blogId);

        //清理所有用户"我的点赞"中 有该笔记 的记录
        Set<String> userLikeKeys = stringRedisTemplate.keys(BLOG_LIKED_USER_KEY + "*");
        if(userLikeKeys != null){
            for(String userLikeKey : userLikeKeys){
                stringRedisTemplate.opsForZSet().remove(userLikeKey, blogId.toString());
            }
        }

        //清理帖子详情三级缓存(redis 键由 lua 读取，删除后回源重建)
        stringRedisTemplate.delete("localvibe:blog:id:"+blogId);

        //从所有粉丝的收件箱中移除该笔记(推送的feed流)
        List<Follow> followers=followService.query().
                eq("follow_user_id",userId).list();
        for(Follow follower:followers){
            String key=USER_FOLLOWERS_FEED_BLOG_KEY+follower.getUserId();
            stringRedisTemplate.opsForZSet().remove(key,blogId.toString());
        }
        return Result.success();
    }

    //点赞/取消点赞
    @Override
    public Result likeBlog(Long blogId) {
        //获取当前登录的用户信息
        UserDTO userDTO=UserHolder.getUser();
        //避免userDTo为null 得到的userId也为空 空指针异常 传入Redis
        if (userDTO == null || userDTO.getId()== null) {
            // 未登录直接返回，不执行Redis操作
            return Result.fail("用户未登录,请先登录");
        }
        Long userId= UserHolder.getUser().getId();

        //笔记存入redis的key
        String key=BLOG_LIKED_KEY+blogId;

     /*   //判断当前是否已经点过赞了
        Boolean isLike=stringRedisTemplate.opsForZSet().
                isMember(key,userId.toString());*/
        //sortedSet 没有isMember 只能通过score是否存在判断
        Double score = stringRedisTemplate.opsForZSet()
                .score(key, userId.toString());

        //没点过赞
        if(score==null){
        //if(BooleanUtil.isFalse(isLike)){
            //没有点赞 就可以点赞 数据库的赞总数要+1
            Boolean isSuccess=update().setSql("liked=liked+1")
                    .eq("id",blogId).update();
            //点赞成功 更新redis 数据 使用sortedSet
            if(BooleanUtil.isTrue(isSuccess)){
                stringRedisTemplate.opsForZSet().
                add(key,userId.toString(),System.currentTimeMillis());
                //同步"我的点赞"反向索引
                stringRedisTemplate.opsForZSet().
                        add(BLOG_LIKED_USER_KEY + userId, blogId.toString(), System.currentTimeMillis());
            }
        }else{ //已经点赞 再点就是取消点赞
            //已经点赞 再点就是取消点赞 数据库的赞总数要-1
            Boolean isSuccess=update().
                    setSql("liked =liked - 1")
                    .gt("liked", 0)
                    .eq("id",blogId).update();
            //取消点赞成功
            if(BooleanUtil.isTrue(isSuccess)){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
                //同步移除"我的点赞"反向索引
                stringRedisTemplate.opsForZSet().remove(BLOG_LIKED_USER_KEY + userId, blogId.toString());
            }
        }

        //点赞数变化，清理帖子详情缓存(openresty lua 读取，删除后回源重建)
        stringRedisTemplate.delete("localvibe:blog:id:" + blogId);
        return Result.success();
    }

    //按照热度的分页查询 分页查询是比较每一个贴子的点赞总数
    @Override
    public Result queryHotBlogPage(Integer pageNumber) {
        // 根据用户查询 按照点赞降序排序
        Page<Blog> page =query()
                .orderByDesc("liked")
                .page(new Page<>(pageNumber, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();

        /* blog 数据库里没有包含对应用户的昵称和头像的字段,但是实体类中有
        减少数据库内存,只需要返回给前端的时候出现把这两个变量复制就好
        ,循环查询并且返回昵称和头像 保存进blog类 因为昵称和头像是可以更修改的 用户id不可以改,要是一个人发过多条
        blog 并且频繁改头像和昵称 那么就会导致数据不一致,查询出的数据都需要判断是否修改,不如
        只在查询的时候添加/重置这些信息 不管是否修改  以id为桥梁
         */
       /* records.forEach(blog ->{
            Long userId = blog.getUserId();
            //plus 调用的是其他实体的sql查询服务
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            completeLikeOnWebByBlog(blog);
        });
      records.forEach(this::completeBlogUserInfoOnWebByUser);
        */
        //补充给前端用户名和头像 该登录的用户是否给该帖子点过赞
        completeBlogUsersOnWeb(records);
        records.forEach(this::completeLikeOnWebByBlog);
        return Result.success(records);
    }

    @Override
    public Result queryBlogById(Long blogId) {
        //查询blog
        Blog blog=getById(blogId);
        //不存在
        if(blog==null){
            return Result.fail("帖子不存在,查询失败");
        }
        /*帖子存在 就需要查询用户得到用户的昵称和头像 存入实体类的成员变量
        一起返回给前端
         */
        completeBlogUserInfoOnWebByUser(blog);

        /*判断当前的用户对这个帖子有没有点过赞 封装在blog类中返回给前端
        前端颜色不同 来向用户表示有没有点过赞
        给每个帖子 加上帖子的发帖人和发帖人头像
         */
        completeLikeOnWebByBlog(blog);

        return Result.success(blog);
    }

    // 得到blog(有userId)对应用户的昵称和头像 存入实体类的成员变量一起返回给前端
    private void completeBlogUserInfoOnWebByUser(Blog blog) {
        Long userId = blog.getUserId();
        //plus 调用的是其他实体的sql查询服务
        User user = userService.getById(userId);
        //补充用户名和头像
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    //批量补充多个帖子的作者昵称/头像（一次查询，避免逐条查询的 N+1）
    private void completeBlogUsersOnWeb(List<Blog> blogs) {
        //帖子数 为空
        if (blogs == null || blogs.isEmpty()) {
            return;
        }
        /* Set<Long> userIds = blogs.stream().map(Blog::getUserId)
            .filter(Objects::nonNull).collect(Collectors.toSet());
         */
        //找出 ids 集合
        Set<Long> userIds=new HashSet<>();
        for(Blog blog:blogs){
            Long userId=blog.getUserId();
            if(userId!=null)
                userIds.add(userId);
        }
        if (userIds.isEmpty()) {
            return;
        }
        /* Map<Long, User> userMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
         */
        List<User> userList=userService.listByIds(userIds);
        //id->User 的映射map
        Map<Long, User> userMap=new HashMap<>();
        for(User user:userList){
            Long id=user.getId();
            if(id!=null)
                userMap.put(id,user);
        }

        //给每个 帖子 加上 发帖人的姓名的头像
        for (Blog blog : blogs) {
            User user = userMap.get(blog.getUserId());
            if (user != null) {
                blog.setName(user.getNickName());
                blog.setIcon(user.getIcon());
            }
        }
    }

    //得到blog 的isLike的值(true/false),返回给前端
    public void completeLikeOnWebByBlog(Blog blog) {
        //获取当前登录的用户信息
        UserDTO userDTO=UserHolder.getUser();
          /*未登录游客直接标记未点赞，不再执行Redis查询,未登录时候拿到的
        是null 未登录进入页面会自动的查询 会直接报错空指针
         */
        if(userDTO==null){
            blog.setIsLike(false);
            return;
        }

        Long userId= UserHolder.getUser().getId();
        //笔记存入redis的key
        String key=BLOG_LIKED_KEY+ blog.getId();

        //sortedSet score!=null 说明已经点赞过了 true
        Double score = stringRedisTemplate.
                opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);


        /*  //hashSet判断当前是否已经点过赞了 但这里用的是TreeSet
        Boolean isLike=stringRedisTemplate.opsForSet().
                isMember(key,userId.toString());
        //修改blog类的成员变量isLike
        blog.setIsLike(BooleanUtil.isTrue(isLike));*/
    }

    //按照时间的先后顺序查询所有的点赞的列表(人) 最先点赞的前5个人
    @Override
    public Result queryLikeListByTime(Long blogId) {
        //笔记存入redis的key
        String key=BLOG_LIKED_KEY+ blogId;
        //查询前5个用户 zrange 0 4  查到的是 key 的value sk(键) sv(时间score)
        Set<String> userIdSetByTimeString =
                stringRedisTemplate.opsForZSet().range(key, 0, 4);

        //判断集合是否为空 为空直接返回空集合
        if(userIdSetByTimeString ==null || userIdSetByTimeString.isEmpty())
            return Result.success(Collections.emptyList());

        //解析用户id 得到用户列表 for一个个转也可以  map:映射
       /* Set<Long> userIdSetByTimeLong = userIdSetByTimeString.stream().
                map(Long::valueOf).collect(Collectors.toSet());*/
        // 创建一个用于存放 Long 类型 ID 的 Set 集合
        Set<Long> userIdSetByTimeLong = new HashSet<>();

        for (String idStr : userIdSetByTimeString) {
            // 防御性判断：防止字符串为 null 或空串导致 NumberFormatException
            if (idStr != null && !idStr.trim().isEmpty()) {
                userIdSetByTimeLong.add(Long.valueOf(idStr));
            }
        }

        /*根据用户的id 查询用户对象,对象的数据都是保存在数据库的,用sql
        保护用户的隐式 隐藏私密信息 user->userDTO stream前的得到的是users
        注意plus这里给的listByIds  listByIds(userIdSetByTimeLong)
        是使用 in(id值) 进行遍历的,in不会排序,这就会导致数据库和前端里面
        的数据是无序的(redis是有序的),所以不能用单独使用in 后面再排序
        order by field(id,id1,...)
         */
        // 把idList 用,拼接起来     idStr : id1,id2...
        String idStr =StrUtil.join(",",userIdSetByTimeLong);

        //plus 不能根据field的id排序 last(手动写sql语句 实现排序)
        List<UserDTO> userDTOSetByTime=userService.
                query().in("id",userIdSetByTimeLong).
                last("ORDER BY FIELD(id,"+ idStr +")")
                .list().stream().map(user->
                    BeanUtil.copyProperties(user,UserDTO.class) )
                .collect(Collectors.toList());
        return Result.success(userDTOSetByTime);

    }

    //滚动查询 来自关注者发布的笔记推送 关注者的笔记推送是先发送到收件箱redis
    @Override
    public Result scrollQueryBlogFromFollowee(Long max, Integer offset) {
        //获取当前登录的用户信息
        UserDTO userDTO=UserHolder.getUser();
        //避免userDTo为null 得到的userId也为空 空指针异常 传入Redis
        if (userDTO == null || userDTO.getId()== null) {
            // 未登录直接返回，不执行Redis操作
            return Result.fail("用户未登录,请先登录");
        }
        Long userId= UserHolder.getUser().getId();
        /*获取该用户的收件箱redis中接受关注者笔记的推送消息 和第一个函数
        saveBlogAndFeedToFollowers呼应(把发布的帖子都存入粉丝的收件箱),
         */
        String key= USER_FOLLOWERS_FEED_BLOG_KEY+userId;
        /*获取redis 中收到的推送的帖子数 查询
        zReverseRangeByScore key maxSco minSco limit offset count n
        TypedTuple<String>: 元组存的是键值对 k,v(blogId,time)拼成的String
        元组可能有多个,组成set集合 count 可以指定一次查多少个
        不需要循环,这页查询到2个后,前端下拉直接,会出现下一页的信息,往上拉会看见新发的

        max：上一页最后一条数据的 score 值（时间戳）；
        offset：偏移量，跳过多少个
         */
        Set<ZSetOperations.TypedTuple<String>> typedTuples =
         stringRedisTemplate.opsForZSet().
         reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //判空
        if(typedTuples==null || typedTuples.isEmpty()){
            return Result.fail("关注列表没有收到新笔记,查询失败");
        }
        //解析数据,从二元组里 解析出 blogId minTime(时间戳) 确定offset
        List<Long> blogIds =new ArrayList<>(typedTuples.size());

        //找到最小的时间(score) 因为SortedSet已经排好序了(逆序) 就是最后一个
        Long minTime=0L;
        //求offset 最小值是1(minTime的个数)
        offset=1;
        for(ZSetOperations.TypedTuple<String> tuple:typedTuples){
            //获取id value
            String idStr=tuple.getValue();
            blogIds.add(Long.valueOf(idStr));
            //获取时间戳 score
            Long time=tuple.getScore().longValue();;
            //求offset  就是求minTime的个数
            if(time==minTime){
                offset++;
                continue;
            }
            else offset=1;
            minTime=time;
        }
        /*根据blogId 查询出blog 直接这样查询有问题,因为plus 进行多个查询
          时候是根据in() 查询的,无序的,但是这里需要的是有序的  需要手动写sql
          List<Blog> blogs=listByIds(blogIds);
         */
        // ids集合 转化为 字符串的形式
        String idStr=StrUtil.join(",",blogIds);
        //手写sql 查询该次滚动查询的所有的blog数据
        List<Blog> blogs= query().in("id",blogIds).
                last("ORDER BY FIELD(id,"+ idStr +")").list();

        //查询blog时候 还要展示 写该blog的用户,该帖子被点过的赞的总数
        //改造：批量补充作者昵称/头像（一次查询，避免逐条 N+1）
        completeBlogUsersOnWeb(blogs);
        //补充该 blog 是否被点赞
        blogs.forEach(this::completeLikeOnWebByBlog);

        //封装结果返回
        ScrollResult scrollResult=new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(offset);
        scrollResult.setMinTime(minTime);
        return Result.success(scrollResult);
    }

    // 我的点赞列表：我点赞过的帖子（Redis 反向索引，首次查询自动全量同步历史点赞）
    @Override
    public Result queryMyLikedBlogs() {
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null || userDTO.getId() == null) {
            return Result.fail("用户未登录,请先登录");
        }
        Long userId = userDTO.getId();
        String userKey = BLOG_LIKED_USER_KEY + userId;

        //反向索引不存在时，全量扫描所有帖子的点赞集合，构建该用户的点赞记录(兼容历史数据)
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(userKey))) {
            Set<String> keys = stringRedisTemplate.keys(BLOG_LIKED_KEY + "*");
            if (keys != null) {
                for (String key : keys) {
                    Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
                    if (score != null) {
                        stringRedisTemplate.opsForZSet().add(userKey,
                                key.substring(BLOG_LIKED_KEY.length()), score);
                    }
                }
            }
        }

        Set<String> blogIdStrs = stringRedisTemplate.opsForZSet().reverseRange(userKey, 0, -1);
        if (blogIdStrs == null || blogIdStrs.isEmpty()) {
            return Result.success(Collections.emptyList());
        }

        List<Long> blogIds = blogIdStrs.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", blogIds);
        List<Blog> blogs = query().in("id", blogIds)
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        List<Blog> result = new ArrayList<>();
        if (blogs != null) {
            completeBlogUsersOnWeb(blogs);
            blogs.forEach(this::completeLikeOnWebByBlog);
            result.addAll(blogs);
        }
        return Result.success(result);
    }
}
