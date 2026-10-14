package com.mawai.wiibagent.chat.run;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.ChatTestEndpoints;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 子运行注册表：报告回灌的两条路（轮挂着进收件箱 / 没挂直接落库标 pending）、三道派发检查、取消不回灌。
 */
class ChildRunRegistryTest {

    private static final String SESSION = "wb-1-child";

    private final ChatContextStore contextStore = mock(ChatContextStore.class);

    private ChildRunRegistry registry(int perUser, int global) {
        return registry(ChatTestEndpoints.prefs(true, perUser), global);
    }

    private ChildRunRegistry registry(WorkbenchPrefService prefs, int global) {
        when(contextStore.load(any())).thenReturn(List.of());
        return new ChildRunRegistry(contextStore, new WorkbenchRunRegistry(), prefs, ChatTestEndpoints.PROMPTS,
                global, 5, 20);
    }

    private static ChildRunRegistry.Spec spec(String sessionId) {
        return new ChildRunRegistry.Spec(sessionId, 1L, ChildRun.Kind.DELEGATE, "调研", "任务书", AgentLang.ZH);
    }

    private static void waitUntil(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            assertThat(System.currentTimeMillis()).as("等到超时").isLessThan(deadline);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private List<Message> savedMessages() {
        ArgumentCaptor<List<Message>> saved = ArgumentCaptor.captor();
        verify(contextStore, timeout(5_000)).save(eq(SESSION), eq(1L), saved.capture());
        return saved.getValue();
    }

    @Test
    void 没轮在跑时报告落库并标pending() {
        ChildRunRegistry registry = registry(3, 3);

        ChildRunRegistry.Spawn spawn = registry.spawn(spec(SESSION), run -> "报告正文");

        assertThat(spawn.started()).isTrue();
        assertThat(savedMessages()).singleElement().satisfies(m -> assertThat(m.getText())
                .startsWith("【后台任务#" + spawn.run().id()).contains("调研").contains("已完成").endsWith("报告正文"));
        waitUntil(() -> spawn.run().status() == ChildRun.Status.DONE);
        assertThat(registry.state(SESSION)).isEqualTo(new ChildRunRegistry.SessionState(0, true));
    }

    @Test
    void 有轮挂着时报告进收件箱_摘掉时剩余即欠唤醒() {
        ChildRunRegistry registry = registry(3, 3);
        Deque<Message> inbox = new ArrayDeque<>();
        registry.attachTurn(SESSION, inbox);

        ChildRunRegistry.Spawn spawn = registry.spawn(spec(SESSION), run -> "报告正文");
        waitUntil(() -> spawn.run().status() == ChildRun.Status.DONE);

        assertThat(inbox).hasSize(1);
        verify(contextStore, never()).save(any(), anyLong(), any());
        assertThat(registry.state(SESSION).pending()).isFalse();

        // 轮结束时收件箱里还剩着：交回给轮并进落库，同时欠一次唤醒
        assertThat(registry.detachTurn(SESSION)).hasSize(1);
        assertThat(registry.state(SESSION).pending()).isTrue();
        // 下一轮挂上收件箱就不欠了：落库的报告它随历史读得到
        registry.attachTurn(SESSION, new ArrayDeque<>());
        assertThat(registry.state(SESSION).pending()).isFalse();
    }

    @Test
    void 单用户同时在跑超上限被拒() throws Exception {
        ChildRunRegistry registry = registry(1, 3);
        CountDownLatch release = new CountDownLatch(1);
        ChildRunRegistry.Spawn first = registry.spawn(spec(SESSION), run -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "ok";
        });

        // 同一个用户在另一个会话再派：上限按用户算
        ChildRunRegistry.Spawn second = registry.spawn(spec("wb-1-other"), run -> "x");

        assertThat(first.started()).isTrue();
        assertThat(second.started()).isFalse();
        assertThat(second.refusal()).contains("1");
        release.countDown();
        waitUntil(() -> first.run().status() == ChildRun.Status.DONE);
    }

    /** 开关只管 delegate：深研判是用户在确认卡上点过头的，关了开关照派 */
    @Test
    void 用户关了开关时只拒delegate不拒深研判() {
        ChildRunRegistry registry = registry(ChatTestEndpoints.prefs(false, 3), 3);

        ChildRunRegistry.Spawn delegate = registry.spawn(spec(SESSION), run -> "x");
        ChildRunRegistry.Spawn deep = registry.spawn(new ChildRunRegistry.Spec(SESSION, 1L,
                ChildRun.Kind.DEEP_ANALYSIS, "深研判 BTCUSDT", null, AgentLang.ZH), run -> "研判");

        assertThat(delegate.started()).isFalse();
        assertThat(delegate.refusal()).contains("关闭");
        assertThat(deep.started()).isTrue();
        waitUntil(() -> deep.run().status() == ChildRun.Status.DONE);
    }

    @Test
    void 全局并发满了被拒() {
        ChildRunRegistry registry = registry(5, 1);
        CountDownLatch release = new CountDownLatch(1);
        registry.spawn(new ChildRunRegistry.Spec("wb-2-a", 2L, ChildRun.Kind.DELEGATE, "a", null, AgentLang.ZH), run -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "ok";
        });

        ChildRunRegistry.Spawn refused = registry.spawn(spec(SESSION), run -> "x");

        assertThat(refused.started()).isFalse();
        release.countDown();
    }

    @Test
    void 取消的子运行不回灌报告() {
        ChildRunRegistry registry = registry(3, 3);
        ChildRunRegistry.Spawn spawn = registry.spawn(spec(SESSION), run -> {
            run.cancel().join();     // 主体看着取消信号停下来
            return "迟到的报告";
        });

        registry.cancelAll(SESSION);

        assertThat(spawn.run().status()).isEqualTo(ChildRun.Status.CANCELLED);
        assertThat(registry.state(SESSION)).isEqualTo(new ChildRunRegistry.SessionState(0, false));
        verify(contextStore, after(300).never()).save(any(), anyLong(), any());
    }

    @Test
    void 失败的子运行回灌失败报告() {
        ChildRunRegistry registry = registry(3, 3);

        ChildRunRegistry.Spawn spawn = registry.spawn(spec(SESSION), run -> {
            throw new IllegalStateException("boom");
        });

        assertThat(savedMessages()).singleElement().satisfies(m -> assertThat(m.getText())
                .startsWith("【后台任务#" + spawn.run().id()).contains("失败"));
        waitUntil(() -> spawn.run().status() == ChildRun.Status.FAILED);
        assertThat(registry.state(SESSION).pending()).isTrue();
    }

    @Test
    void 报告超长截断() {
        ChildRunRegistry registry = registry(3, 3);

        registry.spawn(spec(SESSION), run -> "x".repeat(100));

        String text = savedMessages().getFirst().getText();
        String body = text.substring(text.indexOf('\n') + 1);
        assertThat(body).hasSize(21).endsWith("…");
    }
}
