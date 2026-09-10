package com.mawai.wiibagent.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * 叶子 agent 的 ReAct 循环：调模型 → 有 tool_call 就执行 → 回执接回历史 → 再调模型，直到模型不再要工具。
 * 一个 agent 一份实例（工具表与各步骤绑死在建造参数上），每次 {@link #run} 从 0 起算模型调用数，
 * "单次运行"这个作用域天然成立，不需要显式清零。
 * <p>
 * 建造参数：{@code chat} 模型调用层（必填）、{@code limiter} 调用保险丝（必填）、
 * {@code streaming} 走流式还是阻塞（缺省阻塞）、{@code gate} 工具执行前的闸门（可空，如审批卡）、
 * {@code summarizer} 长对话压缩（可空）、{@code trace} 工具调用轨迹（可空）。
 * <p>
 * 三条顺序约束，写反了代码照跑什么都不报错：
 * <ul>
 *   <li><b>轨迹在保险丝外层</b>：模型给出 tool_call 就记，被保险丝拦下没执行的那批也记——
 *       复盘要看的是"模型想调什么"</li>
 *   <li><b>保险丝在闸门外层</b>：到上限直接补占位回执收尾，不再问闸门。反过来会出现
 *       "闸门弹了审批卡，但模型已经没配额把这件事告诉用户"</li>
 *   <li><b>收尾提示贴本轮回执</b>：倒数第二次调用（最后一次能执行工具）的回执末尾贴预算已尽提示，
 *       闸门合成的回执同样要贴——下一次模型调用直接收尾，不撞上限硬切</li>
 * </ul>
 * 流式聚合按帧拼：空 results 的帧丢掉，文本顺序拼接，toolCalls 按 id 取并集先到先得保序，
 * 消息 metadata 与 media 取<b>末帧</b>的——自研模型把原始内容块（思考块 signature、搜索结果密文）
 * 挂在收尾帧消息的 metadata 上，回传全靠这一条。
 * <p>
 * 中断信号两个检查点：模型答完之后（不再执行工具）、工具回执入历史之后（不再调模型）。
 * 两处都是正常退出循环，把已有历史交出去，不抛异常。
 * <p>
 * ToolContext 只带会话号（{@link #SESSION_KEY}）：仓库里所有工具就读这一个键。
 */
public final class ReactLoop {

    /** ToolContext 里的会话号键，工具方法体用它读当前会话（见 {@code ToolRunContext}） */
    public static final String SESSION_KEY = "session_id";

    /**
     * 工具执行前的闸门：返回非空就用它顶掉这批工具的真实执行（如审批卡先占位）。
     * 返回的回执要覆盖 reply 里的每一个 tool_call，少一个就是孤儿。
     */
    public interface ToolGate {
        Optional<ToolResponseMessage> intercept(String sessionId, AssistantMessage reply);
    }

    /** 过程回调，两条都可不实现 */
    public interface Listener {
        /** 流式每帧原样给一份，先于聚合；results 为空的帧已过滤，取 {@code getResults().getFirst()} 即可 */
        default void chunk(ChatResponse frame) {
        }

        /** 每条新进历史的消息，含保险丝补的占位回执 */
        default void message(Message message) {
        }
    }

    private static final Listener NONE = new Listener() {
    };

    /** @param messages 收束时的完整历史（含入参那几条） @param modelCalls 本次运行模型被调了几次 */
    public record Result(List<Message> messages, int modelCalls) {
    }

    private final ResilientChatService chat;
    /** 按工具名查 callback，就是 chat 挂给模型的那份表 */
    private final Map<String, ToolCallback> toolsByName = new LinkedHashMap<>();
    private final boolean streaming;
    private final ModelCallLimiter limiter;
    private final ToolGate gate;
    private final ConversationSummarizer summarizer;
    private final ToolCallTraceHook trace;

    private ReactLoop(Builder builder) {
        this.chat = builder.chat;
        chat.tools().forEach(tool -> toolsByName.put(tool.getToolDefinition().name(), tool));
        this.streaming = builder.streaming;
        this.limiter = builder.limiter;
        this.gate = builder.gate;
        this.summarizer = builder.summarizer;
        this.trace = builder.trace;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private ResilientChatService chat;
        private boolean streaming;
        private ModelCallLimiter limiter;
        private ToolGate gate;
        private ConversationSummarizer summarizer;
        private ToolCallTraceHook trace;

        /** 模型调用层，必填 */
        public Builder chat(ResilientChatService chat) {
            this.chat = chat;
            return this;
        }

        /** true=走流式（答案要逐字推给前端），缺省阻塞 */
        public Builder streaming(boolean streaming) {
            this.streaming = streaming;
            return this;
        }

        /** 模型调用保险丝，必填 */
        public Builder limiter(ModelCallLimiter limiter) {
            this.limiter = limiter;
            return this;
        }

        /** 工具执行前的闸门，可空 */
        public Builder gate(ToolGate gate) {
            this.gate = gate;
            return this;
        }

        /** 长对话压缩，可空 */
        public Builder summarizer(ConversationSummarizer summarizer) {
            this.summarizer = summarizer;
            return this;
        }

        /** 工具调用轨迹，可空 */
        public Builder trace(ToolCallTraceHook trace) {
            this.trace = trace;
            return this;
        }

        public ReactLoop build() {
            Objects.requireNonNull(chat, "chat 不能为空：ReactLoop 得有模型调用层才转得起来");
            Objects.requireNonNull(limiter, "limiter 不能为空：没有保险丝的 ReAct 循环会一路烧 token");
            return new ReactLoop(this);
        }
    }

    /**
     * 跑一轮到底。sessionId / cancel / listener 都可空（listener 为空用空实现）。
     *
     * @param input  起始历史，原表不动，返回的是新表
     * @param cancel 中断信号，完成即中断；流式路径还会把取消传到模型层掐断在途流
     */
    public Result run(List<Message> input, String sessionId, CompletableFuture<Void> cancel, Listener listener) {
        List<Message> messages = new ArrayList<>(input);
        Listener sink = listener == null ? NONE : listener;
        int calls = 0;
        while (true) {
            if (summarizer != null) {
                // 压缩是整体替换历史，之后本轮都用压缩后的这份
                messages = summarizer.compress(messages).<List<Message>>map(ArrayList::new).orElse(messages);
            }
            calls++;
            AssistantMessage reply = streaming
                    ? streamAndAggregate(messages, cancel, sink)
                    : Objects.requireNonNull(chat.execute(messages).getResult(), "模型没有返回任何内容").getOutput();
            messages.add(reply);
            sink.message(reply);
            if (!reply.hasToolCalls()) {
                break;
            }
            if (cancelled(cancel)) {
                break;      // 检查点①：模型答完就被叫停，工具不执行了
            }
            if (trace != null) {
                trace.record(reply);    // 轨迹在保险丝外层：被拦下没执行的也要记
            }
            if (calls >= limiter.limit()) {
                ToolResponseMessage placeholder = limiter.placeholders(reply);
                messages.add(placeholder);
                sink.message(placeholder);
                break;
            }
            ToolResponseMessage responses = gate == null ? null : gate.intercept(sessionId, reply).orElse(null);
            if (responses == null) {
                responses = executeTools(reply, sessionId);
            }
            if (calls == limiter.limit() - 1) {
                responses = limiter.withLastCallNotice(responses);
            }
            messages.add(responses);
            sink.message(responses);
            if (cancelled(cancel)) {
                break;      // 检查点②：工具回执已入历史，但不再拿它去调模型
            }
        }
        return new Result(messages, calls);
    }

    private static boolean cancelled(CompletableFuture<Void> cancel) {
        return cancel != null && cancel.isDone();
    }

    /** 逐个顺序执行，结果按 tool_call 一一配对合成一条回执 */
    private ToolResponseMessage executeTools(AssistantMessage reply, String sessionId) {
        ToolContext context = new ToolContext(sessionId == null ? Map.of() : Map.of(SESSION_KEY, sessionId));
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall toolCall : reply.getToolCalls()) {
            ToolCallback callback = toolsByName.get(toolCall.name());
            if (callback == null) {
                throw new IllegalStateException("No tool callback found for name: " + toolCall.name());
            }
            responses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(),
                    callback.call(toolCall.arguments(), context)));
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }

    /**
     * 在调用线程上逐帧消费并聚合。不用 forEachAsync：那样每帧叠一层栈帧，长回答会 StackOverflowError。
     * 每帧先看 cancel：信号掐上游那一刻队列里可能还排着几帧，点了停止就不再上屏也不进历史。
     * try-with-resources：中途抛出或提前退出时取消订阅，上游连接不留着。
     */
    private AssistantMessage streamAndAggregate(List<Message> messages, CompletableFuture<Void> cancel,
                                                Listener listener) {
        AssistantMessage aggregated = null;
        try (Stream<ChatResponse> frames = chat.streamingExecute(messages, cancel).toStream()) {
            Iterator<ChatResponse> iterator = frames.iterator();
            while (!cancelled(cancel) && iterator.hasNext()) {
                ChatResponse frame = iterator.next();
                if (frame.getResults().isEmpty()) {
                    continue;
                }
                listener.chunk(frame);
                aggregated = merge(aggregated, frame.getResults().getFirst().getOutput());
            }
        }
        if (aggregated == null) {
            if (cancelled(cancel)) {
                // 用户刚点停止就把流掐断了，一帧都没来。返空消息，循环在检查点①正常退出
                return AssistantMessage.builder().content("").build();
            }
            throw new IllegalStateException("模型流没有返回任何帧");
        }
        return aggregated;
    }

    /** 文本拼接（帧的 text 可为 null）；metadata 与 media 取当前这条的，末帧的原始内容块靠它回传 */
    private static AssistantMessage merge(AssistantMessage last, AssistantMessage current) {
        if (last == null) {
            return current;
        }
        return AssistantMessage.builder()
                .content(Objects.requireNonNullElse(last.getText(), "") + Objects.requireNonNullElse(current.getText(), ""))
                .properties(current.getMetadata())
                .toolCalls(mergeToolCalls(last.getToolCalls(), current.getToolCalls()))
                .media(current.getMedia())
                .build();
    }

    /** 按 id 取并集，同 id 先到先得；LinkedHashMap 保住到达顺序 */
    private static List<AssistantMessage.ToolCall> mergeToolCalls(List<AssistantMessage.ToolCall> last,
                                                                  List<AssistantMessage.ToolCall> current) {
        if (last.isEmpty()) {
            return current;
        }
        if (current.isEmpty()) {
            return last;
        }
        Map<String, AssistantMessage.ToolCall> merged = new LinkedHashMap<>();
        last.forEach(toolCall -> merged.put(toolCall.id(), toolCall));
        current.forEach(toolCall -> merged.putIfAbsent(toolCall.id(), toolCall));
        return List.copyOf(merged.values());
    }
}
