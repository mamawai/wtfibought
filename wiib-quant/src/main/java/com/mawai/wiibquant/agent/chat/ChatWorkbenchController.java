package com.mawai.wiibquant.agent.chat;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

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
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(ex -> closed.set(true));

        streamExecutor.submit(() -> run(emitter, closed, userId, sessionId, request.getMessage()));
        return emitter;
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

    private void run(SseEmitter emitter, AtomicBoolean closed, long userId, String sessionId, String message) {
        StringBuilder answer = new StringBuilder();
        String[] lastNode = {""};
        try {
            send(emitter, closed, "session", new JSONObject().fluentPut("sessionId", sessionId));
            // 活跃会话槽：DeepAnalysisToolkit 的 HITL 闸门经此拿 sessionId（ToolContext 桥的兜底）
            approvalRegistry.markActive(sessionId);

            // 跨会话记忆前缀：让 agent 记得用户常看什么、上次聊到哪
            String memory = chatMemoryService.recall(userId);
            String enriched = memory.isEmpty() ? message : memory + "\n用户问题：" + message;

            var graph = chatAgentFactory.chatGraph();
            RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();
            graph.stream(Map.of("messages", List.of(new UserMessage(enriched))), config)
                    .doOnNext(output -> {
                        if (closed.get()) {
                            return;
                        }
                        if (output instanceof StreamingOutput<?> streaming) {
                            String chunk = streaming.message() instanceof AssistantMessage am && !am.hasToolCalls()
                                    ? am.getText() : null;
                            if (chunk != null && !chunk.isEmpty()) {
                                answer.append(chunk);
                                send(emitter, closed, "token", new JSONObject().fluentPut("text", chunk));
                            }
                            return;
                        }
                        // 非流式 NodeOutput=节点边界：agent 切换时发调度事件（工作台的"过程可视化"）
                        String node = output.node();
                        if (node != null && !node.equals(lastNode[0]) && !output.isSTART() && !output.isEND()) {
                            lastNode[0] = node;
                            send(emitter, closed, "agent_start", new JSONObject()
                                    .fluentPut("node", node)
                                    .fluentPut("agent", output.agent()));
                        }
                    })
                    .blockLast();

            // HITL：本轮 agent 触发了贵操作待确认 → 弹确认卡（approve 后前端自动补发继续指令）
            approvalRegistry.drainPending(sessionId).ifPresent(pendingRequest ->
                    send(emitter, closed, "hitl_request", new JSONObject()
                            .fluentPut("sessionId", sessionId)
                            .fluentPut("symbol", pendingRequest.symbol())
                            .fluentPut("reason", pendingRequest.reason())
                            .fluentPut("resumeMessage", "已确认，请继续执行深度研判")));

            if (!closed.get()) {
                send(emitter, closed, "done", new JSONObject()
                        .fluentPut("sessionId", sessionId)
                        .fluentPut("answer", answer.toString()));
                emitter.complete();
                // 跨会话记忆异步写入（规则化提取，失败不影响对话）
                String finalAnswer = answer.toString();
                streamExecutor.submit(() -> chatMemoryService.remember(userId, message, finalAnswer));
            }
        } catch (Exception e) {
            log.error("[Workbench] 对话失败 sessionId={}", sessionId, e);
            if (!closed.get()) {
                send(emitter, closed, "error", new JSONObject()
                        .fluentPut("message", e.getMessage() != null ? e.getMessage() : "研判失败，请重试"));
                emitter.completeWithError(e);
            }
        }
    }

    private void send(SseEmitter emitter, AtomicBoolean closed, String event, JSONObject data) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data.toJSONString()));
        } catch (Exception e) {
            closed.set(true);
        }
    }
}
