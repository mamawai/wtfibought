package com.mawai.wiibquant.agent.chat;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.alibaba.fastjson2.JSONObject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
public class ChatWorkbenchController {

    private final ChatAgentFactory chatAgentFactory;
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Data
    public static class WorkbenchChatRequest {
        private String sessionId; // 空=新会话
        private String message;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "工作台对话（SSE：agent调度过程+token流式）")
    public SseEmitter chat(@RequestBody WorkbenchChatRequest request) {
        StpUtil.checkLogin();
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        long userId = StpUtil.getLoginIdAsLong();
        // sessionId 绑定 userId 前缀，防跨用户续聊他人会话
        String sessionId = request.getSessionId() != null && request.getSessionId().startsWith("wb-" + userId + "-")
                ? request.getSessionId()
                : "wb-" + userId + "-" + UUID.randomUUID();

        SseEmitter emitter = new SseEmitter(180_000L);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(ex -> closed.set(true));

        streamExecutor.submit(() -> run(emitter, closed, sessionId, request.getMessage()));
        return emitter;
    }

    private void run(SseEmitter emitter, AtomicBoolean closed, String sessionId, String message) {
        StringBuilder answer = new StringBuilder();
        String[] lastNode = {""};
        try {
            send(emitter, closed, "session", new JSONObject().fluentPut("sessionId", sessionId));

            var graph = chatAgentFactory.chatGraph();
            RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();
            graph.stream(Map.of("messages", List.of(new UserMessage(message))), config)
                    .doOnNext(output -> {
                        if (closed.get()) {
                            return;
                        }
                        if (output instanceof StreamingOutput<?> streaming) {
                            String chunk = streaming.chunk();
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

            if (!closed.get()) {
                send(emitter, closed, "done", new JSONObject()
                        .fluentPut("sessionId", sessionId)
                        .fluentPut("answer", answer.toString()));
                emitter.complete();
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
