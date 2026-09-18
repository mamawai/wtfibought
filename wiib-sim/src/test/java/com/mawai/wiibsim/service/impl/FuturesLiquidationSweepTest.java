package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.service.FuturesRiskService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 逐仓强平兜底巡检。验的是 sweepAll 自己的行为，撮合判定本身走的是与 tick 路径同一个
 * checkOnPriceUpdate，那部分不在这里重复验。
 */
class FuturesLiquidationSweepTest {

    private final CacheService cacheService = mock(CacheService.class);
    private final BinanceProperties props = mock(BinanceProperties.class);

    private FuturesLiquidationServiceImpl service() {
        return new FuturesLiquidationServiceImpl(mock(FuturesRiskService.class), cacheService, props,
                mock(FuturesPositionMapper.class));
    }

    /**
     * 兜底巡检的全部价值在于"总能跑完"。刚启动缓存没热、某标的行情迟迟不来，取不到价是常态，
     * 此时若让异常冒出循环，排在后面的标的这一轮就全被跳过——等于把兜底本身变成了新的空窗，
     * 而且它补的正是一段静默故障，没人会发现。
     */
    @Test
    void 单个symbol取不到价不中断整轮巡检() {
        when(props.getAllFuturesSymbols()).thenReturn(List.of("NOPRICE", "BTCUSDT"));
        // NOPRICE 两个价都缺 → FuturesHelper#markPrice 抛 BizException
        when(cacheService.getMarkPrice("NOPRICE")).thenReturn(null);
        when(cacheService.getFuturesPrice("NOPRICE")).thenReturn(null);
        when(cacheService.getMarkPrice("BTCUSDT")).thenReturn(new BigDecimal("50000"));
        when(cacheService.getFuturesPrice("BTCUSDT")).thenReturn(new BigDecimal("49950"));
        when(cacheService.zRangeByScoreAndRemove(anyString(), anyDouble(), anyDouble()))
                .thenReturn(Map.<String, Double>of());

        service().sweepAll();

        // 排在后面的标的照样被扫：摸到强平索引才算真的走进了 checkOnPriceUpdate
        verify(cacheService).zRangeByScoreAndRemove(eq("futures:liq:long:BTCUSDT"), anyDouble(), anyDouble());
        // 没价的那个一次索引都不许碰——拿 null/0 当价格去扫会把全部多头仓位判成该强平
        verify(cacheService, never())
                .zRangeByScoreAndRemove(eq("futures:liq:long:NOPRICE"), anyDouble(), anyDouble());
    }

    /**
     * 两个价各取各的口径：mark 走 markPrice()、current 走 latestFuturesPrice()，不能图省事共用一个。
     * 混了的话止盈会拿 mark 价判定，而止盈在 tick 路径上判的是最新成交价，两条路径结论就不一致了。
     */
    @Test
    void mark价与current价分别取() {
        when(props.getAllFuturesSymbols()).thenReturn(List.of("BTCUSDT"));
        when(cacheService.getMarkPrice("BTCUSDT")).thenReturn(new BigDecimal("50000"));
        when(cacheService.getFuturesPrice("BTCUSDT")).thenReturn(new BigDecimal("49950"));
        when(cacheService.zRangeByScoreAndRemove(anyString(), anyDouble(), anyDouble()))
                .thenReturn(Map.<String, Double>of());

        service().sweepAll();

        // LIQ 用 mark：LONG 侧下界即 markPrice
        verify(cacheService).zRangeByScoreAndRemove("futures:liq:long:BTCUSDT", 50000d, Double.MAX_VALUE);
        // TP 用 current：LONG 侧上界即 currentPrice
        verify(cacheService).zRangeByScoreAndRemove("futures:tp:long:BTCUSDT", 0d, 49950d);
    }
}
