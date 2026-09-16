package com.mawai.wiibagent.trader.wakeup;

import org.springframework.ai.chat.messages.AssistantMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 一次唤醒的过程轨迹：纯数据。运行线程写、订阅者连上时读，方法全同步。
 * 每个变更方法返回要外发的帧（事件名 + data），hub 原样扇出；
 * {@link #replay} 按当前状态合成回放帧；{@link #toJson} 是落库形状。
 * 不认识 SSE、langgraph、Spring 容器。
 */
public final class WakeTrace {

    /** 工具回执预览截断长度 */
    public static final int PREVIEW_CHARS = 2000;
    /** 落库形状版本 */
    private static final int VERSION = 1;

    /** 一帧：SSE 事件名 + data */
    public record Frame(String event, ObjectNode data) {
    }

    /** 模型想调的一个工具；args 是解析后的 JSON 对象，解析不了给原字符串 */
    record ToolCall(String id, String name, Object args) {
        ObjectNode json() {
            ObjectNode o = MAPPER.createObjectNode().put("id", id).put("name", name);
            return args instanceof JsonNode node ? o.set("args", node) : o.put("args", (String) args);
        }
    }

    /** 一条工具回执；status ∈ ok/rejected/error，preview 截过 */
    record ToolResult(String id, String name, String status, String preview) {
        ObjectNode json() {
            return MAPPER.createObjectNode().put("id", id).put("name", name)
                    .put("status", status).put("preview", preview);
        }
    }

    /** 第 n 次模型调用：正文 + 想调的工具 + 回执；endedAt 空=进行中 */
    static final class Call {
        final int n;
        final StringBuilder text = new StringBuilder();
        final List<ToolCall> toolCalls = new ArrayList<>();
        final List<ToolResult> results = new ArrayList<>();
        final long startedAt = System.currentTimeMillis();
        Long endedAt;

        Call(int n) {
            this.n = n;
        }

        /** 没正文也没 toolCalls */
        boolean empty() {
            return text.isEmpty() && toolCalls.isEmpty();
        }

        ArrayNode toolCallsJson() {
            ArrayNode arr = MAPPER.createArrayNode();
            toolCalls.forEach(tc -> arr.add(tc.json()));
            return arr;
        }

        ObjectNode json() {
            ArrayNode rs = MAPPER.createArrayNode();
            results.forEach(r -> rs.add(r.json()));
            return MAPPER.createObjectNode()
                    .put("n", n)
                    .put("text", text.toString())
                    .put("startedAt", startedAt)
                    .put("endedAt", endedAt)
                    .set("toolCalls", toolCallsJson())
                    .set("results", rs);
        }
    }

    final long traderId;
    private final String kind;
    private final long wakeTime;
    private final long startedAt = System.currentTimeMillis();
    private final long budgetSeconds;
    private final BigDecimal equity;
    private final int positions;
    private final int pendingOrders;
    private String promptSystem;
    private String promptInstruction;
    private final List<Call> calls = new ArrayList<>();
    /** 收尾块，非空=本轮已结束 */
    private ObjectNode end;

    public WakeTrace(long traderId, String kind, long wakeTime, long budgetSeconds,
                     BigDecimal equity, int positions, int pendingOrders) {
        this.traderId = traderId;
        this.kind = kind;
        this.wakeTime = wakeTime;
        this.budgetSeconds = budgetSeconds;
        this.equity = equity;
        this.positions = positions;
        this.pendingOrders = pendingOrders;
    }

    // ========== 变更：每个返回要外发的帧 ==========

    /** 唤醒开始帧；回放的第一帧也是它 */
    public synchronized Frame runStart() {
        return new Frame("run_start", MAPPER.createObjectNode()
                .put("kind", kind)
                .put("wakeTime", wakeTime)
                .put("budgetSeconds", budgetSeconds)
                .put("equity", equity)
                .put("positions", positions)
                .put("pendingOrders", pendingOrders)
                .put("startedAt", startedAt));
    }

    public synchronized Frame prompt(String system, String instruction) {
        promptSystem = system;
        promptInstruction = instruction;
        return promptFrame();
    }

    /** 第 n 次模型调用开始（1 起） */
    public synchronized Frame callStart() {
        Call call = new Call(calls.size() + 1);
        calls.add(call);
        return modelStart(call);
    }

    /** 模型文本增量，攒进当前 call */
    public synchronized Frame token(String text) {
        Call call = calls.getLast();
        call.text.append(text);
        return new Frame("token", MAPPER.createObjectNode().put("call", call.n).put("text", text));
    }

    /** 一次模型调用结束：text 整段覆盖攒的增量，toolCalls 记下 */
    public synchronized Frame callEnd(String text, List<AssistantMessage.ToolCall> toolCalls) {
        Call call = calls.getLast();
        call.text.setLength(0);
        if (text != null) {
            call.text.append(text);
        }
        for (AssistantMessage.ToolCall tc : toolCalls) {
            call.toolCalls.add(new ToolCall(tc.id(), tc.name(), parseArgs(tc.arguments())));
        }
        call.endedAt = System.currentTimeMillis();
        return modelEnd(call);
    }

    /** 一条工具回执：status 按前缀判，preview 截 {@link #PREVIEW_CHARS} */
    public synchronized Frame toolResult(String id, String name, String responseData) {
        Call call = calls.getLast();
        String status = responseData.startsWith("REJECTED:") ? "rejected"
                : responseData.startsWith("ERROR:") ? "error" : "ok";
        String preview = responseData.length() > PREVIEW_CHARS ? responseData.substring(0, PREVIEW_CHARS) : responseData;
        ToolResult result = new ToolResult(id, name, status, preview);
        call.results.add(result);
        return toolResultFrame(call, result);
    }

    /** 收尾。末尾没正文也没 toolCalls 的空 call 丢掉（保险丝跳 END 后多起的那一个） */
    public synchronized Frame end(String status, String error, BigDecimal equity, Integer latencyMs,
                                  Integer modelCalls, Long totalTokens) {
        if (!calls.isEmpty() && calls.getLast().empty()) {
            calls.removeLast();
        }
        end = MAPPER.createObjectNode()
                .put("status", status)
                .put("error", error)
                .put("equity", equity)
                .put("latencyMs", latencyMs)
                .put("modelCalls", modelCalls)
                .put("totalTokens", totalTokens);
        return new Frame("run_end", end.deepCopy());
    }

    // ========== 读 ==========

    /**
     * 中途连上的回放：run_start → prompt → 已结束的 call 发 model_end + 它的 tool_result
     * → 进行中的 call 发 model_start + 一帧累计 token。
     */
    public synchronized List<Frame> replay() {
        List<Frame> frames = new ArrayList<>();
        frames.add(runStart());
        if (promptSystem != null) {
            frames.add(promptFrame());
        }
        for (Call call : calls) {
            if (call.endedAt != null) {
                frames.add(modelEnd(call));
                for (ToolResult r : call.results) {
                    frames.add(toolResultFrame(call, r));
                }
            } else {
                frames.add(modelStart(call));
                if (!call.text.isEmpty()) {
                    frames.add(new Frame("token", MAPPER.createObjectNode()
                            .put("call", call.n).put("text", call.text.toString())));
                }
            }
        }
        return frames;
    }

    /** 落库形状（含 prompt，读接口按主人与否剥） */
    public synchronized String toJson() {
        ArrayNode callsJson = MAPPER.createArrayNode();
        calls.forEach(c -> callsJson.add(c.json()));
        ObjectNode out = MAPPER.createObjectNode()
                .put("v", VERSION)
                .put("kind", kind)
                .put("wakeTime", wakeTime)
                .put("startedAt", startedAt)
                .put("budgetSeconds", budgetSeconds)
                .put("equity", equity)
                .put("positions", positions)
                .put("pendingOrders", pendingOrders);
        if (promptSystem != null) {
            out.set("prompt", promptJson());
        }
        out.set("calls", callsJson);
        out.set("end", end);
        return MAPPER.writeValueAsString(out);
    }

    // ========== 帧拼装 ==========

    private Frame promptFrame() {
        return new Frame("prompt", promptJson());
    }

    private ObjectNode promptJson() {
        return MAPPER.createObjectNode().put("system", promptSystem).put("instruction", promptInstruction);
    }

    private static Frame modelStart(Call call) {
        return new Frame("model_start", MAPPER.createObjectNode().put("call", call.n));
    }

    private static Frame modelEnd(Call call) {
        return new Frame("model_end", MAPPER.createObjectNode()
                .put("call", call.n)
                .put("text", call.text.toString())
                .set("toolCalls", call.toolCallsJson()));
    }

    private static Frame toolResultFrame(Call call, ToolResult result) {
        return new Frame("tool_result", result.json().put("call", call.n));
    }

    /** 参数不是合法 JSON 就给原字符串 */
    private static Object parseArgs(String arguments) {
        try {
            return MAPPER.readValue(arguments, ObjectNode.class);
        } catch (Exception e) {
            return arguments;
        }
    }
}
