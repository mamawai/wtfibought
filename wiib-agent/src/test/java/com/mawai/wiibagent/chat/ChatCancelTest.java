package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.ChatConcurrencyGate;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.behavior.BehaviorAnalysisService;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.toolkit.NewsToolkit;
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
import static org.mockito.ArgumentMatchers.anyLong;
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
 *   <li><b>派发之前</b>中断＝真省钱：专家和汇总那次调用都不会发生；</li>
 *   <li><b>答案流中途</b>中断＝掐断在途流：取消传到模型层，后面的 token 不再烧；
 *       已经吐出来的半截必须留住——它是花钱换的。</li>
 * </ul>
 * 与让位的分界也要钉死：中断<b>不欠补答</b>，deferredExperts 必须是空。
 */
class ChatCancelTest {

    private static final String SESSION = "wb-1-cancel";
    private static final int NO_COMPRESSION = 999_999;
    private static final int LIMIT = 8;
    private static final String ROUTER_MARK = "你是研判工作台的调度器";

    private final ChatModel deep = mock(ChatModel.class);
    private final ChatModel light = mock(ChatModel.class);
    private final ApprovalRegistry registry = new ApprovalRegistry();
    private final ChatContextStore contextStore = mock(ChatContextStore.class);
    private final StringBuilder answer = new StringBuilder();

