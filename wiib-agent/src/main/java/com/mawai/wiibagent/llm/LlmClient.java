package com.mawai.wiibagent.llm;

import com.openai.errors.OpenAIInvalidDataException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 调模型的客户端：模型、系统提示、工具表绑在一起，每次 execute / streamingExecute 是一次模型调用，
 * 负责捎系统提示、挂工具与 options、搜索许可、退避重试。自己不循环、不执行工具：套进 {@link ReactLoop} 才是 agent，
 * behavior / replay coach / 指令生成器的写稿人这类单次调用直接调。
 * <p>
 * 韧性分层（两条路径职责不同，别再往回加）：
 * <ul>
 *   <li><b>阻塞 execute</b>：不重试。重试归模型层——SseChatModel 自带退避、
 *       OpenAI 走 SDK 的 maxRetries；这里再来一轮就是 3×3=9 次，纯放大尾延迟。
 *       唯一豁免：{@link OpenAIInvalidDataException}（响应体读到一半被掐，HTTP/2 stream reset 等）
 *       是 SDK 重试的盲区——maxRetries 只管请求层（连接失败/429/5xx），读响应失败它不管，
 *       这一类单次重试，不与 SDK 叠乘（真跑一晚实测：一次 reset 废掉整轮唤醒还计入连败）</li>
 *   <li><b>流式 streamingExecute</b>：重试在这一层。模型层的流式路径不做重试，
 *       错误发生在订阅期只能在流水线上处理。冷流重订阅=重新发起请求；仅在尚未向下游吐出
 *       任何帧时重试（吐过帧再重订阅会让下游聚合器拼出重复文本），NonTransient（4xx 配置类
 *       错误）不重试；耗尽则错误透传，交上层 SSE error，用户重发</li>
 * </ul>
 * 上游拒收服务端搜索工具时原样上抛，同一会话里的工具列表保持不变。
 * 端点保存时已经探测过支不支持（{@link LlmEndpointService}），运行时的提示见 {@link LlmErrorMessages}。
 * 工具一律不传 tool_choice（auto），"先调工具再答"靠各 agent 的系统提示词；为什么不强制见 {@link ToolOptions} 类头。
 * <b>中断</b>：{@link #streamingExecute} 的 cancel 完成时掐断整条流水线，取消传到模型层。
 * <p>
 * 不做兜底模型：BYOK 只有用户自己那一个端点，切"同端点另一个模型"没意义（端点挂了两个一起挂）。
 */
@Slf4j
public class LlmClient {

    private final ChatModel model;
    private final int maxAttempts;
    private final long initialDelayMs;
    private final long maxDelayMs;
    private final ChatOptions chatOptions;
    private final SystemMessage systemMessage;
    /** 工具表：既挂进 options 给模型看，也是 {@link ReactLoop} 执行工具时的查表处 */
    private final List<ToolCallback> tools;
    private final boolean webSearch;
    private final String origin;

    private LlmClient(Builder builder) {
        this.model = builder.model;
        this.maxAttempts = builder.maxAttempts;
        this.initialDelayMs = builder.initialDelayMs;
        this.maxDelayMs = builder.maxDelayMs;
        this.tools = List.copyOf(builder.tools);
        // 工具挂进 options：没挂工具时保持 null 走模型默认。
        // 从模型自己的 options 派生而非泛型 builder：具体类型必须跟着模型走，理由见 ToolOptions 类头
        ChatOptions base = tools.isEmpty()
                || !(model.getOptions() instanceof ToolCallingChatOptions)
                ? null
                : ToolOptions.withTools(model, tools);
        this.chatOptions = builder.webSearch ? withWebSearch(base, model) : base;
        this.systemMessage = SystemMessage.builder().text(builder.systemPrompt).build();
        this.webSearch = builder.webSearch;
        this.origin = builder.origin;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 服务端搜索许可盖进 options 的 toolContext（{@link SseChatModel#WEB_SEARCH_KEY}），对这个 client 的每次调用都生效。
     * 没挂 function 工具时（base=null）也要从模型 options 派生一份来捎：许可不许静默丢。
     */
    private static ChatOptions withWebSearch(ChatOptions base, ChatModel model) {
        ChatOptions source = base != null ? base : model.getOptions();
        if (!(source instanceof ToolCallingChatOptions tool)) {
            return base;    // openai 协议之外的裸 options：捎不了也不该在这炸，端点本就搜不了
        }
        Map<String, Object> context = tool.getToolContext() == null
                ? new HashMap<>() : new HashMap<>(tool.getToolContext());
        context.put(SseChatModel.WEB_SEARCH_KEY, true);
        return tool.mutate().toolContext(context).build();
    }

    /** 挂的工具，{@link ReactLoop} 按 tool_call 的名字在这份表里找 callback */
    public List<ToolCallback> tools() {
        return tools;
    }

    public Optional<ChatOptions> chatOptions() {
        return Optional.ofNullable(chatOptions);
    }

    /**
     * 每次请求固定带的那段前缀（系统提示词、工具定义、搜索许可）加上背后端点（{@link Builder#origin}）的指纹。
     * Anthropic 的思考块和这段前缀绑在一起，Responses 的加密推理和组织绑在一起，变了历史里以前的原始内容就对不上了
     */
    public String fingerprint() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((prefix() + '\0' + webSearch + '\0' + origin).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 没有 SHA-256", e);
        }
    }

    /** 按字数估这份 messages 发出去有多少 token：系统提示词和工具定义也算上 */
    public long estimateTokens(List<Message> messages) {
        return ContextUsage.estimate(prefix()) + ContextUsage.estimate(messages);
    }

    /** 每次请求固定带的文本：系统提示词 + 每个工具的名字、描述、参数结构 */
    private String prefix() {
        StringBuilder prefix = new StringBuilder(systemMessage.getText());
        for (ToolCallback tool : tools) {
            ToolDefinition def = tool.getToolDefinition();
            prefix.append('\0').append(def.name()).append('\0').append(def.description())
                    .append('\0').append(def.inputSchema());
        }
        return prefix.toString();
    }

    /** @param cancel 可空。非空且完成时掐断整条流水线 */
    public Flux<ChatResponse> streamingExecute(List<Message> messages, CompletableFuture<Void> cancel) {
        AtomicBoolean emitted = new AtomicBoolean(false);
        Flux<ChatResponse> stream = model.stream(promptOf(model, withSystem(messages), chatOptions))
                .doOnNext(_ -> emitted.set(true))
                .retryWhen(Retry.backoff(maxAttempts - 1, Duration.ofMillis(initialDelayMs))
                        .maxBackoff(Duration.ofMillis(maxDelayMs))
                        .filter(e -> !emitted.get() && !(e instanceof NonTransientAiException))
                        .doBeforeRetry(signal -> log.warn("模型流式调用失败，退避重试 {}/{}: {}",
                                signal.totalRetries() + 2, maxAttempts, String.valueOf(signal.failure())))
                        // 耗尽时抛原始异常而非 RetryExhausted 包装，让上层归类拿到真实原因
                        .onRetryExhaustedThrow((_, signal) -> signal.failure()));
        if (cancel == null) {
            return stream;
        } else {
            // 用户中断：整条流水线（含重试）在这儿被掐断，取消向上游传到 WebClient / SDK 流
            // （Spring AI 2.0.1 起 SDK 流随 dispose 关闭）。suppressCancel=true 必须给：缺省会在流正常结束时反向 cancel 这个 future，循环的中断检查点会把一次正常收尾当成用户中断
            Mono<Void> other = Mono.fromFuture(cancel, true); // 包装成Mono
            return stream.takeUntilOther(other); // 如果other complete就直接cancel掉stream 见内部的onComplete和cancelMainAndComplete
        }
    }

    /**
     * 阻塞路径不重试——重试是模型层的职责（SseChatModel 自带退避、
     * OpenAI 走 SDK 的 maxRetries）。这里再来一轮会叠乘成 3×3=9 次，纯粹放大尾延迟。
     * 流式路径相反：模型层不重试，重试全在上面的 streamingExecute 里。
     * 唯一豁免：读响应失败是 SDK 重试盲区（类头注释），就地再试一次，其余异常原样抛。
     */
    public ChatResponse execute(List<Message> messages) {
        Prompt prompt = promptOf(model, withSystem(messages), chatOptions);
        try {
            return model.call(prompt);
        } catch (OpenAIInvalidDataException e) {
            log.warn("响应读取中断（SDK 不重试此类失败），单次重试: {}", e.toString());
            return model.call(prompt);
        }
    }

    private List<Message> withSystem(List<Message> messages) {
        List<Message> withSystem = new ArrayList<>(messages.size() + 1);
        withSystem.add(systemMessage);
        withSystem.addAll(messages);
        return withSystem;
    }

    /** options 为空（没挂工具）时走该模型自己的默认 */
    private static Prompt promptOf(ChatModel model, List<Message> messages, ChatOptions options) {
        return Prompt.builder().messages(messages)
                .chatOptions(options != null ? options : model.getOptions())
                .build();
    }

    public static class Builder {

        private ChatModel model;
        private String systemPrompt;
        private List<ToolCallback> tools = List.of();
        private int maxAttempts = 3;
        private long initialDelayMs = 500;
        private long maxDelayMs = 4000;
        private boolean webSearch;
        private String origin;

        /** 背后是哪条端点（协议、地址、模型、key 密文拼起来），进 {@link LlmClient#fingerprint}；不需要的 client 不给 */
        public Builder origin(String origin) {
            this.origin = origin;
            return this;
        }

        /**
         * 授权这个 client 使用服务端联网搜索（每次调用都生效）。只给 chat 的主 agent 与 researcher worker 开——
         * 真正搜不搜还要过端点那道闸（{@link SseChatModel} 的 webSearch 构造参数），双闸门缺一不可。
         */
        public Builder webSearch(boolean webSearch) {
            this.webSearch = webSearch;
            return this;
        }

        public Builder model(ChatModel model) {
            this.model = model;
            return this;
        }

        /** 系统提示，必填非空；每次调用都捎在历史最前面 */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /** 工具表，缺省不挂工具 */
        public Builder tools(List<ToolCallback> tools) {
            this.tools = tools;
            return this;
        }

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

        public LlmClient build() {
            if (systemPrompt == null || systemPrompt.isBlank()) {
                throw new IllegalArgumentException(
                        "systemPrompt 不能为空：系统提示是这类调用的纪律与格式约定，不许静默缺席");
            }
            return new LlmClient(this);
        }
    }
}
