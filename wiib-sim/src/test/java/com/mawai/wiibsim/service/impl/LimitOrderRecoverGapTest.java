package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.CryptoOrder;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.CryptoOrderMapper;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.BStockService;
import com.mawai.wiibsim.service.BuffService;
import com.mawai.wiibsim.service.CrossLiquidationService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.CryptoPositionService;
import com.mawai.wiibsim.service.FundingRateService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.MarginAccountService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.RedisLockUtil;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限价单空窗补漏按挂单时间过滤：挂单之前的行情不算数。
 * 不看时间的话，10:04 挂的买单会被 10:01 的低点在 10:05 重连时打成成交。
 */
class LimitOrderRecoverGapTest {

    private static final long T0 = 1700000000000L;
    private static final String SYM = "BTCUSDT";

    /** 前一根插到 40，后一根只到 90：低点只在前一根里 */
    private static final List<KlineBar> BARS = List.of(
            bar(T0, T0 + 59_999L, "100", "40"),
            bar(T0 + 60_000L, T0 + 119_999L, "110", "90"));

    private static KlineBar bar(long openTime, long closeTime, String high, String low) {
        return new KlineBar(openTime, closeTime, new BigDecimal(low), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(high), BigDecimal.ONE);
    }

    private static LocalDateTime at(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault());
    }

    private static Set<ZSetOperations.TypedTuple<String>> hit() {
        return Set.of(ZSetOperations.TypedTuple.of("1", 50d));
    }

    // ==================== 合约 ====================

    private final FuturesOrderMapper orderMapper = mock(FuturesOrderMapper.class);
    private final CacheService cacheService = mock(CacheService.class);

    private FuturesSettlementServiceImpl futuresService() {
        FuturesSettlementServiceImpl service = new FuturesSettlementServiceImpl(mock(UserService.class),
                mock(UserMapper.class), mock(FuturesPositionMapper.class), orderMapper, mock(TradingConfig.class),
                mock(FuturesLeverageBracketRegistry.class), cacheService, mock(FuturesPositionIndexService.class),
                mock(FuturesRiskService.class), mock(CrossMarginService.class), mock(CrossLiquidationService.class),
                mock(RedisLockUtil.class), mock(FundingRateService.class));
        // triggerLimitOrder 走 AOP 代理，喂个 mock 代理才看得到它被调（真触发链路不在本用例范围）
        FuturesSettlementServiceImpl proxy = mock(FuturesSettlementServiceImpl.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(FuturesSettlementServiceImpl.class)).thenReturn(proxy);
        new com.mawai.wiibcommon.util.SpringUtils().setApplicationContext(ctx);
        return service;
    }

    private FuturesOrder futuresOrder(long createdAtMs) {
        FuturesOrder order = new FuturesOrder();
        order.setId(1L);
        order.setSymbol(SYM);
        order.setOrderSide("OPEN_LONG");
        order.setOrderType("LIMIT");
        order.setStatus("PENDING");
        order.setLimitPrice(new BigDecimal("50"));
        order.setCreatedAt(at(createdAtMs));
        return order;
    }

    /** 开多挂单在区间低点之前：低点穿过挂单价 → 触发，成交价按挂单价 */
    @Test
    void 合约挂单早于低点_触发() {
        String key = "futures:limit:open_long:" + SYM;
        when(cacheService.zRangeByScoreWithScores(key, 40d, Double.MAX_VALUE)).thenReturn(hit());
        when(orderMapper.selectById(1L)).thenReturn(futuresOrder(T0));

        futuresService().recoverGap(SYM, BARS);

        // CAS 落定后才摘索引，摘到就证明真走进了触发
        verify(cacheService, timeout(2000)).zRemove(key, "1");
    }

    /** 开多挂单在区间低点之后：它之后的区间最低只有 90，够不到 50 → 不触发 */
    @Test
    void 合约挂单晚于低点_不触发() {
        String key = "futures:limit:open_long:" + SYM;
        when(cacheService.zRangeByScoreWithScores(key, 40d, Double.MAX_VALUE)).thenReturn(hit());
        when(orderMapper.selectById(1L)).thenReturn(futuresOrder(T0 + 60_000L));

        futuresService().recoverGap(SYM, BARS);

        verify(cacheService, never()).zRemove(anyString(), any());
    }

    // ==================== 现货 ====================

    private final CryptoOrderMapper cryptoMapper = mock(CryptoOrderMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, String> zset = mock(ZSetOperations.class);

    @SuppressWarnings("unchecked")
    private CryptoOrderServiceImpl cryptoService() {
        when(redis.opsForZSet()).thenReturn(zset);
        RedisLockUtil lockUtil = mock(RedisLockUtil.class);
        when(lockUtil.tryLock(anyString(), anyLong())).thenReturn("v");
        CryptoOrderServiceImpl service = new CryptoOrderServiceImpl(mock(UserService.class),
                mock(CryptoPositionService.class), mock(TradingConfig.class), lockUtil,
                mock(MarginAccountService.class), mock(BuffService.class), mock(CrossMarginService.class),
                redis, mock(CacheService.class), mock(BStockService.class), mock(TradeFilterRegistry.class));
        // ServiceImpl 的 baseMapper 靠 Spring 注入，脱离容器得自己塞
        ReflectionTestUtils.setField(service, "baseMapper", cryptoMapper);
        CryptoOrderServiceImpl proxy = mock(CryptoOrderServiceImpl.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(CryptoOrderServiceImpl.class)).thenReturn(proxy);
        new com.mawai.wiibcommon.util.SpringUtils().setApplicationContext(ctx);
        return service;
    }

    private CryptoOrder cryptoOrder(long createdAtMs) {
        CryptoOrder order = new CryptoOrder();
        order.setId(1L);
        order.setSymbol(SYM);
        order.setOrderSide("BUY");
        order.setOrderType("LIMIT");
        order.setStatus("PENDING");
        order.setLimitPrice(new BigDecimal("50"));
        order.setCreatedAt(at(createdAtMs));
        return order;
    }

    @Test
    void 现货买单早于低点_触发() {
        String key = "crypto:limit:buy:" + SYM;
        when(zset.rangeByScoreWithScores(key, 40d, Double.MAX_VALUE)).thenReturn(hit());
        when(cryptoMapper.selectById(1L)).thenReturn(cryptoOrder(T0));

        cryptoService().recoverGap(SYM, BARS);

        verify(zset).remove(key, "1");
    }

    @Test
    void 现货买单晚于低点_不触发() {
        String key = "crypto:limit:buy:" + SYM;
        when(zset.rangeByScoreWithScores(key, 40d, Double.MAX_VALUE)).thenReturn(hit());
        when(cryptoMapper.selectById(1L)).thenReturn(cryptoOrder(T0 + 60_000L));

        cryptoService().recoverGap(SYM, BARS);

        verify(zset, never()).remove(anyString(), any());
    }

    /** 空 K 线一次索引都不许碰：拿不到行情就当没发生过 */
    @Test
    void 空K线直接返回() {
        cryptoService().recoverGap(SYM, List.of());

        verify(zset, never()).rangeByScoreWithScores(anyString(), anyDouble(), anyDouble());
    }
}
