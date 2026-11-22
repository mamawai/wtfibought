package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibquant.market.domain.KlineClosedEvent;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复盘调度：5m 收盘扫到期的单笔复盘，日线边界起统一复盘；复盘位一次只让一篇跑，
 * 单笔复盘抢不到就算了，统一复盘抢不到就等。
 */
class ReviewSchedulerTest {

    // 2026-07-27 17:00:00 UTC 整点前 1ms —— Binance closeTime 口径
    private static final long H1_CLOSE = 1785171599999L;
    // 2026-07-27 00:00:00 UTC（日线边界，北京时间 08:00）
    private static final long DAY_BOUNDARY = 1785110400000L;

    /** 查交易员用了 in 条件，拼条件时就要查 TableInfo；不预热的话本类单独跑会炸 */
    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
    }

    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final DebriefRunner debriefRunner = mock(DebriefRunner.class);
    private final ReviewRunner reviewRunner = mock(ReviewRunner.class);
    private final ReviewScheduler scheduler = new ReviewScheduler(traderMapper, debriefRunner, reviewRunner);
    private final DebriefRunner.Due due = new DebriefRunner.Due(new FuturesPositionDTO(), new AiTraderPlan());

    private AiTrader running() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setStatus(AiTrader.STATUS_RUNNING);
        return t;
    }

    /** 库里有这一个交易员 */
    private void inDb(AiTrader t) {
        when(traderMapper.selectList(any())).thenReturn(List.of(t));
        when(traderMapper.selectById(7L)).thenReturn(t);
    }

    /** 一根刚收盘的 5m：墙钟钉在收盘那一刻 */
    private KlineClosedEvent fresh(String symbol, long closeTime) {
        scheduler.nowMs = () -> closeTime + 1;
        return new KlineClosedEvent(this, symbol, "5m", closeTime);
    }

    /** 5m 收盘：有到期的就跑一笔，跑完放位；每个币各发一次事件，同一根只扫一次 */
    @Test
    void fiveMinuteCloseDebriefsADueTradeOncePerBoundary() {
        AiTrader t = running();
        inDb(t);
        when(debriefRunner.next(eq(t), anyLong())).thenReturn(due);

        scheduler.onKlineClosed(fresh("BTCUSDT", H1_CLOSE));
        scheduler.onKlineClosed(fresh("ETHUSDT", H1_CLOSE));

        verify(debriefRunner, timeout(2_000)).debrief(t, due);
        verify(debriefRunner, after(300).times(1)).debrief(any(), any());
        verify(reviewRunner, never()).review(any(), anyLong());
        assertThat(scheduler.isBusy(7L)).isFalse();
    }

    /** 没有到期的单子：不跑，位子放掉 */
    @Test
    void nothingDueRunsNothing() {
        inDb(running());

        scheduler.onKlineClosed(fresh("BTCUSDT", H1_CLOSE));

        verify(debriefRunner, timeout(2_000)).next(any(), anyLong());
        verify(debriefRunner, after(300).never()).debrief(any(), any());
        assertThat(scheduler.isBusy(7L)).isFalse();
    }

    /** 日线边界那一根起统一复盘，时刻就是边界；这一根不扫单笔复盘 */
    @Test
    void dailyBoundaryRunsTheUnifiedReview() {
        AiTrader t = running();
        inDb(t);

        scheduler.onKlineClosed(fresh("BTCUSDT", DAY_BOUNDARY - 1));

        verify(reviewRunner, timeout(2_000)).review(t, DAY_BOUNDARY);
        verify(debriefRunner, after(300).never()).next(any(), anyLong());
    }

    /** 复盘开关关着的、不在运行的：两种复盘都不跑 */
    @Test
    void reviewDisabledOrNotRunningIsLeftAlone() {
        AiTrader off = running();
        off.setReviewEnabled(false);
        inDb(off);

        scheduler.onKlineClosed(fresh("BTCUSDT", H1_CLOSE));
        scheduler.onKlineClosed(fresh("BTCUSDT", DAY_BOUNDARY - 1));

        verify(debriefRunner, after(300).never()).next(any(), anyLong());
        verify(reviewRunner, never()).review(any(), anyLong());
    }

    /** 已爆仓的照常复盘：爆仓前后那几笔也要做单笔复盘、进统一复盘 */
    @Test
    void liquidatedTraderIsStillDebriefedAndReviewed() {
        AiTrader t = running();
        t.setStatus(AiTrader.STATUS_LIQUIDATED);
        inDb(t);
        when(debriefRunner.next(eq(t), anyLong())).thenReturn(due);

        scheduler.onKlineClosed(fresh("BTCUSDT", H1_CLOSE));
        verify(debriefRunner, timeout(2_000)).debrief(t, due);

        long nextDay = DAY_BOUNDARY + 86_400_000L;
        scheduler.onKlineClosed(fresh("BTCUSDT", nextDay - 1));
        verify(reviewRunner, timeout(2_000)).review(t, nextDay);
    }

    /** 查交易员那一下出错不许抛回事件发布方：同一根 K 线上别的监听还要收这个事件 */
    @Test
    void failureWhileListingTradersStaysInsideTheListener() {
        when(traderMapper.selectList(any())).thenThrow(new IllegalStateException("库挂了"));

        assertThatCode(() -> scheduler.onKlineClosed(fresh("BTCUSDT", H1_CLOSE))).doesNotThrowAnyException();
    }

    /** 拿到复盘位之后重新读库：这时已经暂停了就不跑 */
    @Test
    void traderPausedMeanwhileIsNotReviewed() {
        AiTrader listed = running();
        AiTrader paused = running();
        paused.setStatus(AiTrader.STATUS_PAUSED);
        when(traderMapper.selectList(any())).thenReturn(List.of(listed));
        when(traderMapper.selectById(7L)).thenReturn(paused);

        scheduler.onKlineClosed(fresh("BTCUSDT", DAY_BOUNDARY - 1));

        verify(traderMapper, timeout(2_000)).selectById(7L);
        verify(reviewRunner, after(300).never()).review(any(), anyLong());
        assertThat(scheduler.isBusy(7L)).isFalse();
    }

    /** 复盘位被占着：单笔复盘这一根就算了；统一复盘等着，位子一放就跑 */
    @Test
    void debriefGivesUpWhenSlotIsTakenButUnifiedReviewWaits() {
        AiTrader t = running();
        inDb(t);
        assertThat(scheduler.tryOccupy(7L)).isTrue();

        scheduler.onKlineClosed(fresh("BTCUSDT", DAY_BOUNDARY - 300_001L));
        scheduler.onKlineClosed(fresh("BTCUSDT", DAY_BOUNDARY - 1));

        verify(debriefRunner, after(300).never()).next(any(), anyLong());
        verify(reviewRunner, never()).review(any(), anyLong());
        scheduler.release(7L);
        verify(reviewRunner, timeout(2_000)).review(t, DAY_BOUNDARY);
    }

    /** 单笔复盘抛了异常也要放位，不然这个交易员从此不再复盘 */
    @Test
    void slotIsReleasedWhenTheDebriefThrows() {
        AiTrader t = running();
        inDb(t);
        when(debriefRunner.next(eq(t), anyLong())).thenReturn(due);
        doThrow(new IllegalStateException("库挂了")).when(debriefRunner).debrief(any(), any());

        scheduler.onKlineClosed(fresh("BTCUSDT", H1_CLOSE));

        verify(debriefRunner, timeout(2_000)).debrief(t, due);
        verify(debriefRunner, after(300).times(1)).debrief(any(), any());
        assertThat(scheduler.isBusy(7L)).isFalse();
    }

    /** 停机后重放的旧事件、不是 5m 的事件：不处理 */
    @Test
    void staleOrNonFiveMinuteEventsAreIgnored() {
        inDb(running());
        scheduler.nowMs = () -> DAY_BOUNDARY + ReviewScheduler.STALE_EVENT_MS + 1;

        scheduler.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY - 1));
        scheduler.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "1h", DAY_BOUNDARY - 1));

        verify(traderMapper, after(300).never()).selectList(any());
    }
}
