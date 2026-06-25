package com.mawai.wiibfeed.stream;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * REST 兜底轮询器：WS 断开期间周期跑轮询体（拉 REST 价格喂回 handler），重连后停。
 * 把 Spot/Futures 两处重复的 start/stop 骨架收敛到一处，轮询体由 handler 以 Runnable 注入。
 */
@Slf4j
final class RestFallbackPoller {

    private final ScheduledExecutorService scheduler;
    private final long intervalMs;
    private final String label;        // 日志前缀，如 "" / "Futures "
    private final Runnable pollOnce;    // 一轮轮询体（遍历 symbol 拉 REST）
    private ScheduledFuture<?> task;

    RestFallbackPoller(ScheduledExecutorService scheduler, long intervalMs, String label, Runnable pollOnce) {
        this.scheduler = scheduler;
        this.intervalMs = intervalMs;
        this.label = label;
        this.pollOnce = pollOnce;
    }

    void start() {
        if (task != null && !task.isCancelled()) return;
        task = scheduler.scheduleAtFixedRate(pollOnce, 0, intervalMs, TimeUnit.MILLISECONDS);
        log.info("启动{}REST轮询兜底，间隔{}ms", label, intervalMs);
    }

    void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
            log.info("停止{}REST轮询兜底", label);
        }
    }
}
