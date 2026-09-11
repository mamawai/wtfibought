package com.mawai.wiibagent.trader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibagent.learning.ReviewRunner;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.trader.wakeup.TraderScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.time.ZoneId;

/**
 * trader 三个动作（留言 / 手动唤醒 / 点播复盘）的唯一实现。
 * <p>
 * 执行入口只有动作面板这一条 REST 路径。对话轨的工具只把表单推给用户看，
 * 碰不到这里——所以模型说不出"我已经唤醒了"，它确实没有那个能力。
 * <p>
 * 返回一律带 message：三个动作里有"做了"、"被拦下"、"没素材所以跳过且没花钱"三种结局，
 * 最后一种是成功的省钱决定，压进 ok/fail 两态会让它在界面上变成一次报错。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraderActionService {

    /** 留言长度上限：原样进下一轮系统提示词，太长会挤掉真正的交易上下文 */
    public static final int MAX_NOTE_CHARS = 500;
    /** 留言轮次上限：15m 档 24 轮≈6 小时。更长的交代属于常驻规则，该写进配置页的自定义提示词 */
    public static final int MAX_NOTE_ROUNDS = 24;

    private final TraderService traderService;
    private final TraderScheduler scheduler;
    private final ReviewRunner reviewRunner;
    private final AiTraderMapper traderMapper;
    private final AiTraderDecisionMapper decisionMapper;
    /** 三张动作卡的回执与拦因都是当场给用户看的话，跟界面语言 */
    private final MessageCatalog messages;

    /** 动作结果：ok=这次请求被正常处理；message 一律直接给用户看 */
    public record ActionResult(boolean ok, String message) {
    }

    /**
     * 动作面板的一次性状态：三张卡要显示的东西全在这里，前端一个请求填满面板。
     *
     * @param wakeBlockedReason   null=可唤醒，否则是不能唤醒的原话
     * @param reviewBlockedReason null=可复盘（有没有素材另看 hasReviewMaterial）
     * @param noteRounds          当前留言剩余轮次，0=无待读留言
     */
    public record ActionPanel(boolean hasTrader, String name, String status, String pausedReason,
                              Long lastWakeAt, Long nextWakeAt, String wakeBlockedReason,
                              Long lastReviewAt, String lastReviewStatus,
                              boolean hasReviewMaterial, String reviewBlockedReason,
                              String note, int noteRounds, int noteMaxRounds, int noteMaxChars) {
    }

    // ========== 留言 ==========

    /** 写留言：覆盖式，同时只有一条待读。rounds 钳在 1~24 */
    public ActionResult saveNote(long userId, String note, Integer rounds) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return new ActionResult(false, messages.get("trader.notCreated"));
        }
        if (note == null || note.isBlank()) {
            return new ActionResult(false, messages.get("trader.note.empty"));
        }
        String text = note.strip();
        boolean truncated = text.length() > MAX_NOTE_CHARS;
        if (truncated) {
            text = text.substring(0, MAX_NOTE_CHARS);
        }
        int n = rounds == null ? 1 : Math.clamp(rounds, 1, MAX_NOTE_ROUNDS);
        boolean covered = t.getOwnerNote() != null;
        traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, t.getId())
                .set(AiTrader::getOwnerNote, text)
                .set(AiTrader::getOwnerNoteRounds, n)
                .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        log.info("[TraderAction] 留言已记下 traderId={} 长度={} 轮次={}", t.getId(), text.length(), n);
        return new ActionResult(true, messages.get("trader.note.saved", Map.of("n", n))
                + (covered ? messages.get("trader.note.savedCovered") : "")
                + (truncated ? messages.get("trader.note.savedTruncated", Map.of("max", MAX_NOTE_CHARS)) : ""));
    }

    /** 撤回未读留言 */
    public ActionResult clearNote(long userId) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return new ActionResult(false, messages.get("trader.notCreated"));
        }
        if (t.getOwnerNote() == null) {
            return new ActionResult(true, messages.get("trader.note.none"));
        }
        traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, t.getId())
                .set(AiTrader::getOwnerNote, null)
                .set(AiTrader::getOwnerNoteRounds, 0)
                .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        log.info("[TraderAction] 留言已撤回 traderId={}", t.getId());
        return new ActionResult(true, messages.get("trader.note.cleared"));
    }

    // ========== 手动唤醒 ==========

    /**
     * 手动唤醒：治理与准入全归调度器，这里只判"这个 trader 现在该不该被叫醒"。
     * <p>
     * 暂停状态不放行——手动唤醒要是能绕过暂停，"暂停"就成了摆设；连败自动暂停的
     * trader 更不该被一句话叫起来接着亏。
     */
    public ActionResult wake(long userId) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return new ActionResult(false, messages.get("trader.notCreated"));
        }
        String blocked = wakeBlockedReason(t);
        if (blocked != null) {
            return new ActionResult(false, blocked);
        }
        String why = scheduler.tryManualWake(t);
        return why == null
                ? new ActionResult(true, messages.get("trader.wake.started"))
                : new ActionResult(false, why);
    }

    /** 不能唤醒的原因，null=可以。面板与真执行共用，显示的拒因就是点下去会拿到的那一句 */
    public String wakeBlockedReason(AiTrader t) {
        if (AiTrader.STATUS_LIQUIDATED.equals(t.getStatus())) {
            return messages.get("trader.wake.liquidated");
        }
        if (!AiTrader.STATUS_RUNNING.equals(t.getStatus())) {
            String why = t.getPausedReason() == null ? ""
                    : messages.get("trader.wake.pausedWhy", Map.of("reason", t.getPausedReason()));
            return messages.get("trader.wake.paused", Map.of("why", why));
        }
        return scheduler.manualWakeBlockedReason(t);
    }

    // ========== 点播复盘 ==========

    /**
     * 点播复盘：准入同步、执行异步。
     * <p>
     * 复盘预算 {@link ReviewRunner#REVIEW_TIMEOUT_SECONDS} 与整条工作台 SSE 同为 600s，
     * 同步跑满就一秒不剩给回答。但"有没有新素材"留在同步侧当场答：
     * 那是用户唯一关心的"会不会白花钱"，推给时间线等于让他等几分钟再去扑空。
     */
    public ActionResult review(long userId) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return new ActionResult(false, messages.get("trader.notCreated"));
        }
        String blocked = reviewBlockedReason(t);
        if (blocked != null) {
            return new ActionResult(false, blocked);
        }
        long at = System.currentTimeMillis();
        if (!reviewRunner.hasMaterial(t, at)) {
            // 成功而不是失败：跳过是一次省下模型调用的正确决定，界面上不该是红的
            return new ActionResult(true, messages.get("trader.review.noMaterial"));
        }
        // 占的是调度侧同一个位子：只挡点播与点播之间的话，日线交接阶段1 会对同一个 trader
        // 再排一篇复盘，两条都落 REVIEW 行、ai_trader.memory 被覆盖写两次，后完成的赢
        if (!scheduler.tryOccupy(t.getId())) {
            return new ActionResult(false, messages.get("trader.review.busy"));
        }
        Thread.startVirtualThread(() -> {
            try {
                reviewRunner.review(t, at);
            } catch (Exception e) {
                // review() 自己兜住模型调用的失败并留 ERROR 行；这里兜的是它之前那几步库查询，
                // 异步之后没人接得住，不打日志就彻底无声
                log.warn("[TraderAction] 点播复盘异常逃逸 userId={} msg={}", userId, e.getMessage());
            } finally {
                scheduler.release(t.getId());
            }
        });
        return new ActionResult(true, messages.get("trader.review.started"));
    }


    /** 不能复盘的原因，null=可以。素材有无另看 {@link ReviewRunner#hasMaterial} */
    public String reviewBlockedReason(AiTrader t) {
        if (scheduler.isHandoverActive()) {
            // 三阶段交接期间旁路写复盘，会让 learner 读到"半天"的复盘并脏读进记忆
            return messages.get("trader.review.handover");
        }
        if (scheduler.isBusy(t.getId())) {
            return messages.get("trader.review.busy");
        }
        return null;
    }

    // ========== 面板状态 ==========

    /** 三张卡的状态一次取齐 */
    public ActionPanel panel(long userId) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return new ActionPanel(false, null, null, null, null, null, null, null, null,
                    false, null, null, 0, MAX_NOTE_ROUNDS, MAX_NOTE_CHARS);
        }
        // 时刻取 created_at
        AiTraderDecision lastWake = latestDecision(t, AiTraderDecision.KIND_TRADE,
                AiTraderDecision.KIND_ALERT, AiTraderDecision.KIND_MANUAL);
        AiTraderDecision lastReview = latestDecision(t, AiTraderDecision.KIND_REVIEW);
        String reviewBlocked = reviewBlockedReason(t);
        return new ActionPanel(
                true, t.getName(), t.getStatus(), t.getPausedReason(),
                lastWake == null ? null : epochMs(lastWake.getCreatedAt()),
                scheduler.nextRoutineWakeAt(t),
                wakeBlockedReason(t),
                lastReview == null ? null : epochMs(lastReview.getCreatedAt()),
                lastReview == null ? null : lastReview.getStatus(),
                reviewRunner.hasMaterial(t, System.currentTimeMillis()),
                reviewBlocked,
                t.getOwnerNote(),
                t.getOwnerNoteRounds() == null ? 0 : t.getOwnerNoteRounds(),
                MAX_NOTE_ROUNDS, MAX_NOTE_CHARS);
    }

    /** 本局最新的一条指定类型决策行；含 ERROR，面板要说得清"上次复盘失败了" */
    private AiTraderDecision latestDecision(AiTrader t, String... kinds) {
        return decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .in(AiTraderDecision::getKind, (Object[]) kinds)
                .orderByDesc(AiTraderDecision::getCreatedAt)
                .last("LIMIT 1"));
    }

    private static Long epochMs(LocalDateTime t) {
        return t == null ? null : t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
