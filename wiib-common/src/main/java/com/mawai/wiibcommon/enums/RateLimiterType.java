package com.mawai.wiibcommon.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 限流桶：每种桶自带范围、每秒补几个令牌、桶容量（最多能连发几次） */
@Getter
@AllArgsConstructor
public enum RateLimiterType {
    /** 登录用户 K 线回源币安，每人一个桶 */
    KLINE_USER(Scope.USER, 0.5, 30),
    /** 登录用户 K 线回源币安总量 */
    KLINE_MEMBER(Scope.GLOBAL, 2, 60),
    /** 游客 K 线回源币安总量（游客只能拉最新页） */
    KLINE_GUEST(Scope.GLOBAL, 1, 30),
    /** 密码登录，同一 IP：连试 10 次，之后每 6 秒回 1 次 */
    LOGIN_IP(Scope.CALLER, 1.0 / 6, 10),
    /** 密码登录，同一用户名：连试 5 次，之后每分钟回 1 次 */
    LOGIN_USERNAME(Scope.CALLER, 1.0 / 60, 5),
    /** 邀请码注册，同一 IP：连 5 次，之后每 10 分钟回 1 次 */
    REGISTER_IP(Scope.CALLER, 1.0 / 600, 5);

    private final Scope scope;
    private final double permitsPerSecond;
    private final int bucketCapacity;

    /**
     * USER 每人一个桶（取登录 ID，没登录抛 401）；GLOBAL 全站共用一个桶；
     * CALLER 按谁分桶由调用方给（IP、用户名），不能放注解上，直接调 TokenBucketLimiter
     */
    public enum Scope { USER, GLOBAL, CALLER }
}
