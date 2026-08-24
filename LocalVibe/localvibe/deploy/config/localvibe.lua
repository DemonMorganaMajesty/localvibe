-- =====================================================================
-- 城遇(localvibe) · OpenResty 多级缓存 Lua(店铺详情 + 店铺分类 + 帖子详情内容)
-- ---------------------------------------------------------------------
-- 链路：浏览器 -> Windows nginx(82) -> OpenResty(8087)
--       L1: ngx.shared.localvibe_cache        (进程内本地缓存)
--       L2: Redis db8                         (Java/canal 维护 或 lua 自管短TTL键)
--       L3: ngx.location.capture /path/... -> Tomcat
-- 路由：
--       /api/shop/{id}       -> 店铺详情缓存(键 cache:shop:id{id}，Java 逻辑过期格式)
--       /api/shop-type/list  -> 店铺分类缓存(键 cache:shopType:string)
--       /api/blog/{id}       -> 帖子详情内容缓存(键 localvibe:blog:id:{id}，lua 自管短TTL)
--                               isLike(当前用户是否点赞)实时计算，不缓存
-- 不缓存：用户/关注/签到/优惠券库存/秒杀订单等强一致或个性化数据
-- =====================================================================

-- 导入 common 函数库（包含 read_redis 和 read_http）
-- =====================================================================
-- 城遇(localvibe) · OpenResty 多级缓存 Lua(店铺详情 + 店铺分类 + 帖子详情内容 + 热门门店)
-- ---------------------------------------------------------------------
-- 链路：浏览器 -> Windows nginx(82) -> OpenResty(8087)
--       L1: ngx.shared.localvibe_cache        (进程内本地缓存)
--       L2: Redis db8                         (Java/canal 维护 或 lua 自管短TTL键)
--       L3: ngx.location.capture /path/... -> Tomcat
-- 路由：
--       /api/shop/{id}       -> 店铺详情缓存(键 cache:shop:id{id}，Java 逻辑过期格式)
--       /api/shop-type/list  -> 店铺分类缓存(键 cache:shopType:string)
--       /api/shop/hot        -> 热门门店列表(键 localvibe:shop:hot，lua 自管短TTL)
--       /api/blog/{id}       -> 帖子详情内容缓存(键 localvibe:blog:id:{id}，lua 自管短TTL)
--                               liked(点赞总数)/isLike(是否点赞)实时计算，不缓存
-- 不缓存：用户/关注/签到/优惠券库存/秒杀订单等强一致或个性化数据
-- =====================================================================

-- 导入 common 函数库（包含 read_redis 和 read_http）

-- redis 要密码授权 64 98 123 151 行 48-50 行是本地redis的配置
local common = require("common")
local read_redis = common.read_redis
local read_http = common.read_http
-- 导入 cjson
local cjson = require("cjson")
-- 导入共享词典 实现nginx本地缓存
local nginx_cache = ngx.shared.localvibe_cache

-- 跨域和响应类型
ngx.header["Access-Control-Allow-Origin"] = "*"
ngx.header["Content-Type"] = "application/json"

-- Redis 连接参数(WSL 内 Redis，与 application.yaml 保持一致)
local redis_host = "172.24.116.171"   -- 你的 Redis 地址
local redis_port = 6379
local redis_db = 6                    -- 业务缓存库(db6)

-- ========== Redis 写/读工具函数 ==========

-- 写入 Redis(带过期时间，用于 lua 自管缓存键，如帖子内容)
local function write_redis(host, port, key, value, db, ttl)
    local redis = require("resty.redis"):new()
    redis:set_timeouts(1000, 1000, 1000)
    local ok, err = redis:connect(host, port)
    if not ok then
        ngx.log(ngx.ERR, "写入Redis连接失败: ", err)
        return false
    end
    -- 密码认证
    local auth_ok, auth_err = redis:auth("")
    if not auth_ok then
        ngx.log(ngx.ERR, "写入Redis认证失败: ", auth_err)
        redis:set_keepalive(10000, 100)
        return false
    end
    -- 切换数据库
    local select_ok, select_err = redis:select(db)
    if not select_ok then
        ngx.log(ngx.ERR, "写入Redis切换数据库失败: ", select_err)
        redis:set_keepalive(10000, 100)
        return false
    end
    -- 写入并设置过期时间(ttl 单位秒)
    local set_ok, set_err = redis:setex(key, ttl, value)
    if not set_ok then
        ngx.log(ngx.ERR, "写入Redis失败: ", set_err, ", key: ", key)
        redis:set_keepalive(10000, 100)
        return false
    end
    redis:set_keepalive(10000, 100)
    ngx.log(ngx.ERR, "成功写入Redis缓存, key: ", key, ", ttl: ", ttl)
    return true
