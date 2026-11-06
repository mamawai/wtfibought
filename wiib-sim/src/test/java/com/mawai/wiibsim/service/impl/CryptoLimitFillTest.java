package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.CryptoOrderRequest;
import com.mawai.wiibcommon.entity.CryptoOrder;
import com.mawai.wiibcommon.entity.CryptoPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.market.DepthStreamCache;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.ledger.LedgerTx;
import com.mawai.wiibsim.mapper.CryptoOrderMapper;
import com.mawai.wiibsim.service.BStockService;
import com.mawai.wiibsim.service.BookFillPricer;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.CryptoPositionService;
import com.mawai.wiibsim.service.MarginAccountService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.RedisLockUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 现货限价单：对面盘口在挂单价以内的量够整笔吃掉（没盘口看现价够不够得着）的挂上就能成交，
 * 当场按吃到的均价成交，不进挂单簿；不够整笔的照常挂着，被成交穿过按挂单价成交——不能拿限价单白躲滑点。
 * 现价 100；盘口卖一 100.5×0.5、卖二 101×10，买一 99.8×0.5、买二 99.5×10；手续费 0.1%。
 */
class CryptoLimitFillTest {

    private static final Long UID = 7L;
    private static final String SYM = "BTCUSDT";
    private static final String BOOK = "{\"bids\":[[\"99.8\",\"0.5\"],[\"99.5\",\"10\"]],"
            + "\"asks\":[[\"100.5\",\"0.5\"],[\"101\",\"10\"]]}";

