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
    KLINE_GUEST(Scope.GLOBAL, 1, 30);

    private final Scope scope;
    private final double permitsPerSecond;
    private final int bucketCapacity;

    /** USER 每人一个桶（取登录 ID，没登录抛 401）；GLOBAL 全站共用一个桶 */
    public enum Scope { USER, GLOBAL }
}
