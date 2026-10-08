package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.ledger.LedgerTx;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.TradeNotificationService;
import com.mawai.wiibsim.util.FairLockRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.concurrent.locks.Lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 逐仓强平执行时的锁内复核与强平索引回填。
 * 例：逐仓多 1 BTC@100000、100x，保证金 1000，强平价 99397.59；
 * tick 在 99390 把索引摘掉的同时用户追加了 4000，新强平价应为 95381.53。
 */
class FuturesForceCloseRecheckTest {

    private static final String SYM = "BTCUSDT";
    private static final String LIQ_LONG_KEY = "futures:liq:long:" + SYM;
    private static final BigDecimal TICK = new BigDecimal("99390");

    private FuturesPositionMapper positionMapper;
    private CacheService cacheService;
    private FuturesPositionIndexServiceImpl indexService;
    private FuturesRiskServiceImpl riskService;

    @BeforeEach
    void setUp() {
        positionMapper = mock(FuturesPositionMapper.class);
        cacheService = mock(CacheService.class);
        FairLockRegistry lockRegistry = mock(FairLockRegistry.class);
        when(lockRegistry.tryLockAsSystem(anyString())).thenReturn(mock(Lock.class));

        FuturesLeverageBracketRegistry brackets = new FuturesLeverageBracketRegistry();
        indexService = new FuturesPositionIndexServiceImpl(positionMapper, cacheService, brackets);
        TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
        riskService = new FuturesRiskServiceImpl(positionMapper, mock(FuturesOrderMapper.class), mock(UserMapper.class),
                new TradingConfig(), lockRegistry, cacheService, indexService, brackets,
                mock(CrossMarginService.class), mock(TradeNotificationService.class), tx, new LedgerTx(tx));
    }

    private static FuturesPosition isolatedLong(String margin) {
        FuturesPosition p = new FuturesPosition();
        p.setId(9L);
        p.setUserId(7L);
        p.setSymbol(SYM);
        p.setSide("LONG");
        p.setStatus("OPEN");
        p.setMarginMode(FuturesPosition.ISOLATED);
        p.setLeverage(100);
        p.setEntryPrice(new BigDecimal("100000"));
        p.setQuantity(new BigDecimal("1"));
        p.setMargin(new BigDecimal(margin));
        return p;
    }

    @Test
    void 拿到锁时已追加保证金_复核不满足不平_按新保证金重挂强平索引() {
        // 锁内读到的是追加后的 5000：5000−610=4390 > 维持保证金 99390×0.4%=397.56
        when(positionMapper.selectById(9L)).thenReturn(isolatedLong("5000"));

        riskService.forceClose(9L, TICK);

        verify(positionMapper, never()).casClosePosition(anyLong(), anyString(), any(), any());
        ArgumentCaptor<Double> score = ArgumentCaptor.forClass(Double.class);
        verify(cacheService).zAdd(eq(LIQ_LONG_KEY), eq("9"), score.capture());
        assertThat(score.getValue()).isCloseTo(95381.53, offset(0.01));
    }

    @Test
    void 复核仍满足_照常强平且不回挂索引() {
        // 没追加：1000−610=390 ≤ 397.56
        when(positionMapper.selectById(9L)).thenReturn(isolatedLong("1000"));
        when(positionMapper.casClosePosition(anyLong(), anyString(), any(), any())).thenReturn(1);

        riskService.forceClose(9L, TICK);

        verify(positionMapper).casClosePosition(eq(9L), eq("LIQUIDATED"), eq(TICK), any());
        verify(cacheService, never()).zAdd(anyString(), anyString(), anyDouble());
    }

    @Test
    void 全仓仓位不注册强平索引() {
        FuturesPosition cross = isolatedLong("1000");
        cross.setMarginMode(FuturesPosition.CROSS);

        indexService.registerLiquidation(cross);

        verify(cacheService, never()).zAdd(anyString(), anyString(), anyDouble());
    }
}
