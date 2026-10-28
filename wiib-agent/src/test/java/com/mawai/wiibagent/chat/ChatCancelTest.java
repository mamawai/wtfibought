package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.run.ChildRunRegistry;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.behavior.BehaviorAnalysisService;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.toolkit.IndicatorToolkit;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.trader.TraderChatService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户中断这一轮。
 * <p>
 * 两个检查点效果不同，测试也分开钉：
 * <ul>
 *   <li><b>开跑之前</b>中断＝真省钱：一次模型调用都不会发生；</li>
 *   <li><b>答案流中途</b>中断＝掐断在途流：取消传到模型层，后面的 token 不再烧；
 *       已经吐出来的半截必须留住——它是花钱换的。</li>
 * </ul>
 */
class ChatCancelTest {

    private static final String SESSION = "wb-1-cancel";
    private static final int LIMIT = 8;

    private final ChatModel deep = mock(ChatModel.class);
    private final ChatModel light = mock(ChatModel.class);
    private final ApprovalRegistry registry = new ApprovalRegistry();
    private final ChatContextStore contextStore = mock(ChatContextStore.class);
    private final ChildRunRegistry childRuns = ChatTestEndpoints.childRuns(contextStore);
    private final StringBuilder answer = new StringBuilder();

    private static ChatResponse responseOf(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private ChatAgentFactory.Leaves leaves() {
        when(deep.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(light.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.modelsFor(any())).thenReturn(new ChatModelFactory.Models(deep, light));
        ChatEndpoints eps = ChatTestEndpoints.eps(1L, "gpt-5");
        return new ChatAgentFactory(chatModelFactory, mock(MarketToolkit.class), mock(IndicatorToolkit.class), ChatTestEndpoints.newsToolkit(),
                mock(DeepAnalysisService.class), mock(BehaviorAnalysisService.class), mock(TraderChatService.class),
                mock(WorkbenchRunRegistry.class), childRuns, registry,
                ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS, LIMIT, "X")
                .leavesFor(eps, AgentLang.ZH);
    }

    private ChatTurnRunner runner() {
        return new ChatTurnRunner(contextStore, childRuns, ChatTestEndpoints.PROMPTS, mock(WorkbenchRunRegistry.class),
                ChatTestEndpoints.compactor());
    }

    private List<Message> savedContext() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(contextStore).save(eq(SESSION), eq(1L), captor.capture());
        return captor.getValue();
    }

    @Test
    void 开跑之前中断就不烧任何模型调用() {
        CompletableFuture<Void> cancel = CompletableFuture.completedFuture(null);   // 一进来就已经点了停

        ChatTurnRunner.TurnResult result = runner()
                .run(leaves(), 1L, SESSION, "看看行情", null, answer::append, s -> { }, cancel);

        assertThat(result.cancelled()).isTrue();
        verify(deep, never()).stream(any(Prompt.class));
        assertThat(answer.toString()).isEmpty();
        // 上下文里立块牌子：不立的话下一轮模型会替这个没答的问题代答
        assertThat(savedContext().getLast().getText()).contains("中断");
    }

    @Test
    void 答案流中途中断要留住已经吐出来的半截() {
        CompletableFuture<Void> cancel = new CompletableFuture<>();
        // 第一帧出字后就点停：第二帧已经排在队列里也不再上屏，但这次调用的 token 已经烧了
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> Flux.just(
                responseOf("前半截"), responseOf("后半截")));

        ChatTurnRunner.TurnResult result = runner()
                .run(leaves(), 1L, SESSION, "看看行情", null, chunk -> {
                    answer.append(chunk);
                    cancel.complete(null);
                }, s -> { }, cancel);

        assertThat(result.cancelled()).isTrue();
        assertThat(answer.toString()).isEqualTo("前半截");
        // 半截进上下文并标明中断：续聊接得上，模型也知道这句话没说完
        String tail = savedContext().getLast().getText();
        assertThat(tail).startsWith("前半截").endsWith(ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.cancelledNote"));
    }

    @Test
    void 一个字没出时给的是未作答而不是空串() {
        assertThat(ChatTurnRunner.cancelledAnswer(ChatTestEndpoints.PROMPTS, AgentLang.ZH, "")).contains("未作答");
        assertThat(ChatTurnRunner.cancelledAnswer(ChatTestEndpoints.PROMPTS, AgentLang.ZH, "  ")).contains("未作答");
        assertThat(ChatTurnRunner.cancelledAnswer(ChatTestEndpoints.PROMPTS, AgentLang.ZH, "半截")).isEqualTo("半截\n\n" + ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.cancelledNote"));
    }

    /** 点停止后在途的答案流被掐断：取消传到上游（自研协议断连、openai 协议关 SDK 流），token 不再往下烧 */
    @Test
    void 中断时掐断在途答案流() {
        CompletableFuture<Void> cancel = new CompletableFuture<>();
        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        // 吐一帧之后挂住：不掐它永远不结束
        when(deep.stream(any(Prompt.class))).thenAnswer(inv ->
                Flux.concat(Flux.just(responseOf("半截")), Flux.<ChatResponse>never())
                        .doOnCancel(() -> upstreamCancelled.set(true)));

        ChatTurnRunner.TurnResult result = runner()
                .run(leaves(), 1L, SESSION, "看看行情", null, chunk -> {
                    answer.append(chunk);
                    cancel.complete(null);
                }, s -> { }, cancel);

        assertThat(result.cancelled()).isTrue();
        assertThat(answer.toString()).isEqualTo("半截");
        assertThat(upstreamCancelled).isTrue();
    }

    /** 中断信号登记在运行注册表上：没轮在跑如实回 false，轮结束后信号不跨轮泄漏 */
    @Test
    void 没有轮在跑时中断请求如实回false() {
        WorkbenchRunRegistry registry = new WorkbenchRunRegistry();

        // 没登记过任何轮：按钮点晚了，前端要据此如实告诉用户，而不是假装停住了
        assertThat(registry.requestCancel(SESSION)).isFalse();

        CompletableFuture<Void> cancel = new CompletableFuture<>();
        registry.start(SESSION, (event, data) -> true, cancel);
        assertThat(registry.requestCancel(SESSION)).isTrue();
        assertThat(cancel).isCompleted();

        registry.finish(SESSION);
        assertThat(registry.requestCancel(SESSION)).isFalse();
    }
}
