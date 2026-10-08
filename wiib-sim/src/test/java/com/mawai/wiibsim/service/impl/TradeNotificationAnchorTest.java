package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.ledger.LedgerTx;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.TradeNotificationService;
import com.mawai.wiibsim.util.FairLockRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private CrossMarginService crossMarginService;
    private FuturesRiskServiceImpl riskService;
    private CrossLiquidationServiceImpl crossLiquidation;

    /**
     * 裸 mock 单测没有 Spring/MyBatis 上下文，MyBatis-Plus 的 TableInfo 缓存是空的。
     * LambdaQueryWrapper 的 .eq() 靠 lambda 反射就能拿到字段名，缺缓存照样过；
     * .in()/.notLikeRight() 要查 ColumnCache，缺了直接抛 "can not find lambda cache"。
     * 爆仓撤单 cancelCrossOpenOrders 用的正是 .in()，所以这里把 FuturesOrder 的表信息补上。
     */
    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FuturesOrder.class);
    }

    @BeforeEach
    void setUp() {
        positionMapper = mock(FuturesPositionMapper.class);
        tradeNotification = mock(TradeNotificationService.class);
        cacheService = mock(CacheService.class);
        FuturesOrderMapper orderMapper = mock(FuturesOrderMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        TradingConfig tradingConfig = mock(TradingConfig.class);
        FuturesPositionIndexService indexService = mock(FuturesPositionIndexService.class);
        crossMarginService = mock(CrossMarginService.class);
        FuturesLeverageBracketRegistry bracketRegistry = mock(FuturesLeverageBracketRegistry.class);

        when(tradingConfig.calculateFuturesCommission(any(), anyBoolean(), anyBoolean())).thenReturn(BigDecimal.ZERO);
        // 强平复核一律判满足（维持保证金给到 50 ≥ 50−5），这里只验通知锚点
        when(bracketRegistry.calcMaintenanceMargin(anyString(), any())).thenReturn(new BigDecimal("50"));

        TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
        riskService = new FuturesRiskServiceImpl(positionMapper, orderMapper, userMapper, tradingConfig,
                mock(FairLockRegistry.class), cacheService, indexService,
                bracketRegistry, crossMarginService, tradeNotification, tx, new LedgerTx(tx));

        crossLiquidation = new CrossLiquidationServiceImpl(crossMarginService, positionMapper, orderMapper,
                tradingConfig, cacheService, indexService, mock(FairLockRegistry.class),
                tradeNotification, new CrossBandRegistry(), riskService, tx);
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

    /** 锁内重判用的快照：净值 −10 ≤ 维持保证金 0 → 判爆 */
    private static CrossMarginService.CrossAccount liquidatable(FuturesPosition... positions) {
        return new CrossMarginService.CrossAccount(BigDecimal.ZERO, new BigDecimal("-10"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, List.of(positions), Map.of());
    }

    @Test
    void 全仓爆仓合并成一条且仓位数写进quantity() {
        FuturesPosition a = isolatedLong();
        a.setMarginMode(FuturesPosition.CROSS);
        FuturesPosition b = isolatedLong();
        b.setId(10L);
        b.setSymbol("ETHUSDT");
        b.setMarginMode(FuturesPosition.CROSS);

        when(crossMarginService.snapshot(7L, null, null)).thenReturn(liquidatable(a, b));
        when(positionMapper.selectById(9L)).thenReturn(a);
        when(positionMapper.selectById(10L)).thenReturn(b);
        when(cacheService.getMarkPrice(anyString())).thenReturn(new BigDecimal("90"));
        when(positionMapper.casClosePosition(anyLong(), anyString(), any(), any())).thenReturn(1);

        crossLiquidation.liquidateAll(7L, null, null, List.of(9L, 10L));

        ArgumentCaptor<BigDecimal> settle = ArgumentCaptor.forClass(BigDecimal.class);
        verify(tradeNotification).crossLiquidation(eq(7L), eq(2), settle.capture());
        // 两仓各 (90-100)*0.5 = -5，手续费 mock 成 0 → 净结算 -10
        assertThat(settle.getValue()).isEqualByComparingTo("-10");
    }

    @Test
    void 全仓一个都没抢到就不发通知() {
        FuturesPosition a = isolatedLong();
        a.setMarginMode(FuturesPosition.CROSS);
        when(crossMarginService.snapshot(7L, null, null)).thenReturn(liquidatable(a));
        when(positionMapper.selectById(9L)).thenReturn(a);
        when(cacheService.getMarkPrice(anyString())).thenReturn(new BigDecimal("90"));
        when(positionMapper.casClosePosition(anyLong(), anyString(), any(), any())).thenReturn(0);

        crossLiquidation.liquidateAll(7L, null, null, List.of(9L));

        verify(tradeNotification, never()).crossLiquidation(anyLong(), anyInt(), any());
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
