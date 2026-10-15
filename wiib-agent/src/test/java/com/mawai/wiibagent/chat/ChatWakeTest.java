package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.behavior.BehaviorAnalysisService;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.ChatConcurrencyGate;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.run.ChildRun;
import com.mawai.wiibagent.chat.run.ChildRunRegistry;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.chat.store.ChatHistoryService;
import com.mawai.wiibagent.controller.ChatWorkbenchController;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibagent.llm.SseChannel;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import com.mawai.wiibagent.toolkit.IndicatorToolkit;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.trader.TraderChatService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 子运行报告怎么回到对话里：轮进行中到的排进下一次模型调用；最后一次调用之后到的留到 done 的 pending；
 * 没轮在跑时落库，由唤醒轮带着补充标头补答。
 */
class ChatWakeTest {

    private static final String SESSION = "wb-1-wake";
    private static final int NO_COMPRESSION = 999_999;

    private final ChatModel deep = mock(ChatModel.class);
    private final ChatModel light = mock(ChatModel.class);
    private final ApprovalRegistry registry = new ApprovalRegistry();
    private final ChatContextStore contextStore = mock(ChatContextStore.class);
    private final ChildRunRegistry childRuns = ChatTestEndpoints.childRuns(contextStore);
    private final TraderChatService traderChatService = mock(TraderChatService.class);
    private final List<Prompt> mainPrompts = new CopyOnWriteArrayList<>();
    private final StringBuilder answer = new StringBuilder();

    private static ChatResponse responseOf(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static AssistantMessage toolCall(String name) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", name, "{}"))).build();
    }

