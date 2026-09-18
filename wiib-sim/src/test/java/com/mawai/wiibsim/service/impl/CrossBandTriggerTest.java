package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.CrossMarginService.CrossAccount;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.TradeNotificationService;
import com.mawai.wiibsim.util.RedisLockUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 全仓 tick 触发链路：安全带免检、出带必查、插针价钉住、变动作废、锁后复检。
 * 硬约束是"不漏过任何插针"：任何出带 tick 都必须触发精查，且判定/结算用触发那一刻的价格。
 */
class CrossBandTriggerTest {

    private static final Long UID = 7L;
    private static final String SYM = "BTCUSDT";

    private CrossMarginService crossMargin;
    private FuturesPositionMapper positionMapper;
    private FuturesOrderMapper orderMapper;
    private CacheService cacheService;
    private RedisLockUtil redisLockUtil;
    private CrossBandRegistry registry;
    private CrossLiquidationServiceImpl service;

    @BeforeEach
    void setUp() {
        crossMargin = mock(CrossMarginService.class);
        positionMapper = mock(FuturesPositionMapper.class);
        orderMapper = mock(FuturesOrderMapper.class);
        cacheService = mock(CacheService.class);
        redisLockUtil = mock(RedisLockUtil.class);
        registry = new CrossBandRegistry();

        when(redisLockUtil.tryLock(anyString(), anyLong())).thenReturn("v");
        when(redisLockUtil.tryLockWithWait(anyString(), anyLong(), anyLong())).thenReturn("v");
        when(crossMargin.usersOnSymbol(SYM)).thenReturn(Set.of("7"));

        service = new CrossLiquidationServiceImpl(crossMargin, positionMapper, orderMapper,
                new TradingConfig(), cacheService, mock(FuturesPositionIndexService.class),
                redisLockUtil, mock(TradeNotificationService.class), registry);
    }

    /** LONG 20@100、20x、占用100 */
    private static FuturesPosition position() {
        FuturesPosition p = new FuturesPosition();
        p.setId(1L);
        p.setUserId(UID);
        p.setSymbol(SYM);
        p.setSide("LONG");
        p.setMarginMode(FuturesPosition.CROSS);
        p.setLeverage(20);
        p.setQuantity(new BigDecimal("20"));
        p.setEntryPrice(new BigDecimal("100"));
        p.setMargin(new BigDecimal("100"));
        p.setStatus("OPEN");
        return p;
    }

    /** 健康账户：balance 1000、浮盈0、mm 10 → 半宽 0.7×min(990,(100−10)/2)/2000 = 1.575% → 带 [98.425,101.575] */
    private static CrossAccount healthyAt(String refPrice) {
        return new CrossAccount(new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("100"),
                BigDecimal.ZERO, new BigDecimal("10"), List.of(position()),
                Map.of(SYM, new BigDecimal(refPrice)));
    }

    /** 插针到50：upnl −1000 → equity 0 ≤ mm 10 → 爆 */
    private static CrossAccount liquidatableAt50() {
        return new CrossAccount(new BigDecimal("1000"), new BigDecimal("-1000"), new BigDecimal("100"),
                BigDecimal.ZERO, new BigDecimal("10"), List.of(position()),
                Map.of(SYM, new BigDecimal("50")));
    }

    /** tick@100 触发首次精查并等带建好 */
    private void primeBandAt100() {
        when(crossMargin.snapshot(eq(UID), eq(SYM), any())).thenReturn(healthyAt("100"));
        service.onPriceTick(SYM, new BigDecimal("100"));
        waitUntil(() -> !registry.shouldCheck(UID, SYM, 100.0));
    }

