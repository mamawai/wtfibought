package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.ChatConcurrencyGate;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.behavior.BehaviorAnalysisService;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.toolkit.NewsToolkit;
import com.mawai.wiibagent.trader.TraderChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 让位机制的整链验收：<b>用户消息插队、专家结果排队、补答轮接回</b>。
 * <p>
 * 跑的是生产叶子（真 {@link ChatAgentFactory}）+ 真 {@link ChatTurnRunner} + 真
 * {@link ChatYieldCoordinator} + 真 {@link ChatConcurrencyGate}，只有模型与存储打桩——
 * 让位窗口开在 runner 的专家等待期里，mock 掉 runner 就等于把要验的窗口验没了。
 * 测试自己按 controller 的时序编排（openTurn → run → registerDeferred → release → closeTurn，
 * 补答轮：tryAcquire → takeDeferred → run(deferred)），钉的是协调器与 runner 之间的协议，
 * controller 只是这套协议的另一个照抄者。
 */
class ChatYieldCoordinatorTest {

    private static final String SESSION = "wb-1-yield";
    private static final String ROUTER_MARK = "你是研判工作台的调度器";
    private static final String MARKET_MARK = "你是市场状态专家";

    private final ChatModel deep = mock(ChatModel.class);
    private final ChatModel light = mock(ChatModel.class);
    private final ApprovalRegistry registry = new ApprovalRegistry();
    private final ChatContextStore contextStore = mock(ChatContextStore.class);
    private final ChatConcurrencyGate gate = new ChatConcurrencyGate(10);

    private final ChatTurnRunner runner = new ChatTurnRunner(contextStore, registry, ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS);
    private final ChatYieldCoordinator coordinator = new ChatYieldCoordinator();

    /** 会话上下文的假实现：save 真存 load 真取，补答轮读的就是让位轮存的 */
    private final Map<String, List<Message>> contextRows = new ConcurrentHashMap<>();
    /** market 专家的执行闸：让它卡在"取数中"，造出专家等待期 */
    private final CountDownLatch expertStarted = new CountDownLatch(1);
    private final CountDownLatch expertRelease = new CountDownLatch(1);
    /** summarizer 收到的输入（补答轮喂了什么全靠它说话） */
    private final List<Prompt> summarizerPrompts = new CopyOnWriteArrayList<>();

