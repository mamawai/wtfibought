package com.mawai.wiibcommon.aspect;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.annotation.RateLimiter;
import com.mawai.wiibcommon.enums.RateLimiterType;
import com.mawai.wiibcommon.limiter.TokenBucketLimiter;
import com.mawai.wiibcommon.limiter.TokenBucketLimiter.Bucket;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/** 令牌桶限流切面，见 {@link RateLimiter}；扣令牌在 {@link TokenBucketLimiter} */
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimiterAspect {

    private final TokenBucketLimiter limiter;

    @Around("@annotation(rateLimiter)")
    public Object around(ProceedingJoinPoint point, RateLimiter rateLimiter) throws Throwable {
        // 先算好每个桶按谁分：没登录抛的 401 照常往外抛，不当成 Redis 故障放行
        Bucket[] buckets = Arrays.stream(rateLimiter.value()).map(t -> new Bucket(t, who(t))).toArray(Bucket[]::new);
        limiter.acquire(buckets);
        return point.proceed();
    }

    private static String who(RateLimiterType type) {
        return switch (type.getScope()) {
            case USER -> StpUtil.getLoginIdAsString();
            case GLOBAL -> "global";
            case CALLER -> throw new IllegalStateException(type + " 按谁分桶要调用方给，直接调 TokenBucketLimiter");
        };
    }
}
