package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.CryptoOrder;
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
import com.mawai.wiibsim.service.NoBookFillPricer;
import com.mawai.wiibsim.service.CryptoPositionService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.MarginAccountService;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限价单触发索引与DB的先后：DB是事实，索引跟着事实走。
 * <p>
 * 倒过来先摘索引再改DB，中间任一步没走成（抢不到锁、CAS抛异常），单子就永远停在 PENDING
 * 而索引里已经没它了——价格再穿越也没人盯，只有重启重建索引才找得回来。这个类只钉这一条。
 */
class LimitOrderIndexOrderingTest {

    private static final String BUY_KEY = "crypto:limit:buy:BTCUSDT";

    /** 一笔 49999 的成交，穿过 50000 的买单 */
    private static List<PriceTick> tradeThrough() {
        long now = System.currentTimeMillis();
        return List.of(PriceTick.of(new TradeRecord(new BigDecimal("49999"), now, now)));
    }

    private static CryptoOrder pendingBuy() {
        CryptoOrder order = new CryptoOrder();
        order.setId(1L);
        order.setUserId(7L);
        order.setSymbol("BTCUSDT");
        order.setOrderSide("BUY");
        order.setStatus("PENDING");
        order.setLimitPrice(new BigDecimal("50000"));
        order.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        return order;
    }

    /** 现货：抢不到执行锁就直接放手，命中的那条索引必须原地不动，下一笔还能捞到 */
    @Test
    @SuppressWarnings("unchecked")
    void 现货抢不到锁时不许摘索引() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.rangeByScore(eq(BUY_KEY), anyDouble(), anyDouble())).thenReturn(Set.of("1"));
        // tryLock 不打桩=返回 null，即"这单有人在处理"
        RedisLockUtil lockUtil = mock(RedisLockUtil.class);
        CryptoOrderMapper mapper = mock(CryptoOrderMapper.class);
        when(mapper.selectById(1L)).thenReturn(pendingBuy());

        TradeHit.runByUser(cryptoService(redis, lockUtil, mapper).limitHits("BTCUSDT", tradeThrough()));

        verify(lockUtil).tryLock("crypto:order:execute:1", 30);
        verify(zset, never()).remove(anyString(), any());
    }

    /**
     * 现货：CAS 改库这一步抛异常，索引同样必须留着。
     * 摘早了这单在库里还是 PENDING、却没人盯价，只能等每小时对账捞回来。
     */
    @Test
    @SuppressWarnings("unchecked")
    void 现货CAS抛异常时不许摘索引() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.rangeByScore(eq(BUY_KEY), anyDouble(), anyDouble())).thenReturn(Set.of("1"));
        RedisLockUtil lockUtil = mock(RedisLockUtil.class);
        when(lockUtil.tryLock("crypto:order:execute:1", 30)).thenReturn("lock-val");
        CryptoOrderMapper mapper = mock(CryptoOrderMapper.class);
        when(mapper.selectById(1L)).thenReturn(pendingBuy());
        when(mapper.casUpdateToTriggered(anyLong(), any())).thenThrow(new RuntimeException("CAS 炸了"));

        TradeHit.runByUser(cryptoService(redis, lockUtil, mapper).limitHits("BTCUSDT", tradeThrough()));

        verify(mapper).casUpdateToTriggered(anyLong(), any());
        verify(zset, never()).remove(anyString(), any());
    }

    /** 合约：扫描只查不摘，摘索引归触发方在CAS落定之后做 */
    @Test
    void 合约扫描不许原子取走索引() {
        CacheService cacheService = mock(CacheService.class);

        futuresService(cacheService).limitHits("BTCUSDT", tradeThrough());

        verify(cacheService).zRangeByScoreWithScores(eq("futures:limit:open_long:BTCUSDT"), anyDouble(), eq(Double.MAX_VALUE));
        verify(cacheService).zRangeByScoreWithScores(eq("futures:limit:close_long:BTCUSDT"), eq(0d), anyDouble());
        verify(cacheService, never()).zRangeByScoreAndRemove(anyString(), anyDouble(), anyDouble());
    }

    private CryptoOrderServiceImpl cryptoService(StringRedisTemplate redis, RedisLockUtil lockUtil, CryptoOrderMapper mapper) {
        TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
        CryptoOrderServiceImpl service = new CryptoOrderServiceImpl(mock(UserService.class),
                mock(CryptoPositionService.class), mock(TradingConfig.class), lockUtil, mock(MarginAccountService.class),
                mock(CrossMarginService.class), redis, mock(CacheService.class), mock(BStockService.class),
                mock(TradeFilterRegistry.class), tx, new LedgerTx(tx), NoBookFillPricer.create());
        // ServiceImpl 的 baseMapper 靠 Spring 注入，脱离容器得自己塞
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        return service;
    }

    private FuturesSettlementServiceImpl futuresService(CacheService cacheService) {
        return new FuturesSettlementServiceImpl(mock(UserService.class), mock(UserMapper.class),
                mock(FuturesPositionMapper.class), mock(FuturesOrderMapper.class), mock(TradingConfig.class),
                mock(FuturesLeverageBracketRegistry.class), cacheService, mock(FuturesPositionIndexService.class),
                mock(FuturesRiskService.class), mock(CrossMarginService.class), mock(CrossLiquidationService.class),
                mock(FairLockRegistry.class),
                new LedgerTx(new TransactionTemplate(mock(PlatformTransactionManager.class))),
                mock(TradeEventPublisher.class), NoBookFillPricer.create());
    }
}
