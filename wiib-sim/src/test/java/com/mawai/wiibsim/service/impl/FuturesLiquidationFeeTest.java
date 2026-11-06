package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.DepthStreamCache;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.ledger.LedgerTx;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.BankruptcyService;
import com.mawai.wiibsim.service.BookFillPricer;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesTradingService;
import com.mawai.wiibsim.service.TradeNotificationService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.FairLockRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 强平清算费 = 费率 × 成交额，从剩下的钱里扣、扣到 0 为止；扣完交易侧什么都不剩就破产。
 * 费率走快照：BTC/ETH 1.25%、HYPE 2%；手续费固定 0.05；仓位都是 1 张 @100。
 */
class FuturesLiquidationFeeTest {

    private static final BigDecimal FEE = new BigDecimal("0.05");

    private FuturesPositionMapper positionMapper;
    private FuturesOrderMapper orderMapper;
    private UserMapper userMapper;
    private CacheService cacheService;
    private DepthStreamCache depthCache;
    private CrossMarginService crossMarginService;
    private FuturesLeverageBracketRegistry brackets;
    private BankruptcyService bankruptcy;
    private FuturesRiskServiceImpl riskService;
    private CrossLiquidationServiceImpl crossLiquidation;
    private FuturesTradingServiceImpl trading;

