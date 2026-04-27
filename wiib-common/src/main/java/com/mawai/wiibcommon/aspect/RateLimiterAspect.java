package com.mawai.wiibcommon.aspect;

import com.mawai.wiibcommon.annotation.RateLimiter;
import com.mawai.wiibcommon.constant.RateLimiterType;
import com.mawai.wiibcommon.exception.RateLimitException;

import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * 限流切面
 * 基于令牌桶算法的分布式限流拦截器
 */
@Aspect
@Component
@Slf4j
public class RateLimiterAspect {

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> rateLimiterScript;

    public RateLimiterAspect(StringRedisTemplate stringRedisTemplate) throws Exception {
        this.stringRedisTemplate = stringRedisTemplate;

        // 加载Lua脚本
        this.rateLimiterScript = new DefaultRedisScript<>();
        this.rateLimiterScript.setResultType(Long.class);
        String scriptText = StreamUtils.copyToString(
                new ClassPathResource("lua/token_bucket.lua").getInputStream(),
                StandardCharsets.UTF_8);
        this.rateLimiterScript.setScriptText(scriptText);

        log.info("RateLimiterAspect initialized with token bucket lua script");
    }

    @Around("@annotation(rateLimiter)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimiter rateLimiter) throws Throwable {
        try {
            String key = getLimiterKey(rateLimiter.type(), rateLimiter.global());
            double permitsPerSecond = rateLimiter.permitsPerSecond();
            int bucketCapacity = rateLimiter.bucketCapacity();
            long now = System.currentTimeMillis();

            Long result = stringRedisTemplate.execute(
                    rateLimiterScript,
                    Collections.singletonList(key),
                    String.valueOf(permitsPerSecond),
                    String.valueOf(bucketCapacity),
                    String.valueOf(now),
                    "1"
            );

            if (result != 1) {
                log.warn("Rate limit exceeded for key: {}, method: {}",
                        key, joinPoint.getSignature().toShortString());
                throw new RateLimitException(rateLimiter.message());
            }
        } catch (RateLimitException e) {
            throw e;
        } catch (Exception e) {
            log.error("Rate limiter execution failed, fail open", e);
        }

        return joinPoint.proceed();
    }

    private static String getLimiterKey(RateLimiterType type, boolean global) {
        if (global) {
            return "limiter:" + type.name() + ":global";
        }

        String loginId = null;
        try {
            if (StpUtil.isLogin()) {
                loginId = StpUtil.getLoginIdAsString();
            }
        } catch (Exception e) {
            log.warn("Failed to get login id from StpUtil", e);
        }

        if (loginId == null || loginId.isBlank()) {
            throw new RateLimitException("用户登录状态异常，无法进行限流");
        }

        return "limiter:" + type.name() + ":" + loginId;
    }
}
