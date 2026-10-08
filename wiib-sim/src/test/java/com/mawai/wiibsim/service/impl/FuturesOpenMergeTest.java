package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.User;
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
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.FairLockRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
        // 资金方法返"变动后余额"，非 null 即成功；具体数值本类不断言，给个占位即可。
        // 这些值也不会流进账本：记账切面只织在真 Spring 代理上，裸 mock 单测里压根不参与
        when(userMapper.atomicUpdateBalance(anyLong(), any())).thenReturn(new BigDecimal("10000"));
        when(userMapper.atomicSettleBalance(anyLong(), any())).thenReturn(new BigDecimal("10000"));

        service = new FuturesTradingServiceImpl(
                userService, userMapper, positionMapper, orderMapper,
                new TradingConfig(), mock(FairLockRegistry.class), cacheService,
                positionIndexService, bracketRegistry, crossMarginService,
                new TradeFilterRegistry(mock(BinanceRestClient.class)),
                new MessageCatalog(), new LedgerTx(new TransactionTemplate(mock(PlatformTransactionManager.class))));
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

    /**
     * 扣款返 null（余额不足）必须拒单——钉死 atomicUpdateBalance 改 RETURNING 后的"null=没扣成"极性。
     * <p>
     * 在此之前全仓库没有任何一处 stub 资金方法返失败值的单测：把 {@code if (after == null) throw}
     * 写反成 {@code != null}，其余 127 个用例照样全绿。Task 4 要批量转约 40 处调用点，全靠人眼，
     * 这条是那批改动的网——别删。
     */
    @Test
    void 逐仓开仓_扣款返null_按余额不足拒单() {
        when(positionMapper.selectList(any())).thenReturn(List.of());
        when(userMapper.atomicUpdateBalance(anyLong(), any())).thenReturn(null);

        assertThatThrownBy(() -> service.doOpenPosition(UID, marketReq("LONG", "ISOLATED", 50, "1")))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.FUTURES_INSUFFICIENT_BALANCE.getCode());

        // mock 用户余额 10000 远大于本单成本 2.24，扣款前的余额预检不可能先抛；
        // verify 确认确实走到了扣款这一步，异常来自 null 判定而不是预检——否则这测试是假绿的
        verify(userMapper).atomicUpdateBalance(eq(UID), any());
        verify(positionMapper, never()).insert(any(FuturesPosition.class));
    }

    /**
     * 逐仓开仓吃的是全仓可用额度，不是整个钱包。
     * <p>
     * 历史 bug：这里曾走 assertOutflowAllowed（流出后 equity 还高于维持保证金就放行），
     * 而维持保证金比起始保证金小一个数量级（20x 下 0.5% vs 5%），等于全仓占用拦不住逐仓——
     * 钱包 1000 开一笔占用 100 的全仓后，逐仓还能开走 989。金额口径见 CrossOccupancyGuardTest。
     */
    @Test
    void 逐仓开仓_保证金加手续费全额过可用额度闸() {
        when(positionMapper.selectList(any())).thenReturn(List.of());

        service.doOpenPosition(UID, marketReq("LONG", "ISOLATED", 50, "1"));

        // @110 开1个币 50x：保证金 110/50=2.20 + 手续费 0.04，手续费也占额度，别只报保证金
        verify(crossMarginService).assertCanAfford(eq(UID), argThat(c -> c.compareTo(new BigDecimal("2.24")) == 0));
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

    /**
     * 加仓合并会让数量与占用一起变大：ΣV↑ → 账户真实安全半宽变窄，强平安全带若不作废，
     * 旧的过宽区间会把"新账户状态下已足以爆仓"的插针当带内免检放走。
     * 合并不改 symbol 集合、Redis 索引本不需要刷——但带的作废（bump）挂在 refreshUserIndex 上，
     * 所以加仓也必须汇入这个点，与新建仓位路径同款。
     */
    @Test
    void 全仓市价加仓并入_刷新用户索引作废安全带() {
        FuturesPosition lp = pos(1L, "LONG", FuturesPosition.CROSS, 50, "90", "1", "1.80");
        when(positionMapper.selectList(any())).thenReturn(List.of(lp));

        service.doOpenPosition(UID, marketReq("LONG", "CROSS", 50, "1"));

        verify(crossMarginService).refreshUserIndex(UID);
    }

    @Test
    void 无持仓_正常新建() {
        when(positionMapper.selectList(any())).thenReturn(List.of());

        service.doOpenPosition(UID, marketReq("LONG", "CROSS", 50, "1"));

        verify(positionMapper).insert(any(FuturesPosition.class));
        verify(positionIndexService).registerPositionIndex(any(FuturesPosition.class));
    }
}
