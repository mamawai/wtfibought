package com.mawai.wiibagent.chat.gate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 行情配额按 IP 算，BYOK 分摊得了 LLM 成本、分摊不了它。闸门是把并发夹死的唯一手段，
 * 也是全套设计里唯一"泄漏就不可恢复"的组件——漏满名额，服务对所有人永久拒绝。
 */
class ChatConcurrencyGateTest {

    /**
     * 同一用户同时只能跑一轮：开十个标签页也占不满全局名额。
     * 断言拒因而不只是"拒了"——它决定用户看到"你已有一轮在跑"还是"人满了"，是两回事。
     * <p>
     * <b>上限必须取 1，不能图省事写 10</b>：闸门若反过来先占全局位再占用户位，
     * 同一用户的第二次请求会先拿走一个全局名额、再因用户位被占而退回，
     * 拒因就成了 GLOBAL_FULL。上限给大了这个错根本露不出来
     */
    @Test
    void 同一用户只能占一个名额() {
        ChatConcurrencyGate gate = new ChatConcurrencyGate(1);

        assertThat(gate.tryAcquire(1L)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
        assertThat(gate.tryAcquire(1L)).isEqualTo(ChatConcurrencyGate.Acquire.USER_BUSY);

        gate.release(1L);
        assertThat(gate.tryAcquire(1L)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
    }

    /** 全局满了就拒绝，不排队——排队会让 SSE 连接挂着等，还要额外处理超时 */
    @Test
    void 全局满了拒绝新用户() {
        ChatConcurrencyGate gate = new ChatConcurrencyGate(2);

        assertThat(gate.tryAcquire(1L)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
        assertThat(gate.tryAcquire(2L)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
        assertThat(gate.tryAcquire(3L)).isEqualTo(ChatConcurrencyGate.Acquire.GLOBAL_FULL);

        gate.release(1L);
        assertThat(gate.tryAcquire(3L)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
    }

    /** 释放没占过的名额不能把额度凭空变多——否则一次误调用就击穿了上限 */
    @Test
    void 重复释放不会凭空多出名额() {
        ChatConcurrencyGate gate = new ChatConcurrencyGate(1);
        gate.tryAcquire(1L);

        gate.release(1L);
        gate.release(1L);
        gate.release(1L);

        assertThat(gate.tryAcquire(1L)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
        assertThat(gate.tryAcquire(2L)).isEqualTo(ChatConcurrencyGate.Acquire.GLOBAL_FULL);
    }

    /** 并发抢占下拿到的名额总数不能超过上限 */
    @Test
    void 并发抢占不超过上限() throws Exception {
        int limit = 4;
        ChatConcurrencyGate gate = new ChatConcurrencyGate(limit);
        int threads = 32;
        AtomicInteger acquired = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            long uid = i;
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    if (gate.tryAcquire(uid) == ChatConcurrencyGate.Acquire.OK) {
                        acquired.incrementAndGet();
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
            th.setDaemon(true);
            th.start();
        }
        start.countDown();

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();
        assertThat(acquired.get()).isEqualTo(limit);
    }
}