    private static void waitUntil(BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + 3000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("等待超时：条件未达成");
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    void 带内tick免检_不再读库() throws Exception {
        primeBandAt100();
        Thread.sleep(1000); // 越过任何时间型去重窗口：跳过必须是带的裁决，不是时间巧合
        service.onPriceTick(SYM, new BigDecimal("101")); // 带内
        Thread.sleep(300);
        verify(crossMargin, times(1)).snapshot(eq(UID), any(), any());
    }

    @Test
    void 出带tick立即精查_不受时间去重压制() {
        primeBandAt100();
        // 距上次精查远不足900ms——插针不等人，任何时间窗去重都不许吞掉出带触发
        service.onPriceTick(SYM, new BigDecimal("103"));
        verify(crossMargin, timeout(2000).times(2)).snapshot(eq(UID), any(), any());
    }

    @Test
    void 插针价钉住判定与结算_缓存回落价不算数() {
        primeBandAt100();

        // 插针到50（出带且该价下已爆）；缓存价已回落到100
        when(crossMargin.snapshot(UID, SYM, new BigDecimal("50"))).thenReturn(liquidatableAt50());
        when(cacheService.getMarkPrice(SYM)).thenReturn(new BigDecimal("100"));

        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(CrossLiquidationServiceImpl.class)).thenReturn(service);
        new SpringUtils().setApplicationContext(ctx);

        when(positionMapper.selectList(any())).thenReturn(List.of(position()));
        when(positionMapper.casClosePosition(anyLong(), anyString(), any(), any())).thenReturn(1);
        when(orderMapper.selectList(any())).thenReturn(List.of());

        service.onPriceTick(SYM, new BigDecimal("50"));

        // 结算价必须是插针那一刻的50，不是回落后的缓存价100
        verify(positionMapper, timeout(2000)).casClosePosition(eq(1L), eq("LIQUIDATED"),
                eq(new BigDecimal("50")), any());
    }

    @Test
    void 资金变动作废带_带内价也必须重查() {
        primeBandAt100();
        registry.bump(UID); // 模拟 refreshUserIndex 汇合点触发的作废
        service.onPriceTick(SYM, new BigDecimal("100")); // 价格没动，但带已作废
        verify(crossMargin, timeout(2000).times(2)).snapshot(eq(UID), any(), any());
    }

    @Test
    void 锁后复检_排队线程发现新鲜带且钉价带内_不读库() {
        registry.put(UID, registry.epoch(UID), Map.of(SYM, new double[]{98.425, 101.575}));
        // 模拟排队拿到锁的线程：前一个精查已重建带且钉价在带内 → 数学安全，免掉快照
        service.checkUser(UID, SYM, new BigDecimal("100"));
        verify(crossMargin, never()).snapshot(anyLong(), any(), any());
    }

    // ==================== 空窗补漏 ====================

    private static final long T0 = 1700000000000L;

    /** 前一根插到 50，后一根只到 99：插针只在前一根里 */
    private static final List<KlineBar> BARS = List.of(
            bar(T0, T0 + 59_999L, "100", "50"),
            bar(T0 + 60_000L, T0 + 119_999L, "101", "99"));

    private static KlineBar bar(long openTime, long closeTime, String high, String low) {
        return new KlineBar(openTime, closeTime, new BigDecimal(low), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(high), BigDecimal.ONE);
    }

    private static FuturesPosition positionOpenedAt(long ms) {
        FuturesPosition p = position();
        p.setCreatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()));
        return p;
    }

    /** 精查跑完就清带（无持仓），免得第一个钉价建的带把第二个吞掉 */
    private static CrossAccount emptyAccount() {
        return new CrossAccount(new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, List.of(), Map.of());
    }

    @Test
    void 补漏_仓位开在区间之前_低高两端各精查一次() {
        when(positionMapper.selectList(any())).thenReturn(List.of(positionOpenedAt(T0)));
        when(crossMargin.snapshot(eq(UID), eq(SYM), any())).thenReturn(emptyAccount());

        service.recoverGap(SYM, BARS);

        verify(crossMargin, timeout(2000)).snapshot(UID, SYM, new BigDecimal("50"));
        verify(crossMargin, timeout(2000)).snapshot(UID, SYM, new BigDecimal("101"));
    }

    @Test
    void 补漏_仓位开在区间之后_不精查() {
        when(positionMapper.selectList(any())).thenReturn(List.of(positionOpenedAt(T0 + 119_999L)));

        service.recoverGap(SYM, BARS);

        verify(crossMargin, never()).snapshot(anyLong(), any(), any());
    }

    @Test
    void 无持仓清理_带一并移除() {
        registry.put(UID, registry.epoch(UID), Map.of(SYM, new double[]{98.425, 101.575}));
        when(crossMargin.snapshot(eq(UID), isNull(), isNull())).thenReturn(new CrossAccount(
                new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, List.of(), Map.of()));

        service.checkUser(UID); // 兜底直调无钉价：永远真查

        verify(crossMargin).refreshUserIndex(UID);
        assertThat(registry.shouldCheck(UID, SYM, 100.0)).isTrue(); // 带已移除
    }
}
