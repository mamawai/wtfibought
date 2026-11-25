package com.mawai.wiibagent.replay;

import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.ChatModelFactory;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibagent.llm.SseChannel;
import com.mawai.wiibagent.llm.SseChatModel;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.handover.Start;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复盘 AI 教练一次流式调用的事件面：token 逐帧 → done 全文；模型抛错 → error 一帧且不带 key；
 * 通道已关（用户切走）→ 不再拉取后续帧、不发 done；一帧不出时断开 → 掐断调用、名额归还；
 * 关停提前收尾 → 发"已中断"不发 done；口关着 → 新请求直接拒。
 */
class ReplayCoachServiceTest {

    private static final long USER = 7L;

    /** 记账用 emitter：只攒每一帧的 JSON 载荷（event 名/data 前缀/帧尾空行不要） */
    private static final class RecordingEmitter extends SseEmitter {
        final List<String> raw = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            builder.build().forEach(d -> {
                if (d.getData() instanceof String s && s.startsWith("{")) raw.add(s);
            });
        }
    }

    private static ReplayCoachRequest hintReq() {
        return new ReplayCoachRequest(ReplayCoachRequest.MODE_HINT, null, "ETHUSDT", 5, true, null,
                List.of(new ReplayCoachRequest.Bar("D1 00:00", 1, 2, 0.5, 1.5, 10)), null, List.of(), null, null);
    }

    private static ChatResponse frame(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ReplayCoachService service() {
        PromptCatalog prompts = new PromptCatalog();
        return new ReplayCoachService(mock(LlmEndpointService.class), mock(ChatModelFactory.class),
                new ReplayCoachPrompts(prompts), prompts, mock(UserLangResolver.class));
    }

    /** 准入走得通的教练：用户有默认端点，深模型就是给的这个 */
    private static ReplayCoachService startable(ChatModel model) {
        LlmEndpointService endpoints = mock(LlmEndpointService.class);
        when(endpoints.defaultOf(USER)).thenReturn(new UserLlmEndpoint());
        ChatModelFactory models = mock(ChatModelFactory.class);
        when(models.modelsFor(any())).thenReturn(new ChatModelFactory.Models(model, model));
        UserLangResolver langs = mock(UserLangResolver.class);
        when(langs.of(USER)).thenReturn(AgentLang.ZH);
        PromptCatalog prompts = new PromptCatalog();
        return new ReplayCoachService(endpoints, models, new ReplayCoachPrompts(prompts), prompts, langs);
    }

    @Test
    void 逐帧转发token并以done收尾() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(frame("结构："), frame("震荡")));
        RecordingEmitter emitter = new RecordingEmitter();

        service().run(new SseChannel(emitter), new CompletableFuture<>(), model, hintReq(), AgentLang.ZH);

        assertThat(emitter.raw).containsExactly(
                "{\"text\":\"结构：\"}", "{\"text\":\"震荡\"}", "{\"answer\":\"结构：震荡\"}");
    }

    @Test
    void 模型抛错时发error一帧且文案不含原始异常() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(
                Flux.error(new SseChatModel.UpstreamRejected(401, "401 Unauthorized sk-secret")));
        RecordingEmitter emitter = new RecordingEmitter();

        service().run(new SseChannel(emitter), new CompletableFuture<>(), model, hintReq(), AgentLang.ZH);

        assertThat(emitter.raw).hasSize(1);
        assertThat(emitter.raw.get(0)).contains("API key 无效").doesNotContain("sk-secret");
    }

    @Test
    void 通道已关则停止拉帧不发done() {
        ChatModel model = mock(ChatModel.class);
        AtomicInteger pulled = new AtomicInteger();
        when(model.stream(any(Prompt.class))).thenReturn(
                Flux.range(1, 1000).map(i -> frame("t" + i)).doOnNext(f -> pulled.incrementAndGet()));
        RecordingEmitter emitter = new RecordingEmitter();
        SseChannel channel = new SseChannel(emitter);
        channel.markClosed();

        service().run(channel, new CompletableFuture<>(), model, hintReq(), AgentLang.ZH);

        assertThat(emitter.raw).isEmpty();
        // takeWhile 在第一帧就停了；预取几帧是 Reactor 的正常行为，但绝不该把 1000 帧全拉完
        assertThat(pulled.get()).isLessThan(1000);
    }

    /** 关停提前收尾掐断的：已流出的半截不当完整回答发 done，改发一帧"服务更新，已中断" */
    @Test
    void 提前收尾掐断时发中断而不是done() {
        ReplayCoachService service = service();
        CompletableFuture<Void> cancel = new CompletableFuture<>();
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(frame("结构：")).concatWith(Flux.defer(() -> {
            service.cut();
            cancel.complete(null);    // cut 完成的就是在跑那次的这个信号
            return Flux.never();
        })));
        RecordingEmitter emitter = new RecordingEmitter();

        service.run(new SseChannel(emitter), cancel, model, hintReq(), AgentLang.ZH);

        assertThat(emitter.raw).hasSize(2);
        assertThat(emitter.raw.get(0)).isEqualTo("{\"text\":\"结构：\"}");
        assertThat(emitter.raw.get(1)).contains(new PromptCatalog().get(AgentLang.ZH, "coach.error.interrupted"));
    }

    /** 接新请求的口关着（服务关停中）：直接拒，回"服务正在更新"，不占在跑名额 */
    @Test
    void 口关着新请求直接拒() {
        ReplayCoachService service = startable(mock(ChatModel.class));

        assertThatThrownBy(() -> service.start(USER, hintReq()))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.CHAT_SERVICE_UPDATING.getCode());
        assertThat(service.count()).isZero();
    }

    /** 深模型想着、一帧不出时用户切走：断开回调掐断在途调用，run 返回、名额归还，同一用户能再点 */
    @Test
    void 一帧不出时断开则掐断调用并归还名额() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.never()).thenReturn(Flux.just(frame("答")));
        ReplayCoachService service = startable(model);
        service.open(Start.COLD);
        // 单测里没有 MVC 替 emitter 触发断开回调：emitter 换成 mock，从它身上取回调来调
        try (MockedConstruction<SseEmitter> emitters = mockConstruction(SseEmitter.class)) {
            service.start(USER, hintReq());
            assertThatThrownBy(() -> service.start(USER, hintReq()))
                    .isInstanceOf(BizException.class)
                    .hasFieldOrPropertyWithValue("code", ErrorCode.REPLAY_AI_BUSY.getCode());

            ArgumentCaptor<Runnable> onCompletion = ArgumentCaptor.forClass(Runnable.class);
            verify(emitters.constructed().getFirst()).onCompletion(onCompletion.capture());
            onCompletion.getValue().run();

            long deadline = System.currentTimeMillis() + 5_000;
            while (!started(service)) {
                assertThat(System.currentTimeMillis()).as("名额没归还").isLessThan(deadline);
                Thread.sleep(10);
            }
        } finally {
            service.stop();
        }
    }

    private static boolean started(ReplayCoachService service) {
        try {
            service.start(USER, hintReq());
            return true;
        } catch (BizException busy) {
            return false;
        }
    }
}
