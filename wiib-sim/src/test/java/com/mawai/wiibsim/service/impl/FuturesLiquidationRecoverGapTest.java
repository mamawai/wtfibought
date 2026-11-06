package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.service.CrossLiquidationService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.TraderSymbolService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标记价空窗补漏逐仓强平，按开仓时间过滤。要害是"开仓之前的行情不算数"——
 * 不看时间的话，10:04 开的仓会被 10:01 的插针在 10:05 重连时打成强平。
 */
class FuturesLiquidationRecoverGapTest {

    private static final long T0 = 1700000000000L;
    private static final String SYM = "BTCUSDT";
    private static final String LIQ_LONG_KEY = "futures:liq:long:" + SYM;

    /** 前一根插到 40，后一根只到 90：插针只在前一根里；多头强平价 50 */
    private static final List<KlineBar> BARS = List.of(
            bar(T0, T0 + 59_999L, "100", "40"),
            bar(T0 + 60_000L, T0 + 119_999L, "110", "90"));

    private final CacheService cacheService = mock(CacheService.class);
    private final FuturesRiskService riskService = mock(FuturesRiskService.class);
    private final FuturesPositionMapper positionMapper = mock(FuturesPositionMapper.class);
    private final FuturesLiquidationServiceImpl service = new FuturesLiquidationServiceImpl(riskService, cacheService,
            mock(TraderSymbolService.class), positionMapper, mock(FuturesPositionIndexService.class),
            mock(CrossLiquidationService.class));

    private static KlineBar bar(long openTime, long closeTime, String high, String low) {
        return new KlineBar(openTime, closeTime, new BigDecimal(low), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(high), BigDecimal.ONE);
    }

    private static FuturesPosition openedAt(long ms) {
        FuturesPosition p = new FuturesPosition();
        p.setId(1L);
        p.setSymbol(SYM);
        p.setSide("LONG");
        p.setStatus("OPEN");
        p.setCreatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()));
        return p;
    }

    @BeforeEach
    void setUp() {
        when(cacheService.zRangeByScoreAndRemove(anyString(), anyDouble(), anyDouble())).thenReturn(Map.of());
        when(cacheService.zRangeByScoreAndRemove(LIQ_LONG_KEY, 40d, Double.MAX_VALUE)).thenReturn(Map.of("1", 50d));
    }

    /** 钉区间低点 40；时刻记成空窗第一根开盘，强平按钉价成交、不扫现在的盘口 */
    @Test
    void 仓位开在插针之前_强平_钉区间低点() {
        when(positionMapper.selectById(1L)).thenReturn(openedAt(T0));

        service.recoverGap(SYM, BARS);

        verify(riskService, timeout(2000)).forceClose(1L, new BigDecimal("40"), T0);
        verify(cacheService, never()).zAdd(anyString(), anyString(), anyDouble());
    }

    @Test
    void 仓位开在插针之后_原样放回不强平() {
        when(positionMapper.selectById(1L)).thenReturn(openedAt(T0 + 60_000L));

        service.recoverGap(SYM, BARS);

        verify(cacheService).zAdd(LIQ_LONG_KEY, "1", 50d);
        verify(riskService, after(300).never()).forceClose(anyLong(), any(), anyLong());
    }

    @Test
    void 仓位已经没了_索引是过期项_不放回() {
        FuturesPosition closed = openedAt(T0);
        closed.setStatus("LIQUIDATED");
        when(positionMapper.selectById(1L)).thenReturn(closed);

        service.recoverGap(SYM, BARS);

        verify(cacheService, never()).zAdd(anyString(), anyString(), anyDouble());
        verify(riskService, after(300).never()).forceClose(anyLong(), any(), anyLong());
    }
}
