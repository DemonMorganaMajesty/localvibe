package com.localvibe.service.impl;

import com.localvibe.entity.Blog;
import com.localvibe.service.IBlogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.Resource;
import java.util.List;


@Slf4j
@Component
public class CacheWarmupTask {

    @Resource
    private IBlogService blogService;

    //WARM_TOP_N = 50：预热点赞数最高前 50 篇帖子。
    private static final int WARM_TOP_N = 50;
    // OpenResty 地址，WSL内部署，Windows访问地址：127.0.0.1:8087
    private static final String OPENRESTY_BLOG_BASE = "http://127.0.0.1:8087/api/blog/";

    private final RestTemplate restTemplate = new RestTemplate();

    /* initialDelay = 120_000：项目启动等待 2 分钟 才第一次执行，给服务、Redis、OpenResty 足够时间启动完毕。
fixedDelay = 600_000：上一轮任务执行完成之后，间隔 10 分钟执行下一轮预热。
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void warmHotBlogs() {
        List<Blog> hot;
        try {
            hot = blogService.query().orderByDesc("liked").last("limit " + WARM_TOP_N).list();
        } catch (Exception e) {
            log.warn("缓存预热‑查询热门帖子异常: {}", e.getMessage());
            return;
        }
        if (hot == null || hot.isEmpty()) {
            return;
        }
        int ok = 0;
        for (Blog b : hot) {
            try {
                restTemplate.getForEntity(OPENRESTY_BLOG_BASE + b.getId(), String.class);
                ok++;
            } catch (Exception ignore) {
                // 请求失败直接忽略，不中断整体预热流程
            }
        }
        log.info("缓存预热完成: {}/{} 篇", ok, hot.size());
    }
}
