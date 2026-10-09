package com.mawai.wiibcommon.aspect;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.annotation.RateLimiter;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.enums.RateLimiterType;
import com.mawai.wiibcommon.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 令牌桶限流切面，见 {@link RateLimiter} */
@Aspect
@Component
@Slf4j
public class RateLimiterAspect {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> tokenBucket = new DefaultRedisScript<>();

    public RateLimiterAspect(StringRedisTemplate redis) {
        this.redis = redis;
        tokenBucket.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/token_bucket.lua")));
        tokenBucket.setResultType(Long.class);
    }

    @Around("@annotation(rateLimiter)")
    public Object around(ProceedingJoinPoint point, RateLimiter rateLimiter) throws Throwable {
        RateLimiterType[] types = rateLimiter.value();
        int rejected = tryAcquire(types);
        if (rejected > 0) {
            log.warn("[RateLimiter] 拒绝 type={} method={}", types[rejected - 1], point.getSignature().toShortString());
            throw new BizException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
        return point.proceed();
    }

    /**
     * 一次脚本拿所有桶的令牌：返回 0 放行，否则是第一个不够的桶的序号（从 1 开始）。
     * key 放 try 外面：没登录抛的 401 照常往外抛
     */
    private int tryAcquire(RateLimiterType[] types) {
        List<String> keys = Arrays.stream(types).map(RateLimiterAspect::key).toList();
        List<String> args = new ArrayList<>(1 + 2 * types.length);
        args.add(String.valueOf(System.currentTimeMillis()));
        for (RateLimiterType type : types) {
            args.add(String.valueOf(type.getPermitsPerSecond()));
            args.add(String.valueOf(type.getBucketCapacity()));
        }
        try {
            return redis.execute(tokenBucket, keys, args.toArray()).intValue();
        } catch (Exception e) {
            // Redis 出错放行
            log.warn("[RateLimiter] Redis 执行失败，放行 keys={}: {}", keys, e.getMessage());
            return 0;
        }
    }

    private static String key(RateLimiterType type) {
        String who = type.getScope() == RateLimiterType.Scope.GLOBAL ? "global" : StpUtil.getLoginIdAsString();
        return "limiter:" + type.name() + ":" + who;
    }
}
