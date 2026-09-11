package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibquant.mapper.EconCalendarMapper;
import com.mawai.wiibquant.task.EconCalendarCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 唤醒前的财经日历等待闸：K 线边界撞上 High 级数据公布时刻，先等实际值落库再发本轮 trader，
 * 让 trader 醒来就读到刚公布的数字，而不是隔一根 K 线才看见。
 * <p>
 * 5s 一轮窄窗口拉 TradingView（实测公布后约 4s 就有值），最多等 30s 放行——5m 档预算最坏 294s→264s，
 * 离 30s 下限还远。只等带预测/前值的数字型事件：讲话、发布会没有"值"可等，直接放行。
 * 回看 10 分钟：上一根没等到的，下一根再等一次。
 * <p>
 * 同一边界多币的 5m 事件共用一把闸（只轮询一次）；闸自身任何异常一律放行，它不能成为唤醒的单点。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EconCalendarGate {

    static final long LOOKBACK_MS = 10 * 60_000L;
    /** 包私有可调：测试把节奏拨快 */
    long pollMs = 5_000L;
    long maxWaitMs = 30_000L;

    private final EconCalendarMapper mapper;
    private final EconCalendarCollector collector;

    /** 最近一把闸：同一边界只开一次 */
    private long lastBoundary = -1;
    private CompletableFuture<Void> signal = CompletableFuture.completedFuture(null);

    /** 这个边界的放行信号：没有待公布事件就是已完成的 future，调用方 thenRun 就地同步执行 */
    public synchronized CompletableFuture<Void> released(long boundary) {
        if (boundary != lastBoundary) {
            lastBoundary = boundary;
            signal = pending(boundary) ? open(boundary) : CompletableFuture.completedFuture(null);
        }
        return signal;
    }

    /** 回看窗口内到点了实际值还没到的数字型事件；查库炸了按"没有"算，放行 */
    private boolean pending(long boundary) {
        try {
            return mapper.countPendingActual(boundary - LOOKBACK_MS, boundary) > 0;
        } catch (Exception e) {
            log.warn("[EconGate] 查待公布事件失败，直接放行: {}", e.toString());
            return false;
        }
    }

    /** 独立虚拟线程轮询：拉到实际值或等满 maxWaitMs 就放行 */
    private CompletableFuture<Void> open(long boundary) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            long start = System.currentTimeMillis();
            log.info("[EconGate] boundary={} 撞上数据公布，等实际值", boundary);
            try {
                while (true) {
                    try {
                        collector.sync(boundary - LOOKBACK_MS, boundary);
                    } catch (Exception e) {
                        log.warn("[EconGate] 轮询拉取失败: {}", e.toString());
                    }
                    long waited = System.currentTimeMillis() - start;
                    if (!pending(boundary)) {
                        log.info("[EconGate] boundary={} 实际值已到，放行（等了 {}ms）", boundary, waited);
                        break;
                    }
                    if (waited >= maxWaitMs) {
                        log.warn("[EconGate] boundary={} 等满 {}ms 没拿到实际值，放行", boundary, maxWaitMs);
                        break;
                    }
                    Thread.sleep(pollMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                f.complete(null);
            }
        });
        return f;
    }
}
