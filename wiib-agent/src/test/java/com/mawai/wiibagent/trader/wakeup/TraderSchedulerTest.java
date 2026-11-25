package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.handover.Start;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.market.TradeEvent;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibquant.market.domain.KlineClosedEvent;
import com.mawai.wiibquant.mapper.EconCalendarMapper;
import com.mawai.wiibquant.task.EconCalendarCollector;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调度器：例行按边界发，触发按币分发。几轮可以同时跑：一轮整盘的，外加每个可交易币各一轮只处理它的。
 * runner 是 mock：要让一轮"还在跑"，就让对应的方法卡在闩上。
 */
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

    /** 起新轮的口开着：进程启动时由编排开口 */
    private TraderScheduler sched(EconCalendarGate gate) {
        TraderScheduler s = new TraderScheduler(traderMapper, runner, new MessageCatalog(), gate, watchSentinel, watchStore,
                new Desks(), TraderPeer.NONE, mock(PresetExecutor.class), mock(TraderLiveHub.class));
        s.open(Start.COLD);
        return s;
    }

    /** 交易 BTC 和 ETH 的 1h 档 trader */
    private AiTrader trader1h() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setIntervalCode("1h");
        t.setSymbols("BTCUSDT,ETHUSDT");
        t.setStatus(AiTrader.STATUS_RUNNING);
        return t;
    }

    /** 分发触发时按 id 重新查库，查到 RUNNING 才叫 */
    private void traderInDb() {
        when(traderMapper.selectById(7L)).thenReturn(trader1h());
    }

    /** 一根刚收盘的 5m：墙钟钉在收盘那一刻 */
    private KlineClosedEvent fresh(TraderScheduler s, String symbol, long closeTime) {
        s.nowMs = () -> closeTime + 1;
        return new KlineClosedEvent(this, symbol, "5m", closeTime);
    }

    private static AlertTrigger alert(String symbol) {
        return new AlertTrigger(symbol, new BigDecimal("1.2"), new BigDecimal("63120"), AlertTrigger.DOWN, H1_BOUNDARY);
    }

    private static EventTrigger stopLoss(String symbol) {
        return new EventTrigger(new TradeEvent(TradeEvent.STOP_LOSS, 42L, 9L, null, symbol, "LONG",
                new BigDecimal("0.5"), new BigDecimal("63120"), new BigDecimal("-120"), H1_BOUNDARY + 1_000L));
    }

    /** 权益从 10000 回落 15% */
    private static EquityDropTrigger equityDrop() {
        return new EquityDropTrigger(new BigDecimal("10000"), H1_BOUNDARY - 3_600_000L, new BigDecimal("8500"),
                new BigDecimal("15"), new BigDecimal("15"), H1_BOUNDARY - 600_000L);
    }

    private static WatchTrigger closeHit(long watchId, String forSymbol, long at) {
        AiTraderWatch w = new AiTraderWatch();
        w.setId(watchId);
        w.setTraderId(7L);
        w.setRoundNo(1);
        w.setSymbol("BTCUSDT");
        w.setForSymbol(forSymbol);
        w.setKind(AiTraderWatch.CLOSE_ABOVE);
        w.setLevel(new BigDecimal("63000"));
        w.setPeriod("1h");
        w.setNote("突破确认");
        return new WatchTrigger(w, new BigDecimal("63120"), at);
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

    /** 让整盘的例行轮卡住不结束，返回放行用的闩和这一轮的把手 */
    private CountDownLatch holdRoutine(ArgumentCaptor<WakeRound> round) {
        CountDownLatch hold = new CountDownLatch(1);
        doAnswer(inv -> {
            hold.await();
            return null;
        }).when(runner).wake(any(), anyLong(), round.capture());
        return hold;
    }

    /** 让触发叫醒的那几轮卡住不结束 */
    private CountDownLatch holdTriggered() {
        CountDownLatch hold = new CountDownLatch(1);
        doAnswer(inv -> {
            hold.await();
            return null;
        }).when(runner).wakeTriggered(any(), any(), any());
        return hold;
    }

    // ---------- 例行 ----------

    /** 收盘超过 5 分钟的旧事件（停机后重放的积压）：收盘类盯盘照常判，不发例行 */
    @Test
    void staleKlineEventsDoNotWake() {
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + TraderScheduler.STALE_EVENT_MS + 1;

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", H1_CLOSE));
        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY - 1));

        verify(watchSentinel).closeHits(eq("BTCUSDT"), eq(H1_CLOSE), any());
        verify(watchSentinel).closeHits(eq("BTCUSDT"), eq(DAY_BOUNDARY - 1), any());
        verify(runner, after(300).never()).wake(any(), anyLong(), any());
    }

    /** 每个币的收盘事件都要评估收盘类命中，同一边界的例行唤醒只发一次；例行轮是整盘轮 */
    @Test
    void everySymbolsCloseIsEvaluatedButTheBoundaryWakesOnce() {
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        TraderScheduler s = sched();

        s.onKlineClosed(fresh(s, "BTCUSDT", DAY_BOUNDARY - 1));
        s.onKlineClosed(fresh(s, "ETHUSDT", DAY_BOUNDARY - 1));

        verify(watchSentinel).closeHits(eq("BTCUSDT"), eq(DAY_BOUNDARY - 1), any());
        verify(watchSentinel).closeHits(eq("ETHUSDT"), eq(DAY_BOUNDARY - 1), any());
        ArgumentCaptor<WakeRound> round = ArgumentCaptor.forClass(WakeRound.class);
        verify(runner, after(300).times(1)).wake(any(AiTrader.class), eq(DAY_BOUNDARY), round.capture());
        assertThat(round.getValue().scope().single()).isFalse();
    }

    /** 例行轮到点时有单币轮在跑：例行轮照常开始，不跳过；开场白要用的"哪些币另有一轮在处理"查得到 */
    @Test
    void routineStartsWhileSingleSymbolRoundIsRunning() {
        traderInDb();
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY - 600_000L;
        CountDownLatch hold = holdTriggered();
        s.dispatch(7L, alert("BTCUSDT"));
        verify(runner, timeout(2_000)).wakeTriggered(any(), any(), any());

        s.onKlineClosed(fresh(s, "BTCUSDT", H1_CLOSE));

        ArgumentCaptor<WakeRound> routine = ArgumentCaptor.forClass(WakeRound.class);
        verify(runner, timeout(2_000)).wake(any(AiTrader.class), eq(H1_BOUNDARY), routine.capture());
        assertThat(routine.getValue().busyElsewhere()).containsExactly("BTCUSDT");
        hold.countDown();
    }

    /** 整盘轮在跑的时候例行到点：插进它，不另起第二轮整盘的；它没读到就结束了，这根例行另起一轮 */
    @Test
    void routineJoinsRunningWholeBoardRoundAndRunsAfterItWhenUnread() {
        traderInDb();
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY - 600_000L;
        CountDownLatch hold = new CountDownLatch(1);
        ArgumentCaptor<WakeRound> manual = ArgumentCaptor.forClass(WakeRound.class);
        doAnswer(inv -> {
            hold.await();
            return null;
        }).when(runner).wakeManual(any(), anyLong(), manual.capture());
        assertThat(s.tryManualWake(trader1h())).isNull();
        verify(runner, timeout(2_000)).wakeManual(any(), anyLong(), any());

        s.onKlineClosed(fresh(s, "BTCUSDT", H1_CLOSE));

        verify(runner, after(300).never()).wake(any(), anyLong(), any());
        hold.countDown();
        // 那一轮没再调模型，例行没被读到：结束后补发
        verify(runner, timeout(2_000)).wake(any(AiTrader.class), eq(H1_BOUNDARY), any());
    }

    /** 整盘轮读到了插进来的例行，就不再另起 */
    @Test
    void routineReadByRunningRoundIsNotFiredAgain() {
        traderInDb();
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY - 600_000L;
        CountDownLatch hold = new CountDownLatch(1);
        ArgumentCaptor<WakeRound> manual = ArgumentCaptor.forClass(WakeRound.class);
        doAnswer(inv -> {
            hold.await();
            return null;
        }).when(runner).wakeManual(any(), anyLong(), manual.capture());
        s.tryManualWake(trader1h());
        verify(runner, timeout(2_000)).wakeManual(any(), anyLong(), any());
        s.onKlineClosed(fresh(s, "BTCUSDT", H1_CLOSE));

        assertThat(manual.getValue().take().routineBoundary()).isEqualTo(H1_BOUNDARY);
        hold.countDown();

        verify(runner, after(300).never()).wake(any(), anyLong(), any());
    }

    // ---------- 唤醒时段 ----------

    /** 正午整点收盘（新加坡 12:00 = UTC 04:00）：夜间档不醒 */
    @Test
    void routineWakeSkippedOutsideWindow() {
        when(traderMapper.selectList(any())).thenReturn(List.of(nightTrader()));
        TraderScheduler s = sched();

        s.onKlineClosed(fresh(s, "BTCUSDT", bj(12, 0) - 1));

        verify(runner, after(300).never()).wake(any(), anyLong(), any());
    }

    /** 时段内（22:00）照常醒 */
    @Test
    void routineWakeFiresInsideWindow() {
        when(traderMapper.selectList(any())).thenReturn(List.of(nightTrader()));
        TraderScheduler s = sched();

        s.onKlineClosed(fresh(s, "BTCUSDT", bj(22, 0) - 1));

        verify(runner, timeout(2_000)).wake(any(AiTrader.class), eq(bj(22, 0)), any());
    }

    /** 时段外警报也不叫（分发时按库里那份配置的时段判） */
    @Test
    void alertSkippedOutsideWindow() {
        when(traderMapper.selectById(7L)).thenReturn(nightTrader());
        TraderScheduler s = sched();
        s.nowMs = () -> bj(12, 10);

        s.dispatch(7L, alert("BTCUSDT"));

        verify(runner, after(300).never()).wakeTriggered(any(), any(), any());
    }

    /** 手动唤醒不看时段：主人亲手扣扳机 */
    @Test
    void manualWakeIgnoresWindow() {
        TraderScheduler s = sched();
        s.nowMs = () -> bj(12, 10); // 时段外；距下一 1h 边界 50 分钟预算充足

        assertThat(s.tryManualWake(nightTrader())).isNull();

        verify(runner, timeout(2_000)).wakeManual(any(), anyLong(), any());
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

        s.onKlineClosed(fresh(s, "BTCUSDT", H1_CLOSE));

        verify(runner, after(300).never()).wake(any(), anyLong(), any());
        release.complete(null);
        verify(runner, timeout(2_000)).wake(any(AiTrader.class), eq(H1_BOUNDARY), any());
    }

    // ---------- 触发交给谁 ----------

    /** 这个币没人在处理：起一轮只处理它的单币轮 */
    @Test
    void triggerStartsSingleSymbolRoundWhenNobodyHandlesTheSymbol() {
        traderInDb();
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;
        AlertTrigger trig = alert("BTCUSDT");

        s.dispatch(7L, trig);

        ArgumentCaptor<WakeRound> round = ArgumentCaptor.forClass(WakeRound.class);
        verify(runner, timeout(2_000)).wakeTriggered(any(AiTrader.class), eq(List.of(trig)), round.capture());
        assertThat(round.getValue().scope().symbol()).isEqualTo("BTCUSDT");
    }

    /** 哨兵和事件监听广播过来的触发，走同一条分发 */
    @Test
    void firedTriggerEventIsDispatched() {
        traderInDb();
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;
        WatchTrigger hit = closeHit(31L, "ETHUSDT", H1_BOUNDARY + 600_000L);

        s.onTrigger(new TriggerFired(7L, hit));

        // 盯的是 BTC 的价格，为 ETH 布的：叫醒 ETH 的那一轮
        ArgumentCaptor<WakeRound> round = ArgumentCaptor.forClass(WakeRound.class);
        verify(runner, timeout(2_000)).wakeTriggered(any(AiTrader.class), eq(List.of(hit)), round.capture());
        assertThat(round.getValue().scope().symbol()).isEqualTo("ETHUSDT");
    }

    /** 两个币的触发各起一轮，同时在跑 */
    @Test
    void triggersOnDifferentSymbolsRunInParallel() {
        traderInDb();
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;
        CountDownLatch hold = holdTriggered();

        s.dispatch(7L, alert("BTCUSDT"));
        s.dispatch(7L, alert("ETHUSDT"));

        ArgumentCaptor<WakeRound> rounds = ArgumentCaptor.forClass(WakeRound.class);
        verify(runner, timeout(2_000).times(2)).wakeTriggered(any(), any(), rounds.capture());
        assertThat(rounds.getAllValues()).extracting(r -> r.scope().symbol()).containsExactlyInAnyOrder("BTCUSDT", "ETHUSDT");
        hold.countDown();
    }

    /** 这个币正被某个单币轮处理：插进那一轮，不另起；那一轮读到了就到此为止 */
    @Test
    void triggerJoinsTheRoundAlreadyHandlingThatSymbol() {
        traderInDb();
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;
        CountDownLatch hold = holdTriggered();
        s.dispatch(7L, alert("BTCUSDT"));
        ArgumentCaptor<WakeRound> round = ArgumentCaptor.forClass(WakeRound.class);
        verify(runner, timeout(2_000)).wakeTriggered(any(), any(), round.capture());
        EventTrigger stop = stopLoss("BTCUSDT");

        s.dispatch(7L, stop);

        assertThat(round.getValue().take().triggers()).containsExactly(stop);
        hold.countDown();
        verify(runner, after(300).times(1)).wakeTriggered(any(), any(), any());
    }

    /** 插进去的话那一轮没读到就结束了：这条触发在它结束后另起一轮，不丢 */
    @Test
    void unreadTriggerStartsItsOwnRoundAfterTheRoundEnds() {
        traderInDb();
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;
        CountDownLatch hold = new CountDownLatch(1);
        AlertTrigger first = alert("BTCUSDT");
        doAnswer(inv -> {
            hold.await();
            return null;
        }).when(runner).wakeTriggered(any(), eq(List.of(first)), any());
        s.dispatch(7L, first);
        verify(runner, timeout(2_000)).wakeTriggered(any(), eq(List.of(first)), any());
        EventTrigger stop = stopLoss("BTCUSDT");
        s.dispatch(7L, stop);
        verify(runner, after(300).never()).wakeTriggered(any(), eq(List.of(stop)), any());

        hold.countDown();

        verify(runner, timeout(2_000)).wakeTriggered(any(), eq(List.of(stop)), any());
    }

    /** 只有整盘轮在跑：一个币的触发插进整盘轮，不另起单币轮 */
    @Test
    void triggerJoinsRunningWholeBoardRound() {
        traderInDb();
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        TraderScheduler s = sched();
        ArgumentCaptor<WakeRound> routine = ArgumentCaptor.forClass(WakeRound.class);
        CountDownLatch hold = holdRoutine(routine);
        s.onKlineClosed(fresh(s, "BTCUSDT", H1_CLOSE));
        verify(runner, timeout(2_000)).wake(any(), anyLong(), any());
        AlertTrigger trig = alert("ETHUSDT");

        s.dispatch(7L, trig);

        assertThat(routine.getValue().take().triggers()).containsExactly(trig);
        verify(runner, after(300).never()).wakeTriggered(any(), any(), any());
        hold.countDown();
    }

    /** 不归某一个可交易币的触发（全仓爆仓、币已经不在可交易币里）：没有整盘轮在跑就起一轮整盘的 */
    @Test
    void triggerOutsideTradableSymbolsStartsWholeBoardRound() {
        traderInDb();
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;
        AlertTrigger orphan = alert("SOLUSDT");

        s.dispatch(7L, orphan);

        ArgumentCaptor<WakeRound> round = ArgumentCaptor.forClass(WakeRound.class);
        verify(runner, timeout(2_000)).wakeTriggered(any(), eq(List.of(orphan)), round.capture());
        assertThat(round.getValue().scope().single()).isFalse();
    }

    // ---------- 权益回落警报：叫醒的是整盘轮 ----------

    /** 例行轮正在跑：权益回落警报插进例行轮，不另起 */
    @Test
    void equityDropJoinsRunningRoutineRound() {
        traderInDb();
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        TraderScheduler s = sched();
        ArgumentCaptor<WakeRound> routine = ArgumentCaptor.forClass(WakeRound.class);
        CountDownLatch hold = holdRoutine(routine);
        s.onKlineClosed(fresh(s, "BTCUSDT", H1_CLOSE));
        verify(runner, timeout(2_000)).wake(any(), anyLong(), any());
        EquityDropTrigger drop = equityDrop();

        s.dispatch(7L, drop);

        assertThat(routine.getValue().take().triggers()).containsExactly(drop);
        verify(runner, after(300).never()).wakeTriggered(any(), any(), any());
        hold.countDown();
    }

    /** 权益回落警报叫醒的那一轮还在跑，例行到点：例行插进它，不另起第二轮整盘的 */
    @Test
    void routineFallingDueJoinsTheEquityDropRound() {
        traderInDb();
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY - 600_000L;
        CountDownLatch hold = holdTriggered();
        EquityDropTrigger drop = equityDrop();
        s.dispatch(7L, drop);
        ArgumentCaptor<WakeRound> round = ArgumentCaptor.forClass(WakeRound.class);
        verify(runner, timeout(2_000)).wakeTriggered(any(), eq(List.of(drop)), round.capture());
        assertThat(round.getValue().scope().single()).isFalse();

        s.onKlineClosed(fresh(s, "BTCUSDT", H1_CLOSE));

        assertThat(round.getValue().take().routineBoundary()).isEqualTo(H1_BOUNDARY);
        verify(runner, after(300).never()).wake(any(), anyLong(), any());
        hold.countDown();
    }

    /** 某个币正被单币轮处理：权益回落警报照样起一轮整盘的，两轮同时跑，整盘轮知道那个币另有一轮在处理 */
    @Test
    void equityDropStartsWholeBoardRoundBesideSingleSymbolRound() {
        traderInDb();
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY - 600_000L;
        CountDownLatch hold = holdTriggered();
        s.dispatch(7L, alert("BTCUSDT"));
        verify(runner, timeout(2_000)).wakeTriggered(any(), any(), any());
        EquityDropTrigger drop = equityDrop();

        s.dispatch(7L, drop);

        ArgumentCaptor<WakeRound> round = ArgumentCaptor.forClass(WakeRound.class);
        verify(runner, timeout(2_000)).wakeTriggered(any(), eq(List.of(drop)), round.capture());
        assertThat(round.getValue().scope().single()).isFalse();
        assertThat(round.getValue().busyElsewhere()).containsExactly("BTCUSDT");
        hold.countDown();
    }

    /** 这根收盘的收盘类命中与例行同刻：例行轮先登记，命中插进它，不另起一轮 */
    @Test
    void closeHitAtRoutineBoundaryJoinsTheRoutineRound() {
        traderInDb();
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        WatchTrigger hit = closeHit(31L, "BTCUSDT", H1_BOUNDARY);
        when(watchSentinel.closeHits(eq("BTCUSDT"), eq(H1_CLOSE), any())).thenReturn(List.of(hit));
        TraderScheduler s = sched();
        ArgumentCaptor<WakeRound> routine = ArgumentCaptor.forClass(WakeRound.class);
        CountDownLatch hold = holdRoutine(routine);

        s.onKlineClosed(fresh(s, "BTCUSDT", H1_CLOSE));

        verify(runner, timeout(2_000)).wake(any(AiTrader.class), eq(H1_BOUNDARY), any());
        assertThat(routine.getValue().take().triggers()).containsExactly(hit);
        verify(runner, after(300).never()).wakeTriggered(any(), any(), any());
        hold.countDown();
    }

    /** 这根收盘不是它的例行边界（4h 档碰上 1h 收盘命中）：没有例行轮接它，单独起一轮 */
    @Test
    void closeHitWithoutRoutineAtThatBoundaryWakesSeparately() {
        AiTrader t4h = trader1h();
        t4h.setIntervalCode("4h");
        when(traderMapper.selectById(7L)).thenReturn(t4h);
        when(traderMapper.selectList(any())).thenReturn(List.of());
        WatchTrigger hit = closeHit(31L, "BTCUSDT", H1_BOUNDARY);
        when(watchSentinel.closeHits(eq("BTCUSDT"), eq(H1_CLOSE), any())).thenReturn(List.of(hit));
        TraderScheduler s = sched();

        s.onKlineClosed(fresh(s, "BTCUSDT", H1_CLOSE));

        verify(runner, timeout(2_000)).wakeTriggered(any(AiTrader.class), eq(List.of(hit)), any());
        verify(runner, never()).wake(any(), anyLong(), any());
    }

    /** 别的轮已经在开场白里把这条命中告诉过模型：不再为它单独起一轮 */
    @Test
    void watchHitAlreadyToldDoesNotStartARound() {
        traderInDb();
        when(watchStore.wokenIds(List.of(31L))).thenReturn(Set.of(31L));
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;

        s.dispatch(7L, closeHit(31L, "BTCUSDT", H1_BOUNDARY));

        verify(runner, after(300).never()).wakeTriggered(any(), any(), any());
    }

    /** 档位已下线的 trader（没迁的存量 15m）非例行也不叫 */
    @Test
    void retiredIntervalTraderIsNotWokenByTriggers() {
        AiTrader retired = trader1h();
        retired.setIntervalCode("15m");
        when(traderMapper.selectById(7L)).thenReturn(retired);
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;

        s.dispatch(7L, alert("BTCUSDT"));

        verify(runner, after(300).never()).wakeTriggered(any(), any(), any());
    }

    /** 分发时重新查库：已不是 RUNNING（爆仓/暂停）就不叫了 */
    @Test
    void triggerDroppedWhenTraderNotRunningAnymore() {
        AiTrader paused = trader1h();
        paused.setStatus(AiTrader.STATUS_PAUSED);
        when(traderMapper.selectById(7L)).thenReturn(paused);
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;

        s.dispatch(7L, alert("BTCUSDT"));

        verify(runner, after(300).never()).wakeTriggered(any(), any(), any());
    }

    // ---------- 重置 / 删除占着 ----------

    /** 重置占着的时候来的触发先放着，release 一放手就分发；有轮在跑的时候占不上 */
    @Test
    void triggerParkedWhileOccupiedRunsOnRelease() {
        traderInDb();
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;
        assertThat(s.tryOccupy(7L)).isTrue();
        AlertTrigger trig = alert("BTCUSDT");

        s.dispatch(7L, trig);
        verify(runner, after(300).never()).wakeTriggered(any(), any(), any());
        CountDownLatch hold = holdTriggered();
        s.release(7L);

        verify(runner, timeout(2_000)).wakeTriggered(any(), eq(List.of(trig)), any());
        assertThat(s.tryOccupy(7L)).isFalse();
        hold.countDown();
    }

    // ---------- 手动唤醒（对话轨 wake_trader，已过 HITL） ----------

    /** 手动唤醒走 wakeManual：和例行轮一样是整盘轮，决策行标 MANUAL——时间线要看得出扳机在人手里 */
    @Test
    void manualWakeRunsWholeBoardRound() {
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L; // 1h 周期中段，预算充足

        assertThat(s.tryManualWake(trader1h())).isNull();   // null=已触发

        ArgumentCaptor<WakeRound> round = ArgumentCaptor.forClass(WakeRound.class);
        verify(runner, timeout(2_000)).wakeManual(any(AiTrader.class), eq(H1_BOUNDARY), round.capture());
        assertThat(round.getValue().scope().single()).isFalse();
    }

    /** 已经有一轮整盘的在跑：手动唤醒落空，不起第二轮整盘的。单币轮在跑不拦 */
    @Test
    void manualWakeBlockedOnlyByAnotherWholeBoardRound() {
        traderInDb();
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;
        AiTrader t = trader1h();
        CountDownLatch holdSingle = holdTriggered();
        s.dispatch(7L, alert("BTCUSDT"));
        verify(runner, timeout(2_000)).wakeTriggered(any(), any(), any());
        CountDownLatch hold = new CountDownLatch(1);
        doAnswer(inv -> {
            hold.await();
            return null;
        }).when(runner).wakeManual(any(), anyLong(), any());

        assertThat(s.tryManualWake(t)).isNull();
        verify(runner, timeout(2_000)).wakeManual(any(), anyLong(), any());
        String why = s.tryManualWake(t);

        assertThat(why).contains("已经有一轮看整盘的唤醒在跑");
        verify(runner, after(300).times(1)).wakeManual(any(), anyLong(), any());
        hold.countDown();
        holdSingle.countDown();
    }

    /** 距下一根K线太近：例行唤醒马上到，这一次手动的省下来 */
    @Test
    void manualWakeBlockedWhenRoutineWakeImminent() {
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 3_590_000L; // 距下一 1h 边界仅 10s

        String why = s.tryManualWake(trader1h());

        assertThat(why).contains("距下一根K线收盘不足");
        verify(runner, after(300).never()).wakeManual(any(), anyLong(), any());
    }

    // ---------- 边界计算 ----------

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

    /** 5m 是时钟本身的滴答粒度：任意 5m 收盘都是 5m 周期的边界 */
    @Test
    void fiveMinuteBoundary() {
        assertThat(TraderScheduler.boundaryOf(H1_CLOSE, "5m")).isEqualTo(1785171600000L);
        assertThat(TraderScheduler.boundaryOf(H1_CLOSE - 300_000, "5m")).isEqualTo(1785171300000L);
    }
}
