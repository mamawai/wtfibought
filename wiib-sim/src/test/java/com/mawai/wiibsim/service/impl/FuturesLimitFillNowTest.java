package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
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
import com.mawai.wiibsim.service.BookFillPricer;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.FairLockRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 合约限价单挂上就能成交就当场吃单：盘口在挂单价以内够整笔，就按盘口加权均价成交、收吃单费，不挂单；
 * 吃不满整笔才挂着等别人来吃，按挂单费率收；feed 30 秒没写过标记价时判断不了能不能成交，拒单。
 * 盘口：卖 100.0×1、100.5×2，买 99.5×1、99.0×2；最新价 99.8。
 */
class FuturesLimitFillNowTest {

    private static final Long UID = 7L;
    private static final String SYMBOL = "BTCUSDT";
    private static final String LEVELS = "\"bids\":[[\"99.5\",\"1\"],[\"99.0\",\"2\"]],"
            + "\"asks\":[[\"100.0\",\"1\"],[\"100.5\",\"2\"]]";

    private FuturesPositionMapper positionMapper;
    private FuturesOrderMapper orderMapper;
    private CacheService cacheService;
    private FuturesTradingServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        UserService userService = mock(UserService.class);
        UserMapper userMapper = mock(UserMapper.class);
        positionMapper = mock(FuturesPositionMapper.class);
        orderMapper = mock(FuturesOrderMapper.class);
        cacheService = mock(CacheService.class);
        FuturesLeverageBracketRegistry bracketRegistry = mock(FuturesLeverageBracketRegistry.class);

        User user = new User();
        user.setId(UID);
        user.setBalance(new BigDecimal("10000"));
        user.setIsBankrupt(false);
        when(userService.getById(UID)).thenReturn(user);
        long now = System.currentTimeMillis();
        when(cacheService.getFuturesPrice(SYMBOL)).thenReturn(new BigDecimal("99.8"));
        when(cacheService.getMarkPrice(SYMBOL)).thenReturn(new BigDecimal("99.8"));
        when(cacheService.getMarkPriceAt(SYMBOL)).thenReturn(now);
        when(bracketRegistry.getEffectiveMaxLeverage(anyString(), any())).thenReturn(150);
        when(userMapper.atomicSettleBalance(anyLong(), any())).thenReturn(new BigDecimal("10000"));
        when(userMapper.atomicUpdateBalance(anyLong(), any())).thenReturn(new BigDecimal("10000"));
        when(positionMapper.casClosePosition(anyLong(), anyString(), any(), any())).thenReturn(1);
        // 落库回填 id：挂单进索引要用
        doAnswer(inv -> {
            inv.<FuturesOrder>getArgument(0).setId(100L);
            return 1;
        }).when(orderMapper).insert(any(FuturesOrder.class));

