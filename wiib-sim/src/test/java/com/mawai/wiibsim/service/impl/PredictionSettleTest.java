package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.PredictionBuyRequest;
import com.mawai.wiibcommon.entity.PredictionBet;
import com.mawai.wiibcommon.entity.PredictionRound;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.PolymarketPriceClient;
import com.mawai.wiibsim.mapper.PredictionBetMapper;
import com.mawai.wiibsim.mapper.PredictionRoundMapper;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.RedisLockUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 回合结算的四条出路：缓存有价照常派彩 / 缓存没价回源 REST 并补开盘价 / 超时仍没价作废退本金 /
 * 没到阈值就留给下次巡检。外加巡检对卡在 OPEN 的回合补锁重跑，以及"只卖得掉当前窗口"这条闸。
 * <p>
 * 钉的是"钱不能卡住也不能白送"：回合停在哪一步都得有人收尾，收尾之前那笔注单不许按新价套现。
 */
class PredictionSettleTest {

    private static final long WS = 1_700_000_000L;

    private PredictionRoundMapper roundMapper;
    private PredictionBetMapper betMapper;
    private UserService userService;
    private CacheService cacheService;
    private RedisLockUtil redisLockUtil;
    private PolymarketPriceClient priceClient;
    private PredictionServiceImpl service;

