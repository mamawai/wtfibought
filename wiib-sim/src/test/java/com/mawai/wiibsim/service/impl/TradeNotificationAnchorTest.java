package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.TradeNotificationService;
import com.mawai.wiibsim.util.RedisLockUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 三个通知锚点的接线。重点不是"能发出来"，而是：
 * 1) 挂在幂等 CAS 之后 —— CAS 没抢到就一条都不发（补偿扫描/重连补漏会重复调用平仓流程）
 * 2) 止损止盈报的是"这次平掉的量"，不是仓位总量
 * 3) 全仓爆仓合并成一条，仓位数写进 quantity
 */
class TradeNotificationAnchorTest {

    private FuturesPositionMapper positionMapper;
    private TradeNotificationService tradeNotification;
    private CacheService cacheService;
    private FuturesRiskServiceImpl riskService;
    private CrossLiquidationServiceImpl crossLiquidation;

    @BeforeEach
    void setUp() {
        positionMapper = mock(FuturesPositionMapper.class);
        tradeNotification = mock(TradeNotificationService.class);
        cacheService = mock(CacheService.class);
        FuturesOrderMapper orderMapper = mock(FuturesOrderMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        TradingConfig tradingConfig = mock(TradingConfig.class);
        FuturesPositionIndexService indexService = mock(FuturesPositionIndexService.class);
        CrossMarginService crossMarginService = mock(CrossMarginService.class);

        when(tradingConfig.calculateFuturesCommission(any(), anyBoolean(), anyBoolean())).thenReturn(BigDecimal.ZERO);

        riskService = new FuturesRiskServiceImpl(positionMapper, orderMapper, userMapper, tradingConfig,
                mock(RedisLockUtil.class), cacheService, indexService,
                mock(FuturesLeverageBracketRegistry.class), crossMarginService, tradeNotification);

        crossLiquidation = new CrossLiquidationServiceImpl(crossMarginService, positionMapper, orderMapper,
                tradingConfig, cacheService, indexService, mock(RedisLockUtil.class), tradeNotification);
    }

    private static FuturesPosition isolatedLong() {
        FuturesPosition p = new FuturesPosition();
        p.setId(9L);
        p.setUserId(7L);
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setStatus("OPEN");
        p.setMarginMode(FuturesPosition.ISOLATED);
        p.setEntryPrice(new BigDecimal("100"));
        p.setQuantity(new BigDecimal("0.5"));
        p.setMargin(new BigDecimal("50"));
        p.setLeverage(10);
        return p;
    }

    // ==================== 逐仓强平 ====================

    @Test
    void 逐仓强平发一条通知并带上仓位明细() {
        FuturesPosition pos = isolatedLong();
        when(positionMapper.selectById(9L)).thenReturn(pos);
        when(positionMapper.casClosePosition(eq(9L), anyString(), any(), any())).thenReturn(1);

        riskService.doForceClose(9L, new BigDecimal("90"));

        ArgumentCaptor<BigDecimal> pnl = ArgumentCaptor.forClass(BigDecimal.class);
        verify(tradeNotification).liquidation(eq(pos), eq(new BigDecimal("90")), pnl.capture());
        // LONG 从 100 跌到 90，持 0.5 → (90-100)*0.5 = -5
        assertThat(pnl.getValue()).isEqualByComparingTo("-5");
    }

    @Test
    void CAS没抢到就不发通知_防补偿扫描重复触发() {
        when(positionMapper.selectById(9L)).thenReturn(isolatedLong());
        when(positionMapper.casClosePosition(anyLong(), anyString(), any(), any())).thenReturn(0);  // 已被别的路径平掉

        riskService.doForceClose(9L, new BigDecimal("90"));

        verify(tradeNotification, never()).liquidation(any(), any(), any());
    }

    @Test
    void 仓位已不是OPEN时直接返回不发通知() {
        FuturesPosition closed = isolatedLong();
        closed.setStatus("CLOSED");
        when(positionMapper.selectById(9L)).thenReturn(closed);

        riskService.doForceClose(9L, new BigDecimal("90"));

        verify(tradeNotification, never()).liquidation(any(), any(), any());
    }

    // ==================== 止损触发 ====================

    @Test
    void 止损部分平仓报的是这次平掉的量而非仓位总量() {
        FuturesPosition pos = isolatedLong();                                  // 总量 0.5
        // 必须可变：部分平仓要 removeIf 掉已触发的保护单（真实路径是 MyBatis 反序列化出来的 ArrayList）
        pos.setStopLosses(new ArrayList<>(List.of(
                new FuturesStopLoss("sl1", new BigDecimal("95"), new BigDecimal("0.2")))));
        when(positionMapper.selectById(9L)).thenReturn(pos);
        when(positionMapper.atomicPartialClose(eq(9L), any(), any())).thenReturn(1);

        riskService.doBatchTrigger(9L, List.of("sl1"), new BigDecimal("95"), true);

        ArgumentCaptor<BigDecimal> qty = ArgumentCaptor.forClass(BigDecimal.class);
        verify(tradeNotification).protectiveClose(eq(pos), qty.capture(), eq(new BigDecimal("95")), any(), eq(true));
        assertThat(qty.getValue()).isEqualByComparingTo("0.2");                // 不是 0.5
    }

    @Test
    void 止盈走同一入口但isStopLoss为假() {
        FuturesPosition pos = isolatedLong();
        pos.setTakeProfits(List.of(new com.mawai.wiibcommon.entity.FuturesTakeProfit(
                "tp1", new BigDecimal("110"), new BigDecimal("0.5"))));
        when(positionMapper.selectById(9L)).thenReturn(pos);
        when(positionMapper.casClosePosition(eq(9L), anyString(), any(), any())).thenReturn(1);

        riskService.doBatchTrigger(9L, List.of("tp1"), new BigDecimal("110"), false);

        verify(tradeNotification).protectiveClose(eq(pos), any(), eq(new BigDecimal("110")), any(), eq(false));
    }

    @Test
    void 止损CAS没抢到不发通知() {
        FuturesPosition pos = isolatedLong();
        pos.setStopLosses(List.of(new FuturesStopLoss("sl1", new BigDecimal("95"), new BigDecimal("0.5"))));
        when(positionMapper.selectById(9L)).thenReturn(pos);
        when(positionMapper.casClosePosition(anyLong(), anyString(), any(), any())).thenReturn(0);

        riskService.doBatchTrigger(9L, List.of("sl1"), new BigDecimal("95"), true);

        verify(tradeNotification, never()).protectiveClose(any(), any(), any(), any(), anyBoolean());
    }

    // ==================== 全仓爆仓 ====================

    @Test
    void 全仓爆仓合并成一条且仓位数写进quantity() {
        FuturesPosition a = isolatedLong();
        a.setMarginMode(FuturesPosition.CROSS);
        FuturesPosition b = isolatedLong();
        b.setId(10L);
        b.setSymbol("ETHUSDT");
        b.setMarginMode(FuturesPosition.CROSS);

        when(positionMapper.selectList(any())).thenReturn(List.of(a, b));
        when(cacheService.getMarkPrice(anyString())).thenReturn(new BigDecimal("90"));
        when(positionMapper.casClosePosition(anyLong(), anyString(), any(), any())).thenReturn(1);

        crossLiquidation.liquidateAll(7L);

        ArgumentCaptor<BigDecimal> settle = ArgumentCaptor.forClass(BigDecimal.class);
        verify(tradeNotification).crossLiquidation(eq(7L), eq(2), settle.capture());
        // 两仓各 (90-100)*0.5 = -5，手续费 mock 成 0 → 净结算 -10
        assertThat(settle.getValue()).isEqualByComparingTo("-10");
    }

    @Test
    void 全仓一个都没抢到就不发通知() {
        FuturesPosition a = isolatedLong();
        a.setMarginMode(FuturesPosition.CROSS);
        when(positionMapper.selectList(any())).thenReturn(List.of(a));
        when(cacheService.getMarkPrice(anyString())).thenReturn(new BigDecimal("90"));
        when(positionMapper.casClosePosition(anyLong(), anyString(), any(), any())).thenReturn(0);

        crossLiquidation.liquidateAll(7L);

        verify(tradeNotification, never()).crossLiquidation(anyLong(), anyInt(), any());
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
