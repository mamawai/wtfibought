package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibquant.mapper.EconCalendarMapper;
import com.mawai.wiibquant.task.EconCalendarCollector;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 财经日历等待闸：只在"到点了实际值还没到的数字型事件"存在时才等；拉到就放行，最多等 maxWaitMs；
 * 同一边界只开一把闸；闸自身出错一律放行。
 */
class EconCalendarGateTest {

    /** 2026-07-27 17:00:00 UTC，任一 5m 边界 */
    private static final long B = 1_785_171_600_000L;

    private final EconCalendarMapper mapper = mock(EconCalendarMapper.class);
    private final EconCalendarCollector collector = mock(EconCalendarCollector.class);

    private EconCalendarGate gate() {
        EconCalendarGate g = new EconCalendarGate(mapper, collector);
        g.pollMs = 10;
        g.maxWaitMs = 60;
        return g;
    }

    @Test
    void 无待公布_立即放行不拉取() {
        when(mapper.countPendingActual(anyLong(), anyLong())).thenReturn(0);

        assertThat(gate().released(B)).isDone();
        verify(collector, never()).sync(anyLong(), anyLong());
    }

    @Test
    void 有待公布_拉到实际值即放行() throws Exception {
        // 开闸时 1 条待公布，拉一轮后清零
        when(mapper.countPendingActual(B - EconCalendarGate.LOOKBACK_MS, B)).thenReturn(1, 0);

        gate().released(B).get(2, TimeUnit.SECONDS);

        verify(collector).sync(B - EconCalendarGate.LOOKBACK_MS, B);
    }

    @Test
    void 等满仍没拿到_超时放行() throws Exception {
        when(mapper.countPendingActual(anyLong(), anyLong())).thenReturn(1);

        gate().released(B).get(2, TimeUnit.SECONDS);

        verify(collector, atLeast(2)).sync(anyLong(), anyLong());
    }

    @Test
    void 同一边界只开一把闸() throws Exception {
        when(mapper.countPendingActual(anyLong(), anyLong())).thenReturn(1, 0);
        EconCalendarGate g = gate();

        CompletableFuture<Void> first = g.released(B);
        assertThat(g.released(B)).isSameAs(first);   // 多币的 5m 事件拿到同一个信号
        first.get(2, TimeUnit.SECONDS);

        verify(collector, times(1)).sync(anyLong(), anyLong());
    }

    @Test
    void 查库异常_直接放行() {
        when(mapper.countPendingActual(anyLong(), anyLong())).thenThrow(new RuntimeException("db down"));

        assertThat(gate().released(B)).isDone();
        verify(collector, never()).sync(anyLong(), anyLong());
    }
}
