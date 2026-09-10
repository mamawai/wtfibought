package com.mawai.wiibagent.chat;

import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibagent.llm.SseChannel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * 研判工作台对话入口（P4）：SSE 流式暴露多 agent 调度全过程。
 * 事件协议：session(会话号) / agent_start(调度切换) / token(LLM流，带 agent+role 区分专家过程/答案)
 * / progress(长工具阶段进度) / form_request(模型请求弹一张表单卡，执行权归用户点击)
 * / done(完整回答；deferred=true 是让位收尾，pending=会话还欠着补答——前端在空闲时经 /deferred 发起补答轮接回) / error。
 * 续聊上下文按 sessionId 存在自建的 {@link ChatContextStore} 表里，带同一 sessionId 再发即续聊；
 * 断连不中止本轮：{@link ChatTurnStreamer} 跑完照样落历史，前端靠 status 接口+历史回放补答案。
 */
@Slf4j
@Tag(name = "研判工作台")
@RestController
@RequestMapping("/api/ai/workbench")
@RequiredArgsConstructor
public class ChatWorkbenchController {

    private final ChatAgentFactory chatAgentFactory;
    private final LlmEndpointService endpointService;
    private final ApprovalRegistry approvalRegistry;
    private final ChatHistoryService chatHistoryService;
    private final ChatContextStore contextStore;
    private final ChatTurnStreamer turnStreamer;
    /** 重新生成前的上下文回退，见 {@link ChatTurnRewinder} */
    private final ChatTurnRewinder rewinder;
    private final WorkbenchRunRegistry runRegistry;
    private final ChatConcurrencyGate concurrencyGate;
    /** 会话归属与确认失效的提示跟界面语言 */
    private final MessageCatalog messages;
    private final ChatYieldCoordinator yieldCoordinator;
    private final PromptCatalog prompts;
    /** chat 是实时请求：语言走 @CurrentUserId → user.lang，与 trader 同一条路 */
    private final UserLangResolver userLangResolver;
    /** 包私有：名额泄漏那条钉子（{@code ChatWorkbenchAdmissionTest}）要关掉它来制造提交失败 */
    final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** 单条用户消息字符上限；文案见 error.chatMessageTooLong，改这里要一起改 */
    static final int MAX_MESSAGE_CHARS = 10_000;

    @Data
    public static class WorkbenchChatRequest {
        private String sessionId; // 空=新会话
        private String message;
        /** 功能按钮直发时带上；用户自己打字为空，走正常派发 */
        private ChatIntent intent;
    }

    @Data
    public static class RegenerateRequest {
        private String sessionId;
    }

    @Data
    public static class CancelRequest {
        private String sessionId;
    }

    @Data
    public static class DeferredRequest {
        private String sessionId;
    }

    /** 会话运行状态：running=有轮在跑；pending=欠着补答，前端在本地空闲、排队消息发完后发起 /deferred */
    public record SessionStatus(boolean running, boolean pending) {
    }

    /** 清空全部会话的结果：deleted=删掉几个，skipped=在跑或欠补答被跳过几个 */
    public record DeleteAllResult(int deleted, int skipped) {
    }

    @Data
    public static class ApprovalRequest {
        private String sessionId;
        private boolean approved;
        /** 从 hitl_request 事件原样回传，唯一标识"点的是哪张卡" */
        private String requestId;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "工作台对话（SSE：agent调度过程+token流式）")
    public SseEmitter chat(@CurrentUserId long userId, @RequestBody WorkbenchChatRequest request, HttpServletResponse response) {
        SseChannel.noProxyBuffering(response);
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        if (request.getMessage().length() > MAX_MESSAGE_CHARS) {
            throw new BizException(ErrorCode.CHAT_MESSAGE_TOO_LONG);
        }
        // leaves 就是一套创建好的子 agent
        ChatAgentFactory.Leaves leaves = leavesFor(userId);
        // 尝试获取对话名额
        ChatConcurrencyGate.Acquire acquired = concurrencyGate.tryAcquire(userId);
        if (acquired == ChatConcurrencyGate.Acquire.USER_BUSY) {
            // 自己的上一轮对话的专家还在跑：正处专家等待期要求让位（用户消息优先，专家结果转入补答队列），等它退位后抢回名额；路由/汇总中不可让位维持占线拒绝，前端回落本地排队
            acquired = awaitYield(userId);
        }
        if (acquired != ChatConcurrencyGate.Acquire.OK) {
            throw new BizException(acquired == ChatConcurrencyGate.Acquire.USER_BUSY
                    ? ErrorCode.CHAT_ALREADY_RUNNING : ErrorCode.CHAT_CAPACITY_FULL);
        }

        // sessionId 绑定 userId 前缀，防跨用户续聊他人会话
        String sessionId = request.getSessionId() != null && request.getSessionId().startsWith("wb-" + userId + "-")
                ? request.getSessionId()
                : "wb-" + userId + "-" + UUID.randomUUID();

        return streamTurn(userId, sessionId, request.getMessage(), leaves, null, request.getIntent(), null);
    }

