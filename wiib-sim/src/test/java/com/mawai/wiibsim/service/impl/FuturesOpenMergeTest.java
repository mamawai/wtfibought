package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.RedisLockUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 开仓合并（对齐Binance双向持仓）：
 * <ol>
 *   <li>同向市价单并入现有仓位：均价加权、数量/保证金累加、订单记 OPEN_*（不再有 INCREASE）</li>
 *   <li>杠杆/保证金模式是币种级设置：与该币现有任一仓位（含反向）冲突直接拒</li>
 *   <li>反向开仓不合并、不对冲，新建独立仓位（多空双开）</li>
 *   <li>随单SL/TP追加：合并后合计条数≤4、总量≤合并后持仓，超限在动钱之前拒</li>
 * </ol>
 */
class FuturesOpenMergeTest {

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
    private FuturesTradingServiceImpl service;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        userMapper = mock(UserMapper.class);
        positionMapper = mock(FuturesPositionMapper.class);
        orderMapper = mock(FuturesOrderMapper.class);
        cacheService = mock(CacheService.class);
        positionIndexService = mock(FuturesPositionIndexService.class);
        bracketRegistry = mock(FuturesLeverageBracketRegistry.class);
        crossMarginService = mock(CrossMarginService.class);

        User user = new User();
        user.setId(UID);
        user.setBalance(new BigDecimal("10000"));
        user.setIsBankrupt(false);
        when(userService.getById(UID)).thenReturn(user);
        when(cacheService.getFuturesPrice(SYMBOL)).thenReturn(new BigDecimal("110"));
        when(cacheService.getMarkPrice(SYMBOL)).thenReturn(new BigDecimal("110"));
        when(bracketRegistry.getEffectiveMaxLeverage(anyString(), any())).thenReturn(150);
        when(positionMapper.atomicIncreasePosition(anyLong(), any(), any(), any())).thenReturn(1);
        when(userMapper.atomicUpdateBalance(anyLong(), any())).thenReturn(1);
        when(userMapper.atomicSettleBalance(anyLong(), any())).thenReturn(1);

        service = new FuturesTradingServiceImpl(
                userService, userMapper, positionMapper, orderMapper,
                new TradingConfig(), mock(RedisLockUtil.class), cacheService,
                positionIndexService, bracketRegistry, crossMarginService,
                new TradeFilterRegistry(mock(BinanceRestClient.class)));
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

    private static FuturesOpenRequest marketReq(String side, String mode, int leverage, String qty) {
        FuturesOpenRequest r = new FuturesOpenRequest();
        r.setSymbol(SYMBOL);
        r.setSide(side);
        r.setMarginMode(mode);
        r.setLeverage(leverage);
        r.setQuantity(new BigDecimal(qty));
        r.setOrderType("MARKET");
        return r;
    }

    @Test
    void 同向市价单并入_均价加权_订单记OPEN() {
        // 现有 LONG 50x entry90 qty1；@110 再开1 → 新均价 (90+110)/2=100，加保证金 110/50=2.20，手续费 0.04
        FuturesPosition lp = pos(1L, "LONG", FuturesPosition.CROSS, 50, "90", "1", "1.80");
        when(positionMapper.selectList(any())).thenReturn(List.of(lp));

        service.doOpenPosition(UID, marketReq("LONG", "CROSS", 50, "1"));

        verify(crossMarginService).assertCanAfford(eq(UID), argThat(c -> c.compareTo(new BigDecimal("2.24")) == 0));
        verify(userMapper).atomicSettleBalance(eq(UID), argThat(c -> c.compareTo(new BigDecimal("-0.04")) == 0));
        verify(positionMapper).atomicIncreasePosition(eq(1L),
                argThat(p -> p.compareTo(new BigDecimal("100")) == 0),
                argThat(q -> q.compareTo(BigDecimal.ONE) == 0),
                argThat(m -> m.compareTo(new BigDecimal("2.20")) == 0));
        verify(positionMapper, never()).insert(any(FuturesPosition.class));

        ArgumentCaptor<FuturesOrder> captor = ArgumentCaptor.forClass(FuturesOrder.class);
        verify(orderMapper).insert(captor.capture());
        assertThat(captor.getValue().getOrderSide()).isEqualTo("OPEN_LONG");
        assertThat(captor.getValue().getPositionId()).isEqualTo(1L);
        assertThat(captor.getValue().getStatus()).isEqualTo("FILLED");
    }

