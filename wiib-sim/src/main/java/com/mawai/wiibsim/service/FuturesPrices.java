package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 合约查价的三种口径，取不到一律抛价不可用 */
@Component
@RequiredArgsConstructor
public class FuturesPrices {

    /** 市价开仓、限价单只认 feed 这么久内写过标记价的币 */
    private static final long OPEN_PRICE_MAX_AGE_MS = 30_000L;

    private final CacheService cacheService;

    /** 最新价：合约实时成交价优先，标记价兜底 */
    public BigDecimal latest(String symbol) {
        BigDecimal price = cacheService.getFuturesPrice(symbol);
        if (price != null) return price;
        price = cacheService.getMarkPrice(symbol);
        if (price == null) throw new BizException(ErrorCode.CRYPTO_PRICE_UNAVAILABLE);
        return price;
    }

    /**
     * 市价开仓、限价单判能不能当场成交用的价：同 latest，但 feed 超过 30 秒没写过这个币的标记价就当没有价，
     * 拒单（断线时价格停住，不按停住的价成交，也判断不了限价单能不能当场成交）。
     * 心跳看标记价不看最新价：markPrice@1s 每秒必推，miniTicker 没成交就不推，成交稀的币会几十秒没帧。
     * 市价平仓不走这里：下架的币要能按最后价平掉。
     */
    public BigDecimal fresh(String symbol) {
        Long at = cacheService.getMarkPriceAt(symbol);
        if (at == null || System.currentTimeMillis() - at > OPEN_PRICE_MAX_AGE_MS) {
            throw new BizException(ErrorCode.CRYPTO_PRICE_UNAVAILABLE);
        }
        return latest(symbol);
    }

    /** 标记价：标记价优先，合约最新价兜底 */
    public BigDecimal mark(String symbol) {
        BigDecimal mp = cacheService.getMarkPrice(symbol);
        if (mp != null) return mp;
        BigDecimal price = cacheService.getFuturesPrice(symbol);
        if (price == null) throw new BizException(ErrorCode.CRYPTO_PRICE_UNAVAILABLE);
        return price;
    }
}
