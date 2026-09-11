package com.mawai.wiibagent.replay;

import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.ChatModelFactory;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibagent.llm.SseChannel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 复盘 AI 教练一次流式调用的事件面：token 逐帧 → done 全文；模型抛错 → error 一帧且不带 key；
 * 通道已关（用户切走）→ 不再拉取后续帧、不发 done。
 */
class ReplayCoachServiceTest {

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

    @Test
    void 逐帧转发token并以done收尾() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(frame("结构："), frame("震荡")));
        RecordingEmitter emitter = new RecordingEmitter();

        service().run(new SseChannel(emitter), model, hintReq(), AgentLang.ZH);

        assertThat(emitter.raw).containsExactly(
                "{\"text\":\"结构：\"}", "{\"text\":\"震荡\"}", "{\"answer\":\"结构：震荡\"}");
    }

    @Test
    void 模型抛错时发error一帧且文案不含原始异常() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("401 Unauthorized sk-secret")));
        RecordingEmitter emitter = new RecordingEmitter();

        service().run(new SseChannel(emitter), model, hintReq(), AgentLang.ZH);

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

        service().run(channel, model, hintReq(), AgentLang.ZH);

        assertThat(emitter.raw).isEmpty();
        // takeWhile 在第一帧就停了；预取几帧是 Reactor 的正常行为，但绝不该把 1000 帧全拉完
        assertThat(pulled.get()).isLessThan(1000);
    }
}