end

-- 读取 Redis Hash 的单个字段(如 login:userToken:{token} 取 id)
local function hget_redis(host, port, key, field, db)
    local redis = require("resty.redis"):new()
    redis:set_timeouts(1000, 1000, 1000)
    local ok, err = redis:connect(host, port)
    if not ok then
        ngx.log(ngx.ERR, "Redis 连接失败: ", err)
        return nil
    end
    local auth_ok, auth_err = redis:auth("")
    if not auth_ok then
        ngx.log(ngx.ERR, "Redis 认证失败: ", auth_err)
        redis:set_keepalive(10000, 100)
        return nil
    end
    local select_ok, select_err = redis:select(db)
    if not select_ok then
        redis:set_keepalive(10000, 100)
        return nil
    end
    local res = redis:hget(key, field)
    redis:set_keepalive(10000, 100)
    return res
end

-- 读取 Redis ZSet 中成员的分数(如 blog:liked:{id} 判断是否点过赞)
local function zscore_redis(host, port, key, member, db)
    local redis = require("resty.redis"):new()
    redis:set_timeouts(1000, 1000, 1000)
    local ok, err = redis:connect(host, port)
    if not ok then
        ngx.log(ngx.ERR, "Redis 连接失败: ", err)
        return nil
    end
    local auth_ok, auth_err = redis:auth("")
    if not auth_ok then
        ngx.log(ngx.ERR, "Redis 认证失败: ", auth_err)
        redis:set_keepalive(10000, 100)
        return nil
    end
    local select_ok, select_err = redis:select(db)
    if not select_ok then
        redis:set_keepalive(10000, 100)
        return nil
    end
    local res = redis:zscore(key, member)
    if res == ngx.null then
        res = nil
    end
    redis:set_keepalive(10000, 100)
    return res
end

-- 读取 Redis ZSet 的成员数量(如 blog:liked:{id} 的点赞总数，实时获取不缓存)
local function zcard_redis(host, port, key, db)
    local redis = require("resty.redis"):new()
    redis:set_timeouts(1000, 1000, 1000)
    local ok, err = redis:connect(host, port)
    if not ok then
        ngx.log(ngx.ERR, "Redis 连接失败: ", err)
        return nil
    end
    local auth_ok, auth_err = redis:auth("")
    if not auth_ok then
        ngx.log(ngx.ERR, "Redis 认证失败: ", auth_err)
        redis:set_keepalive(10000, 100)
        return nil
    end
    local select_ok, select_err = redis:select(db)
    if not select_ok then
        redis:set_keepalive(10000, 100)
        -- HTTP 也没查到(帖子不存在/被删) -> 缓存空值防穿透, 短TTL
        local empty = cjson.encode({ success = false, errorMsg = "帖子不存在或已被删除" })
        write_redis(redis_host, redis_port, key, empty, redis_db, BLOG_EMPTY_TTL)
        nginx_cache:set(key, empty, BLOG_EMPTY_TTL)
        return empty
    end
    local res = redis:zcard(key)
    redis:set_keepalive(10000, 100)
    return res
end

