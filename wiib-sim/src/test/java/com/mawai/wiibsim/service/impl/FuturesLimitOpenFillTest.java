package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.CrossLiquidationService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.RedisLockUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 限价开仓单成交侧（挂单期间币种格局可能已变，成交前必须复查）：
 * <ol>
 *   <li>同向仓位在且杠杆/模式一致 → 并入：均价加权、订单回填 position_id</li>
 *   <li>杠杆不一致 → 撤单退款（逐仓退冻结），不硬成交也不改单</li>
 *   <li>模式冲突 → 撤单（全仓单无冻结无退款动作）</li>
 *   <li>无同向仓位 → 维持原新建行为</li>
 * </ol>
 */
class FuturesLimitOpenFillTest {

    private static final Long UID = 7L;
    private static final String SYMBOL = "BTCUSDT";

    private UserService userService;
    private UserMapper userMapper;
    private FuturesPositionMapper positionMapper;
    private FuturesOrderMapper orderMapper;
    private CacheService cacheService;
    private FuturesPositionIndexService positionIndexService;
    private FuturesLeverageBracketRegistry bracketRegistry;
    private FuturesSettlementServiceImpl service;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        userMapper = mock(UserMapper.class);
        positionMapper = mock(FuturesPositionMapper.class);
        orderMapper = mock(FuturesOrderMapper.class);
        cacheService = mock(CacheService.class);
        positionIndexService = mock(FuturesPositionIndexService.class);
        bracketRegistry = mock(FuturesLeverageBracketRegistry.class);