    /** 爆仓撤单的 LambdaQueryWrapper 用了 .in()，裸 mock 下要先补 FuturesOrder 的表信息 */
    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FuturesOrder.class);
    }

    @BeforeEach
    void setUp() {
        positionMapper = mock(FuturesPositionMapper.class);
        orderMapper = mock(FuturesOrderMapper.class);
        userMapper = mock(UserMapper.class);
        cacheService = mock(CacheService.class);
        crossMarginService = mock(CrossMarginService.class);
        brackets = mock(FuturesLeverageBracketRegistry.class);
        bankruptcy = mock(BankruptcyService.class);
        TradingConfig tradingConfig = mock(TradingConfig.class);
        FuturesPositionIndexService indexService = mock(FuturesPositionIndexService.class);
        TradeNotificationService notification = mock(TradeNotificationService.class);
        FairLockRegistry lockRegistry = mock(FairLockRegistry.class);

        when(tradingConfig.calculateFuturesCommission(any(), anyBoolean(), anyBoolean())).thenReturn(FEE);
        // 维持保证金给到 50：逐仓用例的复核都判满足
        when(brackets.calcMaintenanceMargin(anyString(), any())).thenReturn(new BigDecimal("50"));
        when(positionMapper.casClosePosition(anyLong(), anyString(), any(), any())).thenReturn(1);
        when(lockRegistry.tryLockAsUser(anyString())).thenReturn(mock(Lock.class));

        // 盘口默认读不到（成交价走兜底价），按盘口扫的用例自己塞快照
        depthCache = mock(DepthStreamCache.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForStream()).thenReturn(mock(StreamOperations.class));
        BookFillPricer pricer = new BookFillPricer(depthCache, redis);

        TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
        LedgerTx ledgerTx = new LedgerTx(tx);
        TradeFilterRegistry filterRegistry = new TradeFilterRegistry(mock(BinanceRestClient.class));
        riskService = new FuturesRiskServiceImpl(positionMapper, orderMapper, userMapper, tradingConfig,
                lockRegistry, cacheService, indexService, brackets, crossMarginService,
                notification, tx, ledgerTx, filterRegistry, bankruptcy, pricer);
        crossLiquidation = new CrossLiquidationServiceImpl(crossMarginService, positionMapper, orderMapper,
                tradingConfig, cacheService, indexService, lockRegistry, notification,
                new CrossBandRegistry(), riskService, tx, filterRegistry, userMapper, bankruptcy, pricer);
        trading = new FuturesTradingServiceImpl(mock(UserService.class), userMapper, positionMapper, orderMapper,
                tradingConfig, lockRegistry, cacheService, indexService, brackets, crossMarginService,
                filterRegistry, new MessageCatalog(), ledgerTx, riskService, pricer);
    }

    private static FuturesPosition position(long id, String symbol, String mode, String margin) {
        FuturesPosition p = new FuturesPosition();
        p.setId(id);
        p.setUserId(7L);
        p.setSymbol(symbol);
        p.setSide("LONG");
        p.setStatus("OPEN");
        p.setMarginMode(mode);
        p.setLeverage(10);
        p.setEntryPrice(new BigDecimal("100"));
        p.setQuantity(BigDecimal.ONE);
        p.setMargin(new BigDecimal(margin));
        return p;
    }

    private List<FuturesOrder> insertedOrders(int count) {
        ArgumentCaptor<FuturesOrder> captor = ArgumentCaptor.forClass(FuturesOrder.class);
        verify(orderMapper, times(count)).insert(captor.capture());
        return captor.getAllValues();
    }

    // ==================== 逐仓 ====================

    @Test
    void 逐仓剩下的够付_收满清算费_其余返还() {
        when(positionMapper.selectById(9L)).thenReturn(position(9L, "BTCUSDT", FuturesPosition.ISOLATED, "50"));

        riskService.doForceClose(9L, new BigDecimal("90"), System.currentTimeMillis());

        // 剩 50 − 10 − 0.05 = 39.95；清算费 1.25% × 90 = 1.13；返还 38.82
        FuturesOrder order = insertedOrders(1).getFirst();
        assertThat(order.getLiquidationFee()).isEqualByComparingTo("1.13");
        assertThat(order.getCommission()).isEqualByComparingTo("0.05");
        assertThat(order.getRealizedPnl()).isEqualByComparingTo("-10");
        ArgumentCaptor<BigDecimal> back = ArgumentCaptor.forClass(BigDecimal.class);
        verify(userMapper).atomicUpdateBalance(eq(7L), back.capture());
        assertThat(back.getValue()).isEqualByComparingTo("38.82");
        verify(bankruptcy).bankruptIfNothingLeft(7L);
    }

    @Test
    void 逐仓剩下的不够付_清算费扣到零_不返还() {
        when(positionMapper.selectById(9L)).thenReturn(position(9L, "BTCUSDT", FuturesPosition.ISOLATED, "10"));

        riskService.doForceClose(9L, new BigDecimal("90.5"), System.currentTimeMillis());

        // 剩 10 − 9.5 − 0.05 = 0.45，清算费本该 1.13，只收 0.45
        assertThat(insertedOrders(1).getFirst().getLiquidationFee()).isEqualByComparingTo("0.45");
        verify(userMapper, never()).atomicUpdateBalance(anyLong(), any());
        verify(bankruptcy).bankruptIfNothingLeft(7L);
    }

    @Test
    void 逐仓越过破产价_按破产价记_手续费和清算费都不收() {
        when(positionMapper.selectById(9L)).thenReturn(position(9L, "BTCUSDT", FuturesPosition.ISOLATED, "10"));

        riskService.doForceClose(9L, new BigDecimal("85"), System.currentTimeMillis());

        // 破产价 90，亏损记成保证金 10
        FuturesOrder order = insertedOrders(1).getFirst();
        assertThat(order.getFilledPrice()).isEqualByComparingTo("90");
        assertThat(order.getRealizedPnl()).isEqualByComparingTo("-10");
        assertThat(order.getCommission()).isEqualByComparingTo("0");
        assertThat(order.getLiquidationFee()).isEqualByComparingTo("0");
        verify(userMapper, never()).atomicUpdateBalance(anyLong(), any());
    }

    // ==================== 手动平仓碰上已到强平 ====================

    private static FuturesCloseRequest marketCloseAll() {
        FuturesCloseRequest req = new FuturesCloseRequest();
        req.setPositionId(9L);
        req.setOrderType("MARKET");
        return req;
    }

    @Test
    void 手动市价平仓时标记价已到强平_按强平处理_照收清算费() {
        when(positionMapper.selectById(9L)).thenReturn(position(9L, "BTCUSDT", FuturesPosition.ISOLATED, "10"));
        when(cacheService.getMarkPrice("BTCUSDT")).thenReturn(new BigDecimal("90.5"));

        FuturesOrderResponse resp = trading.closePosition(7L, marketCloseAll());

        assertThat(resp.getStatus()).isEqualTo("LIQUIDATED");
        assertThat(resp.getLiquidationFee()).isEqualByComparingTo("0.45");
        verify(positionMapper).casClosePosition(eq(9L), eq("LIQUIDATED"), any(), any());
        verify(positionMapper, never()).casClosePosition(anyLong(), eq("CLOSED"), any(), any());
    }

    @Test
    void 手动市价平仓时没到强平_照常平仓() {
        when(positionMapper.selectById(9L)).thenReturn(position(9L, "BTCUSDT", FuturesPosition.ISOLATED, "10"));
        when(cacheService.getMarkPrice("BTCUSDT")).thenReturn(new BigDecimal("99"));
        when(cacheService.getFuturesPrice("BTCUSDT")).thenReturn(new BigDecimal("99"));
        // 10 − 1 = 9 > 0.4
        when(brackets.calcMaintenanceMargin(anyString(), any())).thenReturn(new BigDecimal("0.4"));

        FuturesOrderResponse resp = trading.closePosition(7L, marketCloseAll());

        assertThat(resp.getStatus()).isEqualTo("FILLED");
        verify(positionMapper).casClosePosition(eq(9L), eq("CLOSED"), any(), any());
        verify(positionMapper, never()).casClosePosition(anyLong(), eq("LIQUIDATED"), any(), any());
    }

    @Test
    void 手动市价平仓有新鲜盘口_按买盘扫出的均价成交() {
        when(positionMapper.selectById(9L)).thenReturn(position(9L, "BTCUSDT", FuturesPosition.ISOLATED, "10"));
        when(cacheService.getMarkPrice("BTCUSDT")).thenReturn(new BigDecimal("99"));
        when(cacheService.getFuturesPrice("BTCUSDT")).thenReturn(new BigDecimal("99"));
        when(brackets.calcMaintenanceMargin(anyString(), any())).thenReturn(new BigDecimal("0.4"));
        // 平多是卖：吃买一 0.4@99.5、买二 0.6@99.4 → 均价 99.44
        when(depthCache.snapshot("BTCUSDT")).thenReturn(new DepthStreamCache.Snapshot(System.currentTimeMillis(),
                "{\"bids\":[[\"99.5\",\"0.4\"],[\"99.4\",\"5\"]],\"asks\":[[\"99.6\",\"5\"]]}"));

        FuturesOrderResponse resp = trading.closePosition(7L, marketCloseAll());

        assertThat(resp.getFilledPrice()).isEqualByComparingTo("99.44");
        verify(positionMapper).casClosePosition(eq(9L), eq("CLOSED"), eq(new BigDecimal("99.44000000")), any());
    }

    @Test
    void 全仓手动平仓时账户已够爆仓_不让平() {
        when(positionMapper.selectById(9L)).thenReturn(position(9L, "BTCUSDT", FuturesPosition.CROSS, "10"));
        // 净值 −1 ≤ 维持保证金 0
        when(crossMarginService.snapshot(7L)).thenReturn(new CrossMarginService.CrossAccount(BigDecimal.ONE,
                new BigDecimal("-2"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(position(9L, "BTCUSDT", FuturesPosition.CROSS, "10")), Map.of()));

        assertThatThrownBy(() -> trading.closePosition(7L, marketCloseAll()))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.FUTURES_LIQUIDATING.getCode());
        verify(positionMapper, never()).casClosePosition(anyLong(), anyString(), any(), any());
    }

    @Test
    void 反手时已到强平_被强平后不再反向开仓() {
        when(positionMapper.selectById(9L)).thenReturn(position(9L, "BTCUSDT", FuturesPosition.ISOLATED, "10"));
        when(cacheService.getMarkPrice("BTCUSDT")).thenReturn(new BigDecimal("90.5"));

        FuturesTradingService.ReverseResult result = trading.reversePosition(7L, 9L);

        assertThat(result.closed().getStatus()).isEqualTo("LIQUIDATED");
        assertThat(result.opened()).isNull();
        assertThat(result.openError()).isNotBlank();
        verify(positionMapper, never()).insert(any(FuturesPosition.class));
    }

    // ==================== 全仓 ====================

    /** 锁内重判用的快照：净值 0 ≤ 维持保证金 0 → 判爆；balance 是算清算费上限用的余额 */
    private void snapshot(String balance, FuturesPosition... positions) {
        when(crossMarginService.snapshot(7L, null, null)).thenReturn(new CrossMarginService.CrossAccount(
                new BigDecimal(balance), new BigDecimal(balance).negate(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, List.of(positions), Map.of()));
        for (FuturesPosition p : positions) {
            when(positionMapper.selectById(p.getId())).thenReturn(p);
        }
        when(cacheService.getMarkPrice(anyString())).thenReturn(new BigDecimal("90"));
    }

    private BigDecimal settled() {
        ArgumentCaptor<BigDecimal> settle = ArgumentCaptor.forClass(BigDecimal.class);
        verify(crossMarginService).settle(eq(7L), settle.capture());
        return settle.getValue();
    }

    @Test
    void 全仓余额够_收满清算费() {
        snapshot("100", position(9L, "BTCUSDT", FuturesPosition.CROSS, "10"));

        crossLiquidation.liquidateAll(7L, null, null, System.currentTimeMillis(), List.of(9L));

        // 盈亏 −10、手续费 0.05，清算费 1.13 → 结算 −11.18
        assertThat(insertedOrders(1).getFirst().getLiquidationFee()).isEqualByComparingTo("1.13");
        assertThat(settled()).isEqualByComparingTo("-11.18");
        verify(userMapper).selectByIdForUpdate(7L);
        verify(bankruptcy).bankruptIfNothingLeft(7L);
    }

    @Test
    void 全仓余额不够_各仓按比例压_余额正好扣到零() {
        snapshot("21", position(9L, "BTCUSDT", FuturesPosition.CROSS, "10"),
                position(10L, "HYPEUSDT", FuturesPosition.CROSS, "10"));

        crossLiquidation.liquidateAll(7L, null, null, System.currentTimeMillis(), List.of(9L, 10L));

        // 两仓结完 −20.10，余额只剩 0.90；清算费 BTC 1.13 + HYPE 1.80 = 2.93
        // 按比例向下取整 0.34 + 0.55，零头 0.01 补给清算费大的 HYPE
        List<FuturesOrder> orders = insertedOrders(2);
        assertThat(orders.get(0).getLiquidationFee()).isEqualByComparingTo("0.34");
        assertThat(orders.get(1).getLiquidationFee()).isEqualByComparingTo("0.56");
        assertThat(settled()).isEqualByComparingTo("-21.00");
    }

    @Test
    void 全仓按比例压_零头不落到原本清算费为零的仓() {
        FuturesPosition dust = position(11L, "DOGEUSDT", FuturesPosition.CROSS, "1");
        dust.setQuantity(new BigDecimal("0.001"));
        snapshot("22.41", position(9L, "BTCUSDT", FuturesPosition.CROSS, "10"),
                position(10L, "ETHUSDT", FuturesPosition.CROSS, "10"), dust);

        crossLiquidation.liquidateAll(7L, null, null, System.currentTimeMillis(), List.of(9L, 10L, 11L));

        // 三仓结完 −20.16，余额剩 2.25；清算费 1.13 + 1.13 + 尘埃仓 0.00 = 2.26
        // 按比例取整 1.12 + 1.12 + 0，零头 0.01 补给 BTC，尘埃仓还是 0
        List<FuturesOrder> orders = insertedOrders(3);
        assertThat(orders.get(0).getLiquidationFee()).isEqualByComparingTo("1.13");
        assertThat(orders.get(1).getLiquidationFee()).isEqualByComparingTo("1.12");
        assertThat(orders.get(2).getLiquidationFee()).isEqualByComparingTo("0");
        assertThat(settled()).isEqualByComparingTo("-22.41");
    }

    @Test
    void 全仓已经亏穿_不收清算费() {
        snapshot("15", position(9L, "BTCUSDT", FuturesPosition.CROSS, "10"),
                position(10L, "ETHUSDT", FuturesPosition.CROSS, "10"));

        crossLiquidation.liquidateAll(7L, null, null, System.currentTimeMillis(), List.of(9L, 10L));

        // 15 − 20.10 < 0
        insertedOrders(2).forEach(o -> assertThat(o.getLiquidationFee()).isEqualByComparingTo("0"));
        assertThat(settled()).isEqualByComparingTo("-20.10");
    }
}
