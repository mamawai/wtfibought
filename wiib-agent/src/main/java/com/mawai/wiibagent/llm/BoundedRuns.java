package com.mawai.wiibagent.llm;

import com.mawai.wiibcommon.handover.InFlight;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 限时跑一段活：唤醒、复盘、单笔复盘共用。起虚拟线程跑，等到截止时刻；
 * 到点或被叫停，先发停止信号、再打断线程，最多再等 stopWait 让它停下来。
 * 它本身是一份手上的活：提前收尾（{@link #cut}）就是让在跑的每一段现在就停
 */
@Slf4j
@Component
public class BoundedRuns implements InFlight {

    /** 一段活：stop 完成就该尽快停下（交给 ReactLoop 掐在途的模型流） */
    @FunctionalInterface
    public interface Work<T> {
        T run(CompletableFuture<Void> stop) throws Exception;
    }

    /** 被 {@link #cut} 叫停：超时的一种，调用方据此分得清"自己超时"和"被关停打断" */
    public static class CutException extends TimeoutException {
        CutException() {
            super("服务关停，提前收尾");
        }
    }

    /** 在跑的一段。叫停时先发停止信号、再打断线程，和到点时一个顺序 */
    private record Running(FutureTask<?> task, CompletableFuture<Void> stop) {
        void halt() {
            stop.complete(null);
            task.cancel(true);
        }
    }

    private final Set<Running> running = ConcurrentHashMap.newKeySet();
    private volatile boolean cut;

    /**
     * 跑到截止时刻：做完返回结果；到点抛 {@link TimeoutException}；被叫停抛 {@link CutException}。
     * 返回或抛出之前都已经叫停这一段，并等线程停下（最多 stopWait；给 0 就不等）
     *
     * @param label 这一段是谁（如 wake traderId=7），当线程名，也写进日志
     */
    public <T> T run(String label, Work<T> work, long deadline, Duration stopWait) throws Exception {
        CompletableFuture<Void> stop = new CompletableFuture<>();
        FutureTask<T> task = new FutureTask<>(() -> work.run(stop));
        Running me = new Running(task, stop);
        Thread thread = Thread.ofVirtual().name(label).start(task);
        running.add(me);
        try {
            // 叫停之后才开跑的，当场停
            if (cut) {
                me.halt();
            }
            return task.get(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        } catch (CancellationException e) {
            throw new CutException();
        } finally {
            me.halt();
            if (stopWait.isPositive() && !thread.join(stopWait)) {
                log.warn("[BoundedRuns] {} 叫停后 {}s 内线程没停下，不再等", label, stopWait.toSeconds());
            }
            running.remove(me);
        }
    }

    @Override
    public String name() {
        return "llm-runs";
    }

    @Override
    public int count() {
        return running.size();
    }

    @Override
    public void cut() {
        cut = true;
        running.forEach(Running::halt);
    }
}
