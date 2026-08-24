-- lua工具类
-- 30行要填写 redis密码

-- 引入 redis 模块
local redisUtil = require("resty.redis")

-- 关闭 redis 连接的工具方法（放入连接池）
local function close_redis(redis)
    local pool_max_idle_time = 10000 -- 10秒
    local pool_size = 100
    local ok, err = redis:set_keepalive(pool_max_idle_time, pool_size)
    if not ok then
        ngx.log(ngx.ERR, "放入 Redis 连接池失败: ", err)
    end
end

-- 查询 redis 封装（支持数据库切换）
-- 参数：ip, port, key, database（可选，默认 6）
local function read_redis(ip, port, key, database)
    local redis = redisUtil:new()
    redis:set_timeouts(1000, 1000, 1000) -- 连接、读、写超时

    local ok, err = redis:connect(ip, port)
    if not ok then
        ngx.log(ngx.ERR, "连接 Redis 失败: ", err)
        return nil
    end

    -- 密码认证（必须）
    local auth_ok, auth_err = redis:auth("")
    if not auth_ok then
        ngx.log(ngx.ERR, "Redis 认证失败: ", auth_err)
        close_redis(redis)
        return nil
    end

    -- 切换数据库（默认 6）
    local db = database or 6
    local select_ok, select_err = redis:select(db)
    if not select_ok then
        ngx.log(ngx.ERR, "Redis 选择数据库失败: ", select_err, ", db = ", db)
        close_redis(redis)
        return nil
    end

    -- 执行查询
    local response, err = redis:get(key)
    if not response then
        ngx.log(ngx.ERR, "Redis 读取失败: ", err, ", key = ", key)
        close_redis(redis)
        return nil
    end

    -- 处理空值（Redis 返回 ngx.null）
    if response == ngx.null then
        close_redis(redis)
        return nil
    end

    close_redis(redis)
    return response
end

-- 封装内部 HTTP 请求（使用 ngx.location.capture）
local function read_http(path, params)
    local response = ngx.location.capture(path, {
        method = ngx.HTTP_GET,
        args = params,
    })

    if not response then
        ngx.log(ngx.ERR, "HTTP 子请求无响应, path: ", path)
        return nil
    end

    if response.status ~= 200 then
        ngx.log(ngx.ERR, "HTTP 返回非200状态: ", response.status, ", path: ", path)
        return nil
    end

    return response.body
end

-- 导出模块
local _M = {
    read_redis = read_redis,
    read_http = read_http
}
return _M