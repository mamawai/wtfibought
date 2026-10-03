package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.CrossMarginService.CrossAccount;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.TradeNotificationService;
import com.mawai.wiibsim.util.FairLockRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 全仓：同一秒止损和强平同时命中，价格先触及的先执行，都在用户级锁内决定。
 * 判断方式：钉在止损价看账户可不可爆——不可爆 = 止损先到，先执行；可爆 = 强平点先到，先按触发价判强平。
 * 例：余额 1000，全仓多 1 BTC@100000，账户强平点约 99397，这一秒 mark 到 99300。
 */
class CrossLiquidationTouchOrderTest {

    private static final Long UID = 7L;
    private static final String SYM = "BTCUSDT";
    private static final String ETH = "ETHUSDT";
    private static final BigDecimal PIN = new BigDecimal("99300");

    private CrossMarginService crossMargin;
    private FuturesPositionMapper positionMapper;
    private FairLockRegistry lockRegistry;
    private FuturesRiskService riskService;
    private CrossLiquidationServiceImpl service;

    /** 爆仓撤单那句 LambdaQueryWrapper 用了 .in()/.notLikeRight()，裸 mock 下要先补 FuturesOrder 的表信息 */
    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FuturesOrder.class);
    }

    @BeforeEach
    void setUp() {
        crossMargin = mock(CrossMarginService.class);
        positionMapper = mock(FuturesPositionMapper.class);
        lockRegistry = mock(FairLockRegistry.class);
        riskService = mock(FuturesRiskService.class);

        when(lockRegistry.tryLockAsSystem(anyString())).thenReturn(mock(Lock.class));
        when(positionMapper.casClosePosition(anyLong(), anyString(), any(), any())).thenReturn(1);

        service = new CrossLiquidationServiceImpl(crossMargin, positionMapper, mock(FuturesOrderMapper.class),
                new TradingConfig(), mock(CacheService.class), mock(FuturesPositionIndexService.class),
                lockRegistry, mock(TradeNotificationService.class), new CrossBandRegistry(),
                riskService);

        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(CrossLiquidationServiceImpl.class)).thenReturn(service);
        new SpringUtils().setApplicationContext(ctx);
    }

    /** 全仓多 qty BTC@100000、100x，带止损档 */
    private static FuturesPosition btcLong(String qty, FuturesStopLoss... sls) {
        FuturesPosition p = new FuturesPosition();
        p.setId(1L);
        p.setUserId(UID);
        p.setSymbol(SYM);
        p.setSide("LONG");
        p.setMarginMode(FuturesPosition.CROSS);
        p.setLeverage(100);
        p.setQuantity(new BigDecimal(qty));
        p.setEntryPrice(new BigDecimal("100000"));
        p.setMargin(new BigDecimal(qty).multiply(new BigDecimal("1000")));
        p.setStatus("OPEN");
        p.setStopLosses(new ArrayList<>(List.of(sls)));
        return p;
    }

    private static FuturesStopLoss sl(String id, String price, String qty) {
        return new FuturesStopLoss(id, new BigDecimal(price), new BigDecimal(qty));
    }

    /** 余额 1000、1 BTC，按 price 估值：浮亏 price−100000，维持保证金 price×0.4% */
    private static CrossAccount oneBtcAt(String price) {
        BigDecimal p = new BigDecimal(price);
        return new CrossAccount(new BigDecimal("1000"), p.subtract(new BigDecimal("100000")), new BigDecimal("1000"),
                BigDecimal.ZERO, p.multiply(new BigDecimal("0.004")), List.of(btcLong("1")), Map.of(SYM, p));
    }

    /** 止损 0.5 个按 99300 成交后：余额 630.14、剩 0.5 个 → 净值 280.14 > 维持保证金 198.6，不爆 */
    private static CrossAccount halfBtcAfterStopLoss() {
        return new CrossAccount(new BigDecimal("630.14"), new BigDecimal("-350"), new BigDecimal("500"),
                BigDecimal.ZERO, new BigDecimal("198.6"), List.of(btcLong("0.5")), Map.of(SYM, PIN));
    }

    /** 不关心具体数的账户：净值 100，维持保证金 100（可爆）或 0（不可爆） */
    private static CrossAccount account(boolean liquidatable, Map<String, BigDecimal> prices, FuturesPosition... positions) {
        return new CrossAccount(new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                liquidatable ? new BigDecimal("100") : BigDecimal.ZERO, List.of(positions), prices);
    }

    // ==================== 账户级检查（tick / 别的币 / 巡检 / 资金费都走 checkUser） ====================

    @Test
    void 强平点先到但按触发价重判不该爆_接着执行剩下的止损() {
        when(positionMapper.selectList(any())).thenReturn(List.of(btcLong("1", sl("sl1", "99350", "0.5"))));
        // 锁内按触发价重判时账户已经回到安全线以上
        when(crossMargin.snapshot(UID, SYM, PIN)).thenReturn(oneBtcAt("99300"), halfBtcAfterStopLoss());
        when(crossMargin.snapshot(UID, SYM, new BigDecimal("99350"))).thenReturn(oneBtcAt("99350"));

        service.checkUser(UID, SYM, PIN);

        InOrder inOrder = inOrder(lockRegistry, riskService);
        inOrder.verify(lockRegistry).tryLockAsSystem("futures:pos:1");
        inOrder.verify(riskService).batchTriggerStopLoss(1L, List.of("sl1"), PIN);
        verify(positionMapper, never()).casClosePosition(anyLong(), anyString(), any(), any());
    }

    @Test
    void 钉在止损价都不可爆_按触及先后逐个先止损_再按触发价判强平_没爆() {
        // 库里顺序故意倒着放：99450 在前、99500 在后；钉在 99500 净值 500 > 398，钉在 99450 净值 450 > 397.8
        when(positionMapper.selectList(any())).thenReturn(List.of(
                btcLong("1", sl("sl2", "99450", "0.3"), sl("sl1", "99500", "0.3"))));
        when(crossMargin.snapshot(UID, SYM, PIN)).thenReturn(oneBtcAt("99300"), halfBtcAfterStopLoss());
        when(crossMargin.snapshot(UID, SYM, new BigDecimal("99500"))).thenReturn(oneBtcAt("99500"));
        when(crossMargin.snapshot(UID, SYM, new BigDecimal("99450"))).thenReturn(oneBtcAt("99450"));

        service.checkUser(UID, SYM, PIN);

        // 止损按这一秒的触发价成交
        InOrder inOrder = inOrder(crossMargin, riskService, lockRegistry);
        inOrder.verify(crossMargin).snapshot(UID, SYM, new BigDecimal("99500"));
        inOrder.verify(riskService).batchTriggerStopLoss(1L, List.of("sl1"), PIN);
        inOrder.verify(crossMargin).snapshot(UID, SYM, new BigDecimal("99450"));
        inOrder.verify(riskService).batchTriggerStopLoss(1L, List.of("sl2"), PIN);
        inOrder.verify(lockRegistry).tryLockAsSystem("futures:pos:1");
        inOrder.verify(crossMargin).snapshot(UID, SYM, PIN);
        verify(positionMapper, never()).casClosePosition(anyLong(), anyString(), any(), any());
    }

    @Test
    void 空头往上命中_价低的先_没碰到的止损不动() {
        FuturesPosition shortPos = btcLong("1", sl("sl1", "100500", "0.5"), sl("sl2", "100900", "0.5"));
        shortPos.setSide("SHORT");
        BigDecimal pin = new BigDecimal("100700");
        when(positionMapper.selectList(any())).thenReturn(List.of(shortPos));
        when(crossMargin.snapshot(UID, SYM, pin)).thenReturn(
                account(true, Map.of(SYM, pin), shortPos), account(false, Map.of(SYM, pin), shortPos));
        when(crossMargin.snapshot(UID, SYM, new BigDecimal("100500"))).thenReturn(account(false, Map.of(SYM, pin), shortPos));

        service.checkUser(UID, SYM, pin);

        verify(riskService).batchTriggerStopLoss(1L, List.of("sl1"), pin);
        verify(riskService, times(1)).batchTriggerStopLoss(anyLong(), any(), any());
    }

    @Test
    void 兜底巡检无钉价_按快照所用价判命中和成交() {
        when(positionMapper.selectList(any())).thenReturn(List.of(btcLong("1", sl("sl1", "99500", "0.5"))));
        // 快照用的是缓存价 99300
        when(crossMargin.snapshot(UID, null, null)).thenReturn(oneBtcAt("99300"), halfBtcAfterStopLoss());
        when(crossMargin.snapshot(UID, SYM, new BigDecimal("99500"))).thenReturn(oneBtcAt("99500"));

        service.checkUser(UID);

        verify(riskService).batchTriggerStopLoss(1L, List.of("sl1"), PIN);
        verify(positionMapper, never()).casClosePosition(anyLong(), anyString(), any(), any());
    }

    @Test
    void 多币种命中_按离当前价的相对距离排先后() {
        // BTC 止损 99500 离 99300 约 0.2%，ETH 止损 3030 离 3000 是 1%：ETH 那档更早被碰到
        FuturesPosition btc = btcLong("1", sl("btcSl", "99500", "0.5"));
        FuturesPosition eth = btcLong("10", sl("ethSl", "3030", "5"));
        eth.setId(2L);
        eth.setSymbol(ETH);
        Map<String, BigDecimal> prices = Map.of(SYM, PIN, ETH, new BigDecimal("3000"));
        when(positionMapper.selectList(any())).thenReturn(List.of(btc, eth));
        when(crossMargin.snapshot(UID, SYM, PIN)).thenReturn(
                account(true, prices, btc, eth), account(false, prices, btc, eth));
        when(crossMargin.snapshot(eq(UID), eq(ETH), any())).thenReturn(account(false, prices, btc, eth));
        when(crossMargin.snapshot(UID, SYM, new BigDecimal("99500"))).thenReturn(account(false, prices, btc, eth));

        service.checkUser(UID, SYM, PIN);

        InOrder inOrder = inOrder(riskService);
        inOrder.verify(riskService).batchTriggerStopLoss(2L, List.of("ethSl"), new BigDecimal("3000"));
        inOrder.verify(riskService).batchTriggerStopLoss(1L, List.of("btcSl"), PIN);
    }

    @Test
    void 账户不可爆_不读止损列表_不动止损() {
        when(crossMargin.snapshot(UID, SYM, PIN)).thenReturn(oneBtcAt("99500"));

        service.checkUser(UID, SYM, PIN);

        verify(positionMapper, never()).selectList(any());
        verifyNoInteractions(riskService);
    }

    // ==================== 仓位级止损转进来（triggerStopLoss） ====================

    @Test
    void 止损转进来_账户不可爆_在用户级锁内直接执行这批止损() {
        Lock userLock = mock(Lock.class);
        when(lockRegistry.tryLockAsSystem("futures:cross:liq:" + UID)).thenReturn(userLock);
        when(crossMargin.snapshot(UID, SYM, PIN)).thenReturn(oneBtcAt("99500"));

        service.triggerStopLoss(btcLong("1"), List.of("sl1", "sl2"), PIN);

        InOrder inOrder = inOrder(lockRegistry, riskService, userLock);
        inOrder.verify(lockRegistry).tryLockAsSystem("futures:cross:liq:" + UID);
        inOrder.verify(riskService).batchTriggerStopLoss(1L, List.of("sl1", "sl2"), PIN);
        inOrder.verify(userLock).unlock();
        verify(positionMapper, never()).selectList(any());
    }

    @Test
    void 止损转进来_钉在止损价已可爆_先判强平_爆了止损作废() {
        // 止损在 99350，比强平点 99397 还远：钉在 99350 净值 350 ≤ 397.4
        when(positionMapper.selectList(any())).thenReturn(List.of(btcLong("1", sl("sl1", "99350", "0.5"))));
        when(crossMargin.snapshot(UID, SYM, PIN)).thenReturn(oneBtcAt("99300"));
        when(crossMargin.snapshot(UID, SYM, new BigDecimal("99350"))).thenReturn(oneBtcAt("99350"));
        when(positionMapper.selectById(1L)).thenReturn(btcLong("1"));

        service.triggerStopLoss(btcLong("1"), List.of("sl1"), PIN);

        verify(positionMapper).casClosePosition(eq(1L), eq("LIQUIDATED"), eq(PIN), any());
        verify(riskService, never()).batchTriggerStopLoss(anyLong(), any(), any());
    }

    @Test
    void 止损转进来_用户级锁等不到_抛处理中() {
        when(lockRegistry.tryLockAsSystem("futures:cross:liq:" + UID)).thenReturn(null);

        assertThatThrownBy(() -> service.triggerStopLoss(btcLong("1"), List.of("sl1"), PIN))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.ORDER_PROCESSING.getCode());
        verifyNoInteractions(crossMargin, riskService);
    }

    @Test
    void 强平点先到但仓位锁等不到_停下不执行后面的止损_用户锁照常释放() {
        Lock userLock = mock(Lock.class);
        when(lockRegistry.tryLockAsSystem("futures:cross:liq:" + UID)).thenReturn(userLock);
        when(positionMapper.selectList(any())).thenReturn(List.of(btcLong("1", sl("sl1", "99350", "0.5"))));
        when(crossMargin.snapshot(UID, SYM, PIN)).thenReturn(oneBtcAt("99300"));
        when(crossMargin.snapshot(UID, SYM, new BigDecimal("99350"))).thenReturn(oneBtcAt("99350"));
        when(lockRegistry.tryLockAsSystem("futures:pos:1")).thenReturn(null);

        assertThatThrownBy(() -> service.triggerStopLoss(btcLong("1"), List.of("sl1"), PIN))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.ORDER_PROCESSING.getCode());
        verify(riskService, never()).batchTriggerStopLoss(anyLong(), any(), any());
        verify(userLock).unlock();
    }
}
