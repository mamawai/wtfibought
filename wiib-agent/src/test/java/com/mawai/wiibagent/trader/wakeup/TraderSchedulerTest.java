package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibagent.learning.LearningRunner;
import com.mawai.wiibagent.learning.PeerInsightService;
import com.mawai.wiibagent.learning.ReviewRunner;
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

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

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
    private final ReviewRunner reviewRunner = mock(ReviewRunner.class);
    private final LearningRunner learningRunner = mock(LearningRunner.class);
    private final PeerInsightService peers = mock(PeerInsightService.class);

    /** 闸用真类配空 mock：查不到待公布事件就是已完成的放行信号，例行触发与没有闸时一样就地同步发 */
    private TraderScheduler sched() {
        return sched(new EconCalendarGate(mock(EconCalendarMapper.class), mock(EconCalendarCollector.class)));
    }

    private TraderScheduler sched(EconCalendarGate gate) {
        return new TraderScheduler(traderMapper, runner, reviewRunner, learningRunner, peers, new MessageCatalog(), gate);
    }

    private AiTrader trader1h() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setIntervalCode("1h");
        return t;
    }

    /** 同侪池（只有 id 有意义：门槛看的是池里有没有自己、除自己外还剩几个） */
    private static List<AiTrader> pool(long... ids) {
        List<AiTrader> list = new java.util.ArrayList<>();
        for (long id : ids) {
            AiTrader t = new AiTrader();
            t.setId(id);
            list.add(t);
        }
        return list;
    }

    private AlertTrigger trig() {
        return new AlertTrigger("BTCUSDT", new BigDecimal("1.2"), new BigDecimal("63120"), AlertTrigger.DOWN, H1_BOUNDARY);
    }

    /** 北京时间 2026-07-27 hh:mm 的 epoch ms（与 DAY_BOUNDARY 同一天；+8 整时区，整点即 UTC 整点） */
    private static long bj(int h, int m) {
        return ZonedDateTime.of(2026, 7, 27, h, m, 0, 0, WakeWindow.ZONE).toInstant().toEpochMilli();
    }

    private AiTrader nightTrader() {
        AiTrader t = trader1h();
        t.setWakeWindow("21:00-08:30");
        return t;
    }

    // ---------- 唤醒时段 ----------

    /** 正午整点收盘（北京 12:00 = UTC 04:00，四档全是边界）：夜间档不醒，不写 SKIPPED */
    @Test
    void routineWakeSkippedOutsideWindow() {
        when(traderMapper.selectList(any())).thenReturn(List.of(nightTrader()));
        TraderScheduler s = sched();

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", bj(12, 0) - 1));

        verify(runner, after(300).never()).wake(any(), anyLong());
        verify(runner, never()).recordSkipped(any(), anyLong());
    }

    /** 时段内（22:00）照常醒 */
    @Test
    void routineWakeFiresInsideWindow() {
        when(traderMapper.selectList(any())).thenReturn(List.of(nightTrader()));
        TraderScheduler s = sched();

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", bj(22, 0) - 1));

        verify(runner, timeout(2_000)).wake(any(AiTrader.class), eq(bj(22, 0)));
    }

    /** 时段外警报也不叫 */
    @Test
    void alertSkippedOutsideWindow() {
        TraderScheduler s = sched();
        s.nowMs = () -> bj(12, 10);

        s.tryAlertWake(nightTrader(),
                new AlertTrigger("BTCUSDT", new BigDecimal("1.2"), new BigDecimal("63120"), AlertTrigger.DOWN, bj(12, 10)));

        verify(runner, after(300).never()).wakeAlert(any(), any());
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

    /** 时段不覆盖 08:00（21:00-07:00）：日线交接照样复盘+学习，只是 08:00 那根不做交易唤醒 */
    @Test
    void dailyHandoverIgnoresWindowButPhase0Respects() {
        AiTrader t = trader1h();
        t.setWakeWindow("21:00-07:00");
        when(traderMapper.selectList(any())).thenReturn(List.of(t));
        when(peers.peers()).thenReturn(pool(8L, 9L));
        TraderScheduler s = sched();

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY - 1));

        verify(learningRunner, timeout(2_000)).learn(any(AiTrader.class), eq(DAY_BOUNDARY));
        verify(reviewRunner).review(any(AiTrader.class), eq(DAY_BOUNDARY));
        verify(runner, never()).wake(any(), anyLong());
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

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", H1_CLOSE));

        verify(runner, after(300).never()).wake(any(), anyLong());
        release.complete(null);
        verify(runner, timeout(2_000)).wake(any(AiTrader.class), eq(H1_BOUNDARY));
    }

    /** 日线交接的阶段0同样过闸 */
    @Test
    void dailyHandoverWaitsForCalendarGate() throws Exception {
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        EconCalendarGate gate = mock(EconCalendarGate.class);
        CompletableFuture<Void> release = new CompletableFuture<>();
        when(gate.released(DAY_BOUNDARY)).thenReturn(release);
        TraderScheduler s = sched(gate);

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY - 1));

        verify(runner, after(300).never()).wake(any(), anyLong());
        release.complete(null);
        verify(runner, timeout(2_000)).wake(any(AiTrader.class), eq(DAY_BOUNDARY));
        awaitWindowClosed(s);
    }

    // ---------- 警报唤醒准入 ----------

    @Test
    void alertWakePassesAdmissionAndRunsRunner() {
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L; // 1h 周期中段，预算充足

        AlertTrigger trig = trig();
        s.tryAlertWake(trader1h(), trig);

        verify(runner, timeout(2_000)).wakeAlert(any(AiTrader.class), any(AlertTrigger.class));
    }

    /** 冷静期从任何唤醒算起：刚醒过的 trader 5 分钟内不再被警报打扰 */
    @Test
    void alertBlockedDuringCooldown() {
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;

        s.tryAlertWake(trader1h(), trig());
        verify(runner, timeout(2_000)).wakeAlert(any(), any());

        s.tryAlertWake(trader1h(), trig());
        verify(runner, after(300).times(1)).wakeAlert(any(), any()); // 第二次被冷静期拦下
    }

    /** 例行唤醒将至（距边界<30s）警报不抢戏：马上就有新鲜K线信号 */
    @Test
    void alertBlockedWhenRoutineWakeImminent() {
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 3_590_000L; // 距下一 1h 边界仅 10s

        s.tryAlertWake(trader1h(), trig());

        verify(runner, after(300).never()).wakeAlert(any(), any());
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

    /** 手动唤醒也记进冷静期基准：刚被手动叫醒过，紧接着的波动警报没有增量价值 */
    @Test
    void manualWakeFeedsAlertCooldown() {
        TraderScheduler s = sched();
        s.nowMs = () -> H1_BOUNDARY + 600_000L;

        s.tryManualWake(trader1h());
        verify(runner, timeout(2_000)).wakeManual(any(), anyLong());

        s.tryAlertWake(trader1h(), trig());

        verify(runner, after(300).never()).wakeAlert(any(), any());
    }

    // ---------- 日线交接：三阶段 + 屏障 + 停工窗口 ----------

    /** 日线边界走三阶段交接：交易→复盘→学习都发生，且边界一致 */
    @Test
    void dailyBoundaryRunsThreePhases() {
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        when(peers.peers()).thenReturn(pool(8L, 9L));
        TraderScheduler s = sched();

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY - 1));

        verify(learningRunner, timeout(2_000)).learn(any(AiTrader.class), eq(DAY_BOUNDARY));
        verify(reviewRunner).review(any(AiTrader.class), eq(DAY_BOUNDARY));
        verify(runner).wake(any(AiTrader.class), eq(DAY_BOUNDARY));
    }

    /** 非日线边界（普通整点）只有例行唤醒，没有复盘也没有学习 */
    @Test
    void nonDailyBoundaryDoesNotReview() {
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        TraderScheduler s = sched();

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", H1_CLOSE));

        verify(runner, timeout(2_000)).wake(any(AiTrader.class), eq(H1_BOUNDARY));
        verify(reviewRunner, after(300).never()).review(any(), anyLong());
        verify(learningRunner, after(1).never()).learn(any(), anyLong());
    }

    /** review_enabled=false：交易照常，复盘不跑（学习开关独立，这里同侪不足学习也跳过） */
    @Test
    void reviewDisabledSkipsReview() {
        AiTrader t = trader1h();
        t.setReviewEnabled(false);
        when(traderMapper.selectList(any())).thenReturn(List.of(t));
        TraderScheduler s = sched();

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY - 1));

        verify(runner, timeout(2_000)).wake(any(AiTrader.class), eq(DAY_BOUNDARY));
        verify(reviewRunner, after(300).never()).review(any(), anyLong());
    }

    /** 阶段0是屏障：交易没跑完，复盘不许开始（复盘读的是定格的一天，交易还在写就是脏读） */
    @Test
    void reviewWaitsForTradingToFinish() throws Exception {
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        TraderScheduler s = sched();
        CountDownLatch tradeHold = new CountDownLatch(1);
        doAnswer(inv -> {
            tradeHold.await();
            return null;
        }).when(runner).wake(any(), anyLong());

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY - 1));

        verify(runner, timeout(2_000)).wake(any(), anyLong());
        verify(reviewRunner, after(300).never()).review(any(), anyLong());
        tradeHold.countDown();
        verify(reviewRunner, timeout(2_000)).review(any(), eq(DAY_BOUNDARY));
    }

    /**
     * 停工窗口在阶段0发出后就开：交易还在跑时，同币的下一根 5m 边界事件被整个丢弃——
     * 既不再唤醒一次（占住 inFlight 让复盘被跳过），也不记 SKIPPED（那是互斥跳过的痕迹，说明窗口没开）
     */
    @Test
    void windowOpensWhileTradingStillRunning() throws Exception {
        AiTrader t = trader1h();
        t.setIntervalCode("5m");
        when(traderMapper.selectList(any())).thenReturn(List.of(t));
        TraderScheduler s = sched();
        CountDownLatch tradeHold = new CountDownLatch(1);
        doAnswer(inv -> {
            tradeHold.await();
            return null;
        }).when(runner).wake(any(), anyLong());

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY - 1));

        verify(runner, timeout(2_000)).wake(any(), anyLong());
        awaitWindowOpen(s);
        assertThat(tradeHold.getCount()).isEqualTo(1);   // 交易还卡着，窗口已经开了
        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY + 300_000L - 1));
        verify(runner, after(300).times(1)).wake(any(), anyLong());
        verify(runner, never()).recordSkipped(any(), anyLong());
        tradeHold.countDown();
        awaitWindowClosed(s);
    }

    /**
     * 复盘→学习之间的全局屏障：任何一个 trader 的复盘没落库，全体学习都不许开始。
     * 没有屏障，先学的人读到的是同侪昨天的复盘，同一轮学习里各人看到的世界不一样。
     */
    @Test
    void learningWaitsForAllReviewsBarrier() throws Exception {
        AiTrader fast = trader1h();
        AiTrader slow = trader1h();
        slow.setId(8L);
        when(traderMapper.selectList(any())).thenReturn(List.of(fast, slow));
        when(peers.peers()).thenReturn(pool(9L, 10L));   // 两个 learner 都在池外，各有 2 个同侪
        TraderScheduler s = sched();
        CountDownLatch slowReview = new CountDownLatch(1);
        doAnswer(inv -> {
            slowReview.await();
            return null;
        }).when(reviewRunner).review(argThat(t -> t.getId() == 8L), anyLong());

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY - 1));

        // 快的那个复盘早完成了，但慢的还卡着：屏障必须拦住所有人的学习
        verify(reviewRunner, timeout(2_000)).review(argThat(t -> t.getId() == 7L), anyLong());
        verify(learningRunner, after(300).never()).learn(any(), anyLong());
        slowReview.countDown();
        verify(learningRunner, timeout(2_000).times(2)).learn(any(), eq(DAY_BOUNDARY));
    }

    /** 停工窗口拒绝例行：窗口内的K线事件整个丢弃不补跑；窗口关闭后例行恢复 */
    @Test
    void handoverWindowDropsKlineEventsAndRecovers() throws Exception {
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        TraderScheduler s = sched();
        CountDownLatch reviewHold = new CountDownLatch(1);
        doAnswer(inv -> {
            reviewHold.await();
            return null;
        }).when(reviewRunner).review(any(), anyLong());

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY - 1));
        verify(reviewRunner, timeout(2_000)).review(any(), anyLong());   // 复盘在跑=窗口已开

        // 窗口内的下一根 1h 边界事件被整个丢弃——连 SKIPPED 都不记。
        // 只断言"没 wake"不够：复盘占着 inFlight 也能挡 wake 但会记 SKIPPED，
        // 分不清是窗口丢弃还是互斥跳过（变异测试实抓过这个盲区）
        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY + 3_600_000L - 1));
        verify(runner, after(300).times(1)).wake(any(), anyLong());
        verify(runner, never()).recordSkipped(any(), anyLong());

        reviewHold.countDown();
        awaitWindowClosed(s);
        // 窗口关闭后例行恢复如常
        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY + 7_200_000L - 1));
        verify(runner, timeout(2_000).times(2)).wake(any(), anyLong());
    }

    /** 停工窗口拒绝警报与手动：三个唤醒入口一个都不许漏 */
    @Test
    void handoverWindowBlocksAlertAndManualWake() throws Exception {
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        TraderScheduler s = sched();
        s.nowMs = () -> DAY_BOUNDARY + 600_000L;
        CountDownLatch reviewHold = new CountDownLatch(1);
        doAnswer(inv -> {
            reviewHold.await();
            return null;
        }).when(reviewRunner).review(any(), anyLong());
        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY - 1));
        verify(reviewRunner, timeout(2_000)).review(any(), anyLong());

        s.tryAlertWake(trader1h(), trig());
        String why = s.tryManualWake(trader1h());

        verify(runner, after(300).never()).wakeAlert(any(), any());
        assertThat(why).contains("日线交接");
        reviewHold.countDown();
        awaitWindowClosed(s);
    }

    /** 有效同侪不足 2 人：学习整体静默跳过（一个人的竞技场没有同侪可学，不写空话不留 ERROR） */
    @Test
    void learningSkippedWhenTooFewPeers() {
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        when(peers.peers()).thenReturn(pool(8L));
        TraderScheduler s = sched();

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY - 1));

        verify(reviewRunner, timeout(2_000)).review(any(), anyLong());
        verify(learningRunner, after(500).never()).learn(any(), anyLong());
    }

    /** 门槛数的是同侪不含自己：池里 [自己, 甲] 只有 1 个同侪，不学 */
    @Test
    void selfInPoolDoesNotCountAsPeer() {
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        when(peers.peers()).thenReturn(pool(7L, 8L));
        TraderScheduler s = sched();

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY - 1));

        verify(reviewRunner, timeout(2_000)).review(any(), anyLong());
        verify(learningRunner, after(500).never()).learn(any(), anyLong());
    }

    /** 自己在池里且另有 2 个同侪：学 */
    @Test
    void selfInPoolWithTwoPeersLearns() {
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        when(peers.peers()).thenReturn(pool(7L, 8L, 9L));
        TraderScheduler s = sched();

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY - 1));

        verify(learningRunner, timeout(2_000)).learn(argThat(t -> t.getId() == 7L), eq(DAY_BOUNDARY));
    }

    /** learning_enabled=false 的不学习，其他人照学（开关是每人自己的） */
    @Test
    void learningDisabledSkipsOnlyThatTrader() {
        AiTrader on = trader1h();
        AiTrader off = trader1h();
        off.setId(8L);
        off.setLearningEnabled(false);
        when(traderMapper.selectList(any())).thenReturn(List.of(on, off));
        when(peers.peers()).thenReturn(pool(8L, 9L));
        TraderScheduler s = sched();

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY - 1));

        verify(learningRunner, timeout(2_000)).learn(argThat(t -> t.getId() == 7L), anyLong());
        verify(learningRunner, after(300).never()).learn(argThat(t -> t.getId() == 8L), anyLong());
    }

    /** 复盘异常逃逸不许卡死窗口也不许拖垮别人：学习照常发生、窗口最终关闭 */
    @Test
    void reviewExceptionDoesNotJamWindowOrBarrier() throws Exception {
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        when(peers.peers()).thenReturn(pool(8L, 9L));
        doThrow(new RuntimeException("复盘炸了")).when(reviewRunner).review(any(), anyLong());
        TraderScheduler s = sched();

        s.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY - 1));

        verify(learningRunner, timeout(2_000)).learn(any(), eq(DAY_BOUNDARY));
        awaitWindowClosed(s);
    }

    /** 自旋等停工窗口打开 */
    private static void awaitWindowOpen(TraderScheduler s) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!s.isHandoverActive()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("停工窗口2s内没有打开");
            }
            Thread.sleep(10);
        }
    }

    /** 自旋等停工窗口关闭（编排线程在后台收尾，verify 不适合等一个布尔位） */
    private static void awaitWindowClosed(TraderScheduler s) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (s.isHandoverActive()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("停工窗口2s内没有关闭");
            }
            Thread.sleep(10);
        }
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
