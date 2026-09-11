package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.store.ChatHistoryService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

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
 * 三件必须钉住的事：
 * <ol>
 *   <li><b>深浅两条端点合账</b>——路由和专家烧的是浅模型，汇总烧的是深模型，前端要的是一轮的总数；</li>
 *   <li><b>轮边界靠 reset 划</b>——装饰器跟着叶子跨轮缓存，不清零就是上一轮的账接着涨；</li>
 *   <li><b>账本脏了就不报用量</b>——让位交出去的专家批次没人取消，它还在往同一份账本上记，
 *       这时候报出去的是个错的数。</li>
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
        JSONObject event(String name) {
            for (int i = 0; i < raw.size() - 1; i++) {
                if (raw.get(i).contains(name)) {
                    String data = raw.get(i + 1).trim();
                    if (data.startsWith("{")) {
                        return JSON.parseObject(data);
                    }
                }
            }
            return null;
        }
    }

    /** 只有账本是真的：图用不上（runner 被替身顶了），所以 experts/summarizer 给空 */
    private static ChatAgentFactory.Leaves leaves(UsageTrackingChatModel deep, UsageTrackingChatModel light) {
        return new ChatAgentFactory.Leaves(LABEL, deep, light, Map.of(), null, AgentLang.ZH);
    }

    /** 一套能真跑 {@code ChatTurnStreamer.run} 的最小装配 */
    private record Harness(ChatTurnStreamer streamer, ChatHistoryService history,
                           ChatYieldCoordinator coordinator) {
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
            search.accept(inv.getArgument(7));            // progressSink 之后是 searchSink
            Consumer<String> sink = inv.getArgument(5);   // leaves/userId/session/message/intent 之后才是答案 sink
            sink.accept("答案正文");
            return ChatTurnRunner.TurnResult.COMPLETED;
        }).when(turnRunner).run(any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any());

        ChatYieldCoordinator coordinator = new ChatYieldCoordinator();
        ChatTurnStreamer streamer = new ChatTurnStreamer(turnRunner, history, mock(WorkbenchRunRegistry.class),
                coordinator, new ApprovalRegistry(), ChatTestEndpoints.PROMPTS);
        return new Harness(streamer, history, coordinator);
    }

    /** 落库时那条 assistant 行带的读数 */
    private static ChatHistoryService.TurnMeta capturedMeta(ChatHistoryService history) {
        ArgumentCaptor<ChatHistoryService.TurnMeta> captor =
                ArgumentCaptor.forClass(ChatHistoryService.TurnMeta.class);
        verify(history).append(eq(SESSION), eq(1L), eq("assistant"), eq("答案正文"), captor.capture(), any());
        return captor.getValue();
    }

    @Test
    void 一轮的读数是深浅两条端点合起来的账() {
        UsageTrackingChatModel deep = new UsageTrackingChatModel(modelReporting(100, 20, 120));
        UsageTrackingChatModel light = new UsageTrackingChatModel(modelReporting(30, 5, 35));
        // 浅模型两次（路由+专家）、深模型一次（汇总）
        Harness h = harness(() -> {
            light.call(new Prompt("route"));
            light.call(new Prompt("expert"));
            deep.call(new Prompt("summarize"));
        });

        RecordingEmitter emitter = new RecordingEmitter();
        h.streamer().run(new SseChannel(emitter), 1L, SESSION, "看看行情",
                leaves(deep, light), h.coordinator().openTurn(1L), null, null, null);

        ChatHistoryService.TurnMeta meta = capturedMeta(h.history());
        assertThat(meta.modelLabel()).isEqualTo(LABEL);
        assertThat(meta.modelCalls()).isEqualTo(3);
        assertThat(meta.promptTokens()).isEqualTo(160L);        // 30+30+100
        assertThat(meta.completionTokens()).isEqualTo(30L);     // 5+5+20
        assertThat(meta.totalTokens()).isEqualTo(190L);         // 35+35+120
        assertThat(meta.latencyMs()).isNotNull().isGreaterThanOrEqualTo(0);

        // done 事件与落库同一份读数：前端本轮不用等刷新就能显示
        JSONObject done = emitter.event("done");
        assertThat(done).isNotNull();
        assertThat(done.getJSONObject("meta").getLongValue("totalTokens")).isEqualTo(190L);
        assertThat(done.getJSONObject("meta").getString("modelLabel")).isEqualTo(LABEL);
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
                leaves(shared, shared), h.coordinator().openTurn(1L), null, null, null);

        JSONObject search = emitter.event("search");
        assertThat(search).isNotNull();
        assertThat(search.getString("phase")).isEqualTo(SearchEvent.SEARCHING);
        assertThat(search.getString("query")).isEqualTo("BTC news");
        JSONObject done = emitter.event("done");
        assertThat(done.getJSONArray("sources")).extracting(s -> ((JSONObject) s).getString("url"))
                .containsExactly("https://a.com/1", "https://b.com/2");
        ArgumentCaptor<List<SearchEvent.Source>> sources = ArgumentCaptor.captor();
        verify(h.history()).append(eq(SESSION), eq(1L), eq("assistant"), eq("答案正文"), any(), sources.capture());
        assertThat(sources.getValue()).extracting(SearchEvent.Source::url)
                .containsExactly("https://a.com/1", "https://b.com/2");
    }

    @Test
    void 未绑轻模型时深浅是同一份账本不能算两遍() {
        UsageTrackingChatModel shared = new UsageTrackingChatModel(modelReporting(100, 20, 120));
        Harness h = harness(() -> shared.call(new Prompt("one")));

        h.streamer().run(new SseChannel(new RecordingEmitter()), 1L, SESSION, "看看行情",
                leaves(shared, shared), h.coordinator().openTurn(1L), null, null, null);

        ChatHistoryService.TurnMeta meta = capturedMeta(h.history());
        assertThat(meta.modelCalls()).isEqualTo(1);
        assertThat(meta.totalTokens()).isEqualTo(120L);
    }

    @Test
    void 第二轮的账不含第一轮() {
        UsageTrackingChatModel deep = new UsageTrackingChatModel(modelReporting(100, 20, 120));
        UsageTrackingChatModel light = new UsageTrackingChatModel(modelReporting(30, 5, 35));
        Harness h = harness(() -> {
            light.call(new Prompt("route"));
            deep.call(new Prompt("summarize"));
        });
        ChatAgentFactory.Leaves leaves = leaves(deep, light);

        h.streamer().run(new SseChannel(new RecordingEmitter()), 1L, SESSION, "第一问",
                leaves, h.coordinator().openTurn(1L), null, null, null);
        h.streamer().run(new SseChannel(new RecordingEmitter()), 1L, SESSION, "第二问",
                leaves, h.coordinator().openTurn(1L), null, null, null);

        ArgumentCaptor<ChatHistoryService.TurnMeta> captor =
                ArgumentCaptor.forClass(ChatHistoryService.TurnMeta.class);
        verify(h.history(), org.mockito.Mockito.times(2))
                .append(eq(SESSION), eq(1L), eq("assistant"), eq("答案正文"), captor.capture(), any());
        // 两轮各烧一次浅一次深；没清零的话第二轮会是 310
        assertThat(captor.getAllValues().get(0).totalTokens()).isEqualTo(155L);
        assertThat(captor.getAllValues().get(1).totalTokens()).isEqualTo(155L);
    }

    /**
     * 判据是"账本上有没有别轮的在途调用"，不是"这一轮的名额是不是抢来的"——
     * 让位交出去的专家批次没人取消，它能跨过好几轮继续往同一份账本上记 token。
     */
    @Test
    void 账本被别轮在途专家写脏时只报耗时() {
        UsageTrackingChatModel deep = new UsageTrackingChatModel(modelReporting(100, 20, 120));
        UsageTrackingChatModel light = new UsageTrackingChatModel(modelReporting(30, 5, 35));
        Harness h = harness(() -> {
            light.call(new Prompt("route"));
            deep.call(new Prompt("summarize"));
        });
        ChatAgentFactory.Leaves leaves = leaves(deep, light);
        // 上一轮让位时交出去的批次还没跑完：那些专家仍在往这同一份账本上记账
        h.coordinator().registerDeferred(1L, SESSION, "上一个问题",
                new ChatTurnRunner.ExpertBatch(List.of("market_agent"), List.of(new CompletableFuture<Message>())));

        h.streamer().run(new SseChannel(new RecordingEmitter()), 1L, SESSION, "插话",
                leaves, h.coordinator().openTurn(1L), null, null, null);

        ChatHistoryService.TurnMeta meta = capturedMeta(h.history());
        // 端点名与耗时是这一轮自己的，照报；用量混着别轮的账，一律不报
        assertThat(meta.modelLabel()).isEqualTo(LABEL);
        assertThat(meta.latencyMs()).isNotNull();
        assertThat(meta.modelCalls()).isNull();
        assertThat(meta.promptTokens()).isNull();
        assertThat(meta.completionTokens()).isNull();
        assertThat(meta.totalTokens()).isNull();
    }
}
