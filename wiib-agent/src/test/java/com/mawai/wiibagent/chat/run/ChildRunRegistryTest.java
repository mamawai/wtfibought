package com.mawai.wiibagent.chat.run;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.ChatTestEndpoints;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.llm.ContextUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
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

    private final ChatContextStore contextStore = ChatTestEndpoints.contextStore();

    private ChildRunRegistry registry(int perUser, int global) {
        return registry(ChatTestEndpoints.prefs(true, perUser), global);
    }

    private ChildRunRegistry registry(WorkbenchPrefService prefs, int global) {
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
        ArgumentCaptor<ChatContextStore.State> saved = ArgumentCaptor.captor();
        verify(contextStore, timeout(5_000)).save(eq(SESSION), eq(1L), saved.capture());
        return saved.getValue().messages();
    }

    /** 没轮在跑时 child_done 推不出去：跑完的子运行留在 status 里，结局与 pending 一起看得到 */
    @Test
    void 没轮在跑时报告落库并标pending_status报出结局() {
        ChildRunRegistry registry = registry(3, 3);

        ChildRunRegistry.Spawn spawn = registry.spawn(spec(SESSION), run -> "报告正文");

        assertThat(spawn.started()).isTrue();
        assertThat(savedMessages()).singleElement().satisfies(m -> assertThat(m.getText())
                .startsWith("【后台任务#" + spawn.run().id()).contains("调研").contains("已完成").endsWith("报告正文"));
        ChildRunRegistry.SessionState state = registry.state(SESSION);
        assertThat(state.pending()).isTrue();
        assertThat(state.runs()).singleElement().satisfies(view -> {
            assertThat(view.status()).isEqualTo(ChildRun.Status.DONE);
            assertThat(view.endedAt()).isNotNull();
        });
    }

    @Test
    void status报内部步骤_在跑时最后一批算做着() {
        ChildRunRegistry registry = registry(3, 3);
        CountDownLatch release = new CountDownLatch(1);
        ChildRunRegistry.Spawn spawn = registry.spawn(spec(SESSION), run -> {
            registry.phase(run, "开跑");
            registry.toolCalls(run, List.of(
                    new AssistantMessage.ToolCall("c1", "function", "klines", "{\"symbol\":\"BTCUSDT\"}"),
                    new AssistantMessage.ToolCall("c2", "function", "news_search", "不是JSON")));
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "报告正文";
        });
        waitUntil(() -> registry.state(SESSION).runs().getFirst().steps().size() == 3);

        List<ChildRun.Step> steps = registry.state(SESSION).runs().getFirst().steps();
        assertThat(steps).extracting(ChildRun.Step::kind).containsExactly("phase", "tool", "tool");
        assertThat(steps).extracting(ChildRun.Step::active).containsExactly(false, true, true);
        assertThat(steps.get(1).args().get("symbol").asString(null)).isEqualTo("BTCUSDT");
        assertThat(steps.get(2).args()).isNull();

        release.countDown();
        waitUntil(() -> spawn.run().status() == ChildRun.Status.DONE);
        assertThat(registry.state(SESSION).runs().getFirst().steps()).noneMatch(ChildRun.Step::active);
    }

    /** 落库时上下文里原有的用量读数要加上报告这一截，前缀指纹照旧 */
    @Test
    void 报告落库时读数加上报告的估算() {
        when(contextStore.load(SESSION)).thenReturn(new ChatContextStore.State(List.of(), 1000L, "fp"));
        ChildRunRegistry registry = registry(3, 3);

        registry.spawn(spec(SESSION), run -> "报告正文");

        ArgumentCaptor<ChatContextStore.State> saved = ArgumentCaptor.captor();
        verify(contextStore, timeout(5_000)).save(eq(SESSION), eq(1L), saved.capture());
        assertThat(saved.getValue().contextTokens())
                .isEqualTo(1000L + ContextUsage.estimate(saved.getValue().messages()));
        assertThat(saved.getValue().prefix()).isEqualTo("fp");
    }

    /** 这一轮没开跑就收场：收件箱里已经到的报告按没轮在跑的路子落库，欠一次唤醒 */
    @Test
    void 摘掉收件箱时已到的报告照样落库() {
        ChildRunRegistry registry = registry(3, 3);
        Deque<Message> inbox = new ArrayDeque<>();
        registry.attachTurn(SESSION, inbox);
        inbox.add(new UserMessage("【后台任务#1】报告正文"));

        registry.detachTurn(SESSION, 1L);

        assertThat(savedMessages()).singleElement().satisfies(m -> assertThat(m.getText()).endsWith("报告正文"));
        assertThat(registry.state(SESSION).pending()).isTrue();
    }

    /** 上下文读不出来：报告不落库（不拿空历史覆盖），子运行照常收尾，不卡在跑着 */
    @Test
    void 上下文读不出来时报告不覆盖() {
        when(contextStore.load(SESSION)).thenThrow(new RuntimeException("db down"));
        ChildRunRegistry registry = registry(3, 3);

        ChildRunRegistry.Spawn spawn = registry.spawn(spec(SESSION), run -> "报告正文");

        waitUntil(() -> spawn.run().status() == ChildRun.Status.DONE);
        verify(contextStore, never()).save(any(), anyLong(), any());
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

        // 轮结束时收件箱里还剩着：并进终态一起落库，同时欠一次唤醒
        assertThat(registry.settle(SESSION, 1L, ChatContextStore.State.EMPTY)).isTrue();
        assertThat(savedMessages()).singleElement().satisfies(m -> assertThat(m.getText()).startsWith("【后台任务#"));
        assertThat(registry.state(SESSION).pending()).isTrue();
        // 下一轮挂上收件箱就不欠了：落库的报告它随历史读得到
        registry.attachTurn(SESSION, new ArrayDeque<>());
        assertThat(registry.state(SESSION).pending()).isFalse();
    }

    /** 报告正赶在轮结束保存的当口到：存储是整体覆盖，不能被那次保存盖掉 */
    @Test
    void 轮结束保存期间到的报告不丢() {
        ChildRunRegistry registry = registry(3, 3);
        registry.attachTurn(SESSION, new ArrayDeque<>());
        // 模拟库：写完才可见；轮那次保存慢一拍，子运行就在这期间跑完
        AtomicReference<ChatContextStore.State> db = new AtomicReference<>(ChatContextStore.State.EMPTY);
        CountDownLatch turnSaving = new CountDownLatch(1);
        when(contextStore.load(SESSION)).thenAnswer(inv -> db.get());
        doAnswer(inv -> {
            ChatContextStore.State state = inv.getArgument(2);
            if (turnSaving.getCount() > 0) {
                turnSaving.countDown();
                Thread.sleep(300);
            }
            db.set(state);
            return null;
        }).when(contextStore).save(eq(SESSION), eq(1L), any());
        ChildRunRegistry.Spawn spawn = registry.spawn(spec(SESSION), run -> {
            try {
                turnSaving.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "报告正文";
        });

        registry.settle(SESSION, 1L, ChatContextStore.State.EMPTY);
        waitUntil(() -> spawn.run().status() == ChildRun.Status.DONE);

        assertThat(db.get().messages()).singleElement().satisfies(m -> assertThat(m.getText()).endsWith("报告正文"));
        assertThat(registry.state(SESSION).pending()).isTrue();
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

    /** 主 agent 一条回复里并发派几个：数在跑的和登记要一气做完，不然都数到 0 一起过闸 */
    @Test
    void 同批并发派发不越单用户上限() throws Exception {
        ChildRunRegistry registry = registry(1, 10);
        CountDownLatch release = new CountDownLatch(1);
        Function<ChildRun, String> body = run -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "ok";
        };
        // 8 条虚拟线程等同一个发令枪，一起冲 spawn
        CountDownLatch go = new CountDownLatch(1);
        long started;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ChildRunRegistry.Spawn>> spawns = IntStream.range(0, 8)
                    .mapToObj(i -> pool.submit(() -> {
                        go.await();
                        return registry.spawn(spec(SESSION), body);
                    }))
                    .toList();
            go.countDown();
            started = 0;
            for (Future<ChildRunRegistry.Spawn> spawn : spawns) {
                if (spawn.get().started()) {
                    started++;
                }
            }
        }

        assertThat(started).isEqualTo(1);
        release.countDown();
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

        ChildRunRegistry.SessionState state = registry.state(SESSION);
        assertThat(state.pending()).isFalse();
        assertThat(state.runs()).singleElement().satisfies(view ->
                assertThat(view.status()).isEqualTo(ChildRun.Status.CANCELLED));
        verify(contextStore, after(300).never()).save(any(), anyLong(), any());
    }

    @Test
    void 失败的子运行回灌失败报告() {
        ChildRunRegistry registry = registry(3, 3);

        ChildRunRegistry.Spawn spawn = registry.spawn(spec(SESSION), run -> {
            throw new IllegalStateException("boom");
        });

        String report = savedMessages().getFirst().getText();
        assertThat(report).startsWith("【后台任务#" + spawn.run().id()).contains("失败");
        ChildRunRegistry.SessionState state = registry.state(SESSION);
        assertThat(state.pending()).isTrue();
        // 失败原因和报告里给主 agent 的是同一句
        assertThat(state.runs()).singleElement().satisfies(view -> {
            assertThat(view.status()).isEqualTo(ChildRun.Status.FAILED);
            assertThat(report).endsWith(view.error());
        });
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
