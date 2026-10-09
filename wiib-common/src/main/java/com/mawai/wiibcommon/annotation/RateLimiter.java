package com.mawai.wiibcommon.annotation;

import com.mawai.wiibcommon.enums.RateLimiterType;

import java.lang.annotation.*;

/**
 * 令牌桶限流，桶放在 Redis，脚本见 resources/lua/token_bucket.lua，桶的参数见 {@link RateLimiterType}。
 * 桶可以给多个：一次脚本里查完，所有桶都有令牌才一起扣，有一个不够就都不扣。
 * 被拒抛 BizException(RATE_LIMIT_EXCEEDED)；Redis 出错放行。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimiter {

    RateLimiterType[] value();
}