    @Test
    void 杠杆与现有仓位不一致_拒绝() {
        FuturesPosition lp = pos(1L, "LONG", FuturesPosition.CROSS, 100, "100", "1", "1.00");
        when(positionMapper.selectList(any())).thenReturn(List.of(lp));

        assertThatThrownBy(() -> service.doOpenPosition(UID, marketReq("LONG", "CROSS", 50, "1")))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.FUTURES_LEVERAGE_MISMATCH.getCode());
        // 反向同样受币种级杠杆约束（Binance杠杆是symbol级，多空共用）
        assertThatThrownBy(() -> service.doOpenPosition(UID, marketReq("SHORT", "CROSS", 50, "1")))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.FUTURES_LEVERAGE_MISMATCH.getCode());

        verify(positionMapper, never()).insert(any(FuturesPosition.class));
        verify(positionMapper, never()).atomicIncreasePosition(anyLong(), any(), any(), any());
    }

    @Test
    void 保证金模式与现有仓位不一致_拒绝() {
        FuturesPosition lp = pos(1L, "LONG", FuturesPosition.CROSS, 50, "100", "1", "2.00");
        when(positionMapper.selectList(any())).thenReturn(List.of(lp));

        assertThatThrownBy(() -> service.doOpenPosition(UID, marketReq("SHORT", "ISOLATED", 50, "1")))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.FUTURES_MARGIN_MODE_CONFLICT.getCode());
    }

    @Test
    void 反向开仓_不合并_新建独立仓位() {
        FuturesPosition lp = pos(1L, "LONG", FuturesPosition.CROSS, 50, "100", "1", "2.20");
        when(positionMapper.selectList(any())).thenReturn(List.of(lp));

        service.doOpenPosition(UID, marketReq("SHORT", "CROSS", 50, "1"));

        verify(positionMapper).insert(any(FuturesPosition.class));
        verify(positionMapper, never()).atomicIncreasePosition(anyLong(), any(), any(), any());
    }

    @Test
    void 合并SLTP档位超4_动钱前拒绝() {
        FuturesPosition lp = pos(1L, "LONG", FuturesPosition.CROSS, 50, "90", "1", "1.80");
        lp.setStopLosses(List.of(
                new FuturesStopLoss("a", new BigDecimal("80"), new BigDecimal("0.2")),
                new FuturesStopLoss("b", new BigDecimal("81"), new BigDecimal("0.2")),
                new FuturesStopLoss("c", new BigDecimal("82"), new BigDecimal("0.2"))));
        when(positionMapper.selectList(any())).thenReturn(List.of(lp));

        FuturesOpenRequest req = marketReq("LONG", "CROSS", 50, "1");
        FuturesOpenRequest.StopLoss s1 = new FuturesOpenRequest.StopLoss();
        s1.setPrice(new BigDecimal("83")); s1.setQuantity(new BigDecimal("0.2"));
        FuturesOpenRequest.StopLoss s2 = new FuturesOpenRequest.StopLoss();
        s2.setPrice(new BigDecimal("84")); s2.setQuantity(new BigDecimal("0.2"));
        req.setStopLosses(List.of(s1, s2));

        assertThatThrownBy(() -> service.doOpenPosition(UID, req))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.FUTURES_SPLIT_LIMIT.getCode());

        // 拒绝发生在资金变动之前
        verify(crossMarginService, never()).assertCanAfford(anyLong(), any());
        verify(userMapper, never()).atomicSettleBalance(anyLong(), any());
        verify(positionMapper, never()).atomicIncreasePosition(anyLong(), any(), any(), any());
    }

    @Test
    void 合并SLTP追加_注册新增触发索引_保留存量() {
        FuturesPosition lp = pos(1L, "LONG", FuturesPosition.CROSS, 50, "90", "1", "1.80");
        lp.setStopLosses(List.of(new FuturesStopLoss("a", new BigDecimal("80"), new BigDecimal("0.5"))));
        when(positionMapper.selectList(any())).thenReturn(List.of(lp));

        FuturesOpenRequest req = marketReq("LONG", "CROSS", 50, "1");
        FuturesOpenRequest.StopLoss s1 = new FuturesOpenRequest.StopLoss();
        s1.setPrice(new BigDecimal("85")); s1.setQuantity(new BigDecimal("1"));
        req.setStopLosses(List.of(s1));

        service.doOpenPosition(UID, req);

        // 落库为合并列表（存量+新增），索引只注册新增档位
        verify(positionMapper).updateStopLosses(eq(1L), argThat(l -> l.size() == 2));
        verify(positionIndexService).registerStopLosses(eq(1L), eq(SYMBOL), eq("LONG"),
                argThat(l -> l.size() == 1 && l.get(0).getPrice().compareTo(new BigDecimal("85")) == 0));
    }

    @Test
    void 无持仓_正常新建() {
        when(positionMapper.selectList(any())).thenReturn(List.of());

        service.doOpenPosition(UID, marketReq("LONG", "CROSS", 50, "1"));

        verify(positionMapper).insert(any(FuturesPosition.class));
        verify(positionIndexService).registerPositionIndex(any(FuturesPosition.class));
    }
}
