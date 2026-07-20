package com.mawai.wiibquant.agent.chat;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.annotation.RequireAdmin;
import com.mawai.wiibcommon.util.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 研判工作台对话入口（P4）：SSE 流式暴露 Supervisor 多 agent 调度全过程。
 * 事件协议：session(会话号) / agent_start(调度切换) / token(LLM流) / done(完整回答) / error。
 * threadId=sessionId 走 PostgresSaver——断连后带同一 sessionId 重连即续聊。
 */
@Slf4j
@Tag(name = "研判工作台")
@RestController
@RequestMapping("/api/ai/workbench")
@RequiredArgsConstructor
@RequireAdmin // 暂只对管理员(userId=1)开放：LLM 对话按 token 计费，放开前先观察成本
public class ChatWorkbenchController {

    private final ChatAgentFactory chatAgentFactory;
    private final ApprovalRegistry approvalRegistry;
    private final ChatMemoryService chatMemoryService;
    private final ChatHistoryService chatHistoryService;
    private final BaseCheckpointSaver checkpointSaver;
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();
    /** 心跳专用：只发注释帧(微秒级)，单线程够所有会话用；虚拟线程不支持定时调度故用平台线程 */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    /** 深研判期间 SSE 通道会静默数分钟，nginx 默认 proxy_read_timeout 60s 会掐断——20s 一帧留 3 倍余量 */
    private static final long HEARTBEAT_SECONDS = 20;

    @Data
    public static class WorkbenchChatRequest {
        private String sessionId; // 空=新会话
        private String message;
    }

    @Data
    public static class ApprovalRequest {
        private String sessionId;
        private boolean approved;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "工作台对话（SSE：agent调度过程+token流式）")
    public SseEmitter chat(@CurrentUserId long userId, @RequestBody WorkbenchChatRequest request, HttpServletResponse response) {
        // nginx 反代默认缓冲会把 SSE 憋成一次性输出，显式关掉（免改服务器配置）
        response.setHeader("X-Accel-Buffering", "no");
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        // sessionId 绑定 userId 前缀，防跨用户续聊他人会话
        String sessionId = request.getSessionId() != null && request.getSessionId().startsWith("wb-" + userId + "-")
                ? request.getSessionId()
                : "wb-" + userId + "-" + UUID.randomUUID();

        // 深研判轮次要跑 Bull∥Bear+Judge 共3次深模型调用，180s 会掐断回答流，给足 10 分钟
        SseEmitter emitter = new SseEmitter(600_000L);
        SseChannel channel = new SseChannel(emitter);
        emitter.onCompletion(channel::markClosed);
        emitter.onTimeout(() -> {
            channel.markClosed();
            emitter.complete();
        });
        emitter.onError(ex -> channel.markClosed());

        streamExecutor.submit(() -> run(channel, userId, sessionId, request.getMessage()));
        return emitter;
    }