        DepthStreamCache depth = mock(DepthStreamCache.class);
        when(depth.snapshot(SYMBOL)).thenReturn(new DepthStreamCache.Snapshot(now, "{\"E\":" + now + "," + LEVELS + "}"));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForStream()).thenReturn(mock(StreamOperations.class));

        service = new FuturesTradingServiceImpl(
                userService, userMapper, positionMapper, orderMapper,
                new TradingConfig(), mock(FairLockRegistry.class), cacheService,
                mock(FuturesPositionIndexService.class), bracketRegistry, mock(CrossMarginService.class),
                new TradeFilterRegistry(mock(BinanceRestClient.class)),
                new MessageCatalog(), new LedgerTx(new TransactionTemplate(mock(PlatformTransactionManager.class))),
                mock(FuturesRiskService.class), new BookFillPricer(depth, redis));
    }

    private static FuturesOpenRequest limitLong(String qty, String limitPrice) {
        FuturesOpenRequest r = new FuturesOpenRequest();
        r.setSymbol(SYMBOL);
        r.setSide("LONG");
        r.setMarginMode(FuturesPosition.CROSS);
        r.setLeverage(10);
        r.setQuantity(new BigDecimal(qty));
        r.setOrderType("LIMIT");
        r.setLimitPrice(new BigDecimal(limitPrice));
        return r;
    }

    private FuturesOrder insertedOrder() {
        ArgumentCaptor<FuturesOrder> captor = ArgumentCaptor.forClass(FuturesOrder.class);
        verify(orderMapper).insert(captor.capture());
        return captor.getValue();
    }

    /** 挂 100.5 买 2：卖盘 100.0×1 + 100.5×1 吃满，按加权均价 100.25 成交，吃单费 200.50×0.04%=0.08 */
    @Test
    void 限价开多_盘口在挂单价以内够整笔_当场吃单成交() {
        when(positionMapper.selectList(any())).thenReturn(List.of());

        service.doOpenPosition(UID, limitLong("2", "100.5"));

        FuturesOrder order = insertedOrder();
        assertThat(order.getOrderType()).isEqualTo("LIMIT");
        assertThat(order.getStatus()).isEqualTo("FILLED");
        assertThat(order.getLimitPrice()).isEqualByComparingTo("100.5");
        assertThat(order.getFilledPrice()).isEqualByComparingTo("100.25");
        assertThat(order.getCommission()).isEqualByComparingTo("0.08");
        verify(positionMapper).insert(argThat((FuturesPosition p) -> p.getEntryPrice().compareTo(new BigDecimal("100.25")) == 0));
        verify(cacheService, never()).zAdd(anyString(), anyString(), anyDouble());
    }

    /** 挂 100.2 买 2：100.2 以内只有 1 个，吃不满整笔，挂着，挂单费 200.40×0.02%=0.04 */
    @Test
    void 限价开多_吃不满整笔_挂着按挂单费率收() {
        when(positionMapper.selectList(any())).thenReturn(List.of());

        service.doOpenPosition(UID, limitLong("2", "100.2"));

        FuturesOrder order = insertedOrder();
        assertThat(order.getStatus()).isEqualTo("PENDING");
        assertThat(order.getCommission()).isEqualByComparingTo("0.04");
        verify(positionMapper, never()).insert(any(FuturesPosition.class));
        verify(cacheService).zAdd(eq("futures:limit:open_long:" + SYMBOL), eq("100"), anyDouble());
    }

    /** 逐仓多 1 @100、保证金 10，挂 99.5 卖出平仓：买一 99.5×1 够，当场成交，吃单费 99.50×0.04%=0.04 */
    @Test
    void 限价平多_盘口够_当场吃单平仓() {
        FuturesPosition pos = new FuturesPosition();
        pos.setId(1L);
        pos.setUserId(UID);
        pos.setSymbol(SYMBOL);
        pos.setSide("LONG");
        pos.setMarginMode(FuturesPosition.ISOLATED);
        pos.setLeverage(10);
        pos.setEntryPrice(new BigDecimal("100"));
        pos.setQuantity(BigDecimal.ONE);
        pos.setMargin(new BigDecimal("10"));
        pos.setStatus("OPEN");
        when(positionMapper.selectById(1L)).thenReturn(pos);
        FuturesCloseRequest req = new FuturesCloseRequest();
        req.setPositionId(1L);
        req.setQuantity(BigDecimal.ONE);
        req.setOrderType("LIMIT");
        req.setLimitPrice(new BigDecimal("99.5"));

        service.doClosePosition(UID, req);

        verify(positionMapper).casClosePosition(eq(1L), eq("CLOSED"),
                argThat(p -> p.compareTo(new BigDecimal("99.5")) == 0), any());
        FuturesOrder order = insertedOrder();
        assertThat(order.getOrderType()).isEqualTo("LIMIT");
        assertThat(order.getStatus()).isEqualTo("FILLED");
        assertThat(order.getCommission()).isEqualByComparingTo("0.04");
        verify(cacheService, never()).zAdd(anyString(), anyString(), anyDouble());
    }

    /** feed 30 秒没写过标记价：限价单拒收，不下单也不挂单 */
    @Test
    void feed断了_限价单拒收() {
        when(positionMapper.selectList(any())).thenReturn(List.of());
        when(cacheService.getMarkPriceAt(SYMBOL)).thenReturn(System.currentTimeMillis() - 60_000L);

        assertThatThrownBy(() -> service.doOpenPosition(UID, limitLong("2", "100.2")))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.CRYPTO_PRICE_UNAVAILABLE.getCode());
        verify(orderMapper, never()).insert(any(FuturesOrder.class));
        verify(cacheService, never()).zAdd(anyString(), anyString(), anyDouble());
    }
}
