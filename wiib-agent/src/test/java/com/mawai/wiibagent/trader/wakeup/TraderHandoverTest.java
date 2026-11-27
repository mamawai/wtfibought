package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.handover.Start;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibquant.market.domain.KlineClosedEvent;
import com.mawai.wiibquant.mapper.EconCalendarMapper;
import com.mawai.wiibquant.task.EconCalendarCollector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
 * 按 trader 交接：两套调度器（旧实例、新实例），用内存里直接互调的对端连起来。
 * 旧实例让位时有轮在跑的 trader 归旧实例持有：新实例上到来的事先转过去，没接就停着，旧实例跑完放手后新实例分发
 */
class TraderHandoverTest {

    // 2026-07-27 17:00:00 UTC 整点前 1ms
    private static final long H1_CLOSE = 1785171599999L;
    private static final long H1_BOUNDARY = 1785171600000L;
    /** 交接前那会儿：整点前 10 分钟 */
    private static final long BEFORE = H1_BOUNDARY - 600_000L;

    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final EconCalendarGate gate = new EconCalendarGate(mock(EconCalendarMapper.class), mock(EconCalendarCollector.class));
    private final Side old = new Side();
    private final Side neu = new Side();

    {
        when(traderMapper.selectById(7L)).thenReturn(trader1h());
        when(traderMapper.selectList(any())).thenReturn(List.of(trader1h()));
        old.peer.other = neu.scheduler;
        neu.peer.other = old.scheduler;
        // 旧实例在任，新实例待命
        old.scheduler.open(Start.COLD);
    }

    /** 一个实例：自己的调度器、desk、runner */
    private final class Side {
        final TraderWakeupRunner runner = mock(TraderWakeupRunner.class);
        final Desks desks = new Desks();
        final MemPeer peer = new MemPeer();
        final TraderScheduler scheduler;

        Side() {
            PresetExecutor presets = mock(PresetExecutor.class);
            when(presets.whenIdle(anyLong())).thenReturn(CompletableFuture.completedFuture(null));
            scheduler = new TraderScheduler(traderMapper, runner, new MessageCatalog(), gate, mock(WatchSentinel.class),
                    mock(TraderWatchStore.class), desks, peer, presets, mock(TraderLiveHub.class));
            scheduler.nowMs = () -> BEFORE;
        }
    }

    /** 内存里的对端：直接调另一边的调度器，和 HandoffPeerController 转调的一样；down 时像连不上一样抛 */
    private static final class MemPeer implements TraderPeer {
        TraderScheduler other;
        volatile boolean down;
        /** 这一边通知过放手的 trader */
        final BlockingQueue<Long> released = new LinkedBlockingQueue<>();

        @Override
        public Offer offer(long traderId, Arrival arrival) {
            reach();
            return other.offerFromNew(traderId, arrival);
        }

        @Override
        public PresetReply preset(long traderId, long watchId, BigDecimal price, long at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void handBack(long traderId, Arrival arrival, boolean fresh) {
            reach();
            other.handedBack(traderId, arrival, fresh);
        }

        @Override
        public void released(long traderId, Held what) {
            reach();
            other.releasedByOld(traderId);
            released.add(traderId);
        }

        private void reach() {
            if (down) {
                throw new IllegalStateException("连不上");
            }
        }
    }

    private static AiTrader trader1h() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setIntervalCode("1h");
        t.setSymbols("BTCUSDT,ETHUSDT");
        t.setStatus(AiTrader.STATUS_RUNNING);
        return t;
    }

    private static AlertTrigger alert(String symbol) {
        return new AlertTrigger(symbol, new BigDecimal("1.2"), new BigDecimal("63120"), AlertTrigger.DOWN, H1_BOUNDARY);
    }

    /** 让位和接手：旧实例关口、导出，新实例收下、开口 */
    private void handover() {
        old.scheduler.close();
        neu.scheduler.accept(old.scheduler.export());
        neu.scheduler.open(Start.HANDOVER);
    }

