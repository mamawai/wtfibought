package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.FuturesAddMarginRequest;
import com.mawai.wiibcommon.dto.FuturesAdjustLeverageRequest;
import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesReduceMarginRequest;
import com.mawai.wiibcommon.dto.FuturesStopLossRequest;
import com.mawai.wiibcommon.dto.FuturesTakeProfitRequest;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.ledger.LedgerTx;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.TradeNotificationService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.FairLockRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 合约仓位/币种锁的两类入口：
 * 用户操作（开平仓、加减保证金、调杠杆、改止损止盈）走 tryLockAsUser，拿不到报"处理中"、不碰库；
 * 系统操作（强平、强平复核、止损止盈触发）走 tryLockAsSystem，等到锁后按库里最新仓位复核、按触发价成交。
 * <p>
 * 锁桩不打 = 返回 null：用户入口即"锁被占/有系统在排队"，系统入口即"等超时"。
 */
class FuturesLockEntryTest {

    private static final Long UID = 7L;
    private static final String SYM = "BTCUSDT";
    private static final String POS_KEY = "futures:pos:1";
    private static final String SYM_KEY = "futures:sym:7:BTCUSDT";

    private FuturesPositionMapper positionMapper;
    private FuturesOrderMapper orderMapper;
    private CacheService cacheService;
    private FairLockRegistry lockRegistry;
    private Lock posLock;
    private FuturesTradingServiceImpl trading;
    private FuturesRiskServiceImpl risk;

    @BeforeEach
    void setUp() {
        UserMapper userMapper = mock(UserMapper.class);
        positionMapper = mock(FuturesPositionMapper.class);
        orderMapper = mock(FuturesOrderMapper.class);
        cacheService = mock(CacheService.class);
        lockRegistry = mock(FairLockRegistry.class);
        posLock = mock(Lock.class);
        FuturesPositionIndexService indexService = mock(FuturesPositionIndexService.class);
        CrossMarginService crossMargin = mock(CrossMarginService.class);
        FuturesLeverageBracketRegistry brackets = mock(FuturesLeverageBracketRegistry.class);
        TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
        LedgerTx ledgerTx = new LedgerTx(tx);

        trading = new FuturesTradingServiceImpl(
                mock(UserService.class), userMapper, positionMapper, orderMapper,
                new TradingConfig(), lockRegistry, cacheService,
                indexService, brackets, crossMargin,
                new TradeFilterRegistry(mock(BinanceRestClient.class)), new MessageCatalog(), ledgerTx);
        risk = new FuturesRiskServiceImpl(positionMapper, orderMapper, userMapper,
                new TradingConfig(), lockRegistry, cacheService, indexService, brackets,
                crossMargin, mock(TradeNotificationService.class), tx, ledgerTx);
    }

    /** 逐仓多 1 张 @100，保证金 10 */
    private static FuturesPosition position() {
        FuturesPosition p = new FuturesPosition();
        p.setId(1L);
        p.setUserId(UID);
        p.setSymbol(SYM);
        p.setSide("LONG");
        p.setMarginMode(FuturesPosition.ISOLATED);
        p.setLeverage(10);
        p.setQuantity(BigDecimal.ONE);
        p.setEntryPrice(new BigDecimal("100"));
        p.setMargin(new BigDecimal("10"));
        p.setStatus("OPEN");
        return p;
    }

    private static FuturesOpenRequest marketLong() {
        FuturesOpenRequest req = new FuturesOpenRequest();
        req.setSymbol(SYM);
        req.setSide("LONG");
        req.setQuantity(BigDecimal.ONE);
        req.setLeverage(10);
        req.setOrderType("MARKET");
        return req;
    }

