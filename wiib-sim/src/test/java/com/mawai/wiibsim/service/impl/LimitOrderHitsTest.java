package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.CryptoOrder;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.TradeRecord;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.ledger.LedgerTx;
import com.mawai.wiibsim.mapper.CryptoOrderMapper;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.BStockService;
import com.mawai.wiibsim.service.CrossLiquidationService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.CryptoPositionService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.MarginAccountService;
import com.mawai.wiibsim.service.NoBookFillPricer;
import com.mawai.wiibsim.service.PriceTick;
import com.mawai.wiibsim.service.TradeEventPublisher;
import com.mawai.wiibsim.service.TradeHit;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.FairLockRegistry;
import com.mawai.wiibsim.util.RedisLockUtil;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限价单看成交价触发：躺在盘口的 maker 单要严格穿过挂单价（买单要有价低于、卖单要有价高于，正好等于不算），
 * 挂单时就穿价的合约 taker 单碰到就算；只认挂单之后的行情。逐笔成交、补漏 K 线走同一套。
 */
class LimitOrderHitsTest {

    private static final long T0 = 1700000000000L;
    private static final String SYM = "BTCUSDT";

    private static LocalDateTime at(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault());
    }

    private static PriceTick trade(String price, long rt) {
        return PriceTick.of(new TradeRecord(new BigDecimal(price), rt, rt));
    }

    private static PriceTick bar(long openTime, String high, String low) {
        return PriceTick.of(new KlineBar(openTime, openTime + 59_999L, new BigDecimal(high), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(low), BigDecimal.ONE));
    }

    // ==================== 合约 ====================

    private static final String OPEN_LONG_KEY = "futures:limit:open_long:" + SYM;

    private final FuturesOrderMapper orderMapper = mock(FuturesOrderMapper.class);
    private final CacheService cacheService = mock(CacheService.class);
    private final TradingConfig tradingConfig = mock(TradingConfig.class);
    private final FuturesSettlementServiceImpl futures = new FuturesSettlementServiceImpl(mock(UserService.class),
            mock(UserMapper.class), mock(FuturesPositionMapper.class), orderMapper, tradingConfig,
            mock(FuturesLeverageBracketRegistry.class), cacheService, mock(FuturesPositionIndexService.class),
            mock(FuturesRiskService.class), mock(CrossMarginService.class), mock(CrossLiquidationService.class),
            mock(FairLockRegistry.class), new LedgerTx(new TransactionTemplate(mock(PlatformTransactionManager.class))),
            mock(TradeEventPublisher.class), NoBookFillPricer.create());

    /** 开多挂单 50，挂在 createdAt；没带预估手续费按 maker */
    private FuturesOrder openLong(long createdAt) {
        FuturesOrder order = new FuturesOrder();
        order.setId(1L);
        order.setUserId(7L);
        order.setSymbol(SYM);
        order.setOrderSide("OPEN_LONG");
        order.setOrderType("LIMIT");
        order.setStatus("PENDING");
        order.setQuantity(BigDecimal.ONE);
        order.setLimitPrice(new BigDecimal("50"));
        order.setCreatedAt(at(createdAt));
        when(cacheService.zRangeByScoreWithScores(eq(OPEN_LONG_KEY), anyDouble(), anyDouble()))
                .thenReturn(Set.of(ZSetOperations.TypedTuple.of("1", 50d)));
        when(orderMapper.selectById(1L)).thenReturn(order);
        return order;
    }

    /** 挂单时就穿价的 taker 单（预估手续费按吃单费率收的）：碰到挂单价就成交 */
    @Test
    void 合约taker单成交价碰到挂单价就触发() {
        when(tradingConfig.calculateFuturesCommission(any(), anyBoolean(), eq(false))).thenReturn(new BigDecimal("0.01"));
        when(tradingConfig.calculateFuturesCommission(any(), anyBoolean(), eq(true))).thenReturn(new BigDecimal("0.03"));
        openLong(T0).setCommission(new BigDecimal("0.03"));

        List<TradeHit> hits = futures.limitHits(SYM, List.of(trade("50.5", T0 + 10), trade("50", T0 + 20)));

        assertThat(hits).singleElement().extracting(TradeHit::tick).isEqualTo(1);
    }

    @Test
    void 合约成交价正好等于挂单价不算_低于才触发() {
        openLong(T0);

        List<TradeHit> hits = futures.limitHits(SYM, List.of(trade("50", T0 + 10), trade("49.99", T0 + 20)));

        assertThat(hits).singleElement().satisfies(h -> {
            assertThat(h.tick()).isEqualTo(1);
            assertThat(h.userId()).isEqualTo(7L);
        });
        TradeHit.runByUser(hits);
        verify(orderMapper).casUpdateToTriggered(1L, new BigDecimal("49.99"));
    }

    /** 补漏 K 线：开多挂单在区间低点那根之前，触发，记的触发价是挂单价（那根开盘还没穿） */
    @Test
    void 合约挂单早于低点_触发() {
        openLong(T0);

        List<TradeHit> hits = futures.limitHits(SYM, List.of(bar(T0, "100", "40"), bar(T0 + 60_000L, "110", "90")));

        TradeHit.runByUser(hits);
        verify(orderMapper).casUpdateToTriggered(1L, new BigDecimal("50"));
    }

    /** 挂单在低点那根之后：它之后的最低只有 90，够不到 50 */
    @Test
    void 合约挂单晚于低点_不触发() {
        openLong(T0 + 60_000L);

        assertThat(futures.limitHits(SYM, List.of(bar(T0, "100", "40"), bar(T0 + 60_000L, "110", "90")))).isEmpty();
    }

    // ==================== 现货 ====================

    private static final String SELL_KEY = "crypto:limit:sell:" + SYM;

    @Test
    @SuppressWarnings("unchecked")
    void 现货卖单要有价高于挂单价_挂单之前的不算() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.rangeByScore(eq(SELL_KEY), anyDouble(), anyDouble())).thenReturn(Set.of("1"));
        RedisLockUtil lockUtil = mock(RedisLockUtil.class);
        when(lockUtil.tryLock(anyString(), anyLong())).thenReturn("v");
        CryptoOrderMapper mapper = mock(CryptoOrderMapper.class);
        CryptoOrder order = new CryptoOrder();
        order.setId(1L);
        order.setUserId(7L);
        order.setSymbol(SYM);
        order.setOrderSide("SELL");
        order.setOrderType("LIMIT");
        order.setStatus("PENDING");
        order.setLimitPrice(new BigDecimal("50"));
        order.setCreatedAt(at(T0 + 15));
        when(mapper.selectById(1L)).thenReturn(order);
        TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
        CryptoOrderServiceImpl spot = new CryptoOrderServiceImpl(mock(UserService.class),
                mock(CryptoPositionService.class), mock(TradingConfig.class), lockUtil,
                mock(MarginAccountService.class), mock(CrossMarginService.class),
                redis, mock(CacheService.class), mock(BStockService.class), mock(TradeFilterRegistry.class),
                tx, new LedgerTx(tx), NoBookFillPricer.create());
        // ServiceImpl 的 baseMapper 靠 Spring 注入，脱离容器得自己塞
        ReflectionTestUtils.setField(spot, "baseMapper", mapper);

        // 50.2 在挂单之前，50 正好等于不算，50.01 才是
        List<TradeHit> hits = spot.limitHits(SYM,
                List.of(trade("50.2", T0 + 10), trade("50", T0 + 20), trade("50.01", T0 + 30)));

        assertThat(hits).singleElement().extracting(TradeHit::tick).isEqualTo(2);
        TradeHit.runByUser(hits);
        verify(mapper).casUpdateToTriggered(1L, new BigDecimal("50.01"));
    }
}
