package com.mawai.wiibcommon.handover;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 开着才交给处理；关闸等正在处理的跑完才返回，之后来的丢掉 */
class DeliveryGateTest {

    @Test
    void 开着才处理() {
        DeliveryGate gate = new DeliveryGate();
        AtomicInteger handled = new AtomicInteger();

        gate.pass(handled::incrementAndGet);
        assertThat(handled).hasValue(0);

        gate.open();
        gate.pass(handled::incrementAndGet);
        assertThat(handled).hasValue(1);
    }

    @Test
    void 关闸等正在处理的跑完_之后来的丢掉() throws Exception {
        DeliveryGate gate = new DeliveryGate();
        gate.open();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        Thread.startVirtualThread(() -> gate.pass(() -> {
            entered.countDown();
            await(release);
            finished.set(true);
        }));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        Thread closer = Thread.startVirtualThread(gate::close);
        Thread.sleep(100);
        assertThat(closer.isAlive()).isTrue();

        release.countDown();
        closer.join(2_000);
        assertThat(closer.isAlive()).isFalse();
        assertThat(finished).isTrue();

        AtomicInteger late = new AtomicInteger();
        gate.pass(late::incrementAndGet);
        assertThat(late).hasValue(0);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
