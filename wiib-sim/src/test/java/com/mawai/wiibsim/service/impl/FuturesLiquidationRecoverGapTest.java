package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.service.FuturesRiskService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 逐仓空窗补漏按创建时间过滤。要害是"档位挂上之前的行情不算数"——
 * 不看时间的话，10:04 挂的止损会被 10:01 的插针在 10:05 重连时打成触发。
 */
class FuturesLiquidationRecoverGapTest {

    private static final long T0 = 1700000000000L;
    private static final String SYM = "BTCUSDT";
    private static final String SL_LONG_KEY = "futures:sl:long:" + SYM;
    private static final String SL_ID = "sl1";

    /** 前一根插到 40，后一根只到 90：插针只在前一根里 */
    private static final List<KlineBar> BARS = List.of(
            bar(T0, T0 + 59_999L, "100", "40"),
            bar(T0 + 60_000L, T0 + 119_999L, "110", "90"));

    private final CacheService cacheService = mock(CacheService.class);
    private final FuturesRiskService riskService = mock(FuturesRiskService.class);
    private final FuturesPositionMapper positionMapper = mock(FuturesPositionMapper.class);

    private static KlineBar bar(long openTime, long closeTime, String high, String low) {
        return new KlineBar(openTime, closeTime, new BigDecimal(low), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(high), BigDecimal.ONE);
    }

    private static LocalDateTime at(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault());
    }

    /** LONG 仓位，带一档止损 50 */
    private static FuturesPosition position(Long slCreatedAt, long posCreatedAt) {
        FuturesPosition p = new FuturesPosition();
        p.setId(1L);
        p.setSymbol(SYM);
        p.setSide("LONG");
        p.setStatus("OPEN");
        p.setCreatedAt(at(posCreatedAt));
        FuturesStopLoss sl = new FuturesStopLoss(SL_ID, new BigDecimal("50"), new BigDecimal("1"));
        sl.setCreatedAt(slCreatedAt);
        p.setStopLosses(List.of(sl));
        return p;
    }

    private FuturesLiquidationServiceImpl service(Long slCreatedAt, long posCreatedAt) {
        // 粗筛：只有 SL_LONG 这条索引命中，其余五条空
        when(cacheService.zRangeByScoreAndRemove(anyString(), anyDouble(), anyDouble()))
                .thenReturn(Map.of());
        when(cacheService.zRangeByScoreAndRemove(SL_LONG_KEY, 40d, Double.MAX_VALUE))
                .thenReturn(Map.of("1:" + SL_ID, 50d));
        when(positionMapper.selectById(1L)).thenReturn(position(slCreatedAt, posCreatedAt));
        return new FuturesLiquidationServiceImpl(riskService, cacheService,
                mock(BinanceProperties.class), positionMapper);
    }

    /** 止损挂在插针之后：不许触发，摘掉的索引得原样回填 */
    @Test
    void 止损晚于插针_回填索引不触发() {
        service(T0 + 60_000L, T0).recoverGap(SYM, BARS, BARS);

        verify(cacheService, timeout(2000)).zAdd(SL_LONG_KEY, "1:" + SL_ID, 50d);
        verify(riskService, never()).batchTriggerStopLoss(anyLong(), any(), any());
    }

    /** 止损挂在插针之前：触发，钉价取仓位级区间低点（插针那一刻的价） */
    @Test
    void 止损早于插针_触发且钉价为区间低点() {
        service(T0, T0).recoverGap(SYM, BARS, BARS);

        verify(riskService, timeout(2000)).batchTriggerStopLoss(1L, List.of(SL_ID), new BigDecimal("40"));
        verify(cacheService, never()).zAdd(anyString(), anyString(), anyDouble());
    }

    /** 旧数据档位没有 createdAt：退回仓位创建时间，照样触发 */
    @Test
    void 档位无创建时间_退回仓位时间() {
        service(null, T0).recoverGap(SYM, BARS, BARS);

        verify(riskService, timeout(2000)).batchTriggerStopLoss(1L, List.of(SL_ID), new BigDecimal("40"));
    }

    /** 限价单成交建仓：档位时间是下单时间，早于仓位；仓位开在插针之后就不许触发 */
    @Test
    void 档位早于仓位_按仓位时间算() {
        service(T0, T0 + 60_000L).recoverGap(SYM, BARS, BARS);

        verify(cacheService, timeout(2000)).zAdd(SL_LONG_KEY, "1:" + SL_ID, 50d);
        verify(riskService, never()).batchTriggerStopLoss(anyLong(), any(), any());
    }
}
