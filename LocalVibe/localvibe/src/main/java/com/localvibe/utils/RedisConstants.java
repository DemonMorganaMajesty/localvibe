package com.localvibe.utils;

public  class RedisConstants {
    public static final String LOGIN_VERIFICATION_CODE_KEY = "login:verificationCode:";
    public static final Long LOGIN_VERIFICATION_CODE_TTL = 10L;  // 改造：验证码有效期 10 分钟（原 2 分钟）
    public static final String LOGIN_USER_TOKEN_KEY = "login:userToken:";
    public static final Long LOGIN_USER_TOKEN_TTL = 30L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_ID_KEY = "cache:shop:id";

    public static final String CATHE_SHOPTYPE_STRING_KEY="cache:shopType:string";
    public static final String CATHE_SHOPTYPE_LIST_KEY="cache:shopType:list";
    public static final String CATHE_SHOPTYPE_HASH_KEY="cache:shopType:hash";
    public static final Long CACHE_SHOPTYPE_TTL = 1L;

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    //是否点过赞的key的前缀
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    //用户点赞过的笔记集合 反向索引 key:用户id value:blogId(sortedSet,score=点赞时间)
    public static final String BLOG_LIKED_USER_KEY = "blog:liked:user:";
    //评论点赞集合 key:评论id value:点赞用户id(sortedSet)
    public static final String BLOG_COMMENT_LIKED_KEY = "blog-comment:liked:";

    //用户关注的人 用户关注者的前缀
    public static final String USER_FOLLOWEES_KEY="user:followees:";
    //给用户粉丝推送笔记的键
    public static final String USER_FOLLOWERS_FEED_BLOG_KEY="user:followers:feedBlog:";

    public static final String FEED_KEY = "feed:";
    public static final String GEOGRAPHY_SHOPTYPE_KEY = "geography:shopType:";

    //签到
    public static final String USER_SIGNUP_KEY = "signUp:";
}