    private ChatAgentFactory.Leaves leaves() {
        when(deep.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(light.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.modelsFor(any())).thenReturn(new ChatModelFactory.Models(deep, light));
        return new ChatAgentFactory(chatModelFactory, mock(MarketToolkit.class), mock(IndicatorToolkit.class), ChatTestEndpoints.newsToolkit(),
                mock(DeepAnalysisService.class), mock(BehaviorAnalysisService.class), traderChatService,
                mock(WorkbenchRunRegistry.class), childRuns, registry, ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS,
                8, NO_COMPRESSION, 6, "X")
                .leavesFor(ChatTestEndpoints.eps(1L, "gpt-5"), AgentLang.ZH);
    }

    /** 派一个立刻完成的子运行，等它交付完再返回：之后的模型调用一定看得见它的报告 */
    private void spawnAndDeliver(String body) {
        ChildRunRegistry.Spawn spawn = childRuns.spawn(new ChildRunRegistry.Spec(SESSION, 1L,
                ChildRun.Kind.DELEGATE, "调研", "任务书", AgentLang.ZH), run -> body);
        long deadline = System.currentTimeMillis() + 5_000;
        while (spawn.run().status() == ChildRun.Status.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(spawn.run().status()).isEqualTo(ChildRun.Status.DONE);
    }

    private ChatTurnRunner.TurnResult turn(String message) {
        return new ChatTurnRunner(contextStore, childRuns, ChatTestEndpoints.PROMPTS, mock(WorkbenchRunRegistry.class))
                .run(leaves(), 1L, SESSION, message, null, answer::append, s -> { }, null);
    }

    private String savedText() {
        ArgumentCaptor<List<Message>> saved = ArgumentCaptor.captor();
        verify(contextStore).save(eq(SESSION), eq(1L), saved.capture());
        return saved.getValue().stream().map(m -> m.getText() == null ? "" : m.getText())
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void 轮进行中到的报告排进下一次模型调用() {
        // 工具执行期间报告到了：第二次调模型前它已经在收件箱里
        when(traderChatService.overview(1L, AgentLang.ZH)).thenAnswer(inv -> {
            spawnAndDeliver("调研结论");
            return "{\"hasTrader\":true}";
        });
        AtomicInteger calls = new AtomicInteger();
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> {
            mainPrompts.add(inv.getArgument(0));
            return Flux.just(responseOf(calls.getAndIncrement() == 0
                    ? toolCall("trader_overview") : new AssistantMessage("这是答案")));
        });

        ChatTurnRunner.TurnResult result = turn("我的 trader 怎么样");

        assertThat(result.pending()).isFalse();
        // 第一次调用没有报告，第二次的输入里有（在工具回执之后、调模型之前排进去的）
        assertThat(mainPrompts.getFirst().getInstructions()).noneMatch(m -> text(m).contains("调研结论"));
        assertThat(mainPrompts.get(1).getInstructions()).anyMatch(m -> text(m).startsWith("【后台任务#") && text(m).contains("调研结论"));
        assertThat(savedText()).contains("调研结论").contains("这是答案");
    }

    @Test
    void 最后一次模型调用之后到的报告留到pending() {
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> {
            // 模型最后一次调用进行中报告才到：这轮答不到它
            spawnAndDeliver("晚到的调研结论");
            return Flux.just(responseOf(new AssistantMessage("这是答案")));
        });

        ChatTurnRunner.TurnResult result = turn("随便问问");

        assertThat(result.pending()).isTrue();
        // 报告随终态落库，下一轮（唤醒轮）读得到
        assertThat(savedText()).contains("晚到的调研结论").contains("这是答案");
        assertThat(childRuns.state(SESSION).pending()).isTrue();
    }

    @Test
    void 没轮在跑时报告落库_唤醒轮带补充标头作答不落提问行() {
        // 没轮在跑：报告直接落库、标 pending
        when(contextStore.load(SESSION)).thenReturn(List.of());
        spawnAndDeliver("离线到的调研结论");
        assertThat(childRuns.state(SESSION).pending()).isTrue();
        verify(contextStore).save(eq(SESSION), eq(1L), any());

        // 唤醒轮：用户侧消息是唤醒指令，答案带补充标头
        ChatTurnRunner turnRunner = mock(ChatTurnRunner.class);
        doAnswer((Answer<ChatTurnRunner.TurnResult>) inv -> {
            assertThat((String) inv.getArgument(3)).isEqualTo(ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.wake.instruction"));
            Consumer<String> sink = inv.getArgument(5);
            sink.accept("补充内容");
            return ChatTurnRunner.TurnResult.COMPLETED;
        }).when(turnRunner).run(any(), anyLong(), any(), any(), any(), any(), any(), any());
        ChatHistoryService history = mock(ChatHistoryService.class);
        ChatTurnStreamer streamer = new ChatTurnStreamer(turnRunner, history, mock(WorkbenchRunRegistry.class),
                registry, ChatTestEndpoints.PROMPTS);
        List<String> frames = new ArrayList<>();
        SseEmitter emitter = new SseEmitter() {
            @Override
            public void send(SseEventBuilder builder) {
                builder.build().forEach(d -> {
                    if (d.getData() instanceof String s) frames.add(s);
                });
            }
        };
        UsageTrackingChatModel model = new UsageTrackingChatModel(mock(ChatModel.class));
        ChatAgentFactory.Leaves leaves = new ChatAgentFactory.Leaves("x", model, model, null, AgentLang.ZH);

        streamer.run(new SseChannel(emitter), 1L, SESSION, null, leaves, null, null, true);

        String header = ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.wake.header");
        verify(history, never()).append(any(), anyLong(), eq("user"), any());
        verify(history).append(eq(SESSION), eq(1L), eq("assistant"), eq(header + "\n\n补充内容"), any(), any());
        // 标头先于正文作为第一帧答案 token 推出
        assertThat(frames).anyMatch(f -> f.contains(header));
    }

    @Test
    void 没有待补充报告时唤醒被拒() {
        LlmEndpointService endpointService = mock(LlmEndpointService.class);
        when(endpointService.chatEndpoints(1L)).thenReturn(ChatTestEndpoints.eps(1L, "gpt-5"));
        ChatTurnRunner turnRunner = mock(ChatTurnRunner.class);
        when(turnRunner.run(any(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ChatTurnRunner.TurnResult.COMPLETED);
        ChatHistoryService history = mock(ChatHistoryService.class);
        ChatTurnStreamer streamer = new ChatTurnStreamer(turnRunner, history, mock(WorkbenchRunRegistry.class),
                registry, ChatTestEndpoints.PROMPTS);
        UsageTrackingChatModel model = new UsageTrackingChatModel(mock(ChatModel.class));
        ChatAgentFactory agentFactory = mock(ChatAgentFactory.class);
        when(agentFactory.leavesFor(any(), any()))
                .thenReturn(new ChatAgentFactory.Leaves("x", model, model, null, AgentLang.ZH));
        ChatConcurrencyGate gate = new ChatConcurrencyGate(10);
        ChatWorkbenchController controller = new ChatWorkbenchController(agentFactory, endpointService, registry,
                history, contextStore, streamer, mock(ChatTurnRewinder.class), mock(WorkbenchRunRegistry.class),
                childRuns, ChatTestEndpoints.prefs(), gate, new MessageCatalog(), ChatTestEndpoints.PROMPTS,
                ChatTestEndpoints.zhLang(), Executors.newVirtualThreadPerTaskExecutor());
        ChatWorkbenchController.WakeRequest request = new ChatWorkbenchController.WakeRequest();
        request.setSessionId(SESSION);

        // 不欠：2208，名额没被碰过
        assertThatThrownBy(() -> controller.wake(1L, request, mock(HttpServletResponse.class)))
                .isInstanceOf(BizException.class).hasFieldOrPropertyWithValue("code", 2208);
        assertThat(gate.tryAcquire(1L)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
        gate.release(1L);

        // 欠着：放行，跑的是唤醒轮（没有用户提问，消息是唤醒指令）
        when(contextStore.load(SESSION)).thenReturn(List.of());
        spawnAndDeliver("离线到的调研结论");
        controller.wake(1L, request, mock(HttpServletResponse.class));

        verify(turnRunner, timeout(5_000)).run(any(), eq(1L), eq(SESSION),
                eq(ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.wake.instruction")), isNull(), any(), any(), any());
    }

    private static String text(Message m) {
        return m.getText() == null ? "" : m.getText();
    }
}
