package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.service.CrossLiquidationService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.TraderSymbolService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 逐仓强平兜底巡检。验的是 sweepAll 自己的行为，判定本身走的是与 markprice tick 同一个
 * checkOnPriceUpdate，那部分不在这里重复验。巡的是 feed 配置的币加交易员的币。
 */
class FuturesLiquidationSweepTest {

    private final CacheService cacheService = mock(CacheService.class);
    private final TraderSymbolService traderSymbols = mock(TraderSymbolService.class);

    private FuturesLiquidationServiceImpl service() {
        return new FuturesLiquidationServiceImpl(mock(FuturesRiskService.class), cacheService, traderSymbols,
                mock(FuturesPositionMapper.class), mock(FuturesPositionIndexService.class),
                mock(CrossLiquidationService.class));
    }

    private void symbols(String... symbols) {
        Set<String> set = new LinkedHashSet<>(List.of(symbols));
        when(traderSymbols.allFuturesSymbols()).thenReturn(set);
    }

    /**
     * 兜底巡检的全部价值在于"总能跑完"。刚启动缓存没热、某标的行情迟迟不来，取不到价是常态，
     * 此时若让异常冒出循环，排在后面的标的这一轮就全被跳过——等于把兜底本身变成了新的空窗，
     * 而且它补的正是一段静默故障，没人会发现。
     */
    @Test
    void 单个symbol取不到价不中断整轮巡检() {
        symbols("NOPRICE", "BTCUSDT");
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

    /** 这里只巡强平、只看标记价；止损止盈看成交价，巡检在 TradeStreamConsumer#sweep */
    @Test
    void 只按标记价巡强平_不碰止损止盈索引() {
        symbols("BTCUSDT");
        when(cacheService.getMarkPrice("BTCUSDT")).thenReturn(new BigDecimal("50000"));
        when(cacheService.getFuturesPrice("BTCUSDT")).thenReturn(new BigDecimal("49950"));
        when(cacheService.zRangeByScoreAndRemove(anyString(), anyDouble(), anyDouble()))
                .thenReturn(Map.<String, Double>of());

        service().sweepAll();

        verify(cacheService).zRangeByScoreAndRemove("futures:liq:long:BTCUSDT", 50000d, Double.MAX_VALUE);
        verify(cacheService).zRangeByScoreAndRemove("futures:liq:short:BTCUSDT", 0d, 50000d);
        verify(cacheService, never()).zRangeByScoreAndRemove(startsWith("futures:sl:"), anyDouble(), anyDouble());
        verify(cacheService, never()).zRangeByScoreAndRemove(startsWith("futures:tp:"), anyDouble(), anyDouble());
    }
}
