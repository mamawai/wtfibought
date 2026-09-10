package com.mawai.wiibagent.llm;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * 另钉两条 options 契约：首轮强制逐次落地、options 类型跟着模型走（openai 协议硬转 OpenAiChatOptions）；
 * 以及中断两条：信号完成时掐断在途流并把取消传到上游、流正常结束不能反过来把信号 future 取消掉。
 */
class ResilientChatServiceTest {

    private final ChatModel primary = mock(ChatModel.class);

    private ResilientChatService service() {
        when(primary.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        return ResilientChatService.builder()
                .model(primary)
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
        when(primary.call(any(Prompt.class))).thenThrow(new TransientAiException("502"));

        assertThatThrownBy(() -> service().execute(ASK))
                .isInstanceOf(TransientAiException.class);
        verify(primary, times(1)).call(any(Prompt.class));
    }

    /** 系统提示是每个 agent 的纪律与格式约定，缺了不许静默放行 */
    @Test
    void systemPrompt缺省直接拦下() {
        assertThatThrownBy(() -> ResilientChatService.builder().model(primary).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 主模型收到的 Prompt 的 options（逐次调用现算，chatOptions() 那份是不带强制的底稿） */
    private ChatOptions optionsSentTo(ChatModel model) {
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        return prompt.getValue().getOptions();
    }

    /**
     * 首轮强制是<b>逐次</b>落地的：只有"最后一条用户消息之后还没有工具回执"那一次调用带 required，
     * 拿到工具结果后必须放开否则 ReactLoop 收不了尾。responses 协议经 toolContext 捎信号。
     */
    @Test
    void 专家可要求首轮强制用工具_只在首轮() {
        when(primary.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(primary.call(any(Prompt.class))).thenReturn(responseOf("ok"));
        ResilientChatService service = ResilientChatService.builder()
                .model(primary).systemPrompt("你是助手").tools(List.of(mock(ToolCallback.class)))
                .forceFirstToolChoice("required").build();

        service.execute(ASK);
        assertThat(ToolChoice.of(optionsSentTo(primary))).isEqualTo(ToolChoice.REQUIRED);

        // 同一 agent 第二次调用：本轮已有工具回执 → 放开
        org.mockito.Mockito.clearInvocations(primary);
        Message toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "market_snapshot", "{}")))
                .build();
        service.execute(List.of(ASK.getFirst(), new AssistantMessage(""), toolResponse));
        assertThat(ToolChoice.of(optionsSentTo(primary))).isEqualTo(ToolChoice.AUTO);
    }

    /**
     * options 的具体类型必须跟着模型走：Spring AI 2.0 的 OpenAiChatModel 把 prompt 的 options 硬转
     * OpenAiChatOptions（不再合并运行时 options），泛型 builder 造的会当场 ClassCastException。
     * 首轮强制在这条协议上落的是 toolChoice 字段，不是 toolContext 信号。
     */
    @Test
    void openai协议下options保持OpenAiChatOptions且强制落在toolChoice() {
        when(primary.getOptions()).thenReturn(OpenAiChatOptions.builder().model("deepseek-chat").build());
        when(primary.call(any(Prompt.class))).thenReturn(responseOf("ok"));
        ResilientChatService service = ResilientChatService.builder()
                .model(primary).systemPrompt("你是助手").tools(List.of(mock(ToolCallback.class)))
                .forceFirstToolChoice("required").build();

        service.execute(ASK);

        ChatOptions sent = optionsSentTo(primary);
        assertThat(sent).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions openAi = (OpenAiChatOptions) sent;
        assertThat(openAi.getToolChoice()).isEqualTo("required");
        assertThat(openAi.getToolCallbacks()).hasSize(1);
        assertThat(openAi.getModel()).isEqualTo("deepseek-chat");   // 生成参数沿用模型自己的
    }

    @Test
    void 不要求时不塞信号() {
        when(primary.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());

        ResilientChatService service = ResilientChatService.builder()
                .model(primary).systemPrompt("你是助手").tools(List.of(mock(ToolCallback.class))).build();

        ToolCallingChatOptions options = (ToolCallingChatOptions) service.chatOptions().orElseThrow();
        // 不要求时压根不碰 toolContext，保持框架给的原样（这里就是 null）
        assertThat(options.getToolContext()).isNull();
    }

    /**
     * 服务端搜索许可（只有 chat 的 summarizer 开）：与首轮强制不同，它对本 agent 的<b>每次</b>调用
     * 都生效——联网补充不限于首轮。经 toolContext 捎带，ResponsesChatModel 建请求体时读回。
     */
    @Test
    void webSearch开关_许可落进chatOptions的toolContext() {
        when(primary.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());

        ResilientChatService service = ResilientChatService.builder()
                .model(primary).systemPrompt("你是助手").tools(List.of(mock(ToolCallback.class)))
                .webSearch(true).build();

        ToolCallingChatOptions options = (ToolCallingChatOptions) service.chatOptions().orElseThrow();
        assertThat(options.getToolContext().get(SseChatModel.WEB_SEARCH_KEY)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void webSearch开关_无function工具的agent也捎得上() {
        // 没挂工具时 chatOptions 本是 null；搜索许可不许因此静默丢
        when(primary.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());

        ResilientChatService service = ResilientChatService.builder()
                .model(primary).systemPrompt("你是汇总者").webSearch(true).build();

        ToolCallingChatOptions options = (ToolCallingChatOptions) service.chatOptions().orElseThrow();
        assertThat(options.getToolContext().get(SseChatModel.WEB_SEARCH_KEY)).isEqualTo(Boolean.TRUE);
    }

    /**
     * SDK 重试盲区的唯一豁免：响应体读到一半被掐（OpenAIInvalidDataException，如 HTTP/2 stream reset）
     * SDK 的 maxRetries 不管这类失败——这里单次重试救整轮唤醒，不与 SDK 重试叠乘。
     */
    @Test
    void 响应读取中断单次重试() {
        when(primary.call(any(Prompt.class)))
                .thenThrow(new com.openai.errors.OpenAIInvalidDataException("Error reading response",
                        new java.io.IOException("stream was reset: CANCEL")))
                .thenReturn(responseOf("重试成功"));

        ChatResponse result = service().execute(ASK);

        verify(primary, times(2)).call(any(Prompt.class));
        assertThat(result.getResult().getOutput().getText()).isEqualTo("重试成功");
    }

    /**
     * 搜索被拒是配置类失败（老模型 / 中转站不认服务端搜索工具，搜索与 function 工具不能同请求）：
     * 去掉许可重发一次，这一轮不搜；工具照挂，降级只摘搜索许可。
     */
    @Test
    void 搜索被拒时去掉许可重发一次() {
        when(primary.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        List<Prompt> prompts = new ArrayList<>();
        when(primary.stream(any(Prompt.class))).thenAnswer(inv -> {
            prompts.add(inv.getArgument(0));
            return prompts.size() == 1
                    ? Flux.error(new NonTransientAiException(
                            "Gemini API HTTP 400: Multiple tools are supported only when they are all search tools."))
                    : Flux.just(responseOf("不搜也答"));
        });
        ResilientChatService service = ResilientChatService.builder()
                .model(primary).systemPrompt("你是助手").tools(List.of(mock(ToolCallback.class)))
                .webSearch(true).build();

        List<ChatResponse> out = service.streamingExecute(ASK, null).collectList().block();

        assertThat(out).hasSize(1);
        assertThat(prompts).hasSize(2);
        ToolCallingChatOptions first = (ToolCallingChatOptions) prompts.get(0).getOptions();
        ToolCallingChatOptions second = (ToolCallingChatOptions) prompts.get(1).getOptions();
        assertThat(first.getToolContext()).containsEntry(SseChatModel.WEB_SEARCH_KEY, true);
        assertThat(second.getToolContext()).containsEntry(SseChatModel.WEB_SEARCH_KEY, false);
        assertThat(second.getToolCallbacks()).hasSize(1);
    }

    /** 没捎许可的失败退无可退，报错原样透传，不会白发第二次 */
    @Test
    void 没捎许可时搜索类报错不降级() {
        when(primary.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.error(new NonTransientAiException("400 search")));
        ResilientChatService service = ResilientChatService.builder()
                .model(primary).systemPrompt("你是助手").tools(List.of(mock(ToolCallback.class))).build();

        assertThatThrownBy(() -> service.streamingExecute(ASK, null).collectList().block())
                .isInstanceOf(NonTransientAiException.class);
        verify(primary, times(1)).stream(any(Prompt.class));
    }

    // ========== 中断 ==========

    @Test
    void 信号到达时掐断在途流并取消上游() {
        AtomicBoolean upstreamCancelled = new AtomicBoolean();
        when(primary.stream(any(Prompt.class))).thenReturn(
                Flux.<ChatResponse>never().doOnCancel(() -> upstreamCancelled.set(true)));
        CompletableFuture<Void> signal = new CompletableFuture<>();
        AtomicBoolean completed = new AtomicBoolean();

        service().streamingExecute(ASK, signal).subscribe(r -> { }, e -> { }, () -> completed.set(true));
        assertThat(upstreamCancelled).isFalse();

        signal.complete(null);

        assertThat(upstreamCancelled).isTrue();   // 取消传到了模型层：自研协议断连、openai 协议关 SDK 流
        assertThat(completed).isTrue();           // 下游看到的是正常结束，不是异常
    }

    /** Mono.fromFuture 会在流结束时反向 cancel 这个 future，专家等待期的 anyOf 就被误唤醒了 */
    @Test
    void 流正常结束不会反向取消信号() {
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.just(responseOf("答完了")));
        CompletableFuture<Void> signal = new CompletableFuture<>();

        List<ChatResponse> out = service().streamingExecute(ASK, signal).collectList().block();

        assertThat(out).hasSize(1);
        assertThat(signal.isDone()).isFalse();
        assertThat(signal.isCancelled()).isFalse();
    }
}