        User user = new User();
        user.setId(UID);
        user.setIsBankrupt(false);
        when(userService.getById(UID)).thenReturn(user);
        when(bracketRegistry.getEffectiveMaxLeverage(anyString(), any())).thenReturn(150);
        when(orderMapper.casMarkProcessing(anyLong())).thenReturn(1);
        when(orderMapper.casUpdateStatus(anyLong(), anyString(), anyString())).thenReturn(1);
        when(orderMapper.casUpdateToFilled(anyLong(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(positionMapper.atomicIncreasePosition(anyLong(), any(), any(), any())).thenReturn(1);
        when(userMapper.atomicSettleBalance(anyLong(), any())).thenReturn(1);
        when(userMapper.atomicUpdateBalance(anyLong(), any())).thenReturn(1);
        when(userMapper.atomicDeductFrozenBalance(anyLong(), any())).thenReturn(1);

        service = new FuturesSettlementServiceImpl(
                userService, userMapper, positionMapper, orderMapper,
                new TradingConfig(), bracketRegistry, cacheService, positionIndexService,
                mock(FuturesRiskService.class), mock(CrossMarginService.class),
                mock(CrossLiquidationService.class), mock(RedisLockUtil.class), mock(BinanceRestClient.class));
    }

    private static FuturesPosition pos(long id, String side, String mode, int leverage,
                                       String entryPrice, String qty, String margin) {
        FuturesPosition p = new FuturesPosition();
        p.setId(id);
        p.setUserId(UID);
        p.setSymbol(SYMBOL);
        p.setSide(side);
        p.setMarginMode(mode);
        p.setLeverage(leverage);
        p.setEntryPrice(new BigDecimal(entryPrice));
        p.setQuantity(new BigDecimal(qty));
        p.setMargin(new BigDecimal(margin));
        p.setStatus("OPEN");
        return p;
    }

    /** TRIGGERED 限价开仓单；commission 置空按 maker 处理 → 成交价=挂单价 */
    private static FuturesOrder openOrder(String orderSide, String mode, int leverage, String qty, String limitPrice, String frozen) {
        FuturesOrder o = new FuturesOrder();
        o.setId(100L);
        o.setUserId(UID);
        o.setSymbol(SYMBOL);
        o.setOrderSide(orderSide);
        o.setOrderType("LIMIT");
        o.setMarginMode(mode);
        o.setQuantity(new BigDecimal(qty));
        o.setLeverage(leverage);
        o.setLimitPrice(new BigDecimal(limitPrice));
        if (frozen != null) o.setFrozenAmount(new BigDecimal(frozen));
        o.setStatus("TRIGGERED");
        return o;
    }

    @Test
    void 成交时同向仓位在_杠杆一致_并入回填positionId() {
        FuturesPosition lp = pos(1L, "LONG", FuturesPosition.CROSS, 50, "90", "1", "1.80");
        when(positionMapper.selectList(any())).thenReturn(List.of(lp));

        service.doProcessTriggeredOrder(openOrder("OPEN_LONG", "CROSS", 50, "1", "110", null));

        // 均价 (90+110)/2=100，加保证金 110/50=2.20，maker 费 110×0.0002=0.02
        verify(positionMapper).atomicIncreasePosition(eq(1L),
                argThat(p -> p.compareTo(new BigDecimal("100")) == 0),
                argThat(q -> q.compareTo(BigDecimal.ONE) == 0),
                argThat(m -> m.compareTo(new BigDecimal("2.20")) == 0));
        verify(userMapper).atomicSettleBalance(eq(UID), argThat(c -> c.compareTo(new BigDecimal("-0.02")) == 0));
        verify(orderMapper).casUpdateToFilled(eq(100L), eq(1L), any(), any(), any(), any(), any());
        verify(positionMapper, never()).insert(any(FuturesPosition.class));
    }

    @Test
    void 成交时杠杆已不一致_撤单退冻结() {
        // 挂单后用户把币种杠杆从50调到100 → 成交复查不过，逐仓单退冻结金
        FuturesPosition lp = pos(1L, "LONG", FuturesPosition.ISOLATED, 100, "90", "1", "0.90");
        when(positionMapper.selectList(any())).thenReturn(List.of(lp));

        service.doProcessTriggeredOrder(openOrder("OPEN_LONG", "ISOLATED", 50, "1", "110", "2.24"));

        verify(orderMapper).casUpdateStatus(100L, "PROCESSING", "CANCELLED");
        verify(userMapper).atomicDeductFrozenBalance(eq(UID), argThat(f -> f.compareTo(new BigDecimal("2.24")) == 0));
        verify(userMapper).atomicUpdateBalance(eq(UID), argThat(f -> f.compareTo(new BigDecimal("2.24")) == 0));
        verify(positionMapper, never()).atomicIncreasePosition(anyLong(), any(), any(), any());
        verify(positionMapper, never()).insert(any(FuturesPosition.class));
    }

    @Test
    void 成交时模式冲突_撤单_全仓单无退款动作() {
        // 反向仓位也参与币种级模式校验；全仓单没冻结过钱，撤单只翻状态
        FuturesPosition lp = pos(1L, "LONG", FuturesPosition.ISOLATED, 50, "90", "1", "1.80");
        when(positionMapper.selectList(any())).thenReturn(List.of(lp));

        service.doProcessTriggeredOrder(openOrder("OPEN_SHORT", "CROSS", 50, "1", "110", null));

        verify(orderMapper).casUpdateStatus(100L, "PROCESSING", "CANCELLED");
        verify(userMapper, never()).atomicDeductFrozenBalance(anyLong(), any());
        verify(positionMapper, never()).insert(any(FuturesPosition.class));
    }

    @Test
    void 无同向仓位_维持新建行为() {
        when(positionMapper.selectList(any())).thenReturn(List.of());

        service.doProcessTriggeredOrder(openOrder("OPEN_LONG", "CROSS", 50, "1", "110", null));

        verify(positionMapper).insert(any(FuturesPosition.class));
        verify(positionIndexService).registerPositionIndex(any(FuturesPosition.class));
        verify(positionMapper, never()).atomicIncreasePosition(anyLong(), any(), any(), any());
    }
}
