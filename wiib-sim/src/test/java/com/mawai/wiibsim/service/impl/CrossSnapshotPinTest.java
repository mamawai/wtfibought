package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.dto.CrossSnapshotRow;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.BankruptcyService;
import com.mawai.wiibsim.service.CrossBandRegistry;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesPrices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 快照钉价（插针语义）：pinSymbol 按 pinPrice 估值、其余照缓存；
 * refPrices 必须记录快照实际所用价格——安全带要锚在这组价上。
 */
class CrossSnapshotPinTest {

    private static final Long UID = 7L;
    private static final String SYM = "BTCUSDT";

    private CrossMarginServiceImpl crossMargin;

    @BeforeEach
    void setUp() {
        var positionMapper = mock(FuturesPositionMapper.class);
        var cacheService = mock(CacheService.class);
        var bracketRegistry = mock(FuturesLeverageBracketRegistry.class);

        CrossSnapshotRow row = new CrossSnapshotRow();
        row.setBalance(new BigDecimal("1000"));
        row.setPendingReserved(BigDecimal.ZERO);
        row.setPositionId(1L);
        row.setSymbol(SYM);
        row.setSide("LONG");
        row.setLeverage(20);
        row.setEntryPrice(new BigDecimal("100"));
        row.setQuantity(new BigDecimal("20"));
        row.setMargin(new BigDecimal("100"));

        when(positionMapper.selectCrossSnapshot(UID)).thenReturn(List.of(row));
        when(cacheService.getMarkPrice(SYM)).thenReturn(new BigDecimal("100"));
        when(bracketRegistry.calcMaintenanceMargin(eq(SYM), any())).thenReturn(new BigDecimal("10"));

        crossMargin = new CrossMarginServiceImpl(mock(UserMapper.class), positionMapper, new FuturesPrices(cacheService),
                bracketRegistry, mock(FuturesPositionIndexService.class), mock(BankruptcyService.class),
                mock(StringRedisTemplate.class), new CrossBandRegistry());
    }

    @Test
    void 钉价symbol按钉住价估值() {
        var acc = crossMargin.snapshot(UID, SYM, new BigDecimal("120"));
        assertThat(acc.unrealizedPnl()).isEqualByComparingTo("400"); // (120−100)×20，不是缓存价100下的0
        assertThat(acc.refPrices().get(SYM)).isEqualByComparingTo("120");
    }

    @Test
    void 无钉价_refPrices记录快照所用缓存价() {
        var acc = crossMargin.snapshot(UID);
        assertThat(acc.unrealizedPnl()).isEqualByComparingTo("0");
        assertThat(acc.refPrices().get(SYM)).isEqualByComparingTo("100");
    }
}