    private static void assertProcessing(Executable call) {
        assertThatThrownBy(call::execute)
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.ORDER_PROCESSING.getCode());
    }

    // ==================== 用户入口：拿不到就报处理中 ====================

    @Test
    void 开仓_币种锁拿不到_报处理中_不碰库() {
        assertProcessing(() -> trading.openPosition(UID, marketLong()));

        verify(lockRegistry).tryLockAsUser(SYM_KEY);
        verify(lockRegistry, never()).tryLockAsSystem(anyString());
        verifyNoInteractions(positionMapper);
    }

    @Test
    void 开仓并入_仓位锁拿不到_报处理中并放掉币种锁() {
        Lock symLock = mock(Lock.class);
        when(lockRegistry.tryLockAsUser(SYM_KEY)).thenReturn(symLock);
        when(positionMapper.selectList(any())).thenReturn(List.of(position()));

        assertProcessing(() -> trading.openPosition(UID, marketLong()));

        verify(lockRegistry).tryLockAsUser(POS_KEY);
        verify(symLock).unlock();
    }

    @Test
    void 平仓_加减保证金_仓位锁拿不到_都报处理中() {
        FuturesCloseRequest close = new FuturesCloseRequest();
        close.setPositionId(1L);
        close.setOrderType("MARKET");
        FuturesAddMarginRequest add = new FuturesAddMarginRequest();
        add.setPositionId(1L);
        add.setAmount(BigDecimal.ONE);
        FuturesReduceMarginRequest reduce = new FuturesReduceMarginRequest();
        reduce.setPositionId(1L);
        reduce.setAmount(BigDecimal.ONE);

        assertProcessing(() -> trading.closePosition(UID, close));
        assertProcessing(() -> trading.addMargin(UID, add));
        assertProcessing(() -> trading.reduceMargin(UID, reduce));

        verify(lockRegistry, times(3)).tryLockAsUser(POS_KEY);
        verify(lockRegistry, never()).tryLockAsSystem(anyString());
        verifyNoInteractions(positionMapper);
    }

    @Test
    void 调杠杆_其中一张仓位锁拿不到_报处理中并放掉已拿的锁() {
        FuturesPosition second = position();
        second.setId(2L);
        second.setSide("SHORT");
        Lock symLock = mock(Lock.class);
        when(lockRegistry.tryLockAsUser(SYM_KEY)).thenReturn(symLock);
        when(lockRegistry.tryLockAsUser(POS_KEY)).thenReturn(posLock);
        when(positionMapper.selectList(any())).thenReturn(List.of(position(), second));
        FuturesAdjustLeverageRequest req = new FuturesAdjustLeverageRequest();
        req.setSymbol(SYM);
        req.setLeverage(20);

        assertProcessing(() -> trading.adjustLeverage(UID, req));

        verify(lockRegistry).tryLockAsUser("futures:pos:2");
        InOrder inOrder = inOrder(posLock, symLock);
        inOrder.verify(posLock).unlock();
        inOrder.verify(symLock).unlock();
        verify(positionMapper, never()).updateLeverageAndMargin(anyLong(), anyInt(), any());
    }

    @Test
    void 改止损止盈_仓位锁拿不到_报处理中() {
        FuturesStopLossRequest sl = new FuturesStopLossRequest();
        sl.setPositionId(1L);
        FuturesTakeProfitRequest tp = new FuturesTakeProfitRequest();
        tp.setPositionId(1L);

        assertProcessing(() -> risk.setStopLoss(UID, sl));
        assertProcessing(() -> risk.setTakeProfit(UID, tp));

        verify(lockRegistry, times(2)).tryLockAsUser(POS_KEY);
        verifyNoInteractions(positionMapper);
    }

    // ==================== 系统入口：排队等锁，等超时按失败处理 ====================

    @Test
    void 强平_止损_止盈_等锁超时_抛处理中交给索引回填() {
        assertProcessing(() -> risk.forceClose(1L, new BigDecimal("90")));
        assertProcessing(() -> risk.batchTriggerStopLoss(1L, List.of("sl1"), new BigDecimal("95")));
        assertProcessing(() -> risk.batchTriggerTakeProfit(1L, List.of("tp1"), new BigDecimal("110")));

        verify(lockRegistry, times(3)).tryLockAsSystem(POS_KEY);
        verify(lockRegistry, never()).tryLockAsUser(anyString());
        verifyNoInteractions(positionMapper);
    }

    @Test
    void 强平复核_等锁超时_本次不查() {
        risk.checkAndLiquidate(1L, new BigDecimal("90"));

        verify(lockRegistry).tryLockAsSystem(POS_KEY);
        verifyNoInteractions(positionMapper);
    }

    // ==================== 止损止盈：等到锁后按库里最新档位复核、按触发价成交 ====================

    private FuturesPosition withStopLoss(String slId, String slQty) {
        FuturesPosition p = position();
        p.setStopLosses(new ArrayList<>(List.of(new FuturesStopLoss(slId, new BigDecimal("95"), new BigDecimal(slQty)))));
        return p;
    }

    @Test
    void 止损_等到锁后按触发价成交_不看当前价() {
        when(lockRegistry.tryLockAsSystem(POS_KEY)).thenReturn(posLock);
        when(positionMapper.selectById(1L)).thenReturn(withStopLoss("sl1", "1"));
        when(positionMapper.casClosePosition(anyLong(), anyString(), any(), any())).thenReturn(1);
        // 等锁期间价格已经回到 99，成交仍按触发那一刻的 95
        when(cacheService.getMarkPrice(SYM)).thenReturn(new BigDecimal("99"));

        risk.batchTriggerStopLoss(1L, List.of("sl1"), new BigDecimal("95"));

        ArgumentCaptor<BigDecimal> pnl = ArgumentCaptor.forClass(BigDecimal.class);
        InOrder inOrder = inOrder(lockRegistry, positionMapper, posLock);
        inOrder.verify(lockRegistry).tryLockAsSystem(POS_KEY);
        inOrder.verify(positionMapper).casClosePosition(eq(1L), eq("CLOSED"), eq(new BigDecimal("95")), pnl.capture());
        inOrder.verify(posLock).unlock();
        assertThat(pnl.getValue()).isEqualByComparingTo("-5");
    }

    @Test
    void 止盈_等到锁后按触发价成交() {
        FuturesPosition p = position();
        p.setTakeProfits(new ArrayList<>(List.of(new FuturesTakeProfit("tp1", new BigDecimal("110"), BigDecimal.ONE))));
        when(lockRegistry.tryLockAsSystem(POS_KEY)).thenReturn(posLock);
        when(positionMapper.selectById(1L)).thenReturn(p);
        when(positionMapper.casClosePosition(anyLong(), anyString(), any(), any())).thenReturn(1);

        risk.batchTriggerTakeProfit(1L, List.of("tp1"), new BigDecimal("110"));

        verify(positionMapper).casClosePosition(eq(1L), eq("CLOSED"), eq(new BigDecimal("110")), any());
        verify(posLock).unlock();
    }

    @Test
    void 止损_等锁期间档位被删_拿到锁后不执行() {
        when(lockRegistry.tryLockAsSystem(POS_KEY)).thenReturn(posLock);
        when(positionMapper.selectById(1L)).thenReturn(position());

        risk.batchTriggerStopLoss(1L, List.of("sl1"), new BigDecimal("95"));

        verify(positionMapper, never()).casClosePosition(anyLong(), anyString(), any(), any());
        verify(positionMapper, never()).atomicPartialClose(anyLong(), any(), any());
        verify(orderMapper, never()).insert(any(FuturesOrder.class));
        verify(posLock).unlock();
    }

    @Test
    void 止损_等锁期间档位被改成新的_旧档位不执行() {
        when(lockRegistry.tryLockAsSystem(POS_KEY)).thenReturn(posLock);
        // 改止损会换新 id：库里现在只有 sl2
        when(positionMapper.selectById(1L)).thenReturn(withStopLoss("sl2", "1"));

        risk.batchTriggerStopLoss(1L, List.of("sl1"), new BigDecimal("95"));

        verify(positionMapper, never()).casClosePosition(anyLong(), anyString(), any(), any());
        verify(positionMapper, never()).atomicPartialClose(anyLong(), any(), any());
        verify(orderMapper, never()).insert(any(FuturesOrder.class));
    }

    @Test
    void 止损_等锁期间用户部分平仓_按当前持仓截断() {
        FuturesPosition p = withStopLoss("sl1", "0.5");
        p.setQuantity(new BigDecimal("0.4"));
        p.setMargin(new BigDecimal("4"));
        when(lockRegistry.tryLockAsSystem(POS_KEY)).thenReturn(posLock);
        when(positionMapper.selectById(1L)).thenReturn(p);
        when(positionMapper.casClosePosition(anyLong(), anyString(), any(), any())).thenReturn(1);

        risk.batchTriggerStopLoss(1L, List.of("sl1"), new BigDecimal("95"));

        ArgumentCaptor<FuturesOrder> order = ArgumentCaptor.forClass(FuturesOrder.class);
        verify(positionMapper).casClosePosition(eq(1L), eq("CLOSED"), eq(new BigDecimal("95")), any());
        verify(orderMapper).insert(order.capture());
        assertThat(order.getValue().getQuantity()).isEqualByComparingTo("0.4");
    }

    @Test
    void 止损_等锁期间仓位已平_直接跳过() {
        FuturesPosition p = withStopLoss("sl1", "1");
        p.setStatus("CLOSED");
        when(lockRegistry.tryLockAsSystem(POS_KEY)).thenReturn(posLock);
        when(positionMapper.selectById(1L)).thenReturn(p);

        risk.batchTriggerStopLoss(1L, List.of("sl1"), new BigDecimal("95"));

        verify(positionMapper, never()).casClosePosition(anyLong(), anyString(), any(), any());
        verify(orderMapper, never()).insert(any(FuturesOrder.class));
        verify(posLock).unlock();
    }
}