-- ========== 店铺详情三级缓存 ==========
local function shop_handler(id)
    -- 1. 获取店铺 ID（优先从路径变量获取，其次从查询参数）
    if not id or id == "" then
        local args = ngx.req.get_uri_args()
        id = args["id"]
    end

    if not id or id == "" then
        ngx.say([[{"success":false,"errorMsg":"缺失店铺ID"}]])
        return
    end

    -- 读取 Redis 中 Java 逻辑过期格式的店铺缓存
    -- Java 侧(cacheClient.setWithLogicExpire)写入格式：{"expireTime":"...","data":{...}}
    -- 本脚本只读 Redis 不写 Redis，避免破坏逻辑过期格式；Redis 键由 canal 失效
    local function read_shop_from_redis(redis_host, redis_port, key, redis_db)
        local json = read_redis(redis_host, redis_port, key, redis_db)
        if not json or json == "" then
            return nil
        end
        -- 安全解析 Java 逻辑过期 JSON
        local ok, obj = pcall(cjson.decode, json)
        if not ok or not obj or not obj.data then
            ngx.log(ngx.ERR, "解析店铺缓存失败, key: ", key)
            return nil
        end
        -- 转换为接口响应格式 {"success":true,"data":{...}}
        return cjson.encode({ success = true, data = obj.data })
    end

    -- 封装查询函数：先读本地缓存，再读 Redis，最后读 HTTP，并逐级写入缓存
    local function read_data(key, path, redis_host, redis_port, redis_db, local_ttl)
        -- ① 查询本地缓存
        local resp = nginx_cache:get(key)
        if resp and resp ~= "" then
            ngx.log(ngx.ERR, "本地缓存命中, key: ", key)
            return resp
        end

        -- ② 查询 Redis（Java 逻辑过期格式，转换为接口格式）
        resp = read_shop_from_redis(redis_host, redis_port, key, redis_db)
        if resp and resp ~= "" then
            ngx.log(ngx.ERR, "Redis 命中, key: ", key)
            -- 写入本地缓存
            nginx_cache:set(key, resp, local_ttl)
            ngx.log(ngx.ERR, "已写入本地缓存, key: ", key, ", ttl: ", local_ttl)
            return resp
        end

        -- ③ Redis 未命中，查询 HTTP（内部代理，Java 回源数据库并重建缓存）
        ngx.log(ngx.ERR, "Redis 未命中, 查询 HTTP, path: ", path)
        resp = read_http(path, nil)
        if resp and resp ~= "" then
            -- 写入本地缓存（Redis 由 Java 回源时重建，lua 不覆盖逻辑过期格式）
            nginx_cache:set(key, resp, local_ttl)
            ngx.log(ngx.ERR, "已写入本地缓存, key: ", key, ", ttl: ", local_ttl)
        end
        return resp
    end

    -- 构建 Redis key 和内部代理路径
    local shopKey = "cache:shop:id" .. id
    local shopPath = "/path/shop/" .. id
    local SHOP_LOCAL_TTL = 40

    -- 获取数据
    local shopJSON = read_data(shopKey, shopPath, redis_host, redis_port, redis_db, SHOP_LOCAL_TTL)

    -- 判空
    if not shopJSON or shopJSON == "" then
        ngx.log(ngx.ERR, "获取店铺信息失败, id: ", id)
        ngx.say([[{"success":false,"errorMsg":"获取店铺信息失败"}]])
        return
    end

    -- 返回最终 JSON
    ngx.say(shopJSON)
end

-- ========== 店铺分类列表三级缓存 ==========
local function shop_type_handler()
    -- 构建 Redis key 和内部代理路径
    -- Java 侧(ShopTypeServiceImpl.selectByRedisString)维护的缓存键：整体 JSON 数组字符串
    local shopTypeKey = "cache:shopType:string"
    local shopTypePath = "/path/shop-type/list"
    local SHOPTYPE_LOCAL_TTL = 120
    local SHOPTYPE_REDIS_TTL = 600

    -- 读取 Redis 并包装成接口响应格式
    local function read_shop_type_from_redis(redis_host, redis_port, key, redis_db)
        local json = read_redis(redis_host, redis_port, key, redis_db)
        if not json or json == "" then
            return nil
        end
        -- 安全解析，确保是合法 JSON 数组
        local ok, obj = pcall(cjson.decode, json)
        if not ok or type(obj) ~= "table" then
            ngx.log(ngx.ERR, "解析店铺分类缓存失败, key: ", key)
            return nil
        end
        -- 转换为接口响应格式 {"success":true,"data":[...]}
        return cjson.encode({ success = true, data = obj })
    end

    -- 封装查询函数：先读本地缓存，再读 Redis，最后读 HTTP，并逐级写入缓存
    local function read_data(key, path, redis_host, redis_port, redis_db, local_ttl, redis_ttl)
        -- ① 查询本地缓存
        local resp = nginx_cache:get(key)
        if resp and resp ~= "" then
            ngx.log(ngx.ERR, "本地缓存命中, key: ", key)
            return resp
        end

        -- ② 查询 Redis（Java 维护的 JSON 数组，转成接口格式）
        resp = read_shop_type_from_redis(redis_host, redis_port, key, redis_db)
        if resp and resp ~= "" then
            ngx.log(ngx.ERR, "Redis 命中, key: ", key)
            -- 写入本地缓存
            nginx_cache:set(key, resp, local_ttl)
            ngx.log(ngx.ERR, "已写入本地缓存, key: ", key, ", ttl: ", local_ttl)
            return resp
        end

        -- ③ Redis 未命中，查询 HTTP（内部代理回源 Java）
        ngx.log(ngx.ERR, "Redis 未命中, 查询 HTTP, path: ", path)
        resp = read_http(path, nil)
        if resp and resp ~= "" then
            -- 回源响应为 Result 包装格式 {"success":true,"data":[...]}，
            -- 剥离包装后按 Java 预热格式(整体 JSON 数组)写回 Redis string 键，
            -- 避免 canal 失效后该键长期冷缓存(Java 侧当前只重建 hash 键)
            local ok, obj = pcall(cjson.decode, resp)
            if ok and obj and obj.data then
                write_redis(redis_host, redis_port, key, cjson.encode(obj.data), redis_db, redis_ttl)
            end
            -- 写入本地缓存(响应原样缓存，避免重复包装)
            nginx_cache:set(key, resp, local_ttl)
            ngx.log(ngx.ERR, "已写入本地缓存, key: ", key, ", ttl: ", local_ttl)
        end
        return resp
    end

    -- 获取数据
    local shopTypeJSON = read_data(shopTypeKey, shopTypePath, redis_host, redis_port, redis_db, SHOPTYPE_LOCAL_TTL, SHOPTYPE_REDIS_TTL)

    -- 判空
    if not shopTypeJSON or shopTypeJSON == "" then
        ngx.log(ngx.ERR, "获取店铺分类失败")
        ngx.say([[{"success":false,"errorMsg":"获取店铺分类失败"}]])
        return
    end

    -- 返回最终 JSON
    ngx.say(shopTypeJSON)
