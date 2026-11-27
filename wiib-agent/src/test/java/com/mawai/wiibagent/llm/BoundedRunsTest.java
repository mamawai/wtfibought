package com.mawai.wiibagent.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 限时跑一段活：正常结束、到点、被叫停三种，异常类型分得开，线程确实停下 */
class BoundedRunsTest {

    private final BoundedRuns runs = new BoundedRuns();

    @Test
    void 正常做完返回结果_线程以标签命名_不算在跑() throws Exception {
        String out = runs.run("wake traderId=7", stop -> Thread.currentThread().getName(),
                System.currentTimeMillis() + 5_000, Duration.ofSeconds(1));

        assertThat(out).isEqualTo("wake traderId=7");
        assertThat(runs.count()).isZero();
    }

    @Test
    void 到点抛超时_不是叫停_停止信号发了线程也停了() {
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<Boolean> stopped = new AtomicReference<>(false);

        assertThatThrownBy(() -> runs.run("t", stop -> {
            worker.set(Thread.currentThread());
            stop.join();
            stopped.set(true);
            return "late";
        }, System.currentTimeMillis() + 200, Duration.ofSeconds(2)))
                .isInstanceOf(TimeoutException.class)
                .isNotInstanceOf(BoundedRuns.CutException.class);

        assertThat(stopped.get()).isTrue();
        assertThat(worker.get().isAlive()).isFalse();
        assertThat(runs.count()).isZero();
    }

    @Test
    void 叫停时在跑的当场停_抛叫停异常_线程停下才返回() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller = Thread.startVirtualThread(() -> {
            try {
                runs.run("t", stop -> {
                    worker.set(Thread.currentThread());
                    started.countDown();
                    // 被打断就醒，和真的会话卡在模型调用上一样
                    Thread.sleep(60_000);
                    return "never";
                }, System.currentTimeMillis() + 60_000, Duration.ofSeconds(2));
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(runs.count()).isEqualTo(1);

        runs.cut();

        assertThat(caller.join(Duration.ofSeconds(3))).isTrue();
        assertThat(thrown.get()).isInstanceOf(BoundedRuns.CutException.class);
        assertThat(worker.get().isAlive()).isFalse();
        assertThat(runs.count()).isZero();
    }

    @Test
    void 叫停之后才开跑的_当场停() {
        runs.cut();

        assertThatThrownBy(() -> runs.run("t", stop -> {
            Thread.sleep(60_000);
            return "never";
        }, System.currentTimeMillis() + 60_000, Duration.ofSeconds(2)))
                .isInstanceOf(BoundedRuns.CutException.class);
    }
}