    /** 整点那根 5m 收盘到了新实例 */
    private void hourClosesOnNew() {
        neu.scheduler.nowMs = () -> H1_BOUNDARY;
        neu.scheduler.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", H1_CLOSE));
    }

    /** 旧实例上起一轮整盘的（手动），卡住不结束；返回放行的闩，round 拿到这一轮 */
    private CountDownLatch bookRoundOnOld(ArgumentCaptor<WakeRound> round) {
        CountDownLatch hold = new CountDownLatch(1);
        doAnswer(inv -> {
            hold.await();
            return null;
        }).when(old.runner).wakeManual(any(), anyLong(), round.capture());
        assertThat(old.scheduler.tryManualWake(trader1h())).isNull();
        verify(old.runner, timeout(2_000)).wakeManual(any(), anyLong(), any());
        return hold;
    }

    /** 旧实例通知放手的下一个 trader */
    private Long nextReleasedByOld() throws InterruptedException {
        return old.peer.released.poll(2, TimeUnit.SECONDS);
    }

    /** 等转过去的例行插进这一轮（例行是在虚拟线程上转的），取走它的边界 */
    private static Long routineArrivedAt(WakeRound round) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Long boundary = round.take().routineBoundary();
            if (boundary != null) {
                return boundary;
            }
            Thread.sleep(10);
        }
        return null;
    }

    /** 等转过去的触发插进这一轮（触发也是在虚拟线程上转的），取走它们 */
    private static List<WakeTrigger> triggersArrivedAt(WakeRound round) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            List<WakeTrigger> triggers = round.take().triggers();
            if (!triggers.isEmpty()) {
                return triggers;
            }
            Thread.sleep(10);
        }
        return List.of();
    }

    /** 旧实例关口后才收到的整点收盘（被日历闸压着的那种）：到点发例行，口关着交还新实例 */
    private void hourClosesOnOldAfterClose() {
        old.scheduler.nowMs = () -> H1_BOUNDARY;
        old.scheduler.onKlineClosed(new KlineClosedEvent(this, "ETHUSDT", "5m", H1_CLOSE));
    }

    /** A1：没有轮在跑时交接，旧实例什么都不持有，下一根例行由新实例发 */
    @Test
    void nothingRunningNewInstanceTakesEverything() {
        handover();

        assertThat(old.scheduler.heldHere()).isEmpty();
        hourClosesOnNew();
        verify(neu.runner, timeout(2_000)).wake(any(), eq(H1_BOUNDARY), any());
    }

    /** A2：整盘轮跑到一半交接，期间到点的例行转给旧实例那一轮，读到了就不再发；跑完放手 */
    @Test
    void routineDuringHoldJoinsOldRound() throws InterruptedException {
        ArgumentCaptor<WakeRound> round = ArgumentCaptor.forClass(WakeRound.class);
        CountDownLatch hold = bookRoundOnOld(round);
        handover();
        assertThat(old.scheduler.heldHere()).containsExactly(7L);

        hourClosesOnNew();
        assertThat(routineArrivedAt(round.getValue())).isEqualTo(H1_BOUNDARY);
        hold.countDown();

        assertThat(nextReleasedByOld()).isEqualTo(7L);
        assertThat(old.scheduler.heldHere()).isEmpty();
        assertThat(neu.desks.isHeldByOld(7L)).isFalse();
        verify(neu.runner, after(300).never()).wake(any(), anyLong(), any());
    }

    /** 旧实例那一轮没读到转来的例行：交还新实例停着，放手后新实例发，只发一次 */
    @Test
    void unreadRoutineComesBackAndFiresOnceAfterRelease() {
        CountDownLatch hold = bookRoundOnOld(ArgumentCaptor.forClass(WakeRound.class));
        handover();
        hourClosesOnNew();
        verify(neu.runner, after(300).never()).wake(any(), anyLong(), any());

        hold.countDown();

        verify(neu.runner, timeout(2_000)).wake(any(), eq(H1_BOUNDARY), any());
        verify(neu.runner, after(300).times(1)).wake(any(), anyLong(), any());
        verify(old.runner, never()).wake(any(), anyLong(), any());
    }

    /** A3：持有期间来的触发插进旧实例在跑的那一轮 */
    @Test
    void triggerDuringHoldJoinsOldRound() throws InterruptedException {
        ArgumentCaptor<WakeRound> round = ArgumentCaptor.forClass(WakeRound.class);
        CountDownLatch hold = bookRoundOnOld(round);
        handover();

        neu.scheduler.dispatch(7L, alert("BTCUSDT"));

        assertThat(triggersArrivedAt(round.getValue())).containsExactly(alert("BTCUSDT"));
        hold.countDown();
        verify(neu.runner, after(300).never()).wakeTriggered(any(), any(), any());
    }

    /** A4：持有期间来另一个币的触发，旧实例没有能接的轮：新实例停着，放手后起单币轮 */
    @Test
    void otherSymbolTriggerParksUntilRelease() {
        CountDownLatch hold = new CountDownLatch(1);
        doAnswer(inv -> {
            hold.await();
            return null;
        }).when(old.runner).wakeTriggered(any(), any(), any());
        old.scheduler.dispatch(7L, alert("BTCUSDT"));
        verify(old.runner, timeout(2_000)).wakeTriggered(any(), any(), any());
        handover();

        neu.scheduler.dispatch(7L, alert("ETHUSDT"));
        verify(neu.runner, after(300).never()).wakeTriggered(any(), any(), any());

        hold.countDown();
        ArgumentCaptor<WakeRound> round = ArgumentCaptor.forClass(WakeRound.class);
        verify(neu.runner, timeout(2_000)).wakeTriggered(any(), eq(List.of(alert("ETHUSDT"))), round.capture());
        assertThat(round.getValue().scope().symbol()).isEqualTo("ETHUSDT");
    }

    /** 让位那一刻还在途的判定想起新轮：被拒，交还新实例，新实例照平时起 */
    @Test
    void closedOldHandsBackToNew() {
        handover();

        old.scheduler.dispatch(7L, alert("BTCUSDT"));

        verify(neu.runner, timeout(2_000)).wakeTriggered(any(), eq(List.of(alert("BTCUSDT"))), any());
        verify(old.runner, never()).wakeTriggered(any(), any(), any());
    }

    /** 新实例还没开口时交还来的停着，开口时分发 */
    @Test
    void handBackBeforeNewOpensWaitsForOpen() {
        old.scheduler.close();
        old.scheduler.dispatch(7L, alert("BTCUSDT"));
        verify(neu.runner, after(300).never()).wakeTriggered(any(), any(), any());

        neu.scheduler.accept(old.scheduler.export());
        neu.scheduler.open(Start.HANDOVER);

        verify(neu.runner, timeout(2_000)).wakeTriggered(any(), eq(List.of(alert("BTCUSDT"))), any());
    }

    /** A7：旧实例已经发过这根例行，新实例收下边界后同一根不再发 */
    @Test
    void routineFiredOnOldIsNotFiredAgainOnNew() {
        old.scheduler.nowMs = () -> H1_BOUNDARY;
        old.scheduler.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", H1_CLOSE));
        verify(old.runner, timeout(2_000)).wake(any(), eq(H1_BOUNDARY), any());

        handover();
        hourClosesOnNew();

        verify(neu.runner, after(300).never()).wake(any(), anyLong(), any());
    }

    /** A7：旧实例关口后才发的那根例行交还新实例跑；新实例自己再收到这根收盘不再发 */
    @Test
    void routineFiredOnOldAfterCloseRunsOnceOnNew() {
        handover();
        hourClosesOnOldAfterClose();
        verify(neu.runner, timeout(2_000)).wake(any(), eq(H1_BOUNDARY), any());

        hourClosesOnNew();

        verify(neu.runner, after(300).times(1)).wake(any(), anyLong(), any());
        verify(old.runner, never()).wake(any(), anyLong(), any());
    }

    /** 反过来：新实例先发了这根，旧实例关口后才发的那根交还来就不要了 */
    @Test
    void lateRoutineFromOldDroppedWhenNewAlreadyFired() {
        handover();
        hourClosesOnNew();
        verify(neu.runner, timeout(2_000)).wake(any(), eq(H1_BOUNDARY), any());

        hourClosesOnOldAfterClose();

        verify(neu.runner, after(300).times(1)).wake(any(), anyLong(), any());
    }

    /** 被旧实例持有的：新实例上手动唤醒、重置删除都回忙，立即复盘当作有轮在跑 */
    @Test
    void heldTraderIsBusyOnNew() {
        CountDownLatch hold = bookRoundOnOld(ArgumentCaptor.forClass(WakeRound.class));
        handover();

        assertThat(neu.scheduler.manualWakeBlockedReason(trader1h(), AgentLang.ZH)).isNotNull();
        assertThat(neu.scheduler.tryOccupy(7L)).isFalse();
        assertThat(neu.scheduler.runningAt(7L, "BTCUSDT", BEFORE)).isTrue();
        hold.countDown();
    }

    /** 旧实例在交接单导出之后、新实例收下之前就放手了：那条放手通知被忽略，按旧实例此刻导出的名单核对时补上 */
    @Test
    void releaseBeforeAcceptIsCaughtUpByPeerList() throws InterruptedException {
        CountDownLatch hold = bookRoundOnOld(ArgumentCaptor.forClass(WakeRound.class));
        old.scheduler.close();
        var note = old.scheduler.export();
        hold.countDown();
        assertThat(nextReleasedByOld()).isEqualTo(7L);

        neu.scheduler.accept(note);
        neu.scheduler.open(Start.HANDOVER);
        assertThat(neu.scheduler.tryOccupy(7L)).isFalse();

        neu.scheduler.releaseNotHeldByOld(old.scheduler.export());

        assertThat(neu.scheduler.tryOccupy(7L)).isTrue();
    }

    /** A9：旧实例连不上，转过去的事停着；探活判它没了之后全部当放手，停着的分发 */
    @Test
    void deadOldReleasedByProbe() {
        CountDownLatch hold = bookRoundOnOld(ArgumentCaptor.forClass(WakeRound.class));
        handover();
        neu.peer.down = true;

        neu.scheduler.dispatch(7L, alert("BTCUSDT"));
        verify(neu.runner, after(300).never()).wakeTriggered(any(), any(), any());

        neu.scheduler.releaseAllHeldByOld();

        verify(neu.runner, timeout(2_000)).wakeTriggered(any(), eq(List.of(alert("BTCUSDT"))), any());
        hold.countDown();
    }

    /** A10：新实例接手后崩溃重启，重新收一遍旧实例此刻的导出，被持有的照旧不起轮，旧实例放手后才起 */
    @Test
    void restartedNewRecoversHeldList() {
        CountDownLatch hold = bookRoundOnOld(ArgumentCaptor.forClass(WakeRound.class));
        handover();
        Side restarted = new Side();
        restarted.peer.other = old.scheduler;
        old.peer.other = restarted.scheduler;

        restarted.scheduler.accept(old.scheduler.export());
        restarted.scheduler.open(Start.COLD);
        restarted.scheduler.dispatch(7L, alert("ETHUSDT"));

        verify(restarted.runner, after(300).never()).wakeTriggered(any(), any(), any());
        hold.countDown();
        verify(restarted.runner, timeout(2_000)).wakeTriggered(any(), eq(List.of(alert("ETHUSDT"))), any());
    }
}