    private static ChatResponse responseOf(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private ChatAgentFactory.Leaves leaves() {
        when(deep.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(light.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        doAnswer(inv -> {
            contextRows.put(inv.getArgument(0), inv.getArgument(2));
            return null;
        }).when(contextStore).save(any(), anyLong(), any());
        when(contextStore.load(any())).thenAnswer(inv ->
                contextRows.getOrDefault(inv.getArgument(0), List.of()));
        // 路由永远点名 market；market 专家卡闸直到测试放行
        when(light.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt prompt = inv.getArgument(0);
            String head = prompt.getInstructions().getFirst().getText();
            if (head.contains(ROUTER_MARK)) {
                return responseOf(AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("r", "function", "route",
                                "{\"next\":[\"market_agent\"]}"))).build());
            }
            if (head.contains(MARKET_MARK)) {
                expertStarted.countDown();
                if (!expertRelease.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("测试没放行专家闸");
                }
                return responseOf(new AssistantMessage("市场结论"));
            }
            return responseOf(new AssistantMessage("新闻结论"));
        });
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> {
            summarizerPrompts.add(inv.getArgument(0));
            return Flux.just(responseOf(new AssistantMessage("补答答案")));
        });
        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.modelsFor(any())).thenReturn(new ChatModelFactory.Models(deep, light));
        ChatEndpoints llmConfig = ChatTestEndpoints.eps(1L, "gpt-5");   // 叶子指纹含 userId（trader 工具按它认人）
        return new ChatAgentFactory(chatModelFactory, mock(MarketToolkit.class), mock(NewsToolkit.class),
                mock(DeepAnalysisService.class), mock(BehaviorAnalysisService.class),
                mock(TraderChatService.class),
                mock(WorkbenchRunRegistry.class),
                registry, ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS, 8, 999_999, 6, "X")
                .leavesFor(llmConfig, AgentLang.ZH);
    }

    /** 照 controller 的时序把一轮跑在后台线程上：run → yielded 则记账 → 还名额 → closeTurn */
    private Thread startTurn(ChatAgentFactory.Leaves leaves, ChatYieldCoordinator.TurnHandle turn,
                             AtomicReference<ChatTurnRunner.TurnResult> resultRef) {
        Thread thread = new Thread(() -> {
            try {
                ChatTurnRunner.TurnResult result = runner.run(leaves, 1L, SESSION, "看看行情", null,
                        chunk -> { }, event -> { }, s -> { }, turn, null);
                resultRef.set(result);
                if (result.yielded()) {
                    coordinator.registerDeferred(1L, SESSION, "看看行情", result.deferredExperts());
                }
            } finally {
                gate.release(1L);
                coordinator.closeTurn(turn);
            }
        });
        thread.start();
        return thread;
    }

    private String summarizerFed() {
        return summarizerPrompts.getLast().getInstructions().stream()
                .map(m -> m.getText() == null ? "" : m.getText())
                .reduce("", (a, b) -> a + "\n" + b);
    }

    /**
     * 主场景整链：专家等待期让位 → 新消息抢到名额 → 专家结果排队（后端不偷跑）→
     * 前端发起补答轮，普通轮接回批次收尾。每一步的时序都是协议的一部分。
     */
    @Test
    void 专家等待期让位且补答轮接回批次() throws Exception {
        ChatAgentFactory.Leaves leaves = leaves();
        assertThat(gate.tryAcquire(1L)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
        ChatYieldCoordinator.TurnHandle turn = coordinator.openTurn(1L);
        AtomicReference<ChatTurnRunner.TurnResult> result = new AtomicReference<>();
        Thread turnThread = startTurn(leaves, turn, result);

        // 等专家真的开跑再扣扳机。专家开跑与 runner 进等待期之间有微秒级窗口
        //（dispatchAsync 先提交、enterExpertWait 后执行），短重试兜掉这个测试侧竞态
        assertThat(expertStarted.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> turnDone = null;
        long yieldDeadline = System.currentTimeMillis() + 5_000;
        while (turnDone == null && System.currentTimeMillis() < yieldDeadline) {
            turnDone = coordinator.requestYield(1L);
            if (turnDone == null) {
                Thread.sleep(10);
            }
        }
        assertThat(turnDone).as("专家等待期必须可让位").isNotNull();

        // 原轮秒级退位（不等专家跑完），新消息抢到名额
        turnDone.get(10, TimeUnit.SECONDS);
        assertThat(gate.tryAcquire(1L)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
        turnThread.join(10_000);
        assertThat(result.get().yielded()).isTrue();

        // 让位存档里必须有占位答复：新轮的 summarizer 不该替这个问题代答
        assertThat(contextRows.get(SESSION).stream().map(Message::getText).toList())
                .contains(ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.yieldPlaceholder"));

        // 专家跑完了：后端只是欠着，不自己跑补答——没有客户端在场的答案没人看
        expertRelease.countDown();
        assertThat(result.get().deferredExperts().all().get(10, TimeUnit.SECONDS))
                .extracting(Message::getText).anySatisfy(t -> assertThat(t).contains("市场结论"));
        assertThat(coordinator.hasPending(SESSION)).as("status 口径：欠着补答").isTrue();
        assertThat(summarizerPrompts).as("后端不偷跑补答").isEmpty();

        // 新轮结束还名额：依然不偷跑
        ChatYieldCoordinator.TurnHandle newTurn = coordinator.openTurn(1L);
        gate.release(1L);
        coordinator.closeTurn(newTurn);
        assertThat(summarizerPrompts).isEmpty();
        assertThat(coordinator.hasPending(SESSION)).isTrue();

        // 前端发起补答轮：拿名额 → 出队头 → 普通轮接回批次
        assertThat(gate.tryAcquire(1L)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
        ChatYieldCoordinator.DeferredWork work = coordinator.takeDeferred(SESSION).orElseThrow();
        assertThat(work.question()).isEqualTo("看看行情");
        assertThat(coordinator.hasPending(SESSION)).as("出队即不再欠账").isFalse();
        ChatYieldCoordinator.TurnHandle deferredTurn = coordinator.openTurn(1L);
        StringBuilder answer = new StringBuilder();
        ChatTurnRunner.TurnResult deferredResult = runner.run(leaves, 1L, SESSION, "补答指令", null,
                answer::append, event -> { }, s -> { }, deferredTurn, work.batch());
        gate.release(1L);
        coordinator.closeTurn(deferredTurn);

        assertThat(deferredResult.yielded()).isFalse();
        assertThat(answer).hasToString("补答答案");
        // 补答轮喂给 summarizer 的：让位时存的占位答复（最新历史）+ 接回的专家结论 + 补答指令
        assertThat(summarizerFed())
                .contains(ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.yieldPlaceholder"))
                .contains("市场结论")
                .contains("补答指令");
        assertThat(coordinator.takeDeferred(SESSION)).isEmpty();
    }

    /** 让位窗口只开在专家等待期：没轮在跑、或在跑但没进等待期，都不可让位（前端回落排队） */
    @Test
    void 非专家等待期不可让位() {
        assertThat(coordinator.requestYield(1L)).as("没有轮在跑").isNull();

        ChatYieldCoordinator.TurnHandle turn = coordinator.openTurn(1L);
        assertThat(coordinator.requestYield(1L)).as("轮在跑但没进专家等待期").isNull();
        coordinator.closeTurn(turn);
    }

    /** 连续让位欠下多单：按让位先后一单一单出队，取完即不再欠账 */
    @Test
    void 补答按让位先后出队() {
        coordinator.registerDeferred(1L, SESSION, "问A", ChatTurnRunner.ExpertBatch.EMPTY);
        coordinator.registerDeferred(1L, SESSION, "问B", ChatTurnRunner.ExpertBatch.EMPTY);

        assertThat(coordinator.hasPending(SESSION)).isTrue();
        assertThat(coordinator.takeDeferred(SESSION)).map(ChatYieldCoordinator.DeferredWork::question).contains("问A");
        assertThat(coordinator.hasPending(SESSION)).isTrue();
        assertThat(coordinator.takeDeferred(SESSION)).map(ChatYieldCoordinator.DeferredWork::question).contains("问B");
        assertThat(coordinator.hasPending(SESSION)).isFalse();
        assertThat(coordinator.takeDeferred(SESSION)).isEmpty();
    }
}
