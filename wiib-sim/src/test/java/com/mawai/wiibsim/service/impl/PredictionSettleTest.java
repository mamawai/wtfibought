package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.PredictionBuyRequest;
import com.mawai.wiibcommon.dto.PredictionPnlResponse;
import com.mawai.wiibcommon.entity.PredictionBet;
import com.mawai.wiibcommon.entity.PredictionRound;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.PolymarketPriceClient;
import com.mawai.wiibsim.mapper.PredictionBetMapper;
import com.mawai.wiibsim.mapper.PredictionRoundMapper;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.RedisLockUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
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

    /** 买入：份数 = 金额 ÷ 本回合卖一，手续费在金额之外另扣，扣的是 cost + fee */
    @Test
    void 买入按官方公式在金额之外另扣手续费() {
        when(redisLockUtil.executeWithLock(anyString(), anyLong(), anyLong(), any()))
                .thenAnswer(inv -> inv.getArgument(3, Supplier.class).get());
        long ws = windowAgo(0);
        when(cacheService.getPredictionAsk(ws, "UP")).thenReturn(new BigDecimal("0.50"));
        PredictionRound open = lockedRound(new BigDecimal("100"), ws);
        open.setStatus("OPEN");
        when(roundMapper.selectOne(any())).thenReturn(open);

        PredictionBuyRequest req = new PredictionBuyRequest();
        req.setSide("UP");
        req.setAmount(new BigDecimal("10"));
        service.buy(7L, req);

        // 20 份 @0.50：cost 10，fee = 20 × 0.07 × 0.5 × 0.5 = 0.35，扣 10.35；手续费落在注单上
        verify(userService).updateGameBalance(eq(7L), argThat(v -> v.compareTo(new BigDecimal("-10.35")) == 0));
        ArgumentCaptor<PredictionBet> bet = ArgumentCaptor.forClass(PredictionBet.class);
        verify(betMapper).insert(bet.capture());
        assertThat(bet.getValue().getBuyFee()).isEqualByComparingTo("0.35");
    }

    /** feed 跨窗停机，快照还停在上一回合：上一回合收官时的卖一不能拿来成交 */
    @Test
    void 只有上一回合的报价时拒绝买入() {
        when(cacheService.getPredictionAsk(windowAgo(1), "UP")).thenReturn(new BigDecimal("0.01"));

        PredictionBuyRequest req = new PredictionBuyRequest();
        req.setSide("UP");
        req.setAmount(new BigDecimal("10"));
        assertThatThrownBy(() -> service.buy(7L, req))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.PREDICTION_PRICE_UNAVAILABLE.getCode());
        verifyNoInteractions(userService);
    }

    /** 卖出：到手 = 份数 × 本回合买一 − 手续费 */
    @Test
    void 卖出到手额扣掉官方公式的手续费() {
        when(redisLockUtil.executeWithLock(anyString(), anyLong(), anyLong(), any()))
                .thenAnswer(inv -> inv.getArgument(3, Supplier.class).get());
        when(betMapper.selectById(9L)).thenReturn(bet("ACTIVE", new BigDecimal("20"), new BigDecimal("10")));
        long ws = windowAgo(0);
        PredictionRound open = lockedRound(new BigDecimal("100"), ws);
        open.setStatus("OPEN");
        when(roundMapper.selectById(1L)).thenReturn(open);
        when(cacheService.getPredictionBid(ws, "UP")).thenReturn(new BigDecimal("0.60"));
        when(betMapper.casSell(eq(9L), any())).thenReturn(1);

        service.sell(7L, 9L, null);

        // 20 份 @0.60：revenue 12，fee = 20 × 0.07 × 0.6 × 0.4 = 0.336，到手 11.664
        verify(userService).updateGameBalance(eq(7L), argThat(v -> v.compareTo(new BigDecimal("11.664")) == 0));
    }

    /** 部分卖出：本金和买入手续费都按卖出份数拆到卖出那行 */
    @Test
    void 部分卖出按份数拆买入手续费() {
        when(redisLockUtil.executeWithLock(anyString(), anyLong(), anyLong(), any()))
                .thenAnswer(inv -> inv.getArgument(3, Supplier.class).get());
        PredictionBet held = bet("ACTIVE", new BigDecimal("20"), new BigDecimal("10"));
        held.setBuyFee(new BigDecimal("0.35"));
        when(betMapper.selectById(9L)).thenReturn(held);
        long ws = windowAgo(0);
        PredictionRound open = lockedRound(new BigDecimal("100"), ws);
        open.setStatus("OPEN");
        when(roundMapper.selectById(1L)).thenReturn(open);
        when(cacheService.getPredictionBid(ws, "UP")).thenReturn(new BigDecimal("0.60"));
        when(betMapper.casPartialSell(eq(9L), any(), any(), any())).thenReturn(1);

        service.sell(7L, 9L, new BigDecimal("5"));

        // 卖 5 / 20 份：本金拆走 2.5，买入手续费拆走 0.0875
        verify(betMapper).casPartialSell(eq(9L), argThat(c -> c.compareTo(new BigDecimal("5")) == 0),
                argThat(c -> c.compareTo(new BigDecimal("2.5")) == 0),
                argThat(f -> f.compareTo(new BigDecimal("0.0875")) == 0));
        ArgumentCaptor<PredictionBet> sold = ArgumentCaptor.forClass(PredictionBet.class);
        verify(betMapper).insert(sold.capture());
        assertThat(sold.getValue().getBuyFee()).isEqualByComparingTo("0.0875");
    }

    /** 前端浮点分摊带出尾差：要卖的其实就是全部，按整笔卖出，不留 0 份零头 */
    @Test
    void 卖出份数带浮点尾差按整笔卖出() {
        when(redisLockUtil.executeWithLock(anyString(), anyLong(), anyLong(), any()))
                .thenAnswer(inv -> inv.getArgument(3, Supplier.class).get());
        when(betMapper.selectById(9L)).thenReturn(bet("ACTIVE", new BigDecimal("5.0468"), new BigDecimal("2.5234")));
        long ws = windowAgo(0);
        PredictionRound open = lockedRound(new BigDecimal("100"), ws);
        open.setStatus("OPEN");
        when(roundMapper.selectById(1L)).thenReturn(open);
        when(cacheService.getPredictionBid(ws, "UP")).thenReturn(new BigDecimal("0.60"));
        when(betMapper.casSell(eq(9L), any())).thenReturn(1);

        service.sell(7L, 9L, new BigDecimal("5.046799999999999"));

        verify(betMapper).casSell(eq(9L), any());
        verify(betMapper, never()).casPartialSell(anyLong(), any(), any(), any());
    }

    /** 个人盈亏同排行榜口径：已了结的扣买入手续费，持仓中的浮动盈亏也扣 */
    @Test
    void 个人盈亏扣买入手续费() {
        PredictionBet won = bet("WON", new BigDecimal("20"), new BigDecimal("10"));
        won.setPayout(new BigDecimal("20"));
        won.setBuyFee(new BigDecimal("0.35"));
        PredictionBet draw = bet("DRAW", new BigDecimal("20"), new BigDecimal("10"));
        draw.setPayout(new BigDecimal("10"));
        draw.setBuyFee(new BigDecimal("0.35"));
        // 破产取消的老注单 payout 是空
        PredictionBet cancelled = bet("CANCELLED", new BigDecimal("4"), new BigDecimal("2"));
        cancelled.setBuyFee(new BigDecimal("0.07"));
        long ws = windowAgo(0);
        PredictionBet active = bet("ACTIVE", new BigDecimal("10"), new BigDecimal("5"));
        active.setWindowStart(ws);
        active.setBuyFee(new BigDecimal("0.18"));
        when(betMapper.selectList(any())).thenReturn(List.of(won, draw, cancelled, active));
        when(cacheService.getPredictionBid(ws, "UP")).thenReturn(new BigDecimal("0.60"));

        PredictionPnlResponse pnl = service.getUserPnl(7L);

        // 赢的 20 − 10 − 0.35 = 9.65，退本金的亏手续费 0.35，破产取消的亏 2 + 0.07
        assertThat(pnl.getRealizedPnl()).isEqualByComparingTo("7.23");
        // 持仓市值 6 − 本金 5 − 买入手续费 0.18 = 0.82
        assertThat(pnl.getTotalPnl()).isEqualByComparingTo("8.05");
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
