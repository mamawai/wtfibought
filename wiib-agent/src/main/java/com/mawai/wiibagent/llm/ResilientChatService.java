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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 叶子 {@link ReactLoop} 的模型调用层：捎系统提示、挂工具与 options、首轮强制、搜索许可、退避重试。
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
 * 两条路径共有的一层是 <b>tool_choice 降级</b>（{@link #toolChoiceRejected}）：上游拒收强制时
 * 去掉强制重发。它不是重试——换的是请求本身，原样再发多少次都一样。
 * 流式路径还有一层同款的<b>搜索降级</b>（{@link #searchRejected}）：上游拒收服务端搜索工具时去掉许可重发
 * （只有 summarizer 捎许可且它是流式的，阻塞路径用不上）。
 * <b>中断</b>：{@link #streamingExecute} 的 cancel 完成时掐断整条流水线，取消传到模型层。
 * <p>
 * 不做兜底模型：BYOK 只有用户自己那一个端点，切"同端点另一个模型"没意义（端点挂了两个一起挂）。
 */
@Slf4j
public class ResilientChatService {

    private final ChatModel primaryModel;
    private final int maxAttempts;
    private final long initialDelayMs;
    private final long maxDelayMs;
    private final ChatOptions chatOptions;
    /** 可空。非空=首轮强制用工具（"required" 或具体工具名），逐次调用时经 {@link #optionsFor} 落地 */
    private final String forceFirstToolChoice;
    private final SystemMessage systemMessage;
    /** 本 agent 的工具表：既挂进 options 给模型看，也是 {@link ReactLoop} 执行工具时的查表处 */
    private final List<ToolCallback> tools;

    private ResilientChatService(Builder builder) {
        this.primaryModel = builder.primaryModel;
        this.maxAttempts = builder.maxAttempts;
        this.initialDelayMs = builder.initialDelayMs;
        this.maxDelayMs = builder.maxDelayMs;
        this.forceFirstToolChoice = builder.forceFirstToolChoice;
        this.tools = List.copyOf(builder.tools);
        // 工具挂进 options：没工具的 agent 保持 null 走模型默认。
        // 从模型自己的 options 派生而非泛型 builder：具体类型必须跟着模型走，理由见 ToolChoice 类头
        ChatOptions base = tools.isEmpty()
                || !(primaryModel.getOptions() instanceof ToolCallingChatOptions)
                ? null
                : ToolChoice.withTools(primaryModel, tools);
        this.chatOptions = builder.webSearch ? withWebSearch(base, primaryModel) : base;
        this.systemMessage = SystemMessage.builder().text(builder.systemPrompt).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 服务端搜索许可盖进 options 的 toolContext（{@link SseChatModel#WEB_SEARCH_KEY}）。
     * 与首轮强制不同，它对本 agent 的每次调用都生效——联网补充不限于首轮，落在底稿上而非逐次现算。
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

    /**
     * 本次调用的 options：首轮强制用工具的话，把 tool_choice 按协议落进去（{@link ToolChoice#apply}）。
     * 逐次算而不是建服务时算死：ReactLoop 是循环，只有"最后一条用户消息之后还没有工具回执"那一次才强制，
     * 拿到工具结果后必须放开否则收不了尾。同一个 ChatModel 实例被多个 agent 共用，
     * "这个 agent 必须先拿真实数据"是 agent 自己的属性，所以落在 options 上而不是模型构造参数里。
     */
    private ChatOptions optionsFor(List<Message> messages) {
        if (forceFirstToolChoice == null || chatOptions == null || !ToolChoice.isFirstTurn(messages)) {
            return chatOptions;
        }
        return ToolChoice.apply(chatOptions, forceFirstToolChoice);
    }

    /** 本 agent 挂的工具，{@link ReactLoop} 按 tool_call 的名字在这份表里找 callback */
    public List<ToolCallback> tools() {
        return tools;
    }

    public Optional<ChatOptions> chatOptions() {
        return Optional.ofNullable(chatOptions);
    }

    /** @param cancel 可空。非空且完成时掐断整条流水线 */
    public Flux<ChatResponse> streamingExecute(List<Message> messages, CompletableFuture<Void> cancel) {
        List<Message> withSystem = withSystem(messages);
        ChatOptions used = optionsFor(withSystem);
        AtomicBoolean emitted = new AtomicBoolean(false);
        Flux<ChatResponse> stream = primaryModel.stream(promptOf(primaryModel, withSystem, used))
                .doOnNext(r -> emitted.set(true))
                .retryWhen(Retry.backoff(maxAttempts - 1, Duration.ofMillis(initialDelayMs))
                        .maxBackoff(Duration.ofMillis(maxDelayMs))
                        // 强制被拒是配置类失败，重试多少次都一样，留给下面降级
                        .filter(e -> !emitted.get() && !(e instanceof NonTransientAiException)
                                && !toolChoiceRejected(e, used))
                        .doBeforeRetry(signal -> log.warn("模型流式调用失败，退避重试 {}/{}: {}",
                                signal.totalRetries() + 2, maxAttempts, String.valueOf(signal.failure())))
                        // 耗尽时抛原始异常而非 RetryExhausted 包装，让下面的兜底拿到真实原因
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .onErrorResume(e -> {
                    // 强制被拒发生在请求刚落地、必然还没吐过帧，重订阅不会拼出重复文本
                    if (toolChoiceRejected(e, used)) {
                        log.warn("上游拒收 tool_choice 强制，本次降级为不强制重发: {}", e.toString());
                        return primaryModel.stream(promptOf(primaryModel, withSystem, chatOptions));
                    }
                    // 搜索工具被拒同理：去掉许可重发一次，这一轮就不搜
                    if (searchRejected(e, used)) {
                        log.warn("上游拒收服务端搜索工具，本次降级为不搜重发: {}", e.toString());
                        return primaryModel.stream(promptOf(primaryModel, withSystem, withoutWebSearch(used)));
                    }
                    return Flux.error(e);
                });
        // 用户中断：整条流水线（含重试与降级）在这儿被掐断，取消向上游传到 WebClient / SDK 流
        // （Spring AI 2.0.1 起 SDK 流随 dispose 关闭）。suppressCancel=true 必须给：
        // 缺省会在流正常结束时反向 cancel 这个 future，专家等待期挂在它上面的 anyOf 会被误唤醒
        return cancel == null ? stream : stream.takeUntilOther(Mono.fromFuture(cancel, true));
    }

    /**
     * 上游是否拒了本次声明的服务端搜索工具（老模型不认、中转站不支持、搜索与 function 工具不能同请求…）。
     * 判据：本次 options 捎了搜索许可 ∧ 报错文案提到 search——各家措辞不一（web_search / google_search /
     * "all search tools"），只认 search 一个词，误判的代价只是多发一次请求。撞了才退、不缓存、不探测。
     */
    private static boolean searchRejected(Throwable e, ChatOptions used) {
        return carriesWebSearch(used) && e.getMessage() != null
                && e.getMessage().toLowerCase().contains("search");
    }

    private static boolean carriesWebSearch(ChatOptions options) {
        return options instanceof ToolCallingChatOptions tool && tool.getToolContext() != null
                && Boolean.TRUE.equals(tool.getToolContext().get(SseChatModel.WEB_SEARCH_KEY));
    }

    /** 撤销搜索许可（写成 false：builder 的 toolContext 是合并不是替换，删不掉键），工具与其余上下文照旧 */
    private static ChatOptions withoutWebSearch(ChatOptions options) {
        ToolCallingChatOptions tool = (ToolCallingChatOptions) options;
        return tool.mutate().toolContext(Map.of(SseChatModel.WEB_SEARCH_KEY, false)).build();
    }

    /**
     * 阻塞路径不重试——重试是模型层的职责（SseChatModel 自带退避、
     * OpenAI 走 SDK 的 maxRetries）。这里再来一轮会叠乘成 3×3=9 次，纯粹放大尾延迟。
     * 流式路径相反：模型层不重试，重试全在上面的 streamingExecute 里。
     */
    public ChatResponse execute(List<Message> messages) {
        return callPrimary(withSystem(messages));
    }

    /**
     * 两处就地补救，其余异常原样抛：
     * <ul>
     *   <li>读响应失败：SDK 重试盲区（类头注释的唯一豁免），再试一次</li>
     *   <li>上游拒收 tool_choice 强制：去掉强制重发一次，判据见 {@link #toolChoiceRejected}</li>
     * </ul>
     */
    private ChatResponse callPrimary(List<Message> withSystem) {
        ChatOptions used = optionsFor(withSystem);
        Prompt prompt = promptOf(primaryModel, withSystem, used);
        try {
            return primaryModel.call(prompt);
        } catch (OpenAIInvalidDataException e) {
            log.warn("响应读取中断（SDK 不重试此类失败），单次重试: {}", e.toString());
            return primaryModel.call(prompt);
        } catch (RuntimeException e) {
            if (!toolChoiceRejected(e, used)) {
                throw e;
            }
            log.warn("上游拒收 tool_choice 强制，本次降级为不强制重发: {}", e.toString());
            return primaryModel.call(promptOf(primaryModel, withSystem, chatOptions));
        }
    }

    /**
     * 上游是否拒了本次的 tool_choice 强制。真跑实证：模型开思考档位时上游直接 400
     * （"tool_choice does not support being set to required or object in thinking mode"），
     * 首轮必炸、整轮唤醒作废——比"首轮没强制调工具"糟得多，所以撞了就退回不强制。
     * <p>
     * 两个判据缺一不可：
     * <ul>
     *   <li>{@code used != chatOptions}：这次真加了强制（{@link #optionsFor} 加过料才是新对象），
     *       没加过强制的失败退无可退</li>
     *   <li>报错文案里有 {@code tool_choice}：不按异常类型判——各协议抛的类型不同
     *       （openai 路 BadRequestException / 自研协议 NonTransientAiException），
     *       按类型判会漏。误判的代价只是多发一次请求</li>
     * </ul>
     * 不做能力探测表、不缓存端点支不支持：撞了才退，一次一次算。
     */
    private boolean toolChoiceRejected(Throwable e, ChatOptions used) {
        return used != chatOptions && e.getMessage() != null && e.getMessage().contains(ToolChoice.PARAM);
    }

    private List<Message> withSystem(List<Message> messages) {
        List<Message> withSystem = new ArrayList<>(messages.size() + 1);
        withSystem.add(systemMessage);
        withSystem.addAll(messages);
        return withSystem;
    }

    /** options 为空（无工具的 agent）时走该模型自己的默认 */
    private static Prompt promptOf(ChatModel model, List<Message> messages, ChatOptions options) {
        return Prompt.builder().messages(messages)
                .chatOptions(options != null ? options : model.getOptions())
                .build();
    }

    public static class Builder {

        private ChatModel primaryModel;
        private String systemPrompt;
        private List<ToolCallback> tools = List.of();
        private int maxAttempts = 3;
        private long initialDelayMs = 500;
        private long maxDelayMs = 4000;
        private String forceFirstToolChoice;
        private boolean webSearch;

        /**
         * 首轮强制用工具（"required" 或具体工具名）。给"必须拿真实数据"的专家用：
         * 模型多半自带联网/搜索等内置能力，tool_choice=auto 时会绕开挂上去的工具自己答。
         * 只作用于首轮，拿到工具结果后恢复 auto，否则模型收不了尾。
         */
        public Builder forceFirstToolChoice(String forceFirstToolChoice) {
            this.forceFirstToolChoice = forceFirstToolChoice;
            return this;
        }

        /**
         * 授权本 agent 使用服务端联网搜索（每次调用都生效）。只给 chat 的 summarizer 开——
         * 真正搜不搜还要过端点那道闸（{@link SseChatModel} 的 webSearch 构造参数），双闸门缺一不可。
         */
        public Builder webSearch(boolean webSearch) {
            this.webSearch = webSearch;
            return this;
        }

        public Builder model(ChatModel primaryModel) {
            this.primaryModel = primaryModel;
            return this;
        }

        /** 系统提示，必填非空；每次调用都捎在历史最前面 */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /** 本 agent 的工具表，缺省不挂工具 */
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

        public ResilientChatService build() {
            if (systemPrompt == null || systemPrompt.isBlank()) {
                throw new IllegalArgumentException(
                        "systemPrompt 不能为空：系统提示是每个 agent 的纪律与格式约定，不许静默缺席");
            }
            return new ResilientChatService(this);
        }
    }
}