    @GetMapping("/sessions")
    @Operation(summary = "我的历史会话列表（标题=首条提问，按最后活跃倒序）")
    public Result<List<ChatHistoryService.SessionSummary>> sessions(@CurrentUserId long userId) {
        return Result.ok(chatHistoryService.sessions(userId, 50));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    @Operation(summary = "单会话消息记录（点进历史会话回看，续聊仍走 /chat 带同一 sessionId）")
    public Result<List<ChatHistoryService.ChatMessage>> sessionMessages(@CurrentUserId long userId,
                                                                        @PathVariable String sessionId) {
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail("会话不存在或无权限");
        }
        return Result.ok(chatHistoryService.messages(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @Operation(summary = "删除历史会话（展示记录 + 后端 checkpoint 上下文）")
    public Result<Void> deleteSession(@CurrentUserId long userId, @PathVariable String sessionId) {
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail("会话不存在或无权限");
        }
        chatHistoryService.deleteSession(sessionId);
        // checkpoint 是尽力清：失败只影响存储占用，不影响"列表里已删"的用户观感
        try {
            checkpointSaver.release(RunnableConfig.builder().threadId(sessionId).build());
        } catch (Exception e) {
            log.warn("[Workbench] checkpoint 释放失败 sessionId={} msg={}", sessionId, e.toString());
        }
        return Result.ok(null);
    }

    /** HITL 确认回执：approve 后前端自动补发"请继续执行深度研判"，agent 重调工具时闸门放行。 */
    @PostMapping("/approve")
    @Operation(summary = "贵操作确认（HITL）")
    public Result<Void> approve(@CurrentUserId long userId, @RequestBody ApprovalRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail("会话不存在或无权限");
        }
        if (request.isApproved()) {
            approvalRegistry.approve(sessionId);
        } else {
            approvalRegistry.reject(sessionId);
        }
        return Result.ok(null);
    }

    /**
     * 路由指令过滤：supervisor 派发专家时按协议输出裸 JSON 数组（如 ["news_agent"]），
     * 属内部控制流不该进用户答案。按段缓冲——段首是 '[' 的段先扣住不外发，
     * 段结束时仍是合法字符串数组即整段丢弃，否则原文补发（答案恰好以 [ 开头的场景）。
     * <p>
     * 分段边界靠框架每次 LLM 调用末尾的 *_FINISHED 聚合帧（见 chat 方法内说明），
     * 不能靠节点名切换：所有 ReactAgent 的模型节点都叫 _AGENT_MODEL_，名字永远不变。
     * 按 key(node|agent) 分桶：supervisor 一次派发多个专家时子 agent 并行跑、chunk 交错到达，
     * 共用一个缓冲会互相污染判定状态。
     */
    static final class RoutingFilter {
        private final Consumer<String> sink;
        private final Map<String, Segment> segments = new LinkedHashMap<>();

        RoutingFilter(Consumer<String> sink) {
            this.sink = sink;
        }

        void onChunk(String key, String chunk) {
            segments.computeIfAbsent(key, k -> new Segment()).onChunk(chunk);
        }

        /** 一次 LLM 调用结束：被扣住的段若确是路由数组即丢弃，否则补发。 */
        void endSegment(String key) {
            Segment segment = segments.remove(key);
            if (segment != null) {
                segment.end();
            }
        }

        /** 流结束兜底：异常中断等场景可能没有对应的聚合帧，把在途段全部收尾。 */
        void endAll() {
            segments.values().forEach(Segment::end);
            segments.clear();
        }

        private final class Segment {
            private final StringBuilder buf = new StringBuilder();
            private boolean decided = false;
            private boolean hold = false;

            void onChunk(String chunk) {
                if (decided && !hold) {
                    sink.accept(chunk);
                    return;
                }
                buf.append(chunk);
                if (!decided) {
                    String lead = buf.toString().stripLeading();
                    if (lead.isEmpty()) return;
                    decided = true;
                    hold = lead.charAt(0) == '[';
                    if (!hold) {
                        sink.accept(buf.toString());
                        buf.setLength(0);
                    }
                }
            }

            void end() {
                if (!buf.isEmpty() && !(hold && isRoutingArray(buf.toString()))) {
                    sink.accept(buf.toString());
                }
                buf.setLength(0);
            }
        }

        private static boolean isRoutingArray(String text) {
            String t = text.trim();
            if (!t.startsWith("[") || !t.endsWith("]")) return false;
            try {
                JSONArray arr = JSON.parseArray(t);
                return arr != null && !arr.isEmpty() && arr.stream().allMatch(e -> e instanceof String);
            } catch (Exception e) {
                return false;
            }
        }
    }

    private void run(SseChannel channel, long userId, String sessionId, String message) {
        StringBuilder answer = new StringBuilder();
        RoutingFilter filter = new RoutingFilter(text -> {
            answer.append(text);
            channel.send("token", new JSONObject().fluentPut("text", text));
        });
        String[] lastNode = {""};
        // 深研判这类工具在图内同步阻塞跑，期间通道零字节。心跳全程喂着，中间层才不会当连接死了掐断
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleWithFixedDelay(
                channel::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        try {
            channel.send("session", new JSONObject().fluentPut("sessionId", sessionId));
            // 活跃会话槽：DeepAnalysisToolkit 的 HITL 闸门经此拿 sessionId（ToolContext 桥的兜底）
            approvalRegistry.markActive(sessionId);
            chatHistoryService.append(sessionId, userId, "user", message);

            // 跨会话记忆前缀：让 agent 记得用户常看什么、上次聊到哪
            String memory = chatMemoryService.recall(userId);
            String enriched = memory.isEmpty() ? message : memory + "\n用户问题：" + message;

            var graph = chatAgentFactory.chatGraph();
            RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();
            graph.stream(Map.of("messages", List.of(new UserMessage(enriched))), config)
                    .doOnNext(output -> {
                        if (channel.isClosed()) {
                            return;
                        }
                        if (output instanceof StreamingOutput<?> streaming) {
                            // 框架对每次 LLM 调用先逐帧下发增量(*_STREAMING)，流结束再补 1 帧完整聚合
                            // 文本(*_FINISHED)。聚合帧内容与增量完全重复，绝不能当 token 外发（否则每条
                            // 消息双份），只用作"一次调用结束"的分段边界驱动路由过滤
                            String key = output.node() + "|" + output.agent();
                            OutputType type = streaming.getOutputType();
                            if (type != null && type.name().endsWith("_FINISHED")) {
                                filter.endSegment(key);
                                return;
                            }
                            String chunk = streaming.message() instanceof AssistantMessage am && !am.hasToolCalls()
                                    ? am.getText() : null;
                            if (chunk != null && !chunk.isEmpty()) {
                                // 经路由过滤外发：supervisor 的派发 JSON 数组是内部控制流，不进答案
                                filter.onChunk(key, chunk);
                            }
                            return;
                        }
                        // 非流式 NodeOutput=节点边界：agent 切换时发调度事件（工作台的"过程可视化"）
                        String node = output.node();
                        if (node != null && !node.equals(lastNode[0]) && !output.isSTART() && !output.isEND()) {
                            lastNode[0] = node;
                            channel.send("agent_start", new JSONObject()
                                    .fluentPut("node", node)
                                    .fluentPut("agent", output.agent()));
                        }
                    })
                    .blockLast();
            filter.endAll();   // 收尾：在途段若是被扣住的路由数组即丢弃，否则补发

            // HITL：本轮 agent 触发了贵操作待确认 → 弹确认卡（approve 后前端自动补发继续指令）
            approvalRegistry.drainPending(sessionId).ifPresent(pendingRequest ->
                    channel.send("hitl_request", new JSONObject()
                            .fluentPut("sessionId", sessionId)
                            .fluentPut("symbol", pendingRequest.symbol())
                            .fluentPut("reason", pendingRequest.reason())
                            .fluentPut("resumeMessage", "已确认，请继续执行深度研判")));

            if (!channel.isClosed()) {
                channel.send("done", new JSONObject()
                        .fluentPut("sessionId", sessionId)
                        .fluentPut("answer", answer.toString()));
                channel.complete();
                // 跨会话记忆 + 历史记录异步写入（都是增益不是主链，失败不影响对话）
                String finalAnswer = answer.toString();
                streamExecutor.submit(() -> {
                    chatHistoryService.append(sessionId, userId, "assistant", finalAnswer);
                    chatMemoryService.remember(userId, message, finalAnswer);
                });
            }
        } catch (Exception e) {
            log.error("[Workbench] 对话失败 sessionId={}", sessionId, e);
            if (!channel.isClosed()) {
                channel.send("error", new JSONObject()
                        .fluentPut("message", e.getMessage() != null ? e.getMessage() : "研判失败，请重试"));
                channel.completeWithError(e);
            }
        } finally {
            heartbeat.cancel(false);
        }
    }

    /**
     * SSE 通道：emitter + 关闭标志 + 写锁收在一起。
     * 锁是必须的——SseEmitter.send 非线程安全，心跳线程与主流线程并发写会让帧交错损坏。
     * 锁在实例上而非 Controller 上，各会话互不阻塞。
     */
    private static final class SseChannel {
        private final SseEmitter emitter;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Object writeLock = new Object();

        SseChannel(SseEmitter emitter) {
            this.emitter = emitter;
        }

        boolean isClosed() {
            return closed.get();
        }

        void markClosed() {
            closed.set(true);
        }

        void send(String event, JSONObject data) {
            write(SseEmitter.event().name(event).data(data.toJSONString()));
        }

        /** 心跳：SSE 注释帧，前端 dispatch 取不到 data 直接忽略，纯粹喂饱中间层的空闲计时器。 */
        void heartbeat() {
            write(SseEmitter.event().comment("hb"));
        }

        private void write(SseEmitter.SseEventBuilder builder) {
            if (closed.get()) {
                return;
            }
            synchronized (writeLock) {
                if (closed.get()) {
                    return;
                }
                try {
                    emitter.send(builder);
                } catch (Exception e) {
                    closed.set(true);
                }
            }
        }

        /** complete 与写共用锁：避免心跳正在写时通道被关，Tomcat 抛 IllegalStateException */
        void complete() {
            synchronized (writeLock) {
                closed.set(true);
                emitter.complete();
            }
        }

        void completeWithError(Throwable t) {
            synchronized (writeLock) {
                closed.set(true);
                emitter.completeWithError(t);
            }
        }
    }
}
