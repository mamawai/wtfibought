package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.ledger.LedgerTx;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.CrossLiquidationService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.TradeEventPublisher;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.FairLockRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** 挂着的限价平仓单成交：剩下的仓位不够这张单平，就把剩下的全平掉，订单数量记实际平掉的 */
class FuturesLimitCloseFillTest {

    private static final Long UID = 7L;

    private FuturesPositionMapper positionMapper;
    private FuturesOrderMapper orderMapper;
    private FuturesSettlementServiceImpl service;

    @BeforeEach
    void setUp() {
        UserService userService = mock(UserService.class);
        UserMapper userMapper = mock(UserMapper.class);
        positionMapper = mock(FuturesPositionMapper.class);
        orderMapper = mock(FuturesOrderMapper.class);

        User user = new User();
        user.setId(UID);
        user.setIsBankrupt(false);
        when(userService.getById(UID)).thenReturn(user);
        when(orderMapper.casMarkProcessing(anyLong())).thenReturn(1);
        when(orderMapper.casUpdateToFilled(anyLong(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(positionMapper.casClosePosition(anyLong(), anyString(), any(), any())).thenReturn(1);
        when(userMapper.atomicUpdateBalance(anyLong(), any())).thenReturn(new BigDecimal("10000"));

        service = new FuturesSettlementServiceImpl(
                userService, userMapper, positionMapper, orderMapper,
                new TradingConfig(), mock(FuturesLeverageBracketRegistry.class), mock(CacheService.class),
                mock(FuturesPositionIndexService.class), mock(FuturesRiskService.class), mock(CrossMarginService.class),
                mock(CrossLiquidationService.class), mock(FairLockRegistry.class),
                new LedgerTx(new TransactionTemplate(mock(PlatformTransactionManager.class))),
                mock(TradeEventPublisher.class));
    }

    /** 逐仓多原来 1 个，被止损平掉 0.5，剩 0.5 @100、保证金 5；挂着的平仓单 110 平 1 个 */
    @Test
    void 剩下的不够这张单平_把剩下的全平掉() {
        FuturesPosition pos = new FuturesPosition();
        pos.setId(1L);
        pos.setUserId(UID);
        pos.setSymbol("BTCUSDT");
        pos.setSide("LONG");
        pos.setMarginMode(FuturesPosition.ISOLATED);
        pos.setLeverage(10);
        pos.setEntryPrice(new BigDecimal("100"));
        pos.setQuantity(new BigDecimal("0.5"));
        pos.setMargin(new BigDecimal("5"));
        pos.setStatus("OPEN");
        when(positionMapper.selectById(1L)).thenReturn(pos);
        FuturesOrder order = new FuturesOrder();
        order.setId(100L);
        order.setUserId(UID);
        order.setPositionId(1L);
        order.setSymbol("BTCUSDT");
        order.setOrderSide("CLOSE_LONG");
        order.setOrderType("LIMIT");
        order.setMarginMode(FuturesPosition.ISOLATED);
        order.setQuantity(BigDecimal.ONE);
        order.setLeverage(10);
        order.setLimitPrice(new BigDecimal("110"));
        order.setStatus("TRIGGERED");

        service.doProcessTriggeredOrder(order);

        // 按 110 全平剩下的 0.5：盈亏 (110−100)×0.5=5，订单数量记 0.5
        verify(positionMapper).casClosePosition(eq(1L), eq("CLOSED"),
                argThat(p -> p.compareTo(new BigDecimal("110")) == 0), argThat(p -> p.compareTo(new BigDecimal("5")) == 0));
        verify(orderMapper).casUpdateToFilled(eq(100L), isNull(), argThat(q -> q.compareTo(new BigDecimal("0.5")) == 0),
                any(), any(), any(), any(), any());
        verify(orderMapper, never()).casUpdateStatus(anyLong(), anyString(), eq("CANCELLED"));
    }
}
