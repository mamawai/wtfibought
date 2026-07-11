package com.mawai.wiibcommon.annotation;

import com.mawai.wiibcommon.constant.RateLimiterType;

import java.lang.annotation.*;

/**
 * 限流注解
 * 基于令牌桶算法的分布式限流
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimiter {

    /**
     * 每秒生成的令牌数 -- 一分钟1个
     * @return 令牌生成速率
     */
    double permitsPerSecond() default (1.0 / 60);

    /**
     * 令牌桶容量
     * @return 桶容量
     */
    int bucketCapacity() default 1;

    /**
     * 限流失败时的提示信息
     * @return 错误信息
     */
    String message() default "请求过于频繁，请稍后再试";

    /**
     * 限流器类型（必填，为了区分不同的限流器类型）
     * @return 限流器类型
     */
    RateLimiterType type();
}