    /**
     * 补答轮：接回让位时交出去的专家批次，把欠的答案补上。前端在本地没有轮在跑、排队消息也发完之后发起。
     * 与普通轮的差别只有三处（见 {@link ChatTurnStreamer}）：不落 user 行、用户侧消息是补答指令、答案带【补答】标头。
     */
    @PostMapping(value = "/deferred", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "补答轮：接回让位时交出去的专家批次，补上欠的答案（SSE，事件协议同 /chat）")
    public SseEmitter deferred(@CurrentUserId long userId, @RequestBody DeferredRequest request,
                               HttpServletResponse response) {
        SseChannel.noProxyBuffering(response);
        String sessionId = request.getSessionId();
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")
                || !yieldCoordinator.hasPending(sessionId)) {
            throw new BizException(ErrorCode.CHAT_NOTHING_DEFERRED);
        }
        ChatAgentFactory.Leaves leaves = leavesFor(userId);
        // 补答不做让位握手：用户消息永远优先，补答只能等空档，占线就拒、前端下次空闲再来。
        // 反过来的话，另一个标签页看见欠账发起补答，会把这个标签页正在跑的用户轮挤掉
        ChatConcurrencyGate.Acquire acquired = concurrencyGate.tryAcquire(userId);
        if (acquired != ChatConcurrencyGate.Acquire.OK) {
            throw new BizException(acquired == ChatConcurrencyGate.Acquire.USER_BUSY
                    ? ErrorCode.CHAT_ALREADY_RUNNING : ErrorCode.CHAT_CAPACITY_FULL);
        }
        // 名额到手后才出队：两个标签页同时来，只有拿到名额的那个取得走这一单
        ChatYieldCoordinator.DeferredWork work = yieldCoordinator.takeDeferred(sessionId).orElse(null);
        if (work == null) {
            concurrencyGate.release(userId);
            throw new BizException(ErrorCode.CHAT_NOTHING_DEFERRED);
        }
        return streamTurn(userId, sessionId, work.question(), leaves, null, null, work.batch());
    }

    /**
     * 为对应userId创建一套子agent
     */
    private ChatAgentFactory.Leaves leavesFor(long userId) {
        ChatEndpoints eps = endpointService.chatEndpoints(userId);
        if (eps == null) {
            throw new BizException(ErrorCode.LLM_CONFIG_MISSING);
        }
        try {
            return chatAgentFactory.leavesFor(eps, userLangResolver.of(userId));
        } catch (Exception e) {
            log.warn("[Workbench] 建模失败 userId={}", userId, e);
            throw new BizException(ErrorCode.LLM_CONFIG_INVALID);
        }
    }

    @PostMapping(value = "/regenerate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "重新生成会话最后一条回答（SSE，事件协议同 /chat）")
    public SseEmitter regenerate(@CurrentUserId long userId, @RequestBody RegenerateRequest request,
                                 HttpServletResponse response) {
        SseChannel.noProxyBuffering(response);
        String sessionId = request.getSessionId();
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            throw new BizException(ErrorCode.CHAT_REGENERATE_UNAVAILABLE);
        }
        ChatAgentFactory.Leaves leaves = leavesFor(userId);
        // 重新生成不做让位握手：它不是等着要答案的新问题，占线就直接拒，用户等那轮跑完再点
        ChatConcurrencyGate.Acquire acquired = concurrencyGate.tryAcquire(userId);
        if (acquired != ChatConcurrencyGate.Acquire.OK) {
            throw new BizException(acquired == ChatConcurrencyGate.Acquire.USER_BUSY
                    ? ErrorCode.CHAT_ALREADY_RUNNING : ErrorCode.CHAT_CAPACITY_FULL);
        }
        ChatTurnRewinder.Rollback rollback;
        try {
            // 名额到手后才回退，理由见 rewind 的 javadoc；回不去的几种原因在 HTTP 上是同一个码
            rollback = rewinder.rewind(sessionId, userId)
                    .orElseThrow(() -> new BizException(ErrorCode.CHAT_REGENERATE_UNAVAILABLE));
        } catch (RuntimeException e) {
            concurrencyGate.release(userId);
            throw e;
        }
        // 重新生成不带意图：库里存的是提问原文，按钮意图是请求级的、没落库。
        // 行为分析这类提问原文本身就够明确，路由与汇总的成文规则接得住
        return streamTurn(userId, sessionId, rollback.question(), leaves, rollback.answerId(), null, null);
    }

    /**
     * 建流并把这一轮丢给执行器。/chat、/regenerate、/deferred 共用。
     *
     * @param replacedAnswerId null=普通轮；非空=重新生成轮，它在顶替这条旧答案——
     *                         提问已在库里不再落一遍，且这一轮不许被新消息挤走
     *                         （旧答案的位置已经腾出来了，被挤掉就没处放新答案）
     * @param deferred         非空=补答轮，message 是被让位的原问题，这批是替它派的专家
     */
    private SseEmitter streamTurn(long userId, String sessionId, String message,
                                  ChatAgentFactory.Leaves leaves, Long replacedAnswerId, ChatIntent intent,
                                  ChatTurnRunner.ExpertBatch deferred) {
        // 给足 10 分钟
        SseEmitter emitter = new SseEmitter(600_000L);
        SseChannel channel = new SseChannel(emitter);
        emitter.onCompletion(channel::markClosed);
        emitter.onTimeout(() -> {
            channel.markClosed();
            emitter.complete();
        });
        emitter.onError(_ -> channel.markClosed());

        ChatYieldCoordinator.TurnHandle turn = yieldCoordinator.openTurn(userId, replacedAnswerId == null);
        try {
            streamExecutor.execute(() -> {
                // 名额在这一层try/catch/finally归还
                try {
                    turnStreamer.run(channel, userId, sessionId, message, leaves, turn, replacedAnswerId, intent, deferred);
                } catch (Throwable e) {
                    log.error("[Workbench] 对话任务异常退出 sessionId={}", sessionId, e);
                    // 那边只兜 Exception，Error 穿到这里时通道还开着：不收口前端要挂到 10 分钟超时
                    if (!channel.isClosed()) {
                        channel.send("error", new JSONObject().fluentPut("message", prompts.get(leaves.lang(), "llm.error.fallback")));
                        channel.complete();
                    }
                } finally {
                    concurrencyGate.release(userId);
                    yieldCoordinator.closeTurn(turn);  // 必须在还名额之后：turnDone 是让位等待者抢名额的发令枪
                }
            });
        } catch (Throwable e) {
            // 任务提交失败 lambda 里那个 finally 不触发，名额只能在这还
            concurrencyGate.release(userId);
            yieldCoordinator.closeTurn(turn);
            throw e;
        }
        return emitter;
    }

    /** 让位握手：发信号 → 等在跑轮退位（有硬顶）→ 抢名额。任何一步不成都归于"占线"。 */
    private ChatConcurrencyGate.Acquire awaitYield(long userId) {
        CompletableFuture<Void> turnDone = yieldCoordinator.requestYield(userId);
        if (turnDone == null) {
            return ChatConcurrencyGate.Acquire.USER_BUSY;
        }
        try {
            turnDone.get(ChatYieldCoordinator.YIELD_HANDSHAKE_MS, TimeUnit.MILLISECONDS);
            return concurrencyGate.tryAcquire(userId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ChatConcurrencyGate.Acquire.USER_BUSY;
        } catch (ExecutionException | TimeoutException e) {
            return ChatConcurrencyGate.Acquire.USER_BUSY;
        }
    }

    @PostMapping("/cancel")
    @Operation(summary = "中断在跑的这一轮（跑到下一个检查点收尾，半截答案照落库）")
    public Result<Boolean> cancel(@CurrentUserId long userId, @RequestBody CancelRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.ok(false);
        }
        // 按 userId 找在跑的那一轮（闸门保证每人至多一轮）；没在跑就是按钮点晚了，如实回 false
        return Result.ok(yieldCoordinator.requestCancel(userId));
    }

    @GetMapping("/sessions")
    @Operation(summary = "我的历史会话列表（标题=首条提问，按最后活跃倒序）")
    public Result<List<ChatHistoryService.SessionSummary>> sessions(@CurrentUserId long userId) {
        return Result.ok(chatHistoryService.sessions(userId, 50));
    }

    @GetMapping("/sessions/{sessionId}/status")
    @Operation(summary = "会话运行状态（切页/刷新回来判断 AI 是否还在后台跑、是否欠着补答）")
    public Result<SessionStatus> sessionStatus(@CurrentUserId long userId, @PathVariable String sessionId) {
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail(messages.get("agent.chat.sessionNotFound"));
        }
        return Result.ok(new SessionStatus(runRegistry.isRunning(sessionId), yieldCoordinator.hasPending(sessionId)));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    @Operation(summary = "单会话消息记录（点进历史会话回看，续聊仍走 /chat 带同一 sessionId）")
    public Result<List<ChatHistoryService.ChatMessage>> sessionMessages(@CurrentUserId long userId,
                                                                        @PathVariable String sessionId) {
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail(messages.get("agent.chat.sessionNotFound"));
        }
        return Result.ok(chatHistoryService.messages(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @Operation(summary = "删除历史会话（展示记录 + 后端续聊上下文）；在跑或欠补答的会话拒删")
    public Result<Void> deleteSession(@CurrentUserId long userId, @PathVariable String sessionId) {
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail(messages.get("agent.chat.sessionNotFound"));
        }
        // 与 /status 同一口径：这轮收尾/补答落库还会往会话里写 assistant 行，先删掉它会以无标题空壳重新冒出来
        if (runRegistry.isRunning(sessionId) || yieldCoordinator.hasPending(sessionId)) {
            return Result.fail(messages.get("agent.chat.sessionRunning"));
        }
        chatHistoryService.deleteSession(sessionId);
        // 展示记录与续聊上下文是两套存储，删会话得都清；挂着的确认卡/授权一并清
        contextStore.purge(sessionId);
        approvalRegistry.purgeSession(sessionId);
        return Result.ok(null);
    }

    @DeleteMapping("/sessions")
    @Operation(summary = "清空我的全部历史会话；在跑或欠补答的会话跳过不删")
    public Result<DeleteAllResult> deleteAllSessions(@CurrentUserId long userId) {
        int deleted = 0;
        int skipped = 0;
        for (String sessionId : chatHistoryService.sessionIds(userId)) {
            // 跳过规则与单删一致：这轮收尾/补答落库还会往会话里写 assistant 行
            if (runRegistry.isRunning(sessionId) || yieldCoordinator.hasPending(sessionId)) {
                skipped++;
                continue;
            }
            chatHistoryService.deleteSession(sessionId);
            contextStore.purge(sessionId);
            approvalRegistry.purgeSession(sessionId);
            deleted++;
        }
        return Result.ok(new DeleteAllResult(deleted, skipped));
    }

    /** HITL 确认回执：approve 后前端自动补发"请继续执行深度研判"，agent 重调工具时闸门放行。 */
    @PostMapping("/approve")
    @Operation(summary = "贵操作确认（HITL）")
    public Result<Void> approve(@CurrentUserId long userId, @RequestBody ApprovalRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail(messages.get("agent.chat.sessionNotFound"));
        }
        // 标识对不上 = 用户点的是被新请求覆盖掉的旧卡片。
        // 此时若照批，用户看着"深研判 BTC"点的同意会授权给新请求里的别的标的。
        // 用 UUID 不用时间戳：两次登记之间是微秒级，同一毫秒内时间戳比对恒成立、等于没比
        boolean ok = request.isApproved()
                ? approvalRegistry.approve(sessionId, request.getRequestId())
                : approvalRegistry.reject(sessionId, request.getRequestId());
        return ok ? Result.ok(null) : Result.fail(messages.get("agent.chat.approvalExpired"));
    }
}