end

-- ========== 热门门店列表三级缓存 ==========
-- 首页"热门门店"一排展示；键 localvibe:shop:hot(lua 自管短TTL)，回源 Java /shop/hot
local function shop_hot_handler()
    -- 构建 Redis key 和内部代理路径
    local hotKey = "localvibe:shop:hot"
    local hotPath = "/path/shop/hot"
    local HOT_LOCAL_TTL = 60
    local HOT_REDIS_TTL = 120

    -- 封装查询函数：本地 -> Redis -> HTTP(回源后写两级缓存)
    local function read_hot_data(key, path, redis_host, redis_port, redis_db, local_ttl, redis_ttl)
        -- ① 查询本地缓存
        local resp = nginx_cache:get(key)
        if resp and resp ~= "" then
            ngx.log(ngx.ERR, "本地缓存命中, key: ", key)
            return resp
        end

        -- ② 查询 Redis（lua 自管键，原样存储 Result JSON）
        resp = read_redis(redis_host, redis_port, key, redis_db)
        if resp and resp ~= "" then
            ngx.log(ngx.ERR, "Redis 命中, key: ", key)
            nginx_cache:set(key, resp, local_ttl)
            ngx.log(ngx.ERR, "已写入本地缓存, key: ", key, ", ttl: ", local_ttl)
            return resp
        end

        -- ③ 查询 HTTP（内部代理回源 Java，响应即 Result 格式，原样缓存）
        ngx.log(ngx.ERR, "Redis 未命中, 查询 HTTP, path: ", path)
        resp = read_http(path, nil)
        if resp and resp ~= "" then
            write_redis(redis_host, redis_port, key, resp, redis_db, redis_ttl)
            nginx_cache:set(key, resp, local_ttl)
            ngx.log(ngx.ERR, "已写入两级缓存, key: ", key, ", ttl: ", redis_ttl)
        end
        return resp
    end

    -- 获取数据
    local hotJSON = read_hot_data(hotKey, hotPath, redis_host, redis_port, redis_db, HOT_LOCAL_TTL, HOT_REDIS_TTL)

    -- 判空
    if not hotJSON or hotJSON == "" then
        ngx.log(ngx.ERR, "获取热门门店失败")
        ngx.say([[{"success":false,"errorMsg":"获取热门门店失败"}]])
        return
    end

    -- 返回最终 JSON
    ngx.say(hotJSON)
end

