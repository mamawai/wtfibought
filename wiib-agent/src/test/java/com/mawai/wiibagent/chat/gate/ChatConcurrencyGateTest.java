package com.mawai.wiibagent.chat.gate;

import com.mawai.wiibagent.chat.ChatTestEndpoints;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.handover.Start;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mawai.wiibagent.chat.gate.ChatConcurrencyGate.Owner.PROMPT_GEN;
import static com.mawai.wiibagent.chat.gate.ChatConcurrencyGate.Owner.WORKBENCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 行情配额按 IP 算，BYOK 分摊得了 LLM 成本、分摊不了它。闸门是把并发夹死的唯一手段，
 * 也是全套设计里唯一"泄漏就不可恢复"的组件——漏满名额，服务对所有人永久拒绝。
 */
class ChatConcurrencyGateTest {

    /** 开着口的闸：进程启动时由编排开口 */
    private static ChatConcurrencyGate opened(int limit) {
        return ChatTestEndpoints.openGate(limit);
    }

    /** Redis 一时出错：这次照样报错，但本实例的用户位不能就此卡死，好了之后能接着占 */
    @Test
    void Redis出错不留下占着的用户位() {
        StringRedisTemplate redis = ChatTestEndpoints.userSlotRedis();
        ChatConcurrencyGate gate = new ChatConcurrencyGate(redis, "", 10);
        gate.open(Start.COLD);
        when(redis.opsForValue().setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("连不上 Redis"))
                .thenReturn(true);

        assertThatThrownBy(() -> gate.tryAcquire(1L, WORKBENCH)).isInstanceOf(IllegalStateException.class);
        assertThat(gate.count()).isZero();
        assertThat(gate.tryAcquire(1L, WORKBENCH)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
    }

    /** 进程被杀掉没还的用户位：这个槽位下一个进程启动时清掉，另一个槽位占着的不动 */
    @Test
    void 启动时清掉本槽位上一个进程留下的用户位() {
        StringRedisTemplate redis = ChatTestEndpoints.userSlotRedis();
        ChatConcurrencyGate killed = new ChatConcurrencyGate(redis, "a", 10);
        ChatConcurrencyGate other = new ChatConcurrencyGate(redis, "b", 10);
        killed.open(Start.COLD);
        other.open(Start.COLD);
        killed.tryAcquire(1L, WORKBENCH);
        other.tryAcquire(2L, PROMPT_GEN);

        ChatConcurrencyGate restarted = new ChatConcurrencyGate(redis, "a", 10);
        restarted.clearLeftovers();

        assertThat(restarted.isBusy(1L)).isFalse();
        assertThat(restarted.isBusy(2L)).isTrue();
    }

    /** 交接期间两个实例共用 Redis 里的用户位：一边占着，另一边这个用户也算占线、发不了；谁占着决定拒绝码 */
    @Test
    void 另一个实例占着也算占线() {
        StringRedisTemplate redis = ChatTestEndpoints.userSlotRedis();
        ChatConcurrencyGate a = new ChatConcurrencyGate(redis, "a", 10);
        ChatConcurrencyGate b = new ChatConcurrencyGate(redis, "b", 10);
        a.open(Start.COLD);
        b.open(Start.HANDOVER);

        assertThat(a.tryAcquire(1L, PROMPT_GEN)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
        assertThat(b.isBusy(1L)).isTrue();
        assertThatThrownBy(() -> b.acquireOrThrow(1L, WORKBENCH))
                .hasFieldOrPropertyWithValue("code", ErrorCode.PROMPT_GEN_RUNNING.getCode());
        assertThat(b.count()).isZero();

        b.release(1L);
        assertThat(a.isBusy(1L)).isTrue();
        a.release(1L);
        assertThat(b.tryAcquire(1L, WORKBENCH)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
    }

    /** 接新对话的口关着（服务关停中）：新的一轮直接拒，给"服务正在更新"的码，不占用户位 */
    @Test
    void 口关着新的一轮直接拒() {
        ChatConcurrencyGate gate = opened(10);
        gate.close();

        assertThat(gate.tryAcquire(1L, WORKBENCH)).isEqualTo(ChatConcurrencyGate.Acquire.CLOSED);
        assertThat(gate.isBusy(1L)).isFalse();
        assertThatThrownBy(() -> gate.acquireOrThrow(1L, PROMPT_GEN))
                .hasFieldOrPropertyWithValue("code", ErrorCode.CHAT_SERVICE_UPDATING.getCode());
    }

    /** 占着的名额算手上的活：占到就算一份，还了就清零；没占到的不算 */
    @Test
    void 占着的名额算手上的活() {
        ChatConcurrencyGate gate = opened(1);
        assertThat(gate.count()).isZero();

        gate.tryAcquire(1L, WORKBENCH);
        gate.tryAcquire(2L, WORKBENCH);
        assertThat(gate.count()).isEqualTo(1);

        gate.release(1L);
        assertThat(gate.count()).isZero();
    }

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
        ChatConcurrencyGate gate = opened(1);

        assertThat(gate.tryAcquire(1L, WORKBENCH)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
        assertThat(gate.tryAcquire(1L, WORKBENCH)).isEqualTo(ChatConcurrencyGate.Acquire.USER_BUSY);

        gate.release(1L);
        assertThat(gate.tryAcquire(1L, WORKBENCH)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
    }

    /**
     * 占线状态跟着名额走：前端排队等占线时轮询它，报错一次就会让排队消息一直不发或反复撞 2203。
     * 全局满被拒的那个用户没占到位，不算占线
     */
    @Test
    void 占线状态跟着名额走() {
        ChatConcurrencyGate gate = opened(1);
        assertThat(gate.isBusy(1L)).isFalse();

        gate.tryAcquire(1L, WORKBENCH);
        assertThat(gate.isBusy(1L)).isTrue();
        assertThat(gate.isBusy(2L)).isFalse();

        assertThat(gate.tryAcquire(2L, WORKBENCH)).isEqualTo(ChatConcurrencyGate.Acquire.GLOBAL_FULL);
        assertThat(gate.isBusy(2L)).isFalse();

        gate.release(1L);
        assertThat(gate.isBusy(1L)).isFalse();
    }

    /** 用户位被提示词生成占着时给单独的码，工作台据此说"提示词生成中"；被工作台占着还是原来的码 */
    @Test
    void 占着用户位的是谁决定拒绝码() {
        ChatConcurrencyGate gate = opened(10);
        gate.tryAcquire(1L, PROMPT_GEN);
        gate.tryAcquire(2L, WORKBENCH);

        assertThatThrownBy(() -> gate.acquireOrThrow(1L, WORKBENCH))
                .hasFieldOrPropertyWithValue("code", ErrorCode.PROMPT_GEN_RUNNING.getCode());
        assertThatThrownBy(() -> gate.acquireOrThrow(2L, PROMPT_GEN))
                .hasFieldOrPropertyWithValue("code", ErrorCode.CHAT_ALREADY_RUNNING.getCode());
    }

    /** 全局满了就拒绝，不排队——排队会让 SSE 连接挂着等，还要额外处理超时 */
    @Test
    void 全局满了拒绝新用户() {
        ChatConcurrencyGate gate = opened(2);

        assertThat(gate.tryAcquire(1L, WORKBENCH)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
        assertThat(gate.tryAcquire(2L, WORKBENCH)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
        assertThat(gate.tryAcquire(3L, WORKBENCH)).isEqualTo(ChatConcurrencyGate.Acquire.GLOBAL_FULL);

        gate.release(1L);
        assertThat(gate.tryAcquire(3L, WORKBENCH)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
    }

    /** 释放没占过的名额不能把额度凭空变多——否则一次误调用就击穿了上限 */
    @Test
    void 重复释放不会凭空多出名额() {
        ChatConcurrencyGate gate = opened(1);
        gate.tryAcquire(1L, WORKBENCH);

        gate.release(1L);
        gate.release(1L);
        gate.release(1L);

        assertThat(gate.tryAcquire(1L, WORKBENCH)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
        assertThat(gate.tryAcquire(2L, WORKBENCH)).isEqualTo(ChatConcurrencyGate.Acquire.GLOBAL_FULL);
    }

    /** 并发抢占下拿到的名额总数不能超过上限 */
    @Test
    void 并发抢占不超过上限() throws Exception {
        int limit = 4;
        ChatConcurrencyGate gate = opened(limit);
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
                    if (gate.tryAcquire(uid, WORKBENCH) == ChatConcurrencyGate.Acquire.OK) {
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
