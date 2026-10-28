package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.store.ChatHistoryService;
import com.mawai.wiibagent.llm.ContextUsage;
import com.mawai.wiibagent.llm.SearchEvent;
import com.mawai.wiibagent.llm.SseChannel;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每轮读数（模型端点 / 耗时 / token）的口径。
 * <p>
 * 两件必须钉住的事：
 * <ol>
 *   <li><b>一轮的账含整理上下文那次调用</b>——它和作答都走主模型、记在同一本账上，前端要的是一轮的总数；</li>
 *   <li><b>轮边界靠 reset 划</b>——装饰器跟着叶子跨轮缓存，不清零就是上一轮的账接着涨。</li>
 * </ol>
 * 用真 {@link UsageTrackingChatModel} 包 mock 模型，在 runner 的替身里真调几次模型来"烧"token，
 * 这样账本走的是生产同一条路，而不是测试自己塞一个数进去。
 */
class ChatTurnMetaTest {

    private static final String SESSION = "wb-1-meta";
    private static final String LABEL = "我的端点 · gpt-5";

    /** 每次调用都报同一份 usage 的假模型；具体数值由调用方给 */
    private static ChatModel modelReporting(int prompt, int completion, int total) {
        ChatModel inner = mock(ChatModel.class);
        ChatResponseMetadata meta = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(prompt, completion, total)).build();
        when(inner.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))), meta));
        return inner;
    }

    /** 记账用的 emitter：done 事件里的 meta 只能从发出去的帧里看 */
    private static final class RecordingEmitter extends SseEmitter {
        private final List<String> raw = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            builder.build().forEach(d -> {
                if (d.getData() instanceof String s) raw.add(s);
            });
        }

        /** 事件帧是 "event:名字\n" + "data:JSON\n\n" 分开落的，按名字取紧随其后的那条 data */
        JsonNode event(String name) {
            for (int i = 0; i < raw.size() - 1; i++) {
                if (raw.get(i).contains(name)) {
                    String data = raw.get(i + 1).trim();
                    if (data.startsWith("{")) {
                        return MAPPER.readTree(data);
                    }
                }
            }
            return null;
        }
    }

    /** 只有账本是真的：主 agent 用不上（runner 被替身顶了），给空 */
    private static ChatAgentFactory.Leaves leaves(UsageTrackingChatModel deep) {
        return new ChatAgentFactory.Leaves(LABEL, deep, null, null, AgentLang.ZH, 256_000);
    }

    /** 一套能真跑 {@code ChatTurnStreamer.run} 的最小装配 */
    private record Harness(ChatTurnStreamer streamer, ChatHistoryService history) {
    }

    /**
     * @param burn runner 替身在这一轮里"烧模型"的动作：调几次就是几次模型调用
     */
    private static Harness harness(Runnable burn) {
        return harness(burn, sink -> { });
    }

    /** @param search 这一轮里模型报的搜索过程：喂给 runner 的 searchSink */
    private static Harness harness(Runnable burn, Consumer<Consumer<SearchEvent>> search) {
        ChatHistoryService history = mock(ChatHistoryService.class);
        ChatTurnRunner turnRunner = mock(ChatTurnRunner.class);
        doAnswer((Answer<ChatTurnRunner.TurnResult>) inv -> {
            burn.run();
            search.accept(inv.getArgument(6));            // 答案 sink 之后是 searchSink
            Consumer<String> sink = inv.getArgument(5);   // leaves/userId/session/message/intent 之后才是答案 sink
            sink.accept("答案正文");
            return ChatTurnRunner.TurnResult.COMPLETED;
        }).when(turnRunner).run(any(), anyLong(), any(), any(), any(), any(), any(), any());

        ChatTurnStreamer streamer = new ChatTurnStreamer(turnRunner, history, mock(WorkbenchRunRegistry.class),
                new ApprovalRegistry(), ChatTestEndpoints.PROMPTS);
        return new Harness(streamer, history);
    }

    /** 落库时那条 assistant 行带的读数 */
    private static ChatHistoryService.TurnMeta capturedMeta(ChatHistoryService history) {
        ArgumentCaptor<ChatHistoryService.TurnMeta> captor =
                ArgumentCaptor.forClass(ChatHistoryService.TurnMeta.class);
        verify(history).append(eq(SESSION), eq(1L), eq("assistant"), eq("答案正文"), captor.capture(), any());
        return captor.getValue();
    }

    @Test
    void 一轮的读数含整理上下文那次调用() {
        UsageTrackingChatModel deep = new UsageTrackingChatModel(modelReporting(100, 20, 120));
        // 整理上下文一次、主 agent 作答一次
        Harness h = harness(() -> {
            deep.call(new Prompt("compact"));
            deep.call(new Prompt("answer"));
        });

        RecordingEmitter emitter = new RecordingEmitter();
        h.streamer().run(new SseChannel(emitter), 1L, SESSION, "看看行情",
                leaves(deep), null, null, false);

        ChatHistoryService.TurnMeta meta = capturedMeta(h.history());
        assertThat(meta.modelLabel()).isEqualTo(LABEL);
        assertThat(meta.modelCalls()).isEqualTo(2);
        assertThat(meta.promptTokens()).isEqualTo(200L);
        assertThat(meta.completionTokens()).isEqualTo(40L);
        assertThat(meta.totalTokens()).isEqualTo(240L);
        assertThat(meta.latencyMs()).isNotNull().isGreaterThanOrEqualTo(0);

        // done 事件与落库同一份读数：前端本轮不用等刷新就能显示
        JsonNode done = emitter.event("done");
        assertThat(done).isNotNull();
        assertThat(done.get("meta").path("totalTokens").asLong(0)).isEqualTo(240L);
        assertThat(done.get("meta").path("modelLabel").asString(null)).isEqualTo(LABEL);
    }

    /** 搜索过程逐条外发；来源按 url 去重后随 done 下发并落库——刷新后答案底部的来源还在 */
    @Test
    void 搜索事件外发且来源随done与落库() {
        UsageTrackingChatModel shared = new UsageTrackingChatModel(modelReporting(1, 1, 2));
        Harness h = harness(() -> { }, sink -> {
            sink.accept(SearchEvent.searching("BTC news"));
            sink.accept(SearchEvent.searched("BTC news", List.of(
                    new SearchEvent.Source("https://a.com/1", "A1"), new SearchEvent.Source("https://b.com/2", "B2"))));
            sink.accept(SearchEvent.cited(List.of(new SearchEvent.Source("https://a.com/1", "A1"))));
        });

        RecordingEmitter emitter = new RecordingEmitter();
        h.streamer().run(new SseChannel(emitter), 1L, SESSION, "BTC 新闻",
                leaves(shared), null, null, false);

        JsonNode search = emitter.event("search");
        assertThat(search).isNotNull();
        assertThat(search.path("phase").asString(null)).isEqualTo(SearchEvent.SEARCHING);
        assertThat(search.path("query").asString(null)).isEqualTo("BTC news");
        JsonNode done = emitter.event("done");
        assertThat(done.get("sources")).extracting(s -> s.path("url").asString(null))
                .containsExactly("https://a.com/1", "https://b.com/2");
        ArgumentCaptor<List<SearchEvent.Source>> sources = ArgumentCaptor.captor();
        verify(h.history()).append(eq(SESSION), eq(1L), eq("assistant"), eq("答案正文"), any(), sources.capture());
        assertThat(sources.getValue()).extracting(SearchEvent.Source::url)
                .containsExactly("https://a.com/1", "https://b.com/2");
    }

    /** 这一轮结束时的上下文用量随 done 下发并落库：刷新后底栏计量条还在 */
    @Test
    void 上下文用量随done下发并落库() {
        ChatHistoryService history = mock(ChatHistoryService.class);
        ChatTurnRunner turnRunner = mock(ChatTurnRunner.class);
        ContextUsage context = new ContextUsage(1200, 256_000, false);
        doAnswer((Answer<ChatTurnRunner.TurnResult>) inv -> {
            Consumer<String> sink = inv.getArgument(5);
            sink.accept("答案正文");
            return new ChatTurnRunner.TurnResult(false, false, context);
        }).when(turnRunner).run(any(), anyLong(), any(), any(), any(), any(), any(), any());
        ChatTurnStreamer streamer = new ChatTurnStreamer(turnRunner, history, mock(WorkbenchRunRegistry.class),
                new ApprovalRegistry(), ChatTestEndpoints.PROMPTS);
        UsageTrackingChatModel model = new UsageTrackingChatModel(modelReporting(1, 1, 2));

        RecordingEmitter emitter = new RecordingEmitter();
        streamer.run(new SseChannel(emitter), 1L, SESSION, "看看行情", leaves(model), null, null, false);

        assertThat(capturedMeta(history).context()).isEqualTo(context);
        assertThat(emitter.event("done").get("meta").get("context").path("tokens").asLong()).isEqualTo(1200L);
    }

    @Test
    void 第二轮的账不含第一轮() {
        UsageTrackingChatModel deep = new UsageTrackingChatModel(modelReporting(100, 20, 120));
        Harness h = harness(() -> {
            deep.call(new Prompt("compact"));
            deep.call(new Prompt("answer"));
        });
        ChatAgentFactory.Leaves leaves = leaves(deep);

        h.streamer().run(new SseChannel(new RecordingEmitter()), 1L, SESSION, "第一问",
                leaves, null, null, false);
        h.streamer().run(new SseChannel(new RecordingEmitter()), 1L, SESSION, "第二问",
                leaves, null, null, false);

        ArgumentCaptor<ChatHistoryService.TurnMeta> captor =
                ArgumentCaptor.forClass(ChatHistoryService.TurnMeta.class);
        verify(h.history(), org.mockito.Mockito.times(2))
                .append(eq(SESSION), eq(1L), eq("assistant"), eq("答案正文"), captor.capture(), any());
        // 两轮各烧两次；没清零的话第二轮会是 480
        assertThat(captor.getAllValues().get(0).totalTokens()).isEqualTo(240L);
        assertThat(captor.getAllValues().get(1).totalTokens()).isEqualTo(240L);
    }
}
