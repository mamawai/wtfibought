package com.mawai.wiibquant.agent.chat;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级贵操作授权闸（P5 HITL）：工具被调时登记 pending → SSE 弹确认卡 → 用户 approve 后
 * 授权带 TTL，agent 重调工具时放行。框架 HumanInTheLoopHook 面向同步 CLI 场景（resume 协议黑盒），
 * web SSE 下用工具闸门实现同等体验；断连恢复由 checkpoint 保证，授权状态本身短 TTL 无需持久化。
 */
@Component
public class ApprovalRegistry {

    /** 授权有效期：确认后 10 分钟内 agent 重调工具放行 */
    private static final long APPROVAL_TTL_MS = 10 * 60_000L;

    public record PendingRequest(String symbol, String reason, long requestedAt) {
    }

    private final Map<String, PendingRequest> pending = new ConcurrentHashMap<>();
    private final Map<String, Long> approvedUntil = new ConcurrentHashMap<>();
    /** 活跃会话槽：ToolContext 拿不到 sessionId 时的兜底（单用户场景精确；多用户并发退化为最后活跃者，P7 可换 header 透传） */
    private final java.util.concurrent.atomic.AtomicReference<String> activeSession =
            new java.util.concurrent.atomic.AtomicReference<>("unknown-session");

    public void markActive(String sessionId) {
        activeSession.set(sessionId);
    }

    public String activeSession() {
        return activeSession.get();
    }

    /** 工具侧：登记待确认请求（同 session 重复登记覆盖）。 */
    public void requestApproval(String sessionId, String symbol, String reason) {
        pending.put(sessionId, new PendingRequest(symbol, reason, System.currentTimeMillis()));
    }

    /** Controller 侧：本轮流结束后取走 pending（有则发 hitl_request 事件）。 */
    public Optional<PendingRequest> drainPending(String sessionId) {
        return Optional.ofNullable(pending.remove(sessionId));
    }

    /** 用户确认：授予带 TTL 的授权。 */
    public void approve(String sessionId) {
        approvedUntil.put(sessionId, System.currentTimeMillis() + APPROVAL_TTL_MS);
    }

    /** 用户拒绝：清掉可能残留的授权。 */
    public void reject(String sessionId) {
        approvedUntil.remove(sessionId);
        pending.remove(sessionId);
    }

    /** 工具侧：检查并消费授权（一次授权只放行一次执行，防止后续误触发）。 */
    public boolean consumeApproval(String sessionId) {
        Long until = approvedUntil.remove(sessionId);
        return until != null && until >= System.currentTimeMillis();
    }
}
