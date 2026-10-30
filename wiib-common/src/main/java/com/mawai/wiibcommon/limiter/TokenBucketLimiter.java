package com.mawai.wiibcommon.limiter;

import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.enums.RateLimiterType;
import com.mawai.wiibcommon.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis 令牌桶，脚本见 resources/lua/token_bucket.lua，桶的参数见 {@link RateLimiterType}。
 * 注解 {@code @RateLimiter} 走切面调它；按 IP、按用户名这种要调用方给主体的桶直接调 {@link #acquire}
 */
@Slf4j
@Component
public class TokenBucketLimiter {

    /** 一个桶：哪种桶 + 按谁分（登录 ID、global、IP、用户名） */
    public record Bucket(RateLimiterType type, String who) {
    }

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>();

    public TokenBucketLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/token_bucket.lua")));
        script.setResultType(Long.class);
    }

    /** 一次脚本查完所有桶：都有令牌才一起扣、放行；有一个不够就都不扣，抛 RATE_LIMIT_EXCEEDED。Redis 出错放行 */
    public void acquire(Bucket... buckets) {
        List<String> keys = new ArrayList<>(buckets.length);
        List<String> args = new ArrayList<>(1 + 2 * buckets.length);
        args.add(String.valueOf(System.currentTimeMillis()));
        for (Bucket b : buckets) {
            keys.add("limiter:" + b.type().name() + ":" + b.who());
            args.add(String.valueOf(b.type().getPermitsPerSecond()));
            args.add(String.valueOf(b.type().getBucketCapacity()));
        }
        int rejected;
        try {
            rejected = redis.execute(script, keys, args.toArray()).intValue();
        } catch (Exception e) {
            log.warn("[RateLimiter] Redis 执行失败，放行 keys={}: {}", keys, e.getMessage());
            return;
        }
        if (rejected > 0) {
            log.warn("[RateLimiter] 拒绝 key={}", keys.get(rejected - 1));
            throw new BizException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
    }
}
