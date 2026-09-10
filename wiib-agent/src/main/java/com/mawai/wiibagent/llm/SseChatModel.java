package com.mawai.wiibagent.llm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * 自研 SSE 协议 ChatModel 的公共骨架：Responses / Anthropic Messages / Gemini generateContent 三个子类共用。
 * 这里管的是与框架、与网络的契约，子类只管协议形状（请求路径与头、请求体、SSE 事件→帧、错误文案、usage）。
 * <p>
 * 与上层的契约（{@link ReactLoop} + Spring AI 2.0）：
 * <ul>
 *   <li>只负责"说"：返回带 toolCalls 的 AssistantMessage、接受 ToolResponseMessage 入参。
 *       工具一律由 {@link ReactLoop} 执行——Spring AI 2.0 已从 ChatModel 层移除内部工具执行</li>
 *   <li>流式叶子走 stream()，压缩等走阻塞 call()；阻塞路径也走 SSE 收帧后合并</li>
 *   <li>流式帧由 {@link ReactLoop} 聚合：每帧只发增量文本，工具调用整只发一帧，收尾帧带 finishReason/usage；
 *       它把<b>最后一帧</b> AssistantMessage 的 metadata 当作最终消息的 metadata，子类靠这一点把原始内容块挂回去
 *       （阻塞路径的 {@link #mergeFrames} 同样取最后一帧的）；空文本帧也必须带一个 Generation，否则被它过滤</li>
 * </ul>
 * toolContext 三个键：{@link #TIMEOUT_KEY}、{@link #WEB_SEARCH_KEY}、{@link ToolChoice#CONTEXT_KEY}。
 *
 * @param <S> 单次订阅的累计状态，子类按需扩展 {@link StreamState}
 */
public abstract class SseChatModel<S extends SseChatModel.StreamState> implements ChatModel {

    /** 阻塞调用超时；openai 协议路（SDK）引用同一常量对齐——思考模型长回答，官方默认 60s 不够 */
    public static final Duration CALL_TIMEOUT = Duration.ofMinutes(10);
    /** SSE 相邻事件最大间隔：防半开连接把消费方永久挂死。5 分钟是给高档长思考的静默期留余量——
     *  多数服务端思考期间也会发 reasoning 事件/keepalive 注释行，都算心跳 */
    private static final Duration STREAM_IDLE_TIMEOUT = Duration.ofMinutes(5);
    /** SSE 首事件超时：首帧不涉及思考静默，60s 已是极宽松的界。只防"上游完全无响应"——
     *  带 keepalive 心跳的挂死字节级超时拦不住，那种病由 doCall 的整体超时兜 */
    private static final Duration FIRST_EVENT_TIMEOUT = Duration.ofSeconds(60);

    /** toolContext 键：本次调用整体超时（Duration）。路由这类轻调用传短值快速失败进重试，缺省 {@link #CALL_TIMEOUT} */
    public static final String TIMEOUT_KEY = "wiib_call_timeout";

    /**
     * toolContext 键：本次调用允许服务端搜索（Boolean.TRUE 时生效）。
     * 与端点配置构成双闸门——{@code webSearch}（端点声明支持）∧ 本键（这个 agent 被授权搜）
     * 同时成立才往 tools 里加该协议的服务端搜索工具。搜索是 opt-in 语义：不声明上游就不搜，
     * 只有 chat 的 summarizer 捎这个键（ResilientChatService.Builder#webSearch），
     * 专家与 trader 链路的数据源必须可控，拿不到搜索。
     */
    public static final String WEB_SEARCH_KEY = "wiib_web_search";

    /** 瞬时错误重试参数，与 ResilientChatService 对齐 */
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 500;
    private static final long MAX_BACKOFF_MS = 4000;

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** 日志与报错文案里的协议名，如 Responses */
    protected final String tag;
    protected final String model;
    protected final Double temperature;
    /** 思考档位，用户自填；null=不传走模型默认。各协议自己决定落到哪个字段 */
    protected final String reasoningEffort;
    protected final ToolCallingManager toolCallingManager;
    /** 端点声明支持服务端联网搜索（双闸门的端点那半） */
    protected final boolean webSearch;
    private final WebClient webClient;

    /**
     * @param baseUrl 已按该协议的路径约定剥过版本后缀（见 {@link OpenAiBaseUrl#strip}）
     * @param headers 鉴权等固定请求头
     */
    protected SseChatModel(String tag, String baseUrl, Map<String, String> headers, String model, Double temperature,
                           String reasoningEffort, ToolCallingManager toolCallingManager, boolean webSearch) {
        this.tag = tag;
        this.model = model;
        this.temperature = temperature;
        this.reasoningEffort = reasoningEffort;
        this.toolCallingManager = toolCallingManager;
        this.webSearch = webSearch;
        // 深研判单次回包可达数百KB，默认256KB codec上限不够
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024));
        headers.forEach(builder::defaultHeader);
        this.webClient = builder.build();
    }

    // ========== 子类实现的协议形状 ==========

    protected abstract S newState();

    /** 请求路径（相对 baseUrl），可含查询串 */
    protected abstract String requestUri(Prompt prompt);

    protected abstract JSONObject requestBody(Prompt prompt);

    /** 一个已解析的 SSE 事件 → 零到多帧；收尾事件用 {@link #finalFrame} 发带 finishReason/usage 的帧 */
    protected abstract Flux<ChatResponse> toFrames(JSONObject event, S state);

    // ========== 框架契约 ==========

    /**
     * Spring AI 2.0 的契约方法是 getOptions()，getDefaultOptions() 已退化为它的转发别名。
     * 覆写成旧名会命中接口默认实现（返回普通 ChatOptions 而非 ToolCallingChatOptions），
     * ResilientChatService 构造时 instanceof 恒假 → 专家/汇总的工具全程挂不上（真跑实证过）。
     */
    @Override
    public @NonNull ChatOptions getOptions() {
        ToolCallingChatOptions.Builder<?> builder = ToolCallingChatOptions.builder().model(model);
        if (temperature != null) {
            builder.temperature(temperature);
        }
        return builder.build();
    }

    @Override
    public @NonNull ChatResponse call(@NonNull Prompt prompt) {
        return callWithRetry(prompt);
    }

    @Override
    public @NonNull Flux<ChatResponse> stream(@NonNull Prompt prompt) {
        // 纯透传，帧到即发——工具执行归图，本层不攒帧；流式路径的重试在 ResilientChatService
        return streamOnce(prompt);
    }

    /**
     * 给单次调用捎整体超时，按协议落点：openai 协议落 {@code OpenAiChatOptions.timeout}
     * （Spring AI 2.0.1 起逐请求传给 SDK，盖过 client 级超时），自研协议经 toolContext 捎、{@link #doCall} 读回。
     */
    public static ChatOptions withCallTimeout(ChatOptions options, Duration timeout) {
        // OpenAiChatOptions 也实现了 ToolCallingChatOptions，必须先判它
        if (options instanceof OpenAiChatOptions openAi) {
            return openAi.mutate().timeout(timeout).build();
        }
        if (options instanceof ToolCallingChatOptions tool) {
            Map<String, Object> context = tool.getToolContext() == null
                    ? new HashMap<>() : new HashMap<>(tool.getToolContext());
            context.put(TIMEOUT_KEY, timeout);
            return tool.mutate().toolContext(context).build();
        }
        return options;
    }

    // ========== 阻塞路径 ==========

    /** 瞬时错误退避重试：只重试 {@link TransientAiException}(429/5xx/断流/挂死)，配置类错误重试也没用 */
    private ChatResponse callWithRetry(Prompt prompt) {
        TransientAiException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return doCall(prompt);
            } catch (TransientAiException e) {
                last = e;
                log.warn("[{}] {} 第 {}/{} 次调用瞬时失败：{}", tag, model, attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleepBackoff(attempt);
                }
            }
        }
        throw last;
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(Math.min(INITIAL_BACKOFF_MS << (attempt - 1), MAX_BACKOFF_MS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重试退避等待被中断", e);
        }
    }

    /**
     * 阻塞路径也走 SSE，收帧后合并：
     * ① 非流式下网关读完上游才回包，"上游挂死"与"长思考"在字节层不可区分；SSE 有首帧和帧间隔，挂死能判定并进重试。
     * ② 与 stream() 共用 streamOnce 一条协议解析路，各类渠道的事件怪癖只在子类 toFrames 一处处理。
     */
    private ChatResponse doCall(Prompt prompt) {
        Duration timeout = callTimeout(prompt);
        List<ChatResponse> frames;
        try {
            frames = streamOnce(prompt).collectList().block(timeout);
        } catch (IllegalStateException e) {
            // block 到点＝上游挂死的最终判据：keepalive 心跳会骗过字节级超时，只有这道闸拦得住。
            // 判瞬时进重试——实测掐线换连接后 3s 内即成功
            throw new TransientAiException(tag + " 调用 " + timeout.toSeconds() + "s 未完成，判定上游挂死", e);
        }
        return mergeFrames(frames);
    }

    private static Duration callTimeout(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions tool && tool.getToolContext() != null
                && tool.getToolContext().get(TIMEOUT_KEY) instanceof Duration timeout) {
            return timeout;
        }
        return CALL_TIMEOUT;
    }

    /**
     * 帧合并：拼文本、收工具调用，finishReason/usage/消息 metadata 取收尾帧；没等到收尾帧＝上游断流，判瞬时可重试。
     * 消息 metadata 跟着最后一帧走，与 {@link ReactLoop} 的流式合并同口径——子类挂在上面的原始内容块两条路都不丢。
     */
    private ChatResponse mergeFrames(List<ChatResponse> frames) {
        ChatResponse last = frames == null || frames.isEmpty() ? null : frames.getLast();
        String finishReason = last == null ? null : last.getResult().getMetadata().getFinishReason();
        if (finishReason == null || finishReason.isBlank()) {
            // 收尾事件没到流就终了：连接级偶发，换个连接大概率就好，交给 callWithRetry
            throw new TransientAiException(tag + " SSE 断流：未收到收尾事件就结束了");
        }
        StringBuilder text = new StringBuilder();
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (ChatResponse frame : frames) {
            AssistantMessage output = frame.getResult().getOutput();
            if (output.getText() != null) {
                text.append(output.getText());
            }
            toolCalls.addAll(output.getToolCalls());
        }
        // 工具是否真被调用：toolCalls 空=模型自己答的（内置搜索/记忆），没走我们挂上去的工具
        log.info("[{}] {} toolCalls={} 文本{}字", tag, model,
                toolCalls.stream().map(AssistantMessage.ToolCall::name).toList(), text.length());
        AssistantMessage message = AssistantMessage.builder().content(text.toString()).toolCalls(toolCalls)
                .properties(last.getResult().getOutput().getMetadata()).build();
        Generation generation = new Generation(message, ChatGenerationMetadata.builder()
                .finishReason(finishReason).build());
        return new ChatResponse(List.of(generation), last.getMetadata());
    }

    // ========== SSE 骨架 ==========

    /**
     * 单轮 SSE：增量文本逐帧发（供工作台 token 流），工具调用整只发一帧，收尾事件发带 usage 的收尾帧。
     * state 每次订阅新建：ResilientChatService 靠重订阅实现重试，共享 state 会污染收尾帧判断。
     */
    private Flux<ChatResponse> streamOnce(Prompt prompt) {
        JSONObject body = requestBody(prompt);
        String uri = requestUri(prompt);
        return Flux.defer(() -> {
            S state = newState();
            return webClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(body.toString())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp ->
                            resp.bodyToMono(String.class).defaultIfEmpty("")
                                    .flatMap(errBody -> Mono.error(toApiException(resp.statusCode().value(), errBody))))
                    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                    })
                    // 两段式超时：首帧管挂死（零字节永远不来），帧间管半开连接；都判瞬时进重试
                    .timeout(Mono.delay(FIRST_EVENT_TIMEOUT), _ -> Mono.delay(STREAM_IDLE_TIMEOUT))
                    .onErrorMap(TimeoutException.class, _ ->
                            new TransientAiException(tag + " SSE 首帧 " + FIRST_EVENT_TIMEOUT.toSeconds()
                                    + "s 未到或相邻事件间隔超 " + STREAM_IDLE_TIMEOUT.toMinutes() + " 分钟，判定连接挂死"))
                    .concatMap(sse -> parse(sse, state))
                    // 整条流一个可解析事件都没有：上游返回的根本不是我们认的 SSE，得当场说清楚，
                    // 否则退化成静默空回答更难查
                    .switchIfEmpty(Flux.defer(() -> state.sawMalformed
                            ? Flux.error(new NonTransientAiException(tag + " SSE 全程无可解析事件，上游返回格式不兼容"))
                            : Flux.empty()));
        });
    }

    /** SSE 行 → JSON 事件：空行、[DONE]、非 JSON 行（不规范网关会把 event: 行当 data 发）一律跳过 */
    private Flux<ChatResponse> parse(ServerSentEvent<String> sse, S state) {
        String data = sse.data();
        if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
            return Flux.empty();
        }
        JSONObject event;
        try {
            event = JSON.parseObject(data);
        } catch (JSONException e) {
            if (!state.sawMalformed) {
                state.sawMalformed = true;
                log.warn("[{}] SSE 事件非 JSON，已跳过 data={}", tag,
                        data.length() > 200 ? data.substring(0, 200) + "…" : data);
            }
            return Flux.empty();
        }
        return event == null ? Flux.empty() : toFrames(event, state);
    }

    /** 一次订阅的累计状态：判定收尾帧的 finishReason、无增量服务端的兜底、畸形流与陌生事件的观测 */
    protected static class StreamState {
        public boolean sawText;
        public boolean sawToolCall;
        boolean sawMalformed;
        /** 陌生事件类型每种只记一条日志：流上到底在发什么不能是盲区，又不许刷屏 */
        private final Set<String> unknownTypes = new HashSet<>();

        /** 首次见到该陌生类型返回 true（该记日志了） */
        public boolean firstUnknown(String type) {
            return unknownTypes.add(type);
        }
    }

    // ========== 帧与请求的公共帮手 ==========

    protected ChatResponse textFrame(String delta) {
        AssistantMessage message = AssistantMessage.builder().content(delta).build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    protected ChatResponse toolCallFrame(AssistantMessage.ToolCall toolCall) {
        AssistantMessage message = AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    /** 收尾帧：finishReason 按有无工具调用定，metadata 由子类填（id/usage/观测项） */
    protected ChatResponse finalFrame(boolean sawToolCall, ChatResponseMetadata metadata) {
        return finalFrame(sawToolCall, metadata, Map.of());
    }

    /** @param messageProperties 挂在收尾帧消息上的 metadata，会成为最终 AssistantMessage 的 metadata（原始内容块回传靠它） */
    protected ChatResponse finalFrame(boolean sawToolCall, ChatResponseMetadata metadata,
                                      Map<String, Object> messageProperties) {
        AssistantMessage message = AssistantMessage.builder().content("").properties(messageProperties).build();
        Generation generation = new Generation(message, ChatGenerationMetadata.builder()
                .finishReason(sawToolCall ? "TOOL_CALLS" : "STOP").build());
        return new ChatResponse(List.of(generation), metadata);
    }

    /** 搜索过程帧：空文本（带一个 Generation 才不被聚合器过滤）+ metadata 上挂事件 JSON */
    protected ChatResponse searchFrame(SearchEvent event) {
        AssistantMessage message = AssistantMessage.builder().content("").build();
        return new ChatResponse(List.of(new Generation(message)),
                metadata().keyValue(SearchEvent.KEY, event.toJson()).build());
    }

    protected ChatResponseMetadata.Builder metadata() {
        return ChatResponseMetadata.builder().model(model);
    }

    /**
     * 第 i 条 assistant 是否还在工具循环里（后面紧跟工具回执）。
     * 原始内容块（思考块 signature、搜索结果密文）只在这条上回放，已结束的轮次按文本 + 工具调用拼：
     * 换模型后旧 signature 会被拒，密文也只会把请求体越滚越大
     */
    protected static boolean inToolLoop(List<Message> history, int i) {
        return i + 1 < history.size() && history.get(i + 1).getMessageType() == MessageType.TOOL;
    }

    /** 请求级 model 覆盖端点配置（路由等单次调用不改模型，一般就是端点的） */
    protected String effectiveModel(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        return options != null && options.getModel() != null ? options.getModel() : model;
    }

    protected Double effectiveTemperature(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        return options != null && options.getTemperature() != null ? options.getTemperature() : temperature;
    }

    /** 服务端搜索双闸门：端点声明支持 ∧ 本次调用捎了许可 */
    protected boolean searchAllowed(ToolCallingChatOptions options) {
        return webSearch && options.getToolContext() != null
                && Boolean.TRUE.equals(options.getToolContext().get(WEB_SEARCH_KEY));
    }

    /**
     * 请求侧证据日志，与响应侧 toolCalls 日志对称：排"模型不调工具"先看这——
     * tools 空即压根没发工具定义，tool_choice 是强制与否的实据；服务端工具无 name 记 type
     */
    protected void logRequest(String model, Object toolChoice, List<String> tools) {
        log.info("[{}] 请求 model={} tool_choice={} tools={}", tag, model, toolChoice, tools);
    }

    /** 429/5xx 归为瞬时（可重试），其余 4xx 直接失败——配置错误重试也没用 */
    private RuntimeException toApiException(int status, String body) {
        String message = tag + " API HTTP " + status + ": " + (body.length() > 500 ? body.substring(0, 500) : body);
        if (status == 429 || status >= 500) {
            return new TransientAiException(message);
        }
        return new NonTransientAiException(message);
    }
}
