package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.ledger.LedgerTx;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.BankruptcyService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.NoBookFillPricer;
import com.mawai.wiibsim.service.TradeNotificationService;
import com.mawai.wiibsim.util.FairLockRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仓位全平以后，它名下还挂着的平仓限价单一起撤掉、摘索引；部分平仓不动 */
class FuturesCloseOrderCancelTest {

    private FuturesPositionMapper positionMapper;
    private FuturesOrderMapper orderMapper;
    private CacheService cacheService;
    private FuturesRiskServiceImpl risk;

    @BeforeEach
    void setUp() {
        positionMapper = mock(FuturesPositionMapper.class);
        orderMapper = mock(FuturesOrderMapper.class);
        cacheService = mock(CacheService.class);
        FairLockRegistry lockRegistry = mock(FairLockRegistry.class);
        when(lockRegistry.tryLockAsSystem(anyString())).thenReturn(mock(Lock.class));
        when(positionMapper.casClosePosition(anyLong(), anyString(), any(), any())).thenReturn(1);
        when(positionMapper.atomicPartialClose(anyLong(), any(), any())).thenReturn(1);
        TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
        risk = new FuturesRiskServiceImpl(positionMapper, orderMapper, mock(UserMapper.class),
                new TradingConfig(), lockRegistry, cacheService, mock(FuturesPositionIndexService.class),
                mock(FuturesLeverageBracketRegistry.class), mock(CrossMarginService.class),
                mock(TradeNotificationService.class), tx, new LedgerTx(tx),
                new TradeFilterRegistry(mock(BinanceRestClient.class)), mock(BankruptcyService.class),
                NoBookFillPricer.create());
    }

    /** 逐仓多 1 张 @100，止损 95 平 slQty */
    private static FuturesPosition withStopLoss(String slQty) {
        FuturesPosition p = new FuturesPosition();
        p.setId(1L);
        p.setUserId(7L);
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setMarginMode(FuturesPosition.ISOLATED);
        p.setLeverage(10);
        p.setQuantity(BigDecimal.ONE);
        p.setEntryPrice(new BigDecimal("100"));
        p.setMargin(new BigDecimal("10"));
        p.setStatus("OPEN");
        p.setStopLosses(new ArrayList<>(List.of(new FuturesStopLoss("sl1", new BigDecimal("95"), new BigDecimal(slQty)))));
        return p;
    }

    @Test
    void 止损全平_撤掉挂着的平仓限价单并摘索引() {
        when(positionMapper.selectById(1L)).thenReturn(withStopLoss("1"));
        FuturesOrder pending = new FuturesOrder();
        pending.setId(5L);
        pending.setSymbol("BTCUSDT");
        pending.setOrderSide("CLOSE_LONG");
        when(orderMapper.cancelPendingCloseOrders(1L)).thenReturn(List.of(pending));

        risk.batchTriggerStopLoss(1L, List.of("sl1"), new BigDecimal("95"), System.currentTimeMillis());

        verify(cacheService).zRemove("futures:limit:close_long:BTCUSDT", "5");
    }

    @Test
    void 止损部分平仓_平仓限价单留着() {
        when(positionMapper.selectById(1L)).thenReturn(withStopLoss("0.4"));

        risk.batchTriggerStopLoss(1L, List.of("sl1"), new BigDecimal("95"), System.currentTimeMillis());

        verify(orderMapper, never()).cancelPendingCloseOrders(anyLong());
    }
}
