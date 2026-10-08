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
import com.mawai.wiibsim.service.FundingRateService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.FairLockRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
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
 *   <li>成交成本超出挂单时的冻结/预留（taker 开空按触发价成交）→ 超出部分过可用额度、逐仓从余额补扣，
 *       补不上撤单退款；maker 单成本等于预留，不查额度</li>
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
    private CrossMarginService crossMarginService;
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
        // 资金方法返"变动后余额"，非 null 即成功；具体数值本类不断言，给个占位即可。
        // 这些值也不会流进账本：记账切面只织在真 Spring 代理上，裸 mock 单测里压根不参与
        when(userMapper.atomicSettleBalance(anyLong(), any())).thenReturn(new BigDecimal("10000"));
        when(userMapper.atomicUpdateBalance(anyLong(), any())).thenReturn(new BigDecimal("10000"));
        when(userMapper.atomicDeductFrozenBalance(anyLong(), any())).thenReturn(BigDecimal.ZERO);

        crossMarginService = mock(CrossMarginService.class);
        service = new FuturesSettlementServiceImpl(
                userService, userMapper, positionMapper, orderMapper,
                new TradingConfig(), bracketRegistry, cacheService, positionIndexService,
                mock(FuturesRiskService.class), crossMarginService,
                mock(CrossLiquidationService.class), mock(FairLockRegistry.class), mock(FundingRateService.class),
                new LedgerTx(new TransactionTemplate(mock(PlatformTransactionManager.class))));
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

    /**
     * 1x 开空 0.3 @30000，挂单时标记价 60000 → taker：冻结 9000 + 9000×0.04%=3.60；
     * 下一个 tick 60000 触发，按触发价成交：保证金 18000、taker 费 7.20，成本比冻结多 9003.60
     */
    private static FuturesOrder takerShortFilledAt60000(String mode) {
        FuturesOrder o = openOrder("OPEN_SHORT", mode, 1, "0.3", "30000", "9003.60");
        o.setCommission(new BigDecimal("3.60"));   // 挂单时按 taker 估的费，成交侧据此认 taker
        o.setFilledPrice(new BigDecimal("60000")); // 触发 tick 价
        return o;
    }

    /** 无全仓持仓的快照，可用额度 = balance + upnl */
    private static CrossMarginService.CrossAccount account(String balance, String upnl) {
        return new CrossMarginService.CrossAccount(new BigDecimal(balance), new BigDecimal(upnl),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), Map.of());
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void 成交时同向仓位在_杠杆一致_并入回填positionId() {
        FuturesPosition lp = pos(1L, "LONG", FuturesPosition.CROSS, 50, "90", "1", "1.80");
        when(positionMapper.selectList(any())).thenReturn(List.of(lp));

        service.doProcessTriggeredOrder(openOrder("OPEN_LONG", "CROSS", 50, "1", "110", "2.22"));

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

    /** 与 FuturesOpenMergeTest 同款不变量：全仓加仓并入必须汇入 refreshUserIndex 作废安全带 */
    @Test
    void 全仓限价成交并入_刷新用户索引作废安全带() {
        FuturesPosition lp = pos(1L, "LONG", FuturesPosition.CROSS, 50, "90", "1", "1.80");
        when(positionMapper.selectList(any())).thenReturn(List.of(lp));

        service.doProcessTriggeredOrder(openOrder("OPEN_LONG", "CROSS", 50, "1", "110", "2.22"));

        verify(crossMarginService).refreshUserIndex(UID);
    }

    /** maker 单成本等于预留，不查可用额度 */
    @Test
    void 无同向仓位_维持新建行为() {
        when(positionMapper.selectList(any())).thenReturn(List.of());

        service.doProcessTriggeredOrder(openOrder("OPEN_LONG", "CROSS", 50, "1", "110", "2.22"));

        verify(positionMapper).insert(any(FuturesPosition.class));
        verify(positionIndexService).registerPositionIndex(any(FuturesPosition.class));
        verify(positionMapper, never()).atomicIncreasePosition(anyLong(), any(), any(), any());
        verify(crossMarginService, never()).snapshot(anyLong());
    }

    /** 冻结 9003.60、成交保证金 18000：超出的 9003.60 从余额扣 */
    @Test
    void 逐仓taker开空按触发价成交_超出冻结的部分从余额补扣() {
        when(positionMapper.selectList(any())).thenReturn(List.of());
        when(crossMarginService.snapshot(UID)).thenReturn(account("20000", "0"));

        service.doProcessTriggeredOrder(takerShortFilledAt60000("ISOLATED"));

        verify(userMapper).atomicUpdateBalance(eq(UID), argThat(a -> a.compareTo(bd("-9003.60")) == 0));
        verify(userMapper).atomicDeductFrozenBalance(eq(UID), argThat(f -> f.compareTo(bd("9003.60")) == 0));
        verify(userMapper, never()).atomicUpdateBalance(eq(UID), argThat(a -> a.signum() > 0));
        verify(positionMapper).insert(argThat((FuturesPosition p) ->
                p.getMargin().compareTo(bd("18000")) == 0 && p.getEntryPrice().compareTo(bd("60000")) == 0));
    }

    /** 可用额度够（全仓浮盈撑着）但钱包现金不够补：撤单退冻结，不建仓 */
    @Test
    void 逐仓超出部分余额补不上_撤单退冻结() {
        when(positionMapper.selectList(any())).thenReturn(List.of());
        when(crossMarginService.snapshot(UID)).thenReturn(account("5000", "20000"));
        when(userMapper.atomicUpdateBalance(eq(UID), argThat(a -> a.signum() < 0))).thenReturn(null);

        service.doProcessTriggeredOrder(takerShortFilledAt60000("ISOLATED"));

        verify(orderMapper).casUpdateStatus(100L, "PROCESSING", "CANCELLED");
        verify(userMapper).atomicDeductFrozenBalance(eq(UID), argThat(f -> f.compareTo(bd("9003.60")) == 0));
        verify(userMapper).atomicUpdateBalance(eq(UID), argThat(a -> a.compareTo(bd("9003.60")) == 0));
        verify(positionMapper, never()).insert(any(FuturesPosition.class));
        verify(orderMapper, never()).casUpdateToFilled(anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 逐仓超出部分过不了可用额度_撤单退冻结_余额不先扣() {
        when(positionMapper.selectList(any())).thenReturn(List.of());
        when(crossMarginService.snapshot(UID)).thenReturn(account("9003.59", "0"));

        service.doProcessTriggeredOrder(takerShortFilledAt60000("ISOLATED"));

        verify(orderMapper).casUpdateStatus(100L, "PROCESSING", "CANCELLED");
        verify(userMapper, never()).atomicUpdateBalance(eq(UID), argThat(a -> a.signum() < 0));
        verify(userMapper).atomicUpdateBalance(eq(UID), argThat(a -> a.compareTo(bd("9003.60")) == 0));
        verify(positionMapper, never()).insert(any(FuturesPosition.class));
    }

    /** 全仓成交后占用按 18000 算、比挂单预留多 9003.60，可用额度不够就撤单 */
    @Test
    void 全仓taker成交成本超出预留_可用不够_撤单不建仓() {
        when(positionMapper.selectList(any())).thenReturn(List.of());
        when(crossMarginService.snapshot(UID)).thenReturn(account("9003.59", "0"));

        service.doProcessTriggeredOrder(takerShortFilledAt60000("CROSS"));

        verify(orderMapper).casUpdateStatus(100L, "PROCESSING", "CANCELLED");
        verify(positionMapper, never()).insert(any(FuturesPosition.class));
        verify(userMapper, never()).atomicSettleBalance(anyLong(), any());
        verify(userMapper, never()).atomicDeductFrozenBalance(anyLong(), any());
    }

    @Test
    void 全仓taker成交成本超出预留_可用够_照常成交只扣手续费() {
        when(positionMapper.selectList(any())).thenReturn(List.of());
        when(crossMarginService.snapshot(UID)).thenReturn(account("9003.60", "0"));

        service.doProcessTriggeredOrder(takerShortFilledAt60000("CROSS"));

        verify(userMapper).atomicSettleBalance(eq(UID), argThat(c -> c.compareTo(bd("-7.20")) == 0));
        verify(userMapper, never()).atomicUpdateBalance(anyLong(), any());
        verify(positionMapper).insert(argThat((FuturesPosition p) -> p.getMargin().compareTo(bd("18000")) == 0));
    }
}