    @BeforeEach
    void setUp() {
        roundMapper = mock(PredictionRoundMapper.class);
        betMapper = mock(PredictionBetMapper.class);
        userService = mock(UserService.class);
        cacheService = mock(CacheService.class);
        redisLockUtil = mock(RedisLockUtil.class);
        priceClient = mock(PolymarketPriceClient.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

        when(redisLockUtil.tryLock(anyString(), anyLong())).thenReturn("lock-val");
        // 事务模板直接跑回调，断的是回调里的业务顺序
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        service = new PredictionServiceImpl(roundMapper, betMapper, userService, cacheService,
                redisLockUtil, mock(MarketBroadcaster.class), transactionTemplate, priceClient);
    }

    private static PredictionRound lockedRound(BigDecimal startPrice, long windowStart) {
        PredictionRound round = new PredictionRound();
        round.setId(1L);
        round.setWindowStart(windowStart);
        round.setStartPrice(startPrice);
        round.setStatus("LOCKED");
        return round;
    }

    private static PredictionBet bet(String status, BigDecimal contracts, BigDecimal cost) {
        PredictionBet b = new PredictionBet();
        b.setId(9L);
        b.setUserId(7L);
        b.setRoundId(1L);
        b.setSide("UP");
        b.setStatus(status);
        b.setContracts(contracts);
        b.setCost(cost);
        return b;
    }

    /** 当前窗口起点往前推 n 个 5 分钟窗口，避免用死时间戳算"多久以前" */
    private static long windowAgo(int windows) {
        long now = Instant.now().getEpochSecond();
        return now - (now % 300) - windows * 300L;
    }

    @Test
    void 缓存有收盘价时正常派彩() {
        when(roundMapper.selectOne(any())).thenReturn(lockedRound(new BigDecimal("100"), WS));
        when(cacheService.getPolymarketClosePrice(WS)).thenReturn(new BigDecimal("110"));
        when(roundMapper.casSettleRound(eq(1L), any(), eq("UP"))).thenReturn(1);
        when(betMapper.selectList(any())).thenReturn(List.of(bet("WON", new BigDecimal("5"), new BigDecimal("2"))));

        service.settleRound(WS);

        verify(betMapper).settleWon(1L, "UP");
        verify(betMapper).settleLost(1L, "DOWN");
        verify(userService).updateGameBalance(7L, new BigDecimal("5"));
        // 缓存命中就别再打 Polymarket
        verifyNoInteractions(priceClient);
    }

    @Test
    void 收盘价等于开盘价按Polymarket规则判UP() {
        when(roundMapper.selectOne(any())).thenReturn(lockedRound(new BigDecimal("100"), WS));
        when(cacheService.getPolymarketClosePrice(WS)).thenReturn(new BigDecimal("100"));
        when(roundMapper.casSettleRound(eq(1L), any(), eq("UP"))).thenReturn(1);
        when(betMapper.selectList(any())).thenReturn(List.of());

        service.settleRound(WS);

        verify(roundMapper).casSettleRound(1L, new BigDecimal("100"), "UP");
        verify(betMapper).settleWon(1L, "UP");
        verify(betMapper).settleLost(1L, "DOWN");
        // 没有平局这条路了：相等不退本金
        verify(betMapper, never()).settleDraw(anyLong());
    }

    @Test
    void 缓存缺价时回源REST并补上开盘价() {
        // 开盘价一直没回填过（建行时就是 null），定盘要先把它补上才比得出涨跌
        when(roundMapper.selectOne(any())).thenReturn(lockedRound(null, WS));
        when(cacheService.getPolymarketClosePrice(WS)).thenReturn(null);
        when(cacheService.getPolymarketOpenPrice(WS)).thenReturn(null);
        when(priceClient.fetch(WS)).thenReturn(new PolymarketPriceClient.CryptoPrice(
                new BigDecimal("100"), new BigDecimal("90"), true));
        when(roundMapper.casSettleRound(eq(1L), any(), eq("DOWN"))).thenReturn(1);
        when(betMapper.selectList(any())).thenReturn(List.of());

        service.settleRound(WS);

        verify(roundMapper).fillStartPrice(1L, new BigDecimal("100"));
        verify(roundMapper).casSettleRound(1L, new BigDecimal("90"), "DOWN");
        verify(cacheService).putPolymarketOpenPrice(WS, new BigDecimal("100"));
        verify(cacheService).putPolymarketClosePrice(WS, new BigDecimal("90"));
    }

    @Test
    void 超时仍取不到价则作废退本金() {
        long staleWs = windowAgo(20);   // 100 分钟前，早过作废阈值
        when(roundMapper.selectOne(any())).thenReturn(lockedRound(new BigDecimal("100"), staleWs));
        when(cacheService.getPolymarketClosePrice(staleWs)).thenReturn(null);
        when(priceClient.fetch(staleWs)).thenReturn(null);
        when(betMapper.selectCount(any())).thenReturn(1L);
        when(roundMapper.casVoidRound(1L)).thenReturn(1);
        when(betMapper.selectList(any())).thenReturn(List.of(bet("DRAW", new BigDecimal("5"), new BigDecimal("2"))));

        service.settleRound(staleWs);

        verify(betMapper).settleDraw(1L);
        verify(userService).updateGameBalance(7L, new BigDecimal("2"));   // 退的是 cost
        verify(roundMapper, never()).casSettleRound(anyLong(), any(), anyString());
    }

    /**
     * 巡检是"重跑"，所以幂等边界必须钉死：casSettleRound 的 WHERE 带 status='LOCKED'，
     * 返 0 就说明别人已经结过了，这一趟一分钱都不许动——不然一个回合派彩两遍。
     */
    @Test
    void CAS没抢到就不许再派一次彩() {
        when(roundMapper.selectOne(any())).thenReturn(lockedRound(new BigDecimal("100"), WS));
        when(cacheService.getPolymarketClosePrice(WS)).thenReturn(new BigDecimal("110"));
        when(roundMapper.casSettleRound(eq(1L), any(), eq("UP"))).thenReturn(0);

        service.settleRound(WS);

        verify(betMapper, never()).settleWon(anyLong(), anyString());
        verify(betMapper, never()).settleLost(anyLong(), anyString());
        verifyNoInteractions(userService);
    }

    /** 作废退款同理：casVoidRound 返 0 说明这回合已经被人收拾过，不能再退一次本金 */
    @Test
    void 作废的CAS没抢到就不许再退一次本金() {
        long staleWs = windowAgo(20);
        when(roundMapper.selectOne(any())).thenReturn(lockedRound(new BigDecimal("100"), staleWs));
        when(cacheService.getPolymarketClosePrice(staleWs)).thenReturn(null);
        when(priceClient.fetch(staleWs)).thenReturn(null);
        when(betMapper.selectCount(any())).thenReturn(1L);
        when(roundMapper.casVoidRound(1L)).thenReturn(0);

        service.settleRound(staleWs);

        verify(betMapper, never()).settleDraw(anyLong());
        verifyNoInteractions(userService);
    }

    @Test
    void 巡检把卡在OPEN的旧回合补锁后再结算() {
        long staleWs = windowAgo(3);
        PredictionRound open = lockedRound(new BigDecimal("100"), staleWs);
        open.setStatus("OPEN");   // lock 事件没到，回合一直没锁上
        when(roundMapper.selectUnsettledBefore(anyLong())).thenReturn(List.of(open));
        when(roundMapper.casLockRound(staleWs)).thenReturn(1);
        // 补锁之后 doSettle 再查一次，此时已是 LOCKED
        when(roundMapper.selectOne(any())).thenReturn(lockedRound(new BigDecimal("100"), staleWs));
        when(cacheService.getPolymarketClosePrice(staleWs)).thenReturn(new BigDecimal("110"));
        when(roundMapper.casSettleRound(eq(1L), any(), eq("UP"))).thenReturn(1);
        when(betMapper.selectList(any())).thenReturn(List.of());

        service.sweepStuckRounds();

        verify(roundMapper).casLockRound(staleWs);
        verify(roundMapper).casSettleRound(1L, new BigDecimal("110"), "UP");
    }

    /** 买入：份数 = 金额 ÷ 卖价，手续费在金额之外另扣，扣的是 cost + fee */
    @Test
    void 买入按官方公式在金额之外另扣手续费() {
        when(redisLockUtil.executeWithLock(anyString(), anyLong(), anyLong(), any()))
                .thenAnswer(inv -> inv.getArgument(3, Supplier.class).get());
        when(cacheService.getPredictionAsk("UP")).thenReturn(new BigDecimal("0.50"));
        PredictionRound open = lockedRound(new BigDecimal("100"), windowAgo(0));
        open.setStatus("OPEN");
        when(roundMapper.selectOne(any())).thenReturn(open);

        PredictionBuyRequest req = new PredictionBuyRequest();
        req.setSide("UP");
        req.setAmount(new BigDecimal("10"));
        service.buy(7L, req);

        // 20 份 @0.50：cost 10，fee = 20 × 0.07 × 0.5 × 0.5 = 0.35，扣 10.35
        verify(userService).updateGameBalance(eq(7L), argThat(v -> v.compareTo(new BigDecimal("-10.35")) == 0));
    }

    /** 卖出：到手 = 份数 × 买价 − 手续费 */
    @Test
    void 卖出到手额扣掉官方公式的手续费() {
        when(redisLockUtil.executeWithLock(anyString(), anyLong(), anyLong(), any()))
                .thenAnswer(inv -> inv.getArgument(3, Supplier.class).get());
        when(betMapper.selectById(9L)).thenReturn(bet("ACTIVE", new BigDecimal("20"), new BigDecimal("10")));
        PredictionRound open = lockedRound(new BigDecimal("100"), windowAgo(0));
        open.setStatus("OPEN");
        when(roundMapper.selectById(1L)).thenReturn(open);
        when(cacheService.getPredictionBid("UP")).thenReturn(new BigDecimal("0.60"));
        when(betMapper.casSell(eq(9L), any())).thenReturn(1);

        service.sell(7L, 9L, null);

        // 20 份 @0.60：revenue 12，fee = 20 × 0.07 × 0.6 × 0.4 = 0.336，到手 11.664
        verify(userService).updateGameBalance(eq(7L), argThat(v -> v.compareTo(new BigDecimal("11.664")) == 0));
    }

    @Test
    void 卖出只认当前窗口的回合() {
        when(redisLockUtil.executeWithLock(anyString(), anyLong(), anyLong(), any()))
                .thenAnswer(inv -> inv.getArgument(3, Supplier.class).get());
        when(betMapper.selectById(9L)).thenReturn(bet("ACTIVE", new BigDecimal("5"), new BigDecimal("2")));
        PredictionRound stale = lockedRound(new BigDecimal("100"), windowAgo(3));
        stale.setStatus("OPEN");   // 结果早已定死，只是没人把它锁上
        when(roundMapper.selectById(1L)).thenReturn(stale);

        assertThatThrownBy(() -> service.sell(7L, 9L, null))
                .isInstanceOf(BizException.class);
        // 一分钱都不能动：这一笔要走结算，不是按今天的盘口价卖
        verifyNoInteractions(userService);
    }

    @Test
    void 缺价但还没超时就留给下次巡检() {
        long recentWs = windowAgo(2);   // 10 分钟前，Polymarket 可能只是晚出数据
        when(roundMapper.selectOne(any())).thenReturn(lockedRound(new BigDecimal("100"), recentWs));
        when(cacheService.getPolymarketClosePrice(recentWs)).thenReturn(null);
        when(priceClient.fetch(recentWs)).thenReturn(null);
        when(betMapper.selectCount(any())).thenReturn(1L);

        service.settleRound(recentWs);

        verify(roundMapper, never()).casVoidRound(anyLong());
        verifyNoInteractions(userService);
    }
}
