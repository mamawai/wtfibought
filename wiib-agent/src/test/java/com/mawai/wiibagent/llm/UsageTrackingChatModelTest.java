package com.mawai.wiibagent.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 用量装饰器：盯 getOptions 透传（覆写错会让 agent 的工具列表变空）与"没拿到 usage 不能当 0"。 */
class UsageTrackingChatModelTest {

    private static ChatResponse respWith(Integer prompt, Integer completion, Integer total) {
        ChatResponseMetadata meta = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(prompt, completion, total)).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))), meta);
    }

    /** 上游没给 usage：metadata 里压根没有 usage 这一项 */
    private static ChatResponse respWithoutUsage() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
    }

    /**
     * 最要命的一条：getOptions 必须是被包模型那个实例本身。
     * 返回自己新造的 options，ResilientChatService 挂出去的 tools 就是空数组，一个工具都调不动。
     */
    @Test
    void getOptionsIsPassedThroughUntouched() {
        ChatModel inner = mock(ChatModel.class);
        ChatOptions opts = ChatOptions.builder().model("m").build();
        when(inner.getOptions()).thenReturn(opts);

        assertThat(new UsageTrackingChatModel(inner).getOptions()).isSameAs(opts);
    }

    /** ReAct 一轮调很多次，token 要跨调用累加 */
    @Test
    void tokensAccumulateAcrossCalls() {
        ChatModel inner = mock(ChatModel.class);
        when(inner.call(any(Prompt.class)))
                .thenReturn(respWith(100, 20, 120), respWith(300, 50, 350));
        UsageTrackingChatModel m = new UsageTrackingChatModel(inner);

        m.call(new Prompt("a"));
        m.call(new Prompt("b"));

        UsageTrackingChatModel.UsageSnapshot s = m.snapshot();
        assertThat(s.modelCalls()).isEqualTo(2);
        assertThat(s.promptTokens()).isEqualTo(400L);
        assertThat(s.completionTokens()).isEqualTo(70L);
        assertThat(s.totalTokens()).isEqualTo(470L);
    }

    /**
     * 网关不报 usage：三项为 null 而不是 0——0 会被当成"这轮没花钱"，是假的。
     * 注意 Spring AI 此时给的是全 0 的 EmptyUsage 而非 null，靠 null 判断不出来，只能认正数。
     */
    @Test
    void missingUsageStaysNullNotZero() {
        ChatModel inner = mock(ChatModel.class);
        when(inner.call(any(Prompt.class))).thenReturn(respWithoutUsage());
        UsageTrackingChatModel m = new UsageTrackingChatModel(inner);

        m.call(new Prompt("a"));

        UsageTrackingChatModel.UsageSnapshot s = m.snapshot();
        assertThat(s.modelCalls()).isEqualTo(1);  // 调用次数照常记
        assertThat(s.promptTokens()).isNull();
        assertThat(s.completionTokens()).isNull();
        assertThat(s.totalTokens()).isNull();
    }

    /**
     * 网关只报一部分字段：报了的照常累加，没报的保持 null，字段之间不互相拖累。
     * （不对 totalTokens 的缺失做断言——DefaultUsage 在 total 为空时会用 prompt+completion 自行推导，
     * 那是 Spring AI 的行为不是本类的）
     */
    @Test
    void partialUsageAccountedPerField() {
        ChatModel inner = mock(ChatModel.class);
        when(inner.call(any(Prompt.class)))
                .thenReturn(respWith(100, 0, 100), respWith(200, 0, 200));
        UsageTrackingChatModel m = new UsageTrackingChatModel(inner);

        m.call(new Prompt("a"));
        m.call(new Prompt("b"));

        UsageTrackingChatModel.UsageSnapshot s = m.snapshot();
        assertThat(s.promptTokens()).isEqualTo(300L);
        assertThat(s.completionTokens()).isNull();  // 两次都是 0 = 上游没报这一项
        assertThat(s.totalTokens()).isEqualTo(300L);
    }

    /** 一次没调过：次数 0、token 全 null */
    @Test
    void snapshotBeforeAnyCall() {
        UsageTrackingChatModel.UsageSnapshot s =
                new UsageTrackingChatModel(mock(ChatModel.class)).snapshot();
        assertThat(s.modelCalls()).isZero();
        assertThat(s.totalTokens()).isNull();
    }

    /**
     * 对话轨的实例跟着叶子图跨轮缓存，只能靠 reset 划轮边界：清完必须回到"一次没调过"的状态，
     * token 三项要回 null 而不是 0——否则新一轮上游不报 usage 时，前端看到的是个假的 0。
     */
    @Test
    void resetGoesBackToUntouchedState() {
        ChatModel inner = mock(ChatModel.class);
        when(inner.call(any(Prompt.class))).thenReturn(respWith(100, 20, 120));
        UsageTrackingChatModel m = new UsageTrackingChatModel(inner);
        m.call(new Prompt("上一轮"));

        m.reset();

        assertThat(m.snapshot().modelCalls()).isZero();
        assertThat(m.snapshot().promptTokens()).isNull();
        assertThat(m.snapshot().completionTokens()).isNull();
        assertThat(m.snapshot().totalTokens()).isNull();

        m.call(new Prompt("这一轮"));
        assertThat(m.snapshot().totalTokens()).isEqualTo(120L);   // 只算清零之后的
    }

    /** 深浅两条端点合账：报了的相加，两边都没报才留 null */
    @Test
    void mergeSumsPerFieldAndKeepsNullWhenNeitherReported() {
        UsageTrackingChatModel.UsageSnapshot deep =
                new UsageTrackingChatModel.UsageSnapshot(2, 100L, 20L, null);
        UsageTrackingChatModel.UsageSnapshot light =
                new UsageTrackingChatModel.UsageSnapshot(3, 30L, null, null);

        UsageTrackingChatModel.UsageSnapshot merged = deep.merge(light);

        assertThat(merged.modelCalls()).isEqualTo(5);
        assertThat(merged.promptTokens()).isEqualTo(130L);
        assertThat(merged.completionTokens()).isEqualTo(20L);   // 一边没报，用报了的那份
        assertThat(merged.totalTokens()).isNull();              // 两边都没报
    }

    /** reset 之后才终止的流入账到发起时那本账：中断丢下的在途流不会污染下一轮 */
    @Test
    void 晚到的入账落进发起时的账本不污染新一轮() {
        ChatModel inner = mock(ChatModel.class);
        Sinks.Many<ChatResponse> late = Sinks.many().unicast().onBackpressureBuffer();
        when(inner.stream(any(Prompt.class))).thenReturn(late.asFlux());
        UsageTrackingChatModel m = new UsageTrackingChatModel(inner);
        m.stream(new Prompt("上一轮")).subscribe();
        m.reset();

        late.tryEmitNext(respWith(100, 20, 120));
        late.tryEmitComplete();

        assertThat(m.snapshot().modelCalls()).isZero();
        assertThat(m.snapshot().totalTokens()).isNull();
    }

    /** 丢下在途流只这一轮不可信：账本换新之后，旧流再晚到也写不进来 */
    @Test
    void 丢下在途流只标本轮不可信() {
        UsageTrackingChatModel m = new UsageTrackingChatModel(mock(ChatModel.class));
        assertThat(m.untrusted()).isFalse();

        m.markAbandoned();
        assertThat(m.untrusted()).isTrue();

        m.reset();
        assertThat(m.untrusted()).isFalse();
    }

    /** 重订阅（ResilientChatService 的流式重试）：每次尝试各自入账，成功那次的 token 不能丢 */
    @Test
    void 重订阅时每次尝试各自入账() {
        ChatModel inner = mock(ChatModel.class);
        AtomicInteger attempts = new AtomicInteger();
        when(inner.stream(any(Prompt.class))).thenReturn(Flux.defer(() -> attempts.incrementAndGet() == 1
                ? Flux.error(new RuntimeException("502"))
                : Flux.just(respWith(100, 20, 120))));
        UsageTrackingChatModel m = new UsageTrackingChatModel(inner);

        m.stream(new Prompt("a")).retry(1).blockLast();

        assertThat(m.snapshot().modelCalls()).isEqualTo(2);     // 失败那次也是一次调用
        assertThat(m.snapshot().totalTokens()).isEqualTo(120L);
    }
}
