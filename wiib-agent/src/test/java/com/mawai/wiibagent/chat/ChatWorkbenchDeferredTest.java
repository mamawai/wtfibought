package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibagent.controller.ChatWorkbenchController;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibagent.llm.SseChannel;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 补答轮入口 {@code /deferred} 的准入与收尾形态。
 * <p>
 * 准入钉三件事：没欠账拒（2208）、占线拒且<b>不做让位握手</b>（2203，另一个标签页发起补答不能挤掉
 * 正在跑的用户轮）、名额到手后才出队。收尾钉的是补答轮与普通轮的三处差别：不落 user 行、
 * 标头是第一帧答案 token、落库内容以 {@code chat.deferred.prefix} 开头（前端据此不给重新生成）。
 * runner 打桩：编排归 {@code ChatTurnRunnerTest}，这里只看 controller 这层。
 */
class ChatWorkbenchDeferredTest {

    private static final String SESSION = "wb-1-deferred";

    private static final class RecordingEmitter extends SseEmitter {
        private final List<String> raw = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            builder.build().forEach(d -> {
                if (d.getData() instanceof String s) raw.add(s);
            });
        }
    }

    private final ChatTurnRunner turnRunner = mock(ChatTurnRunner.class);
    private final ChatHistoryService history = mock(ChatHistoryService.class);
    private final ChatConcurrencyGate gate = new ChatConcurrencyGate(10);
    private final ChatYieldCoordinator coordinator = new ChatYieldCoordinator();
    private final LlmEndpointService endpointService = mock(LlmEndpointService.class);
    private final ChatAgentFactory factory = mock(ChatAgentFactory.class);

    private ChatTurnStreamer streamer() {
        return new ChatTurnStreamer(turnRunner, history, mock(WorkbenchRunRegistry.class), coordinator,
                new ApprovalRegistry(), ChatTestEndpoints.PROMPTS);
    }

    private ChatWorkbenchController controller() {
        when(endpointService.chatEndpoints(1L)).thenReturn(ChatTestEndpoints.eps(1L, "gpt-5"));
        when(factory.leavesFor(any(), any())).thenReturn(leaves());
        return new ChatWorkbenchController(factory, endpointService, new ApprovalRegistry(),
                history, mock(ChatContextStore.class), streamer(), mock(ChatTurnRewinder.class),
                mock(WorkbenchRunRegistry.class), gate, new MessageCatalog(), coordinator,
                ChatTestEndpoints.PROMPTS, ChatTestEndpoints.zhLang(), Executors.newVirtualThreadPerTaskExecutor());
    }

    /** run() 要拿叶子清账本、取语言；runner 是 mock，图用不上 */
    private static ChatAgentFactory.Leaves leaves() {
        UsageTrackingChatModel model = new UsageTrackingChatModel(mock(ChatModel.class));
        return new ChatAgentFactory.Leaves("test", model, model, Map.of(), null, AgentLang.ZH);
    }

    private static ChatTurnRunner.ExpertBatch doneBatch() {
        return new ChatTurnRunner.ExpertBatch(List.of("market_agent"),
                List.of(CompletableFuture.completedFuture(ChatTurnRunner.expertMessage(
                        ChatTestEndpoints.PROMPTS, AgentLang.ZH, "market_agent", "chat.expertStatus.data", "市场结论"))));
    }

    private void requestDeferred(ChatWorkbenchController controller) {
        ChatWorkbenchController.DeferredRequest request = new ChatWorkbenchController.DeferredRequest();
        request.setSessionId(SESSION);
        controller.deferred(1L, request, mock(HttpServletResponse.class));
    }

    @Test
    void 没欠账时拒绝() {
        assertThatThrownBy(() -> requestDeferred(controller()))
                .isInstanceOf(BizException.class).hasFieldOrPropertyWithValue("code", 2208);
        assertThat(gate.tryAcquire(1L)).as("被拒的请求不能占住名额").isEqualTo(ChatConcurrencyGate.Acquire.OK);
    }

    /**
     * 占线只拒不让位：用户消息永远优先，补答只能等空档。反过来的话，B 标签页看见欠账发起补答，
     * 会把 A 标签页正在跑的用户轮挤掉。欠的那一单也得原样留着，等下次空闲再来
     */
    @Test
    void 占线时拒绝且不触发让位() {
        coordinator.registerDeferred(1L, SESSION, "看看行情", doneBatch());
        ChatWorkbenchController controller = controller();
        gate.tryAcquire(1L);                                   // 用户自己的一轮在跑
        ChatYieldCoordinator.TurnHandle running = coordinator.openTurn(1L);
        running.enterExpertWait();                             // 且正处专家等待期：让位窗口是开着的

        assertThatThrownBy(() -> requestDeferred(controller))
                .isInstanceOf(BizException.class).hasFieldOrPropertyWithValue("code", 2203);

        assertThat(running.yieldRequested()).as("补答不许扣让位扳机").isFalse();
        assertThat(coordinator.hasPending(SESSION)).as("欠账原样留着").isTrue();
    }

    /** 收尾形态：不落 user 行；标头随首个 chunk 作为第一帧答案 token 推出；落库 = 标头 + 模型输出，以 prefix 开头 */
    @SuppressWarnings("unchecked")
    @Test
    void 补答轮不落user行且答案带标头() {
        doAnswer(inv -> {
            ((Consumer<String>) inv.getArgument(5)).accept("补上的答案");
            return ChatTurnRunner.TurnResult.COMPLETED;
        }).when(turnRunner).run(any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
        ChatTurnStreamer streamer = streamer();
        RecordingEmitter emitter = new RecordingEmitter();

        streamer.run(new SseChannel(emitter), 1L, SESSION, "看看行情", leaves(),
                coordinator.openTurn(1L), null, null, doneBatch());

        String header = ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.deferred.header", Map.of("question", "看看行情"));
        // 标头是答案流的第一帧，模型输出紧跟其后
        List<String> answerFrames = emitter.raw.stream().filter(s -> s.contains("\"role\":\"answer\"")).toList();
        assertThat(answerFrames).hasSize(2);
        assertThat(answerFrames.get(0)).contains("【补答「看看行情」】");
        assertThat(answerFrames.get(1)).contains("补上的答案");
        verify(history, never()).append(any(), anyLong(), eq("user"), any());
        ArgumentCaptor<String> saved = ArgumentCaptor.captor();
        verify(history).append(eq(SESSION), eq(1L), eq("assistant"), saved.capture(), any(), any());
        assertThat(saved.getValue())
                .startsWith(ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.deferred.prefix"))
                .isEqualTo(header + "\n\n补上的答案");
        // 补答指令进的是模型侧消息，不带提问标记（它不是重新生成要定位的提问）
        ArgumentCaptor<String> enriched = ArgumentCaptor.captor();
        verify(turnRunner).run(any(), anyLong(), eq(SESSION), enriched.capture(), any(), any(), any(), any(), any(),
                argThat(b -> b != null && b.names().equals(List.of("market_agent"))));
        assertThat(enriched.getValue())
                .startsWith(ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.turn.timePrefix"))
                .contains("此前问题「看看行情」")
                .doesNotContain(ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.turn.questionPrefix"));
    }

    /**
     * 补答轮在专家等待期被新消息挤掉：一个答案帧都不许推（标头随首个 chunk 才出，等待期没有 chunk），
     * 否则前端留着一个只有标头、收不了尾的气泡；done 带 question=原问题，前端按它把说明行挂对位置；
     * 原问题连同在途批次重新排队
     */
    @Test
    void 补答轮被让位时不推标头且重新排队() {
        ChatTurnRunner.ExpertBatch batch = doneBatch();
        when(turnRunner.run(any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ChatTurnRunner.TurnResult(batch, false));
        ChatTurnStreamer streamer = streamer();
        RecordingEmitter emitter = new RecordingEmitter();

        streamer.run(new SseChannel(emitter), 1L, SESSION, "看看行情", leaves(),
                coordinator.openTurn(1L), null, null, batch);

        assertThat(emitter.raw).noneMatch(s -> s.contains("\"role\":\"answer\""));
        assertThat(emitter.raw).filteredOn(s -> s.contains("\"deferred\":true")).singleElement().asString()
                .contains("\"question\":\"看看行情\"").contains("\"pending\":true");
        verify(history, never()).append(any(), anyLong(), any(), any(), any(), any());
        ChatYieldCoordinator.DeferredWork requeued = coordinator.takeDeferred(SESSION).orElseThrow();
        assertThat(requeued.question()).isEqualTo("看看行情");
        assertThat(requeued.batch()).isSameAs(batch);
    }

    /** 名额到手后才出队：成功发起一次补答，队列即清空，第二次发起按没欠账拒 */
    @Test
    void 名额到手后才出队() {
        coordinator.registerDeferred(1L, SESSION, "看看行情", doneBatch());
        when(turnRunner.run(any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ChatTurnRunner.TurnResult.COMPLETED);
        ChatWorkbenchController controller = controller();

        requestDeferred(controller);

        assertThat(coordinator.hasPending(SESSION)).isFalse();
        assertThatThrownBy(() -> requestDeferred(controller))
                .isInstanceOf(BizException.class).hasFieldOrPropertyWithValue("code", 2208);
    }
}