    private UserService userService;
    private CryptoPositionService positionService;
    private CryptoOrderMapper mapper;
    private ZSetOperations<String, String> zset;
    private DepthStreamCache depth;
    private CryptoOrderServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userService = mock(UserService.class);
        positionService = mock(CryptoPositionService.class);
        mapper = mock(CryptoOrderMapper.class);
        zset = mock(ZSetOperations.class);
        depth = mock(DepthStreamCache.class);
        CacheService cache = mock(CacheService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        User user = new User();
        user.setId(UID);
        user.setIsBankrupt(false);
        user.setBalance(new BigDecimal("10000"));
        when(userService.getById(UID)).thenReturn(user);
        when(cache.getCryptoPrice(SYM)).thenReturn(new BigDecimal("100"));
        when(depth.spotSnapshot(SYM)).thenReturn(new DepthStreamCache.Snapshot(System.currentTimeMillis(), BOOK));
        when(redis.opsForStream()).thenReturn(mock(StreamOperations.class));
        when(redis.opsForZSet()).thenReturn(zset);
        doAnswer(inv -> {
            inv.<CryptoOrder>getArgument(0).setId(1L);
            return 1;
        }).when(mapper).insert(any(CryptoOrder.class));
        CryptoPosition held = new CryptoPosition();
        held.setQuantity(new BigDecimal("5"));
        when(positionService.findByUserAndSymbol(UID, SYM)).thenReturn(held);

        TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
        service = new CryptoOrderServiceImpl(userService, positionService, new TradingConfig(),
                mock(RedisLockUtil.class), mock(MarginAccountService.class), mock(CrossMarginService.class),
                redis, cache, mock(BStockService.class), mock(TradeFilterRegistry.class), tx, new LedgerTx(tx),
                new BookFillPricer(depth, redis));
        // ServiceImpl 的 baseMapper 靠 Spring 注入，脱离容器得自己塞
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    private static CryptoOrderRequest limit(String limitPrice) {
        CryptoOrderRequest req = new CryptoOrderRequest();
        req.setSymbol(SYM);
        req.setQuantity(BigDecimal.ONE);
        req.setOrderType("LIMIT");
        req.setLimitPrice(new BigDecimal(limitPrice));
        return req;
    }

    private CryptoOrder inserted() {
        ArgumentCaptor<CryptoOrder> order = ArgumentCaptor.forClass(CryptoOrder.class);
        verify(mapper).insert(order.capture());
        return order.getValue();
    }

    @Test
    void 挂单价以内的卖盘够整笔_当场按吃到的均价成交() {
        service.buy(UID, limit("101"));

        // 吃 0.5@100.5 + 0.5@101 均价 100.75；扣 100.75 + 手续费 0.10
        CryptoOrder order = inserted();
        assertThat(order.getOrderType()).isEqualTo("LIMIT");
        assertThat(order.getStatus()).isEqualTo("FILLED");
        assertThat(order.getFilledPrice()).isEqualByComparingTo("100.75");
        verify(userService).updateBalance(eq(UID), argThat(d -> d.compareTo(new BigDecimal("-100.85")) == 0));
        verify(userService, never()).freezeBalance(anyLong(), any());
        verify(zset, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void 挂单价以内的卖盘不够整笔_照常挂着_不按挂单价白拿() {
        // 100.6 以内只有 0.5，买 1 个：不能整笔按 100.6 成交躲掉 101 那档
        service.buy(UID, limit("100.6"));

        assertThat(inserted().getStatus()).isEqualTo("PENDING");
        verify(zset).add("crypto:limit:buy:" + SYM, "1", 100.6);
    }

    @Test
    void 买单挂价高于现价但够不着卖一_照常冻结挂单() {
        // 挂在现价和卖一之间：真实盘口里它只是排在买一，不能当场按挂单价拿到
        service.buy(UID, limit("100.2"));

        assertThat(inserted().getStatus()).isEqualTo("PENDING");
        verify(userService).freezeBalance(eq(UID), argThat(f -> f.compareTo(new BigDecimal("100.30")) == 0));
        verify(zset).add("crypto:limit:buy:" + SYM, "1", 100.2);
    }

    @Test
    void 挂单价以内的买盘够整笔_当场卖掉() {
        service.sell(UID, limit("99.5"));

        // 卖 0.5@99.8 + 0.5@99.5 均价 99.65
        CryptoOrder order = inserted();
        assertThat(order.getOrderSide()).isEqualTo("SELL");
        assertThat(order.getStatus()).isEqualTo("FILLED");
        assertThat(order.getFilledPrice()).isEqualByComparingTo("99.65");
        verify(positionService).reducePosition(UID, SYM, BigDecimal.ONE);
        verify(positionService, never()).freezePosition(anyLong(), anyString(), any());
    }

    @Test
    void 没有新鲜盘口_按现价判_成交价用现价() {
        when(depth.spotSnapshot(SYM)).thenReturn(null);

        service.buy(UID, limit("100.2"));

        assertThat(inserted().getFilledPrice()).isEqualByComparingTo("100");
    }

    @Test
    void 挂着的买单被穿过_按挂单价成交_不是穿过去那笔的价() {
        CryptoOrder order = new CryptoOrder();
        order.setId(1L);
        order.setUserId(UID);
        order.setSymbol(SYM);
        order.setOrderSide("BUY");
        order.setOrderType("LIMIT");
        order.setStatus("TRIGGERED");
        order.setQuantity(BigDecimal.ONE);
        order.setLimitPrice(new BigDecimal("100"));
        order.setTriggerPrice(new BigDecimal("99.5"));
        order.setFrozenAmount(new BigDecimal("100.10"));
        when(mapper.casUpdateToFilled(anyLong(), any(), any(), any())).thenReturn(1);

        service.processTriggeredOrder(order);

        verify(mapper).casUpdateToFilled(eq(1L), argThat(p -> p.compareTo(new BigDecimal("100")) == 0),
                argThat(a -> a.compareTo(new BigDecimal("100.00")) == 0), argThat(c -> c.compareTo(new BigDecimal("0.10")) == 0));
        // 冻结的正好够，没有差额退回
        verify(userService).deductFrozenBalance(UID, new BigDecimal("100.10"));
        verify(userService, never()).updateBalance(anyLong(), any());
    }
}
