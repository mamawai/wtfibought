package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.CrossLiquidationService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FundingRateService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.FairLockRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.locks.Lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 结算侧拿仓位/币种锁都走系统入口：限价单触发后成交、资金费结算，碰上锁被占着就排队等对面放手，
 * 等超时才放弃（限价单交给补扫，资金费这期不收）。资金费每个仓位都在锁内结算，放锁后再做强平复核。
 * <p>
 * 锁桩：tryLockAsSystem 返回 Lock = 等到了，返回 null（不打桩）= 等超时。
 */
class FuturesSettlementLockWaitTest {

    private static final Long UID = 7L;
    private static final String SYMBOL = "BTCUSDT";
    private static final String POS_KEY = "futures:pos:1";
    private static final BigDecimal RATE = new BigDecimal("0.01");

    private UserMapper userMapper;
    private FuturesPositionMapper positionMapper;
    private FuturesOrderMapper orderMapper;
    private CacheService cacheService;
    private FairLockRegistry lockRegistry;
    private Lock posLock;
    private FuturesPositionIndexService positionIndexService;
    private FuturesRiskService riskService;
    private CrossMarginService crossMarginService;
    private CrossLiquidationService crossLiquidationService;
    private FuturesSettlementServiceImpl service;

    @BeforeEach
    void setUp() {
        UserService userService = mock(UserService.class);
        userMapper = mock(UserMapper.class);
        positionMapper = mock(FuturesPositionMapper.class);
        orderMapper = mock(FuturesOrderMapper.class);
        cacheService = mock(CacheService.class);
        lockRegistry = mock(FairLockRegistry.class);
        posLock = mock(Lock.class);
        positionIndexService = mock(FuturesPositionIndexService.class);
        riskService = mock(FuturesRiskService.class);
        crossMarginService = mock(CrossMarginService.class);
        crossLiquidationService = mock(CrossLiquidationService.class);
        FuturesLeverageBracketRegistry bracketRegistry = mock(FuturesLeverageBracketRegistry.class);

        User user = new User();
        user.setId(UID);
        user.setIsBankrupt(false);
        when(userService.getById(UID)).thenReturn(user);
        when(bracketRegistry.getEffectiveMaxLeverage(anyString(), any())).thenReturn(150);
        when(lockRegistry.tryLockAsSystem(POS_KEY)).thenReturn(posLock);
        when(orderMapper.casMarkProcessing(anyLong())).thenReturn(1);
        when(orderMapper.casUpdateToFilled(anyLong(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(positionMapper.atomicIncreasePosition(anyLong(), any(), any(), any())).thenReturn(1);
        when(positionMapper.casClosePosition(anyLong(), anyString(), any(), any())).thenReturn(1);
        when(positionMapper.atomicAddFundingFeeTotal(anyLong(), any())).thenReturn(1);
        when(userMapper.atomicUpdateBalance(anyLong(), any())).thenReturn(new BigDecimal("10000"));
        when(cacheService.getMarkPrice(SYMBOL)).thenReturn(new BigDecimal("100"));

        service = new FuturesSettlementServiceImpl(
                userService, userMapper, positionMapper, orderMapper,
                new TradingConfig(), bracketRegistry, cacheService, positionIndexService,
                riskService, crossMarginService,
                crossLiquidationService, lockRegistry, mock(FundingRateService.class));
        // getAopProxy(this) 拿回自己：单测里没有代理，直接调真实现
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(FuturesSettlementServiceImpl.class)).thenReturn(service);
        new SpringUtils().setApplicationContext(ctx);
    }

    private static FuturesPosition isolatedLong(long id) {
        FuturesPosition p = new FuturesPosition();
        p.setId(id);
        p.setUserId(UID);
        p.setSymbol(SYMBOL);
        p.setSide("LONG");
        p.setMarginMode(FuturesPosition.ISOLATED);
        p.setLeverage(50);
        p.setEntryPrice(new BigDecimal("100"));
        p.setQuantity(BigDecimal.ONE);
        p.setMargin(new BigDecimal("10"));
        p.setStatus("OPEN");
        return p;
    }

    /** TRIGGERED 限价单；commission 空按 maker 处理 → 成交价=挂单价 */
    private static FuturesOrder triggered(String orderSide, Long positionId, String frozen) {
        FuturesOrder o = new FuturesOrder();
        o.setId(100L);
        o.setUserId(UID);
        o.setPositionId(positionId);
        o.setSymbol(SYMBOL);
        o.setOrderSide(orderSide);
        o.setOrderType("LIMIT");
        o.setMarginMode(FuturesPosition.ISOLATED);
        o.setQuantity(BigDecimal.ONE);
        o.setLeverage(50);
        o.setLimitPrice(new BigDecimal("110"));
        if (frozen != null) o.setFrozenAmount(new BigDecimal(frozen));
        o.setStatus("TRIGGERED");
        return o;
    }

    private static boolean amountIs(BigDecimal actual, String expected) {
        return actual != null && actual.compareTo(new BigDecimal(expected)) == 0;
    }

    // ==================== 限价单触发后成交 ====================

    @Test
    void 限价开仓并入_币种锁和仓位锁都走系统入口_等到后成交() {
        Lock symLock = mock(Lock.class);
        when(lockRegistry.tryLockAsSystem("futures:sym:7:BTCUSDT")).thenReturn(symLock);
        when(positionMapper.selectList(any())).thenReturn(List.of(isolatedLong(1L)));
        when(userMapper.atomicDeductFrozenBalance(anyLong(), any())).thenReturn(BigDecimal.ZERO);

        // 110×1/50=2.20 + maker 费 0.02
        service.processTriggeredOrder(triggered("OPEN_LONG", null, "2.22"));

        InOrder inOrder = inOrder(lockRegistry, positionMapper, posLock, symLock);
        inOrder.verify(lockRegistry).tryLockAsSystem("futures:sym:7:BTCUSDT");
        inOrder.verify(lockRegistry).tryLockAsSystem(POS_KEY);
        inOrder.verify(positionMapper).atomicIncreasePosition(eq(1L), any(), any(), any());
        inOrder.verify(posLock).unlock();
        inOrder.verify(symLock).unlock();
        verify(lockRegistry, never()).tryLockAsUser(anyString());
    }

    @Test
    void 限价平仓_仓位锁走系统入口_等到后成交() {
        when(positionMapper.selectById(1L)).thenReturn(isolatedLong(1L));

        service.processTriggeredOrder(triggered("CLOSE_LONG", 1L, null));

        verify(lockRegistry).tryLockAsSystem(POS_KEY);
        verify(positionMapper).casClosePosition(eq(1L), eq("CLOSED"), any(), any());
        verify(posLock).unlock();
    }

    @Test
    void 等锁超时_不成交_单子留给补扫() {
        service.processTriggeredOrder(triggered("OPEN_LONG", null, "2.22"));

        verify(lockRegistry).tryLockAsSystem("futures:sym:7:BTCUSDT");
        verify(orderMapper, never()).casMarkProcessing(anyLong());
    }

    // ==================== 资金费：每种情况都先走系统入口拿仓位锁再收 ====================
    // 名义 100×1，费率 1% → 多头付 1.00、空头收 1.00

    @Test
    void 资金费_收取方_拿锁后入余额() {
        FuturesPosition pos = isolatedLong(1L);
        pos.setSide("SHORT");
        when(positionMapper.selectById(1L)).thenReturn(pos);

        boolean charged = service.chargeFundingFeeOne(pos, RATE);

        assertThat(charged).isTrue();
        InOrder inOrder = inOrder(lockRegistry, userMapper, positionMapper, posLock);
        inOrder.verify(lockRegistry).tryLockAsSystem(POS_KEY);
        inOrder.verify(userMapper).atomicUpdateBalance(eq(UID), argThat(a -> amountIs(a, "1.00")));
        inOrder.verify(positionMapper).atomicAddFundingFeeTotal(eq(1L), argThat(f -> amountIs(f, "-1.00")));
        inOrder.verify(posLock).unlock();
    }

    @Test
    void 资金费_全仓_拿锁后结算_放锁后查账户() {
        FuturesPosition pos = isolatedLong(1L);
        pos.setMarginMode(FuturesPosition.CROSS);
        when(positionMapper.selectById(1L)).thenReturn(pos);

        boolean charged = service.chargeFundingFeeOne(pos, RATE);

        assertThat(charged).isTrue();
        InOrder inOrder = inOrder(lockRegistry, crossMarginService, positionMapper, posLock, crossLiquidationService);
        inOrder.verify(lockRegistry).tryLockAsSystem(POS_KEY);
        inOrder.verify(crossMarginService).settle(eq(UID), argThat(d -> amountIs(d, "-1.00")));
        inOrder.verify(positionMapper).atomicAddFundingFeeTotal(eq(1L), argThat(f -> amountIs(f, "1.00")));
        inOrder.verify(posLock).unlock();
        inOrder.verify(crossLiquidationService).checkUser(UID);
    }

    @Test
    void 资金费_逐仓余额不够_拿锁后从保证金扣() {
        FuturesPosition pos = isolatedLong(1L);
        when(positionMapper.selectById(1L)).thenReturn(pos);
        when(userMapper.atomicUpdateBalance(anyLong(), any())).thenReturn(null);
        when(positionMapper.atomicDeductFundingFee(eq(1L), any())).thenReturn(new BigDecimal("9.00"));

        boolean charged = service.chargeFundingFeeOne(pos, RATE);

        assertThat(charged).isTrue();
        InOrder inOrder = inOrder(lockRegistry, positionMapper, positionIndexService, posLock);
        inOrder.verify(lockRegistry).tryLockAsSystem(POS_KEY);
        inOrder.verify(positionMapper).atomicDeductFundingFee(eq(1L), argThat(f -> amountIs(f, "1.00")));
        inOrder.verify(positionIndexService).updateLiquidationPrice(eq(1L), eq(SYMBOL), eq("LONG"), any());
        inOrder.verify(posLock).unlock();
        verifyNoInteractions(riskService);
    }

    /** 等锁期间用户部分平仓到 0.5、mark 价跳到 200：锁内按 0.5 算，价格仍用结算时刻的 100 */
    @Test
    void 资金费_锁内按最新数量重算_价格用结算时刻的() {
        FuturesPosition snapshot = isolatedLong(1L);
        FuturesPosition latest = isolatedLong(1L);
        latest.setQuantity(new BigDecimal("0.5"));
        when(positionMapper.selectById(1L)).thenReturn(latest);
        when(lockRegistry.tryLockAsSystem(POS_KEY)).thenAnswer(inv -> {
            doReturn(new BigDecimal("200")).when(cacheService).getMarkPrice(SYMBOL);
            return posLock;
        });

        boolean charged = service.chargeFundingFeeOne(snapshot, RATE);

        assertThat(charged).isTrue();
        verify(userMapper).atomicUpdateBalance(eq(UID), argThat(a -> amountIs(a, "-0.50")));
        verify(positionMapper).atomicAddFundingFeeTotal(eq(1L), argThat(f -> amountIs(f, "0.50")));
    }

    @Test
    void 资金费_等锁超时_本期未收() {
        FuturesPosition pos = isolatedLong(1L);
        when(lockRegistry.tryLockAsSystem(POS_KEY)).thenReturn(null);

        boolean charged = service.chargeFundingFeeOne(pos, RATE);

        assertThat(charged).isFalse();
        verify(positionMapper, never()).selectById(anyLong());
        verifyNoInteractions(userMapper, crossMarginService, riskService);
        verify(positionMapper, never()).atomicAddFundingFeeTotal(anyLong(), any());
    }

    @Test
    void 资金费_等锁期间仓位被平_不收() {
        FuturesPosition snapshot = isolatedLong(1L);
        FuturesPosition closed = isolatedLong(1L);
        closed.setStatus("CLOSED");
        when(positionMapper.selectById(1L)).thenReturn(closed);

        boolean charged = service.chargeFundingFeeOne(snapshot, RATE);

        assertThat(charged).isFalse();
        verifyNoInteractions(userMapper, riskService);
        verify(positionMapper, never()).atomicAddFundingFeeTotal(anyLong(), any());
        verify(posLock).unlock();
    }

    /** 保证金 0.5 不够 1.00：扣光，放锁后再做强平复核 */
    @Test
    void 资金费_保证金扣光_放锁后再做强平复核() {
        FuturesPosition pos = isolatedLong(1L);
        pos.setMargin(new BigDecimal("0.5"));
        when(positionMapper.selectById(1L)).thenReturn(pos);
        when(userMapper.atomicUpdateBalance(anyLong(), any())).thenReturn(null);
        when(positionMapper.atomicDeductFundingFee(anyLong(), any())).thenReturn(null);
        when(positionMapper.selectMarginForUpdate(1L)).thenReturn(new BigDecimal("0.5"));
        when(positionMapper.atomicDeductFundingFeePartial(1L)).thenReturn(BigDecimal.ZERO);

        boolean charged = service.chargeFundingFeeOne(pos, RATE);

        assertThat(charged).isTrue();
        InOrder inOrder = inOrder(lockRegistry, positionMapper, posLock, riskService);
        inOrder.verify(lockRegistry).tryLockAsSystem(POS_KEY);
        inOrder.verify(positionMapper).atomicDeductFundingFeePartial(1L);
        inOrder.verify(posLock).unlock();
        inOrder.verify(riskService).checkAndLiquidate(eq(1L), any());
    }
}
