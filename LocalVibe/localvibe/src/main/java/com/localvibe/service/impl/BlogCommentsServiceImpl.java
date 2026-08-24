package com.localvibe.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.localvibe.dto.Result;
import com.localvibe.dto.UserDTO;
import com.localvibe.entity.Blog;
import com.localvibe.entity.BlogComments;
import com.localvibe.entity.User;
import com.localvibe.mapper.BlogCommentsMapper;
import com.localvibe.mapper.BlogMapper;
import com.localvibe.service.IBlogCommentsService;
import com.localvibe.service.IUserService;
import com.localvibe.utils.SystemConstants;
import com.localvibe.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;
import static com.localvibe.utils.RedisConstants.BLOG_COMMENT_LIKED_KEY;


@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 发表评论：校验登录/帖子/内容，插入评论并让帖子评论数+1，返回最新评论数
    @Transactional //多处数据库的 删 改
    @Override
    public Result addComment(Long blogId, String content) {
        //从线程中 拿到当前用户
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null || userDTO.getId() == null) {
            return Result.fail("用户未登录,请先登录");
        }
        if (blogId == null) {
            return Result.fail("缺少帖子id");
        }

        Blog blog = blogMapper.selectById(blogId);
        if (blog == null) {
            return Result.fail("帖子不存在或已被删除");
        }
        if (content == null || content.trim().isEmpty()) {
            return Result.fail("评论内容不能为空");
        }
        BlogComments comment = new BlogComments();
        comment.setUserId(userDTO.getId());
        comment.setBlogId(blogId);
        comment.setParentId(0L);
        comment.setAnswerId(0L);
        comment.setContent(content.trim());
        comment.setLiked(0);
        comment.setStatus(true);
        save(comment);

        // 帖子评论数+1（comments 可能为 NULL，用 IFNULL 兜底）
        blogMapper.update(null, new UpdateWrapper<Blog>().eq("id", blogId).setSql("comments = IFNULL(comments, 0) + 1"));

        Blog freshBlog = blogMapper.selectById(blogId);

        //评论数变化，清理帖子详情缓存(openresty lua 读取，删除后回源重建)
        stringRedisTemplate.delete("localvibe:blog:id:" + blogId);
        return Result.success(freshBlog != null && freshBlog.getComments() != null ? freshBlog.getComments() : 1);
    }

    // 分页查询某帖子的评论列表  最新/最热 每页默认5条（补充用户昵称头像），游客可看
    @Override
    public Result queryCommentsOfBlog(Long blogId, Integer pageNumber, Integer pageSize, String sortMode) {
        if (blogId == null) {
            return Result.fail("帖子id错误");
        }
        int size = (pageSize == null || pageSize <= 0) ? SystemConstants.DEFAULT_PAGE_SIZE : Math.min(pageSize, 20);

        // sortMode:lastest=按时间倒序排序默认, hot=点赞最多的降序排序
        Page<BlogComments> page = query().eq("blog_id", blogId)
                .orderByDesc("hot".equalsIgnoreCase(sortMode) ? "liked" : "create_time")
                .orderByDesc("create_time")
                .page(new Page<>(pageNumber == null ? 1 : pageNumber, size));

        //无评论
        List<BlogComments> records = page.getRecords();
        if (records == null || records.isEmpty()) {
            return Result.success(Collections.emptyList(), page.getTotal());
        }

        // 去除敏感信息
        UserDTO userDTO = UserHolder.getUser();
        // 批量补充评论用户昵称/头像（一次查询，避免逐条查询的 N+1）
        fillCommentUsers(records);

        //该登录用户对 该页的5条评论是否点过赞
        for (BlogComments c : records) {
            c.setIsLike(isCommentLiked(c.getId(), userDTO));
        }
        return Result.success(records, page.getTotal());
    }

    //判断当前的 登录的用户对该评论是否点过赞
    private boolean isCommentLiked(Long commentId, UserDTO userDTO) {
        //未登录的游客直接  false   默认没有点过赞。
        if (userDTO == null || userDTO.getId() == null || commentId == null) {
            return false;
        }
        //查RedisZSet,判断当前登录用户有没有点赞这条评论，给实体isLike字段赋值。
        Double score = stringRedisTemplate.opsForZSet()
                .score(BLOG_COMMENT_LIKED_KEY + commentId, userDTO.getId().toString());
        return score != null;
    }

    //给评论点赞
    @Override
    public Result likeComment(Long commentId) {
        UserDTO userDTO = UserHolder.getUser();
        //未登录 不能点赞
        if (userDTO == null || userDTO.getId() == null) {
            return Result.fail("未登录不能点赞,请先登录");
        }
        //找不到评论 评论被删除
        if (commentId == null || getById(commentId) == null) {
            return Result.fail("找不到评论 评论被删除");
        }
        Long userId = userDTO.getId();
        String key = BLOG_COMMENT_LIKED_KEY + commentId;
        /*member:点赞的用户id  score:保存的是 点赞的时间戳  按时间来排序
        该登录的用户 没有点赞 返回的就是null
         */
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // ========== 分支A：没有点赞 → 那么就是可以点赞 直接点赞 ==========
            // DB：评论点赞数 +1，IFNULL防止null，gt不做限制，允许增加
            update().setSql("liked = IFNULL(liked, 0) + 1").eq("id", commentId).update();
            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
        } else {
            // ========== 分支B：已经点赞 → 取消点赞 ==========
            // DB：点赞数-1；gt("liked",0) 条件：只有liked>0才执行更新，防止变成负数
            update().setSql("liked = IFNULL(liked, 0) - 1").gt("liked", 0).eq("id", commentId).update();
            stringRedisTemplate.opsForZSet().remove(key, userId.toString());
        }
        //获取zset里面成员总数，作为点赞数（以Redis为准）
        Long liked = stringRedisTemplate.opsForZSet().zCard(key);

        // 组装返回给前端：点赞数量、当前用户是否点赞
        Map<String, Object> response = new HashMap<>();
        //liked：点赞数    isLike:score为不为null 则返回false/true
        response.put("liked", liked == null ? 0 : liked.intValue());
        response.put("isLike", score == null);
        return Result.success(response);
    }

    // 删除自己的评论：校验登录/归属，删除评论并让帖子评论数-1，返回最新评论数
    @Override
    public Result deleteComment(Long id) {
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null || userDTO.getId() == null) {
            return Result.fail("用户未登录,请先登录");
        }
        if (id == null) {
            return Result.fail("缺少评论id");
        }
        BlogComments comment = getById(id);
        if (comment == null) {
            return Result.fail("评论不存在或已被删除");
        }
        if (!userDTO.getId().equals(comment.getUserId())) {
            return Result.fail("只能删除自己的评论");
        }

        removeById(id);
        // 帖子评论数-1（不小于0）
        blogMapper.update(null, new UpdateWrapper<Blog>().eq("id", comment.getBlogId())
                .gt("comments", 0).setSql("comments = IFNULL(comments, 0) - 1"));

        Blog freshBlog = blogMapper.selectById(comment.getBlogId());

        //评论数变化，清理帖子详情缓存(openresty lua 读取，删除后回源重建)
        stringRedisTemplate.delete("localvibe:blog:id:" + comment.getBlogId());
        return Result.success(freshBlog != null && freshBlog.getComments() != null ? freshBlog.getComments() : 0);
    }

    // 我的评论：我评论过的帖子列表（帖子标题/首图 + 我的评论内容）
    @Override
    public Result queryMyComments() {
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null || userDTO.getId() == null) {
            return Result.fail("用户未登录,请先登录");
        }
        List<BlogComments> comments = query().eq("user_id", userDTO.getId())
                .orderByDesc("create_time")
                .list();
        //为空
        if (comments == null || comments.isEmpty()) {
            return Result.success(Collections.emptyList());
        }

        // 批量补充对应的帖子标题/首图 + 用户昵称/头像（避免 N+1）
        fillCommentBlogs(comments);
        fillCommentUsers(comments);
        return Result.success(comments);
    }

    // 批量补充评论用户昵称/头像（一次查询）
    private void fillCommentUsers(List<BlogComments> comments) {
        // 1.拿到所有评论里面的userId，收集到Set，自动去重
       /* Set<Long> userIds = comments.stream().map(BlogComments::getUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());*/
        // 1.提取所有userId，过滤null，放入Set去重
        Set<Long> userIds = new HashSet<>();
        for (BlogComments comment : comments) {
            Long userId = comment.getUserId();
            if (userId != null) // 等价 Objects::nonNull
                userIds.add(userId);
        }

        if (userIds.isEmpty()) {
            return;
        }
      /*  Map<Long, User> userMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));*/

        // 批量查询用户
        List<User> userList = userService.listByIds(userIds);
        // 把List<User>转为Map<Long,User>，手动处理key重复
        Map<Long, User> userMap = new HashMap<>();
        for (User user : userList) {
            Long uid = user.getId();
            // 如果key不存在才put；等价 (a,b)->a 保留第一个，后面重复直接丢弃
            if (!userMap.containsKey(uid)) {
                userMap.put(uid, user);
            }
        }

        // 循环每一条评论，从map拿用户信息，填充icon、nickName
        for (BlogComments comment : comments) {
            /* 每条都查库 效率很低  N+1问题
            假设 10 条评论：1 次查评论 SQL + 10 次查用户 SQL → 11 次 SQL（N+1），数据库压力大
            User user = userService.getById(comment.getUserId());
             */
            User user = userMap.get(comment.getUserId());
            // user!=null 必然成立, 防止user 手动被删/。。
            if (user != null) {
                //拿到 发评论的人的 昵称和头像
                comment.setNickName(user.getNickName());
                comment.setIcon(user.getIcon());
            }
        }
    }

    // 批量补充评论对应的帖子标题/首图（一次查询）
    private void fillCommentBlogs(List<BlogComments> comments) {
        // 手动收集blogId，过滤null，HashSet自动去重，不使用Stream
        Set<Long> blogIds = new HashSet<>();
        for (BlogComments comment : comments) {
            Long blogId = comment.getBlogId();
            if (blogId != null) {
                blogIds.add(blogId);
            }
        }

        if (blogIds.isEmpty()) {
            return;
        }

        List<Blog> blogList = blogMapper.selectBatchIds(blogIds);
        // List转Map，手动处理key重复，等价 (a,b)->a，保留第一个
        Map<Long, Blog> blogMap = new HashMap<>();
        for (Blog blog : blogList) {
            Long bid = blog.getId();
            if (!blogMap.containsKey(bid)) {
                blogMap.put(bid, blog);
            }
        }

        for (BlogComments comment : comments) {
            Blog blog = blogMap.get(comment.getBlogId());
            //拿到 该帖子的 标题和封面图
            if (blog != null) {
                comment.setBlogTitle(blog.getTitle());
                comment.setBlogImages(blog.getImages());
            }
        }
    }
}