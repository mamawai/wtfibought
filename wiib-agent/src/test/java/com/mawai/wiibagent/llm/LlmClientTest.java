package com.mawai.wiibagent.llm;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 韧性分层的契约：阻塞路径的重试归模型层（ResponsesChatModel 自带 / OpenAI SDK），
 * 本层不再来一轮——两层都重试会叠乘成 3×3=9 次，白白放大尾延迟。
 * 另钉一条 options 契约：options 类型跟着模型走（openai 协议硬转 OpenAiChatOptions）；
 * 以及中断两条：信号完成时掐断在途流并把取消传到上游、流正常结束不能反过来把信号 future 取消掉。
 */
class LlmClientTest {

    private final ChatModel model = mock(ChatModel.class);

    private LlmClient client() {
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        return LlmClient.builder()
                .model(model)
                .systemPrompt("你是助手")
                .build();
    }

    private static ChatResponse responseOf(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static final List<Message> ASK = List.of(new UserMessage("BTC 现在怎么样"));

    /** 重试是模型层的事：本层只调一次，失败原样抛给上层归类（LlmErrorMessages） */
    @Test
    void 阻塞路径失败只调一次且原样抛出() {
        when(model.call(any(Prompt.class))).thenThrow(new TransientAiException("502"));

        assertThatThrownBy(() -> client().execute(ASK))
                .isInstanceOf(TransientAiException.class);
        verify(model, times(1)).call(any(Prompt.class));
    }

    /** 系统提示是每个 agent 的纪律与格式约定，缺了不许静默放行 */
    @Test
    void systemPrompt缺省直接拦下() {
        assertThatThrownBy(() -> LlmClient.builder().model(model).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    static final class EchoTool {
        @Tool(name = "echo", description = "Echo the text back.")
        public String echo(String text) {
            return text;
        }
    }

    private LlmClient client(String systemPrompt, boolean withTool, boolean webSearch, String origin) {
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        return LlmClient.builder().model(model).systemPrompt(systemPrompt)
                .tools(withTool ? List.of(ToolCallbacks.from(new EchoTool())) : List.of())
                .webSearch(webSearch).origin(origin).build();
    }

    /** 前缀指纹跟着系统提示词、工具定义、搜索许可、背后端点走：哪样变了都得变，一样的配置得一样 */
    @Test
    void 前缀指纹随系统提示词工具搜索许可和端点变() {
        String base = client("你是助手", true, false, "端点").fingerprint();

        assertThat(client("你是助手", true, false, "端点").fingerprint()).isEqualTo(base);
        assertThat(client("你是分析师", true, false, "端点").fingerprint()).isNotEqualTo(base);
        assertThat(client("你是助手", false, false, "端点").fingerprint()).isNotEqualTo(base);
        assertThat(client("你是助手", true, true, "端点").fingerprint()).isNotEqualTo(base);
        assertThat(client("你是助手", true, false, "别的端点").fingerprint()).isNotEqualTo(base);
    }

    /** 按字数估把系统提示词和工具定义也算上 */
    @Test
    void 估算算上系统提示词和工具定义() {
        assertThat(client("你是助手", false, false, null).estimateTokens(ASK)).isEqualTo(4 + ContextUsage.estimate(ASK));
        assertThat(client("你是助手", true, false, null).estimateTokens(ASK))
                .isGreaterThan(client("你是助手", false, false, null).estimateTokens(ASK));
    }

    /** 模型收到的 Prompt 的 options */
    private ChatOptions optionsSentTo(ChatModel model) {
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        return prompt.getValue().getOptions();
    }

    /**
     * options 的具体类型必须跟着模型走：Spring AI 2.0 的 OpenAiChatModel 把 prompt 的 options 硬转
     * OpenAiChatOptions（不再合并运行时 options），泛型 builder 造的会当场 ClassCastException。
     * tool_choice 留空走 auto，不强制。
     */
    @Test
    void openai协议下options保持OpenAiChatOptions且不强制() {
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().model("deepseek-chat").build());
        when(model.call(any(Prompt.class))).thenReturn(responseOf("ok"));
        LlmClient client =LlmClient.builder()
                .model(model).systemPrompt("你是助手").tools(List.of(mock(ToolCallback.class))).build();

        client.execute(ASK);

        ChatOptions sent = optionsSentTo(model);
        assertThat(sent).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions openAi = (OpenAiChatOptions) sent;
        assertThat(openAi.getToolChoice()).isNull();
        assertThat(openAi.getToolCallbacks()).hasSize(1);
        assertThat(openAi.getModel()).isEqualTo("deepseek-chat");   // 生成参数沿用模型自己的
    }

    /**
     * 服务端搜索许可（只有 chat 的主 agent、researcher worker 和它们挂的 WebSearchTool 开）：对这个 client 的<b>每次</b>调用都生效。
     * 经 toolContext 捎带，ResponsesChatModel 建请求体时读回。
     */
    @Test
    void webSearch开关_许可落进chatOptions的toolContext() {
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());

        LlmClient client =LlmClient.builder()
                .model(model).systemPrompt("你是助手").tools(List.of(mock(ToolCallback.class)))
                .webSearch(true).build();

        ToolCallingChatOptions options = (ToolCallingChatOptions) client.chatOptions().orElseThrow();
        assertThat(options.getToolContext().get(SseChatModel.WEB_SEARCH_KEY)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void webSearch开关_无function工具的agent也捎得上() {
        // 没挂工具时 chatOptions 本是 null；搜索许可不许因此静默丢
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());

        LlmClient client =LlmClient.builder()
                .model(model).systemPrompt("你是助手").webSearch(true).build();

        ToolCallingChatOptions options = (ToolCallingChatOptions) client.chatOptions().orElseThrow();
        assertThat(options.getToolContext().get(SseChatModel.WEB_SEARCH_KEY)).isEqualTo(Boolean.TRUE);
    }

    /**
     * SDK 重试盲区的唯一豁免：响应体读到一半被掐（OpenAIInvalidDataException，如 HTTP/2 stream reset）
     * SDK 的 maxRetries 不管这类失败——这里单次重试救整轮唤醒，不与 SDK 重试叠乘。
     */
    @Test
    void 响应读取中断单次重试() {
        when(model.call(any(Prompt.class)))
                .thenThrow(new com.openai.errors.OpenAIInvalidDataException("Error reading response",
                        new java.io.IOException("stream was reset: CANCEL")))
                .thenReturn(responseOf("重试成功"));

        ChatResponse result = client().execute(ASK);

        verify(model, times(2)).call(any(Prompt.class));
        assertThat(result.getResult().getOutput().getText()).isEqualTo("重试成功");
    }

    /** 上游拒收（4xx，如不收搜索工具）流式不重试，原样抛给上层出提示；重试是重订阅，数订阅次数才看得出来 */
    @Test
    void 上游拒收时流式不重试原样抛出() {
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AtomicInteger subscriptions = new AtomicInteger();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.defer(() -> {
            subscriptions.incrementAndGet();
            return Flux.error(new NonTransientAiException(
                    "Gemini API HTTP 400: Multiple tools are supported only when they are all search tools."));
        }));
        LlmClient client = LlmClient.builder()
                .model(model).systemPrompt("你是助手").tools(List.of(mock(ToolCallback.class)))
                .webSearch(true).build();

        assertThatThrownBy(() -> client.streamingExecute(ASK, null).collectList().block())
                .isInstanceOf(NonTransientAiException.class);
        assertThat(subscriptions).hasValue(1);
    }

    // ========== 中断 ==========

    @Test
    void 信号到达时掐断在途流并取消上游() {
        AtomicBoolean upstreamCancelled = new AtomicBoolean();
        when(model.stream(any(Prompt.class))).thenReturn(
                Flux.<ChatResponse>never().doOnCancel(() -> upstreamCancelled.set(true)));
        CompletableFuture<Void> signal = new CompletableFuture<>();
        AtomicBoolean completed = new AtomicBoolean();

        client().streamingExecute(ASK, signal).subscribe(r -> { }, e -> { }, () -> completed.set(true));
        assertThat(upstreamCancelled).isFalse();

        signal.complete(null);

        assertThat(upstreamCancelled).isTrue();   // 取消传到了模型层：自研协议断连、openai 协议关 SDK 流
        assertThat(completed).isTrue();           // 下游看到的是正常结束，不是异常
    }

    /** Mono.fromFuture 缺省会在流结束时反向 cancel 这个 future，循环的中断检查点会把正常收尾当成用户中断 */
    @Test
    void 流正常结束不会反向取消信号() {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(responseOf("答完了")));
        CompletableFuture<Void> signal = new CompletableFuture<>();

        List<ChatResponse> out = client().streamingExecute(ASK, signal).collectList().block();

        assertThat(out).hasSize(1);
        assertThat(signal.isDone()).isFalse();
        assertThat(signal.isCancelled()).isFalse();
    }
}
