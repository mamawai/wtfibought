package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.annotation.RateLimiter;
import com.mawai.wiibcommon.enums.RateLimiterType;
import com.mawai.wiibcommon.market.BinanceRestClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** K 线回源币安的出口，只在 {@link KlineCacheService} 缓存没命中时调，限流挂在这 */
@Component
@RequiredArgsConstructor
public class KlineOrigin {

    private final BinanceRestClient binanceRestClient;

    /** 币安熔断冷却中（不过限流） */
    public boolean blocked() {
        return binanceRestClient.blocked();
    }

    /** 游客：只能拉最新页，过游客总桶 */
    @RateLimiter(RateLimiterType.KLINE_GUEST)
    public String guest(boolean futures, String symbol, String interval, int limit) {
        return fetch(futures, symbol, interval, limit, null);
    }

    /** 登录用户：每人桶和登录用户总桶一起过 */
    @RateLimiter({RateLimiterType.KLINE_USER, RateLimiterType.KLINE_MEMBER})
    public String member(boolean futures, String symbol, String interval, int limit, Long endTime) {
        return fetch(futures, symbol, interval, limit, endTime);
    }

    private String fetch(boolean futures, String symbol, String interval, int limit, Long endTime) {
        return futures
                ? binanceRestClient.getFuturesKlinesLight(symbol, interval, limit, endTime)
                : binanceRestClient.getKlinesLight(symbol, interval, limit, endTime);
    }
}
