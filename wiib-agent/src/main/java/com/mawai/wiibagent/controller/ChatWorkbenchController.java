package com.mawai.wiibagent.controller;

import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibagent.chat.ChatAgentFactory;
import com.mawai.wiibagent.chat.ChatIntent;
import com.mawai.wiibagent.chat.ChatTurnRewinder;
import com.mawai.wiibagent.chat.ChatTurnStreamer;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.ChatConcurrencyGate;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.run.ChildRun;
import com.mawai.wiibagent.chat.run.ChildRunRegistry;
import com.mawai.wiibagent.chat.run.WorkbenchPrefService;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.chat.store.ChatHistoryService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 研判工作台对话入口：SSE 流式暴露主 agent 的作答过程。
 * 事件协议：session(会话号) / token(答案流) / progress(长工具阶段进度) / search(服务端搜索过程)
 * / tool_calls(模型这一批要调的工具，带 runId 的是某个子运行里的) / child_start·child_progress·child_done(后台子运行) / hitl_request(贵操作确认卡)
 * / form_request(模型请求弹一张表单卡，执行权归用户点击) / behavior_report / done(带 pending：子运行报告到了还没答，前端空闲时经 /wake 发起唤醒轮) / error。
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
    private final ChildRunRegistry childRuns;
    private final WorkbenchPrefService prefService;
    private final ChatConcurrencyGate concurrencyGate;
    /** 会话归属与确认失效的提示跟界面语言 */
    private final MessageCatalog messages;
    private final PromptCatalog prompts;
    /** chat 是实时请求：语言走 @CurrentUserId → user.lang，与 trader 同一条路 */
    private final UserLangResolver userLangResolver;
    /** 一轮对话跑在它上面（虚拟线程，见 {@code AgentExecutorConfig}）；名额泄漏那条钉子传一个已关的进来制造提交失败 */
    private final ExecutorService streamExecutor;

    /** 单条用户消息字符上限；文案见 error.chatMessageTooLong，改这里要一起改 */
    static final int MAX_MESSAGE_CHARS = 10_000;

    @Data
    public static class WorkbenchChatRequest {
        private String sessionId; // 空=新会话
        private String message;
        /** 功能按钮直发时带上；用户自己打字为空 */
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
    public static class WakeRequest {
        private String sessionId;
    }

    /**
     * 会话运行状态。
     *
     * @param running  有轮在跑
     * @param pending  子运行报告到了还没答：前端本地空闲时发起 /wake
     * @param children 这个会话派过的后台子运行（结局与内部步骤），按派发先后；前端空闲时轮询它补过程轨，刷新后据它重建在跑的
     */
    public record SessionStatus(boolean running, boolean pending, List<ChildRun.View> children) {
    }

    /** 清空全部会话的结果：deleted=删掉几个，skipped=在跑被跳过几个 */
    public record DeleteAllResult(int deleted, int skipped) {
    }

    @Data
    public static class ApprovalRequest {
        private String sessionId;
        private boolean approved;
        /** 从 hitl_request 事件原样回传，唯一标识"点的是哪张卡" */
        private String requestId;
    }

    @Data
    public static class PrefsRequest {
        private boolean subAgentsEnabled;
        /** 空=保持默认 */
        private Integer subAgentLimit;
    }

    /** 工作台用户偏好：后台子 agent 开关与同时在跑上限；maxLimit 是用户能调到的最大值 */
    public record PrefsView(boolean subAgentsEnabled, int subAgentLimit, int maxLimit) {
    }

    @GetMapping("/prefs")
    @Operation(summary = "我的工作台偏好（后台子 agent 开关与上限）")
    public Result<PrefsView> prefs(@CurrentUserId long userId) {
        WorkbenchPrefService.Prefs prefs = prefService.of(userId);
        return Result.ok(new PrefsView(prefs.subAgentsEnabled(), prefs.subAgentLimit(), prefService.hardLimit()));
    }

    @PutMapping("/prefs")
    @Operation(summary = "保存工作台偏好（上限钳在 1..maxLimit）")
    public Result<PrefsView> savePrefs(@CurrentUserId long userId, @RequestBody PrefsRequest request) {
        WorkbenchPrefService.Prefs prefs = prefService.save(userId, request.isSubAgentsEnabled(), request.getSubAgentLimit());
        return Result.ok(new PrefsView(prefs.subAgentsEnabled(), prefs.subAgentLimit(), prefService.hardLimit()));
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "工作台对话（SSE：作答过程 + token 流式）")
    public SseEmitter chat(@CurrentUserId long userId, @RequestBody WorkbenchChatRequest request, HttpServletResponse response) {
        SseChannel.noProxyBuffering(response);
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        if (request.getMessage().length() > MAX_MESSAGE_CHARS) {
            throw new BizException(ErrorCode.CHAT_MESSAGE_TOO_LONG);
        }
        ChatAgentFactory.Leaves leaves = leavesFor(userId);
        acquireSlot(userId);

        // sessionId 绑定 userId 前缀，防跨用户续聊他人会话
        String sessionId = request.getSessionId() != null && request.getSessionId().startsWith("wb-" + userId + "-")
                ? request.getSessionId()
                : "wb-" + userId + "-" + UUID.randomUUID();

        return streamTurn(userId, sessionId, request.getMessage(), leaves, null, request.getIntent(), false);
    }

    /**
     * 唤醒轮：后台子运行的报告落进了上下文、还没有哪一轮答过，前端在本地空闲时发起这一轮补答。
     * 不欠就拒（2208）：别的标签页先接走了，或后端重启丢了标记。
     */
    @PostMapping(value = "/wake", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "唤醒轮：据已回来的子运行报告补充回答（SSE，事件协议同 /chat）")
    public SseEmitter wake(@CurrentUserId long userId, @RequestBody WakeRequest request, HttpServletResponse response) {
        SseChannel.noProxyBuffering(response);
        String sessionId = request.getSessionId();
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")
                || !childRuns.state(sessionId).pending()) {
            throw new BizException(ErrorCode.CHAT_NOTHING_PENDING);
        }
        ChatAgentFactory.Leaves leaves = leavesFor(userId);
        acquireSlot(userId);
        return streamTurn(userId, sessionId, null, leaves, null, null, true);
    }

    /** 名额：每用户同时一轮。占线直接拒，前端把消息排队、这轮结束自动续发 */
    private void acquireSlot(long userId) {
        ChatConcurrencyGate.Acquire acquired = concurrencyGate.tryAcquire(userId);
        if (acquired != ChatConcurrencyGate.Acquire.OK) {
            throw new BizException(acquired == ChatConcurrencyGate.Acquire.USER_BUSY
                    ? ErrorCode.CHAT_ALREADY_RUNNING : ErrorCode.CHAT_CAPACITY_FULL);
        }
    }

    /**
     * 建主 agent 叶子
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
        acquireSlot(userId);
        ChatTurnRewinder.Rollback rollback;
        try {
            // 名额到手后才回退，理由见 rewind 的 javadoc；回不去的几种原因在 HTTP 上是同一个码
            rollback = rewinder.rewind(sessionId, userId)
                    .orElseThrow(() -> new BizException(ErrorCode.CHAT_REGENERATE_UNAVAILABLE));
        } catch (RuntimeException e) {
            concurrencyGate.release(userId);
            throw e;
        }
        // 被顶掉的那轮派出去的子运行跟着作废：报告回灌到重跑后的上下文里会答非所问
        childRuns.cancelAll(sessionId);
        // 重新生成不带意图：库里存的是提问原文，按钮意图是请求级的、没落库。
        // 行为分析这类提问原文本身就够明确，主 agent 的成文规则接得住
        return streamTurn(userId, sessionId, rollback.question(), leaves, rollback.answerId(), null, false);
    }

    /**
     * 建流并把这一轮丢给执行器。/chat、/regenerate、/wake 共用。
     *
     * @param replacedAnswerId null=普通轮；非空=重新生成轮，它在顶替这条旧答案——提问已在库里不再落一遍
     * @param wake             唤醒轮：message 为 null，用户侧消息是唤醒指令
     */
    private SseEmitter streamTurn(long userId, String sessionId, String message,
                                  ChatAgentFactory.Leaves leaves, Long replacedAnswerId, ChatIntent intent,
                                  boolean wake) {
        // 给足 10 分钟
        SseEmitter emitter = new SseEmitter(600_000L);
        SseChannel channel = new SseChannel(emitter);
        emitter.onCompletion(channel::markClosed);
        emitter.onTimeout(() -> {
            channel.markClosed();
            emitter.complete();
        });
        emitter.onError(_ -> channel.markClosed());

        try {
            streamExecutor.execute(() -> {
                // 名额在这一层 try/finally 归还
                try {
                    turnStreamer.run(channel, userId, sessionId, message, leaves, replacedAnswerId, intent, wake);
                } catch (Throwable e) {
                    log.error("[Workbench] 对话任务异常退出 sessionId={}", sessionId, e);
                    // 那边只兜 Exception，Error 穿到这里时通道还开着：不收口前端要挂到 10 分钟超时
                    if (!channel.isClosed()) {
                        channel.send("error", MAPPER.createObjectNode().put("message", prompts.get(leaves.lang(), "llm.error.fallback")));
                        channel.complete();
                    }
                } finally {
                    concurrencyGate.release(userId);
                }
            });
        } catch (Throwable e) {
            // 任务提交失败 lambda 里那个 finally 不触发，名额只能在这还
            concurrencyGate.release(userId);
            throw e;
        }
        return emitter;
    }

    @PostMapping("/cancel")
    @Operation(summary = "中断在跑的这一轮与它派出的后台子运行（轮跑到下一个检查点收尾，半截答案照落库）")
    public Result<Boolean> cancel(@CurrentUserId long userId, @RequestBody CancelRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.ok(false);
        }
        boolean children = childRuns.state(sessionId).running() > 0;
        childRuns.cancelAll(sessionId);
        // 轮和子运行都没在跑就是按钮点晚了，如实回 false
        return Result.ok(runRegistry.requestCancel(sessionId) || children);
    }

    @GetMapping("/sessions")
    @Operation(summary = "我的历史会话列表（标题=首条提问，按最后活跃倒序）")
    public Result<List<ChatHistoryService.SessionSummary>> sessions(@CurrentUserId long userId) {
        return Result.ok(chatHistoryService.sessions(userId, 50));
    }

    /**
     * 用户占不占线（不分会话）。前端消息被 2203 拒、本地又没有流时轮询它，等那轮跑完再续发——
     * 占线的那轮可能在别的会话或别的标签页，只查本会话的 status 会一直以为早就结束了
     */
    @GetMapping("/busy")
    @Operation(summary = "当前用户是否有一轮对话在跑（不分会话）")
    public Result<Boolean> busy(@CurrentUserId long userId) {
        return Result.ok(concurrencyGate.isBusy(userId));
    }

    @GetMapping("/sessions/{sessionId}/status")
    @Operation(summary = "会话运行状态（切页/刷新回来判断 AI 是否还在后台跑、有没有欠着的唤醒、后台子运行各自的进展）")
    public Result<SessionStatus> sessionStatus(@CurrentUserId long userId, @PathVariable String sessionId) {
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail(messages.get("agent.chat.sessionNotFound"));
        }
        ChildRunRegistry.SessionState children = childRuns.state(sessionId);
        return Result.ok(new SessionStatus(runRegistry.isRunning(sessionId), children.pending(), children.runs()));
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
    @Operation(summary = "删除历史会话（展示记录 + 后端续聊上下文）；在跑的会话拒删")
    public Result<Void> deleteSession(@CurrentUserId long userId, @PathVariable String sessionId) {
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail(messages.get("agent.chat.sessionNotFound"));
        }
        // 与 /status 同一口径：这轮收尾落库还会往会话里写 assistant 行，先删掉它会以无标题空壳重新冒出来
        if (runRegistry.isRunning(sessionId)) {
            return Result.fail(messages.get("agent.chat.sessionRunning"));
        }
        purgeSession(sessionId);
        return Result.ok(null);
    }

    @DeleteMapping("/sessions")
    @Operation(summary = "清空我的全部历史会话；在跑的会话跳过不删")
    public Result<DeleteAllResult> deleteAllSessions(@CurrentUserId long userId) {
        int deleted = 0;
        int skipped = 0;
        for (String sessionId : chatHistoryService.sessionIds(userId)) {
            // 跳过规则与单删一致：这轮收尾落库还会往会话里写 assistant 行
            if (runRegistry.isRunning(sessionId)) {
                skipped++;
                continue;
            }
            purgeSession(sessionId);
            deleted++;
        }
        return Result.ok(new DeleteAllResult(deleted, skipped));
    }

    /** 展示记录与续聊上下文是两套存储，删会话得都清；在跑的子运行停掉，挂着的确认卡/授权一并清 */
    private void purgeSession(String sessionId) {
        childRuns.purge(sessionId);
        chatHistoryService.deleteSession(sessionId);
        contextStore.purge(sessionId);
        approvalRegistry.purgeSession(sessionId);
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