    private static ChatResponse responseOf(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** 路由永远说 FINISH：这几跑不关心专家派发，只看中断本身 */
    private void routerFinishes() {
        when(light.call(any(Prompt.class))).thenAnswer(inv -> {
            String head = ((Prompt) inv.getArgument(0)).getInstructions().getFirst().getText();
            return responseOf(head.contains(ROUTER_MARK) ? "FINISH" : "专家结论");
        });
    }

    private ChatAgentFactory.Leaves leaves() {
        when(deep.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(light.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.modelsFor(any())).thenReturn(new ChatModelFactory.Models(deep, light));
        ChatEndpoints eps = ChatTestEndpoints.eps(1L, "gpt-5");
        return new ChatAgentFactory(chatModelFactory, mock(MarketToolkit.class), mock(NewsToolkit.class),
                mock(DeepAnalysisService.class), mock(BehaviorAnalysisService.class), mock(TraderChatService.class),
                mock(WorkbenchRunRegistry.class), registry,
                ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS, LIMIT, NO_COMPRESSION, 6, "X")
                .leavesFor(eps, AgentLang.ZH);
    }

    /** 中断信号可以在跑到一半时打开：cancelRequested 每次检查点都读一次，粘滞不回退 */
    private static ChatTurnRunner.TurnYield yieldWith(AtomicBoolean cancelled) {
        return yieldWith(cancelled, new AtomicBoolean(false));
    }

    /** 两个信号都能单独摆布：用来钉"中断压过让位"这条优先级 */
    private static ChatTurnRunner.TurnYield yieldWith(AtomicBoolean cancelled, AtomicBoolean yielded) {
        return new ChatTurnRunner.TurnYield() {
            @Override
            public CompletableFuture<Void> enterExpertWait() {
                return yielded.get() ? CompletableFuture.completedFuture(null) : new CompletableFuture<>();
            }

            @Override
            public void exitExpertWait() {
            }

            @Override
            public boolean yieldRequested() {
                return yielded.get();
            }

            @Override
            public boolean cancelRequested() {
                return cancelled.get();
            }
        };
    }

    /** 带真实中断信号的让位面：runner 把它交给 ReactLoop，在途答案流靠它掐断 */
    private static ChatTurnRunner.TurnYield yieldWith(AtomicBoolean cancelled, CompletableFuture<Void> signal) {
        return new ChatTurnRunner.TurnYield() {
            @Override
            public CompletableFuture<Void> enterExpertWait() {
                return new CompletableFuture<>();
            }

            @Override
            public void exitExpertWait() {
            }

            @Override
            public boolean yieldRequested() {
                return false;
            }

            @Override
            public boolean cancelRequested() {
                return cancelled.get();
            }

            @Override
            public CompletableFuture<Void> cancelSignal() {
                return signal;
            }
        };
    }

    private List<Message> savedContext() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(contextStore).save(eq(SESSION), eq(1L), captor.capture());
        return captor.getValue();
    }

    @Test
    void 派发之前中断就不再烧汇总那次调用() {
        routerFinishes();
        AtomicBoolean cancelled = new AtomicBoolean(true);   // 一进循环就已经点了停

        ChatTurnRunner.TurnResult result = new ChatTurnRunner(contextStore, registry, ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS)
                .run(leaves(), 1L, SESSION, "看看行情", null, answer::append, e -> { }, s -> { }, yieldWith(cancelled), null);

        assertThat(result.cancelled()).isTrue();
        // 中断不是让位：不欠补答，没有在途批次要交给协调器
        assertThat(result.yielded()).isFalse();
        // 路由都没问，汇总更没跑——这才是"真省钱"
        verify(light, never()).call(any(Prompt.class));
        verify(deep, never()).stream(any(Prompt.class));
        assertThat(answer.toString()).isEmpty();
        // 上下文里立块牌子：不立的话下一轮 summarizer 会替这个没答的问题代答
        assertThat(savedContext().getLast().getText()).contains("中断");
    }

    @Test
    void 答案流中途中断要留住已经吐出来的半截() {
        routerFinishes();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        CompletableFuture<Void> signal = new CompletableFuture<>();
        // 第一帧出字后就点停：第二帧已经排在队列里也不再上屏，但这次调用的 token 已经烧了
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> Flux.just(
                responseOf("前半截"), responseOf("后半截")));

        ChatTurnRunner.TurnResult result = new ChatTurnRunner(contextStore, registry, ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS)
                .run(leaves(), 1L, SESSION, "看看行情", null, chunk -> {
                    answer.append(chunk);
                    cancelled.set(true);
                    signal.complete(null);   // 与 TurnHandle.requestCancel 同序：先置位再发信号
                }, e -> { }, s -> { }, yieldWith(cancelled, signal), null);

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

    /**
     * 两个信号同时置位时中断必须赢。
     * 顺序反了的话，用户点完停止再敲下一句，让位会先命中——被停掉的那个问题进补答队列，
     * 事后被完整跑完一次 summarizer 并落库，"到此为止不补"就成了一句空话。
     */
    @Test
    void 中断压过让位不留补答的尾巴() {
        routerFinishes();
        AtomicBoolean cancelled = new AtomicBoolean(true);
        AtomicBoolean yielded = new AtomicBoolean(true);

        ChatTurnRunner.TurnResult result = new ChatTurnRunner(contextStore, registry, ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS)
                .run(leaves(), 1L, SESSION, "看看行情", null, answer::append, e -> { }, s -> { },
                        yieldWith(cancelled, yielded), null);

        assertThat(result.cancelled()).isTrue();
        assertThat(result.yielded()).isFalse();          // 没有在途批次要交给协调器排补答
        assertThat(result.deferredExperts()).isNull();
        assertThat(savedContext().getLast().getText()).contains("中断");
    }

    /** 点停止后在途的答案流被掐断：取消传到上游（自研协议断连、openai 协议关 SDK 流），token 不再往下烧 */
    @Test
    void 中断时掐断在途答案流() {
        routerFinishes();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        CompletableFuture<Void> signal = new CompletableFuture<>();
        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        // 吐一帧之后挂住：不掐它永远不结束
        when(deep.stream(any(Prompt.class))).thenAnswer(inv ->
                Flux.concat(Flux.just(responseOf("半截")), Flux.<ChatResponse>never())
                        .doOnCancel(() -> upstreamCancelled.set(true)));

        ChatTurnRunner.TurnResult result = new ChatTurnRunner(contextStore, registry, ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS)
                .run(leaves(), 1L, SESSION, "看看行情", null, chunk -> {
                    answer.append(chunk);
                    cancelled.set(true);
                    signal.complete(null);   // 与 TurnHandle.requestCancel 同序：先置位再发信号
                }, e -> { }, s -> { }, yieldWith(cancelled, signal), null);

        assertThat(result.cancelled()).isTrue();
        assertThat(answer.toString()).isEqualTo("半截");
        assertThat(upstreamCancelled).isTrue();
    }

    @Test
    void 没有轮在跑时中断请求如实回false() {
        ChatConcurrencyGate gate = new ChatConcurrencyGate(10);
        ChatYieldCoordinator coordinator = new ChatYieldCoordinator();

        // 没登记过任何轮：按钮点晚了，前端要据此如实告诉用户，而不是假装停住了
        assertThat(coordinator.requestCancel(1L)).isFalse();

        ChatYieldCoordinator.TurnHandle turn = coordinator.openTurn(1L);
        assertThat(coordinator.requestCancel(1L)).isTrue();
        assertThat(turn.cancelRequested()).isTrue();
        // 光有布尔叫不醒专家等待期那一等，信号 future 必须跟着完成
        assertThat(turn.cancelSignal()).isCompleted();

        // 信号不跨轮泄漏：这一轮结束后开的新轮是干净的
        coordinator.closeTurn(turn);
        ChatYieldCoordinator.TurnHandle next = coordinator.openTurn(1L);
        assertThat(next.cancelRequested()).isFalse();
        assertThat(next.cancelSignal()).isNotCompleted();
    }
}
