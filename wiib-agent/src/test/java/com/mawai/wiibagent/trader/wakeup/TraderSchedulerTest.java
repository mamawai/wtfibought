package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.market.TradeEvent;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import java.util.Set;
import com.mawai.wiibquant.market.domain.KlineClosedEvent;
import com.mawai.wiibquant.mapper.EconCalendarMapper;
import com.mawai.wiibquant.task.EconCalendarCollector;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.mockito.Mockito.doAnswer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraderSchedulerTest {

    // 2026-07-27 17:00:00 UTC 整点前 1ms —— Binance closeTime 口径 xx:59:59.999
    private static final long H1_CLOSE = 1785171599999L;
    private static final long H1_BOUNDARY = 1785171600000L;
    // 2026-07-27 00:00:00 UTC（日线边界）
    private static final long DAY_BOUNDARY = 1785110400000L;

    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final TraderWakeupRunner runner = mock(TraderWakeupRunner.class);
    private final WatchSentinel watchSentinel = mock(WatchSentinel.class);
    private final TraderWatchStore watchStore = mock(TraderWatchStore.class);

    /** 闸用真类配空 mock：查不到待公布事件就是已完成的放行信号，例行触发与没有闸时一样就地同步发 */
    private TraderScheduler sched() {
        return sched(new EconCalendarGate(mock(EconCalendarMapper.class), mock(EconCalendarCollector.class)));
    }

    private TraderScheduler sched(EconCalendarGate gate) {
        return new TraderScheduler(traderMapper, runner, new MessageCatalog(), gate, watchSentinel, watchStore);
    }

    private AiTrader trader1h() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setIntervalCode("1h");
        return t;
    }

    /** 一根刚收盘的 5m：墙钟钉在收盘那一刻 */
    private KlineClosedEvent fresh(TraderScheduler s, String symbol, long closeTime) {
        s.nowMs = () -> closeTime + 1;
        return new KlineClosedEvent(this, symbol, "5m", closeTime);
    }

    /** 收盘超过 5 分钟的旧事件（停机后重放的积压）：收盘类盯盘照常判，不发例行、不留 SKIPPED */
    @Test
    void staleKlineEventsDoNotWake() {
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + TraderScheduler.STALE_EVENT_MS + 1;

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", H1_CLOSE));
        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY - 1));

        verify(watchSentinel).closeHits(eq("BTCUSDT"), eq(H1_CLOSE), any());
        verify(watchSentinel).closeHits(eq("BTCUSDT"), eq(DAY_BOUNDARY - 1), any());
        verify(runner, after(300).never()).wake(any(), anyLong());
        verify(runner, never()).recordSkipped(any(), anyLong());
    }

    private AlertTrigger trig() {
        return new AlertTrigger("BTCUSDT", new BigDecimal("1.2"), new BigDecimal("63120"), AlertTrigger.DOWN, H1_BOUNDARY);
    }

    /** 新加坡时间 2026-07-27 hh:mm 的 epoch ms（与 DAY_BOUNDARY 同一天；+8 整时区，整点即 UTC 整点） */
    private static long bj(int h, int m) {
        return ZonedDateTime.of(2026, 7, 27, h, m, 0, 0, BeijingTime.ZONE).toInstant().toEpochMilli();
    }

    private AiTrader nightTrader() {
        AiTrader t = trader1h();
        t.setWakeWindow("21:00-08:30");
        return t;
    }

    // ---------- 唤醒时段 ----------

    /** 正午整点收盘（新加坡 12:00 = UTC 04:00，四档全是边界）：夜间档不醒，不写 SKIPPED */
    @Test
    void routineWakeSkippedOutsideWindow() {
        when(traderMapper.selectList(any())).thenReturn(List.of(nightTrader()));
        TraderScheduler s = sched();

        s.onKlineClosed(fresh(s, "BTCUSDT",bj(12, 0) - 1));

        verify(runner, after(300).never()).wake(any(), anyLong());
        verify(runner, never()).recordSkipped(any(), anyLong());
    }

    /** 时段内（22:00）照常醒 */
    @Test
    void routineWakeFiresInsideWindow() {
        when(traderMapper.selectList(any())).thenReturn(List.of(nightTrader()));
        TraderScheduler s = sched();

        s.onKlineClosed(fresh(s, "BTCUSDT",bj(22, 0) - 1));

        verify(runner, timeout(2_000)).wake(any(AiTrader.class), eq(bj(22, 0)));
    }

    /** 时段外警报也不叫（领的时候按库里那份配置的时段判） */
    @Test
    void alertSkippedOutsideWindow() {
        AiTrader night = nightTrader();
        night.setStatus(AiTrader.STATUS_RUNNING);
        when(traderMapper.selectById(7L)).thenReturn(night);
        TraderScheduler s = sched();
        s.nowMs = () -> bj(12, 10);

        s.tryTriggerWake(7L,
                new AlertTrigger("BTCUSDT", new BigDecimal("1.2"), new BigDecimal("63120"), AlertTrigger.DOWN, bj(12, 10)));

        verify(runner, after(300).never()).wakeTriggered(any(), any());
    }

    /** 手动唤醒不看时段：主人亲手扣扳机 */
    @Test
    void manualWakeIgnoresWindow() {
        TraderScheduler s = sched();
        s.nowMs = () -> bj(12, 10); // 时段外；距下一 1h 边界 50 分钟预算充足

        assertThat(s.tryManualWake(nightTrader())).isNull();

        verify(runner, timeout(2_000)).wakeManual(any(), anyLong());
    }

    /** 面板"下次例行"跳到时段起点：正午看是当晚 21:00；全天的不变 */
    @Test
    void nextRoutineWakeJumpsIntoWindow() {
        TraderScheduler s = sched();
        s.nowMs = () -> bj(12, 10);

        assertThat(s.nextRoutineWakeAt(nightTrader())).isEqualTo(bj(21, 0));
        assertThat(s.nextRoutineWakeAt(trader1h())).isEqualTo(bj(13, 0));
    }

    // ---------- 财经日历等待闸 ----------

    /** 撞上数据公布：闸没放行前不发唤醒，放行后本轮照发 */
    @Test
    void routineWakeWaitsForCalendarGate() {
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        EconCalendarGate gate = mock(EconCalendarGate.class);
        CompletableFuture<Void> release = new CompletableFuture<>();
        when(gate.released(H1_BOUNDARY)).thenReturn(release);
        TraderScheduler s = sched(gate);

        s.onKlineClosed(fresh(s, "BTCUSDT",H1_CLOSE));

        verify(runner, after(300).never()).wake(any(), anyLong());
        release.complete(null);
        verify(runner, timeout(2_000)).wake(any(AiTrader.class), eq(H1_BOUNDARY));
    }

    // ---------- 警报唤醒准入 ----------

    private AiTrader runningTrader() {
        AiTrader t = trader1h();
        t.setStatus(AiTrader.STATUS_RUNNING);
        return t;
    }

    /** 非例行唤醒领触发时按 id 重新查库，查到 RUNNING 才叫 */
    private void traderInDb() {
        when(traderMapper.selectById(7L)).thenReturn(runningTrader());
    }

    private EventTrigger stopLossEvent(long at) {
        return new EventTrigger(new TradeEvent(TradeEvent.STOP_LOSS, 42L, 9L, null, "BTCUSDT", "LONG",
                new BigDecimal("0.5"), new BigDecimal("63120"), new BigDecimal("-120"), at));
    }

    /** 没人在跑就立刻叫，触发源原样交给 runner（这里一条） */
    @Test
    void triggerWakesImmediatelyWhenIdle() {
        traderInDb();
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;
        AlertTrigger trig = trig();

        s.tryTriggerWake(7L,trig);

        verify(runner, timeout(2_000)).wakeTriggered(any(AiTrader.class), eq(List.of(trig)));
    }

    /** 没有冷静期，也不给例行让路：刚醒过、距边界只剩 10s，照叫（跑过边界那根例行轮 SKIPPED） */
    @Test
    void triggerWakesAgainRightAwayEvenBeforeBoundary() {
        traderInDb();
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 3_590_000L;

        s.tryTriggerWake(7L,trig());
        verify(runner, timeout(2_000)).wakeTriggered(any(), any());
        s.tryTriggerWake(7L,stopLossEvent(H1_BOUNDARY + 3_591_000L));

        verify(runner, timeout(2_000).times(2)).wakeTriggered(any(), any());
    }

    /** 上一轮在跑：来的触发排队，先来后到，前一轮一结束合成一轮补醒 */
    @Test
    void triggersQueuedWhileInFlightThenBatchedAfter() {
        traderInDb();
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;
        CountDownLatch hold = new CountDownLatch(1);
        doAnswer(inv -> {
            hold.await();
            return null;
        }).when(runner).wakeTriggered(any(), any());
        AlertTrigger first = trig();
        EventTrigger second = stopLossEvent(H1_BOUNDARY + 601_000L);
        AlertTrigger third = new AlertTrigger("ETHUSDT", new BigDecimal("1.5"), new BigDecimal("2300"), AlertTrigger.UP, H1_BOUNDARY + 602_000L);

        s.tryTriggerWake(7L,first);
        verify(runner, timeout(2_000)).wakeTriggered(any(), eq(List.of(first)));
        s.tryTriggerWake(7L,second);
        s.tryTriggerWake(7L,third);
        verify(runner, after(300).times(1)).wakeTriggered(any(), any()); // 在跑，先攒着
        hold.countDown();

        verify(runner, timeout(2_000)).wakeTriggered(any(), eq(List.of(second, third)));
    }

    /** 排在手动轮（例行轮同理）后面也一样：那轮一结束，排队的触发立刻补醒 */
    @Test
    void triggerQueuedBehindManualWakeRunsAfterIt() {
        traderInDb();
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;
        CountDownLatch hold = new CountDownLatch(1);
        doAnswer(inv -> {
            hold.await();
            return null;
        }).when(runner).wakeManual(any(), anyLong());
        s.tryManualWake(trader1h());
        verify(runner, timeout(2_000)).wakeManual(any(), anyLong());

        s.tryTriggerWake(7L,trig());
        verify(runner, after(300).never()).wakeTriggered(any(), any());
        hold.countDown();

        verify(runner, timeout(2_000)).wakeTriggered(any(), eq(List.of(trig())));
    }

    /** 重置占着唤醒位（tryOccupy）时来的触发排队，release 一放手就补醒 */
    @Test
    void triggerQueuedBehindOccupiedSlotRunsOnRelease() {
        traderInDb();
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;
        assertThat(s.tryOccupy(7L)).isTrue();

        s.tryTriggerWake(7L,trig());
        verify(runner, after(300).never()).wakeTriggered(any(), any());
        s.release(7L);

        verify(runner, timeout(2_000)).wakeTriggered(any(), eq(List.of(trig())));
    }

    private static WatchTrigger closeHit(long watchId, long at) {
        AiTraderWatch w = new AiTraderWatch();
        w.setId(watchId);
        w.setTraderId(7L);
        w.setRoundNo(1);
        w.setSymbol("BTCUSDT");
        w.setKind(AiTraderWatch.CLOSE_ABOVE);
        w.setLevel(new BigDecimal("63000"));
        w.setPeriod("1h");
        w.setNote("突破确认");
        return new WatchTrigger(w, new BigDecimal("63120"), at);
    }

    /** 哨兵在 tick 上命中的盯盘条件广播过来，走非例行入口 */
    @Test
    void watchHitEventGoesThroughTriggerPath() {
        traderInDb();
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;
        WatchTrigger hit = closeHit(31L, H1_BOUNDARY + 600_000L);

        s.onTrigger(new TriggerFired(7L, hit));

        verify(runner, timeout(2_000)).wakeTriggered(any(AiTrader.class), eq(List.of(hit)));
    }

    /**
     * 这根收盘的收盘类命中与 trader 自己的例行边界同刻：先落库再发例行，例行轮开场白把它并进去念、标成已告知，
     * 排进队列的那份领的时候被过滤掉，不再单独叫一轮
     */
    @Test
    void closeHitAtRoutineBoundaryFoldsIntoRoutineWake() {
        traderInDb();
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        WatchTrigger hit = closeHit(31L, H1_BOUNDARY);
        when(watchSentinel.closeHits(eq("BTCUSDT"), eq(H1_CLOSE), any())).thenReturn(List.of(hit));
        when(watchStore.wokenIds(any())).thenReturn(Set.of(31L)); // 例行轮念过了
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 1_000L;

        s.onKlineClosed(fresh(s, "BTCUSDT",H1_CLOSE));

        verify(runner, timeout(2_000)).wake(any(AiTrader.class), eq(H1_BOUNDARY));
        verify(runner, after(300).never()).wakeTriggered(any(), any());
    }

    /** 这根收盘不是它的例行边界（4h 档碰上 1h 收盘命中）：没有例行轮并它，单独叫一轮 */
    @Test
    void closeHitWithoutRoutineAtThatBoundaryWakesSeparately() {
        AiTrader t4h = runningTrader();
        t4h.setIntervalCode("4h");
        when(traderMapper.selectById(7L)).thenReturn(t4h);
        when(traderMapper.selectList(any())).thenReturn(List.of());
        WatchTrigger hit = closeHit(31L, H1_BOUNDARY);
        when(watchSentinel.closeHits(eq("BTCUSDT"), eq(H1_CLOSE), any())).thenReturn(List.of(hit));
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 1_000L;

        s.onKlineClosed(fresh(s, "BTCUSDT",H1_CLOSE));

        verify(runner, timeout(2_000)).wakeTriggered(any(AiTrader.class), eq(List.of(hit)));
        verify(runner, never()).wake(any(), anyLong());
    }

    /** 每个币的收盘事件都要评估收盘类命中，同一边界的例行唤醒只发一次 */
    @Test
    void everySymbolsCloseIsEvaluatedButTheBoundaryWakesOnce() {
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        TraderScheduler s = sched();

        s.onKlineClosed(fresh(s, "BTCUSDT",DAY_BOUNDARY - 1));
        s.onKlineClosed(fresh(s, "ETHUSDT",DAY_BOUNDARY - 1));

        verify(watchSentinel).closeHits(eq("BTCUSDT"), eq(DAY_BOUNDARY - 1), any());
        verify(watchSentinel).closeHits(eq("ETHUSDT"), eq(DAY_BOUNDARY - 1), any());
        verify(runner, after(300).times(1)).wake(any(AiTrader.class), eq(DAY_BOUNDARY));
    }

    /** 档位已下线的 trader（没迁的存量 5m）非例行也不叫 */
    @Test
    void retiredIntervalTraderIsNotWokenByTriggers() {
        AiTrader retired = runningTrader();
        retired.setIntervalCode("5m");
        when(traderMapper.selectById(7L)).thenReturn(retired);
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;

        s.tryTriggerWake(7L, trig());

        verify(runner, after(300).never()).wakeTriggered(any(), any());
    }

    /** 领触发时重新查库：已不是 RUNNING（爆仓/暂停）就不叫了 */
    @Test
    void triggerDroppedWhenTraderNotRunningAnymore() {
        AiTrader paused = runningTrader();
        paused.setStatus(AiTrader.STATUS_PAUSED);
        when(traderMapper.selectById(7L)).thenReturn(paused);
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;

        s.tryTriggerWake(7L,trig());

        verify(runner, after(300).never()).wakeTriggered(any(), any());
    }

    // ---------- 手动唤醒（对话轨 wake_trader，已过 HITL） ----------

    /** 手动唤醒走 wakeManual：回路同例行，但决策行标 MANUAL——时间线要看得出扳机在人手里 */
    @Test
    void manualWakeRunsRoutineWake() {
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L; // 1h 周期中段，预算充足

        assertThat(s.tryManualWake(trader1h())).isNull();   // null=已触发

        verify(runner, timeout(2_000)).wakeManual(any(AiTrader.class), eq(H1_BOUNDARY));
    }

    /**
     * 同一 trader 不并行：上一轮还在跑时手动唤醒必须落空。
     * 两个唤醒会话同时对着一个 sim 子账户下单，仓位会被重复开。
     */
    @Test
    void manualWakeBlockedWhileAnotherWakeInFlight() {
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;
        AiTrader t = trader1h();
        // 第一次占住互斥位；runner 是 mock 会立刻返回，所以卡住它来维持"还在跑"
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            hold.await();
            return null;
        }).when(runner).wakeManual(any(), anyLong());
        s.tryManualWake(t);
        verify(runner, timeout(2_000)).wakeManual(any(), anyLong());

        String why = s.tryManualWake(t);

        assertThat(why).contains("上一轮唤醒还在跑");
        verify(runner, after(300).times(1)).wakeManual(any(), anyLong());
        hold.countDown();
    }

    /** 距下一根K线太近：例行唤醒马上到，这一次手动的省下来（同 alert 的判据） */
    @Test
    void manualWakeBlockedWhenRoutineWakeImminent() {
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 3_590_000L; // 距下一 1h 边界仅 10s

        String why = s.tryManualWake(trader1h());

        assertThat(why).contains("距下一根K线收盘不足");
        verify(runner, after(300).never()).wakeManual(any(), anyLong());
    }

    // ---------- 日线边界：和别的边界一样，没有停工窗口 ----------

    /**
     * 日线边界只发例行唤醒。上一轮还在跑时，下一根 15m 边界照常按互斥跳过留 SKIPPED，
     * 手动唤醒的拒因是"上一轮还在跑"，警报排队等它跑完补醒
     */
    @Test
    void dailyBoundaryIsAnOrdinaryBoundaryWithNoStopWindow() {
        AiTrader t = runningTrader();
        t.setIntervalCode("15m");
        when(traderMapper.selectList(any())).thenReturn(List.of(t));
        when(traderMapper.selectById(7L)).thenReturn(t);
        TraderScheduler s = sched();
        CountDownLatch tradeHold = new CountDownLatch(1);
        doAnswer(inv -> {
            tradeHold.await();
            return null;
        }).when(runner).wake(any(), anyLong());

        s.onKlineClosed(fresh(s, "BTCUSDT",DAY_BOUNDARY - 1));
        verify(runner, timeout(2_000)).wake(any(AiTrader.class), eq(DAY_BOUNDARY));
        s.onKlineClosed(fresh(s, "BTCUSDT",DAY_BOUNDARY + 900_000L - 1));
        s.nowMs = () -> DAY_BOUNDARY + 1_000_000L;
        s.tryTriggerWake(7L, trig());

        verify(runner, timeout(2_000)).recordSkipped(any(AiTrader.class), eq(DAY_BOUNDARY + 900_000L));
        assertThat(s.tryManualWake(t)).contains("上一轮唤醒还在跑");
        verify(runner, after(300).never()).wakeTriggered(any(), any());
        tradeHold.countDown();
        verify(runner, timeout(2_000)).wakeTriggered(any(), eq(List.of(trig())));
    }

    @Test
    void alignedCloseYieldsBoundary() {
        assertThat(TraderScheduler.boundaryOf(H1_CLOSE, "1h")).isEqualTo(1785171600000L);
        assertThat(TraderScheduler.boundaryOf(H1_CLOSE, "15m")).isEqualTo(1785171600000L); // 整点也是15m边界
    }

    @Test
    void nonAlignedCloseYieldsMinusOne() {
        // 整点前 5 分钟收盘的 5m bar：不是 1h 边界，但是 15m 边界也不是（xx:55）
        assertThat(TraderScheduler.boundaryOf(H1_CLOSE - 300_000, "1h")).isEqualTo(-1);
        assertThat(TraderScheduler.boundaryOf(H1_CLOSE - 300_000, "15m")).isEqualTo(-1);
        // xx:45 收盘：是 15m 边界不是 1h 边界
        assertThat(TraderScheduler.boundaryOf(H1_CLOSE - 900_000, "15m")).isEqualTo(1785170700000L);
        assertThat(TraderScheduler.boundaryOf(H1_CLOSE - 900_000, "1h")).isEqualTo(-1);
    }

    @Test
    void dailyBoundary() {
        long midnightClose = 1785110400000L - 1; // 2026-07-27 00:00:00 UTC 前 1ms
        assertThat(TraderScheduler.boundaryOf(midnightClose, "1d")).isEqualTo(1785110400000L);
        assertThat(TraderScheduler.boundaryOf(midnightClose, "4h")).isEqualTo(1785110400000L);
    }

    @Test
    void unknownIntervalYieldsMinusOne() {
        assertThat(TraderScheduler.boundaryOf(H1_CLOSE, "3m")).isEqualTo(-1);
    }

    /** 5m 是时钟本身的滴答粒度：任意 5m 收盘都是 5m trader 的边界 */
    @Test
    void fiveMinuteBoundary() {
        assertThat(TraderScheduler.boundaryOf(H1_CLOSE, "5m")).isEqualTo(1785171600000L);
        assertThat(TraderScheduler.boundaryOf(H1_CLOSE - 300_000, "5m")).isEqualTo(1785171300000L);
    }
}
