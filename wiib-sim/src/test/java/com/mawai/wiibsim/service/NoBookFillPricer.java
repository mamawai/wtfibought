package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.market.DepthStreamCache;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.Mockito.mock;

/** 单测用：读不到盘口快照，成交价一律走调用方给的兜底价 */
public final class NoBookFillPricer {

    private NoBookFillPricer() {}

    public static BookFillPricer create() {
        return new BookFillPricer(mock(DepthStreamCache.class), mock(StringRedisTemplate.class));
    }
}
