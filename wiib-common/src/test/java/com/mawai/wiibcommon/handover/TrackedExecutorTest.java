package com.mawai.wiibcommon.handover;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/** 交进来加一、跑完减一，抛异常也减 */
class TrackedExecutorTest {

    @Test
    void 在跑的件数跟着增减_抛异常也减掉() throws Exception {
        TrackedExecutor executor = new TrackedExecutor("bg");
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> await(release));
        executor.execute(() -> {
            await(release);
            throw new IllegalStateException("这件活出错了");
        });

        assertThat(executor.count()).isEqualTo(2);

        release.countDown();
        long deadline = System.currentTimeMillis() + 2_000;
        while (executor.count() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(executor.count()).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
