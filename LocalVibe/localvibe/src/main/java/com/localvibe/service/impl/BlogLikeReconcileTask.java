package com.localvibe.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.localvibe.entity.Blog;
import com.localvibe.mapper.BlogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import static com.localvibe.utils.RedisConstants.BLOG_LIKED_KEY;


//@Component：把这个类交给 Spring 容器管理，
// Spring 才会识别里面的@Scheduled定时任务。
@Slf4j
@Component
public class BlogLikeReconcileTask {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private BlogMapper blogMapper;
    /* initialDelay = 60_000：项目启动后，等待 60000 毫秒 (60s)
    才执行第一次任务。避免刚启动，大量任务抢占资源。
   fixedDelay = 60_000：上一次任务全部执行完毕之后，再间隔 60 秒执行下一次。
   区别于 fixedRate：fixedRate 是按固定时刻，不管上一轮有没有跑完，时间到就跑，会造成任务并发。
   fixedDelay 不会并发，更安全，这里选 fixedDelay 很合理。

   ✅优化：引入 Redisson 分布式锁，任务执行前抢占锁，拿到锁才执行，避免多实例重复执行。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    /*定时 任务,使得 redis中的Zset 的点赞的数据和mysql内一致 兜底最终一致性：
   可能故障场景：MySQL更新成功,Redis网络异常/宕机操作失败→DB点赞数和Redis真实点赞人数不一致
   Redis ZSet 是权威数据源，定时拿 Redis 真实点赞数，校正 MySQL 的liked字段。
   先删 mysql(IO 慢) 在改redis(快)
     */
    public void reconcileBlogLikeCounts() {
        //修正了 多少条帖子的 点赞的数据
        int updated = 0;
        /*匹配所有以blog:liked:开头的 key，也就是所有博客点赞的 ZSet。
        生成不能使用 Redis 单线程，数据量大直接阻塞整个 Redis。
         */
        try (Cursor<String> cursor = stringRedisTemplate.scan(
                ScanOptions.scanOptions().match(BLOG_LIKED_KEY + "*")
                        .count(200).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String idStr = key.substring(BLOG_LIKED_KEY.length());
                Long id;
                try {
                    id = Long.valueOf(idStr);
                } catch (NumberFormatException e) {
                    continue;
                }
                //获取ZSet里面成员的总数量，这是 Redis 里面真实点赞用户数（权威真值）。
                Long zcard = stringRedisTemplate.opsForZSet().zCard(key);
                int real = zcard == null ? 0 : zcard.intValue();

                //blogId查询数据库博客；如果博客已经被删除，数据库查不到，直接跳过，不需要校正。
                Blog blog = blogMapper.selectById(id);
                if (blog == null) {
                    continue;
                }

                //数据库 中该 帖子的点赞数
                int db = blog.getLiked() == null ? 0 : blog.getLiked();
                if (db != real) {
                    //取出数据库存的点赞数，做 null 兼容，数据库字段为 null 就当做 0。
                    blogMapper.update(null, new UpdateWrapper<Blog>().eq("id", id).setSql("liked=" + real));
                    updated++;
                }
            }
        } catch (Exception e) {
            log.warn("博客点赞定时校正任务异常:{}", e.getMessage());
        }
        if (updated > 0) {
            log.info("点赞校正完成，共校正 {} 条博客", updated);
        }
    }
}
