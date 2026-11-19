package com.mawai.wiibagent.chat.gate;

import com.mawai.wiibcommon.entity.AiTrader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 对话里选 trader：会话 → 当前 trader；会话 → 挂着的选择卡。
 * <p>
 * 查询工具定不到 trader 时，在本轮里原地等用户在卡上点（{@link #pick}），最多 {@link #WAIT_SECONDS} 秒；
 * 同一批并行的几个查询共用一张卡。没选到（超时、点停止、卡推不出去）就叫停这一轮，
 * 后面走的是用户点停止那条路：这一轮的工具调用和回执都不落库，历史里只留提问和"已中断"。
 * <p>
 * 进程内存级：重启后"当前 trader"丢了，没点名的查询会再弹一次卡。
 */
@Component
@RequiredArgsConstructor
public class TraderPickRegistry {

    /** 选择卡最多等多久 */
    public static final int WAIT_SECONDS = 30;

    /** 卡上的一行：pnlPct 是本局收益率（%） */
    public record Candidate(long id, String name, String status, BigDecimal pnlPct) {
    }

    /** 一张挂着的卡：requestId 是前端回执时对的号；chosen 完成就是收场了，null=没选到 */
    private record Pending(String requestId, CompletableFuture<AiTrader> chosen) {
    }

    private final WorkbenchRunRegistry runRegistry;

    /** 测试注入点：真等多久（毫秒） */
    long waitMs = WAIT_SECONDS * 1000L;

    private final Map<String, Long> current = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /** 本会话当前的 trader，没有给 null */
    public Long current(String sessionId) {
        return current.get(sessionId);
    }

    /** 记成本会话当前的 trader */
    public void remember(String sessionId, long traderId) {
        current.put(sessionId, traderId);
    }

    /**
     * 弹卡等用户选。这个会话已经挂着一张（同一批里别的查询弹的）就跟着等那张，不再推第二张。
     * 选中了给那个 trader，并记成当前 trader；没选到给 null，这时这一轮已经叫停了。
     */
    public AiTrader pick(String sessionId, List<Candidate> candidates) {
        Pending fresh = new Pending(UUID.randomUUID().toString(), new CompletableFuture<>());
        Pending card = pending.putIfAbsent(sessionId, fresh);
        if (card == null) {
            card = fresh;
            open(sessionId, card, candidates);
        }
        AiTrader chosen = card.chosen().join();
        if (chosen == null) {
            runRegistry.requestCancel(sessionId);
        }
        return chosen;
    }

    /** 先挂好几种收场（用户选了、超时、点停止）再推卡，这张卡怎样都会收场；推不出去就当场算没选到 */
    private void open(String sessionId, Pending card, List<Candidate> candidates) {
        CompletableFuture<AiTrader> chosen = card.chosen();
        // 怎么收场都把卡摘掉，下一批要选再弹新的；选中了先记成当前 trader
        chosen.whenComplete((trader, e) -> {
            if (trader != null) {
                current.put(sessionId, trader.getId());
            }
            pending.remove(sessionId, card);
        });
        chosen.completeOnTimeout(null, waitMs, TimeUnit.MILLISECONDS);
        runRegistry.onCancel(sessionId, () -> chosen.complete(null));
        if (!runRegistry.publishTraderPick(sessionId, card.requestId(), WAIT_SECONDS, candidates)) {
            chosen.complete(null);
        }
    }

    /**
     * 用户在卡上点了：requestId 对得上、卡还没收场才算，放行等着的工具。
     * 对不上，或已经超时、点了停止，返回 false
     */
    public boolean complete(String sessionId, String requestId, AiTrader trader) {
        Pending card = pending.get(sessionId);
        return card != null && card.requestId().equals(requestId) && card.chosen().complete(trader);
    }

    /** 删会话：清掉它的当前 trader。挂着的卡只在这一轮跑着时有，而在跑的会话不让删 */
    public void purgeSession(String sessionId) {
        current.remove(sessionId);
    }
}
