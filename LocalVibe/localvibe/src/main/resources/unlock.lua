-- 使用lua 来判当前线程和锁的线程是否一致

if redis.call('get',KEYS[1])==ARGV[1]
then
    -- 是自己的锁才释放
    return redis.call('del',KEYS[1])
end
-- 不是自己的锁 不能删除
return 0
