package com.mawai.wiibquant.agent.llm;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式兼容的模型韧性 interceptor：指数退避重试 + 可选兜底模型，一体替代框架的
 * ModelRetryInterceptor + ModelFallbackInterceptor。
 * <p>
 * 为什么自研：spring-ai-alibaba 1.1.2.0 的这两个 interceptor 都把
 * {@code (Message) modelResponse.getMessage()} 无条件强转，而 AgentLlmNode 流式路径
 * （工作台唯一路径）在 message 里装的是未订阅的 {@code Flux<ChatResponse>}——必炸
 * ClassCastException：retry 判为不可重试直接抛，fallback 把它当"主模型失败"永久降级，
 * 深模型从未生效、token 流全丢。
 * <p>
 * 流式语义（错误发生在订阅期，只能在流水线上处理）：
 * <ul>
 *   <li>重试：冷流重订阅=重新发起请求；仅在尚未向下游吐出任何帧时重试（吐过帧再重订阅
 *       会让下游聚合器拼出重复文本），NonTransient（4xx 配置类错误）不重试</li>
 *   <li>兜底：重试耗尽或不可重试且未吐帧 → 无缝接兜底模型的流（token 流不断）；
 *       已吐帧则错误透传，交上层 SSE error，用户重发</li>
 * </ul>
 */
@Slf4j
public class ResilientModelInterceptor extends ModelInterceptor {

    private final int maxAttempts;
    private final long initialDelayMs;
    private final long maxDelayMs;
    /** 可空：null=纯重试（子 agent），非空=重试耗尽后切兜底（supervisor） */
    private final ChatModel fallbackModel;

    private ResilientModelInterceptor(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.initialDelayMs = builder.initialDelayMs;
        this.maxDelayMs = builder.maxDelayMs;
        this.fallbackModel = builder.fallbackModel;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        ModelResponse response = handler.call(request);
        if (response.getMessage() instanceof Flux<?> flux) {
            @SuppressWarnings("unchecked") // AgentLlmNode 流式路径固定装 Flux<ChatResponse>
            Flux<ChatResponse> stream = (Flux<ChatResponse>) flux;
            return ModelResponse.of(guardStream(stream, request));
        }
        return guardBlocking(response, request);
    }

    private Flux<ChatResponse> guardStream(Flux<ChatResponse> primary, ModelRequest request) {
        AtomicBoolean emitted = new AtomicBoolean(false);
        return primary
                .doOnNext(r -> emitted.set(true))
                .retryWhen(Retry.backoff(maxAttempts - 1, Duration.ofMillis(initialDelayMs))
                        .maxBackoff(Duration.ofMillis(maxDelayMs))
                        .filter(e -> !emitted.get() && !(e instanceof NonTransientAiException))
                        .doBeforeRetry(signal -> log.warn("模型流式调用失败，退避重试 {}/{}: {}",
                                signal.totalRetries() + 2, maxAttempts, String.valueOf(signal.failure())))
                        // 耗尽时抛原始异常而非 RetryExhausted 包装，让下面的兜底拿到真实原因
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .onErrorResume(e -> {
                    if (fallbackModel == null || emitted.get()) {
                        return Flux.error(e);
                    }
                    log.warn("主模型流式调用失败（已重试），切换兜底模型: {}", e.toString());
                    return fallbackModel.stream(fallbackPrompt(request));
                });
    }

    /**
     * 非流式分支（当前工作台全流式走不到，留作契约完整）：AgentLlmNode 阻塞路径不抛异常，
     * 失败被包成 "Exception: ..." 文本消息——按此约定检测并兜底。
     */
    private ModelResponse guardBlocking(ModelResponse response, ModelRequest request) {
        if (fallbackModel == null || !isFailureMessage(response)) {
            return response;
        }
        log.warn("主模型调用失败，切换兜底模型: {}", ((Message) response.getMessage()).getText());
        ChatResponse fb = fallbackModel.call(fallbackPrompt(request));
        if (fb == null || fb.getResult() == null) {
            return response;
        }
        return ModelResponse.of(fb.getResult().getOutput(), fb);
    }

    private boolean isFailureMessage(ModelResponse response) {
        return response.getMessage() instanceof AssistantMessage am
                && am.getText() != null && am.getText().startsWith("Exception:");
    }

    /**
     * 兜底调用的 Prompt：system + 全量对话消息，options 只保留工具语义——
     * model/temperature 等生成参数必须归兜底模型自己的默认（原样透传会把主模型的
     * model 名打到兜底端点上）。
     */
    private Prompt fallbackPrompt(ModelRequest request) {
        List<Message> messages = new ArrayList<>();
        if (request.getSystemMessage() != null) {
            messages.add(request.getSystemMessage());
        }
        messages.addAll(request.getMessages());
        ToolCallingChatOptions source = request.getOptions();
        if (source == null) {
            return new Prompt(messages);
        }
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(source.getToolCallbacks())
                .toolNames(source.getToolNames().toArray(new String[0]))
                .toolContext(source.getToolContext())
                .internalToolExecutionEnabled(source.getInternalToolExecutionEnabled())
                .build();
        return new Prompt(messages, options);
    }

    @Override
    public String getName() {
        return "ResilientModel";
    }

    public static class Builder {

        private int maxAttempts = 3;
        private long initialDelayMs = 500;
        private long maxDelayMs = 4000;
        private ChatModel fallbackModel;

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder initialDelay(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
            return this;
        }

        public Builder maxDelay(long maxDelayMs) {
            this.maxDelayMs = maxDelayMs;
            return this;
        }

        public Builder fallbackModel(ChatModel fallbackModel) {
            this.fallbackModel = fallbackModel;
            return this;
        }

        public ResilientModelInterceptor build() {
            return new ResilientModelInterceptor(this);
        }
    }
}
