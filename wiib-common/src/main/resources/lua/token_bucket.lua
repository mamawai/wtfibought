-- 令牌桶算法实现（多桶）：所有桶都有令牌才一起各扣 1 个，有一个不够就都不扣
-- KEYS[i]: 第 i 个桶的键名
-- ARGV[1]: 当前时间戳（毫秒）
-- ARGV[2i]: 第 i 个桶每秒补几个令牌
-- ARGV[2i+1]: 第 i 个桶的容量
-- 返回 0 = 放行；否则是第一个不够的桶的序号（从 1 开始）

local now = tonumber(ARGV[1])
local tokens = {}
local short = 0

for i = 1, #KEYS do
    local permits_per_second = tonumber(ARGV[2 * i])
    local capacity = tonumber(ARGV[2 * i + 1])

    -- 从Redis hash中获取当前限流信息
    local rate_limit_info = redis.call('HMGET', KEYS[i], 'last_refill_time', 'tokens')
    local last_refill_time = tonumber(rate_limit_info[1])
    local t = tonumber(rate_limit_info[2])

    if last_refill_time == nil or t == nil then
        -- 第一次请求，桶是满的
        t = capacity
    elseif now >= last_refill_time then
        -- 按距上次补充过了多久补令牌（但不超过容量）；时间倒退就不补
        t = math.min(capacity, t + (now - last_refill_time) / 1000.0 * permits_per_second)
    end

    tokens[i] = t
    if short == 0 and t < 1 then
        short = i
    end
end

for i = 1, #KEYS do
    local permits_per_second = tonumber(ARGV[2 * i])
    local capacity = tonumber(ARGV[2 * i + 1])

    -- 都够才扣，并更新桶状态；被拒时不更新
    if short == 0 then
        redis.call('HSET', KEYS[i], 'last_refill_time', now, 'tokens', tokens[i] - 1)
    end

    -- 设置过期时间 向上取整
    redis.call('EXPIRE', KEYS[i], math.ceil(capacity / permits_per_second * 2))
end

return short