-- ========== 帖子详情内容三级缓存 ==========
-- 内容(标题/图片/正文/作者)进缓存；liked(点赞总数)/isLike(是否点赞)实时计算不缓存
local function blog_handler(id)
    -- 1. 获取帖子 ID
    if not id or id == "" then
        local args = ngx.req.get_uri_args()
        id = args["id"]
    end

    if not id or id == "" then
        ngx.say([[{"success":false,"errorMsg":"缺失帖子ID"}]])
        return
    end

    -- 构建键和路径：内容缓存使用 lua 自管键(短TTL)，Java 侧未缓存帖子内容
    local blogKey = "localvibe:blog:id:" .. id
    local blogPath = "/path/blog/" .. id
    local BLOG_LOCAL_TTL = 40
    local BLOG_REDIS_TTL = 90
    local BLOG_EMPTY_TTL = 8        -- 帖子不存在时缓存空值TTL(秒), 防穿透

    -- 封装查询函数：本地 -> Redis(自管键) -> HTTP，回源后剥离 isLike/liked 再写缓存
    local function read_blog_data(key, path, redis_host, redis_port, redis_db, local_ttl, redis_ttl)
        -- ① 查询本地缓存
        local resp = nginx_cache:get(key)
        if resp and resp ~= "" then
            ngx.log(ngx.ERR, "本地缓存命中, key: ", key)
            return resp
        end

        -- ② 查询 Redis
        resp = read_redis(redis_host, redis_port, key, redis_db)
        if resp and resp ~= "" then
            ngx.log(ngx.ERR, "Redis 命中, key: ", key)
            nginx_cache:set(key, resp, local_ttl)
            ngx.log(ngx.ERR, "已写入本地缓存, key: ", key, ", ttl: ", local_ttl)
            return resp
        end

        -- ③ 查询 HTTP（内部代理，游客态，isLike 恒为 false）
        ngx.log(ngx.ERR, "Redis 未命中, 查询 HTTP, path: ", path)
        resp = read_http(path, nil)
        if resp and resp ~= "" then
            -- 剥离 isLike/liked 后写入缓存(点赞数据不缓存，每次实时计算)
            local ok, obj = pcall(cjson.decode, resp)
            if ok and obj and obj.data then
                obj.data.isLike = nil
                obj.data.liked = nil
                local cached = cjson.encode(obj)
                write_redis(redis_host, redis_port, key, cached, redis_db, redis_ttl)
                nginx_cache:set(key, cached, local_ttl)
                return cached
            end
            -- 解析失败则原样缓存(不影响主流程)
            write_redis(redis_host, redis_port, key, resp, redis_db, redis_ttl)
            nginx_cache:set(key, resp, local_ttl)
            return resp
        end
        return nil
    end

    -- 获取缓存内容
    local blogJSON = read_blog_data(blogKey, blogPath, redis_host, redis_port, redis_db, BLOG_LOCAL_TTL, BLOG_REDIS_TTL)

    -- 判空
    if not blogJSON or blogJSON == "" then
        ngx.log(ngx.ERR, "获取帖子信息失败, id: ", id)
        ngx.say([[{"success":false,"errorMsg":"获取帖子信息失败"}]])
        return
    end

    -- 2. 实时计算点赞数 + 当前用户是否点赞(点赞数据不缓存，每次查 Redis)
    --    注意：blog:liked: 集合由 Java 写入，redis_db 必须与 application.yaml 的 database 一致
    local likeKey = "blog:liked:" .. id
    local liked = zcard_redis(redis_host, redis_port, likeKey, redis_db) or 0
    local isLike = false
    local token = ngx.var.http_authorization   -- 前端 common.js 以 authorization 头携带 token
    if token and token ~= "" then
        -- token -> userId(login:userToken:{token} hash 的 id 字段)
        local userId = hget_redis(redis_host, redis_port, "login:userToken:" .. token, "id", redis_db)
        if userId and userId ~= "" then
            -- 是否在 blog:liked:{id} 点赞集合中(zset 存在分数即已点赞)
            local score = zscore_redis(redis_host, redis_port, likeKey, userId, redis_db)
            if score then
                isLike = true
            end
        end
    end

    -- 3. 注入 liked/isLike 后返回
    local ok, obj = pcall(cjson.decode, blogJSON)
    if ok and obj and obj.data then
        obj.data.liked = liked
        obj.data.isLike = isLike
        ngx.say(cjson.encode(obj))
        return
    end
    ngx.say(blogJSON)
end

-- ========== 路由分发 ==========
local uri = ngx.var.uri

-- 店铺分类列表：/api/shop-type/list
if uri == "/api/shop-type/list" then
    shop_type_handler()
    return
end

-- 热门门店列表：/api/shop/hot
if uri == "/api/shop/hot" then
    shop_hot_handler()
    return
end

-- 店铺详情：/api/shop/{id}（conf 中 set $id $1 已注入）
if uri:match("^/api/shop/%d+$") then
    shop_handler(ngx.var.id)
    return
end

-- 帖子详情：/api/blog/{id}
if uri:match("^/api/blog/%d+$") then
    blog_handler(ngx.var.id)
    return
end

-- 未知路由：返回 404
ngx.status = 404
ngx.say([[{"success":false,"errorMsg":"路由不存在"}]])