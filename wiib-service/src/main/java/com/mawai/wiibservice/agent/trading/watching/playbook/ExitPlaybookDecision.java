package com.mawai.wiibservice.agent.trading.watching.playbook;

import java.math.BigDecimal;

/**
 * 单个仓位的 Playbook 判断结果。
 *
 * <p>注意：这里描述的是“这个仓位要不要动作”。{@code entryEvaluationBlocked} 只控制本轮汇总后
 * 是否还能继续评估新开仓，不会把其它仓位也标成异常。</p>
 */
record ExitPlaybookDecision(
        Action action,
        String reason,
        BigDecimal closeQuantity,
        BigDecimal stopLossPrice,
        boolean markBreakevenDone,
        Integer partialMilestoneR,
        boolean markMrMidlineHalfDone,
        boolean entryEvaluationBlocked
) {

    enum Action {
        HOLD,
        CLOSE_FULL,
        CLOSE_PARTIAL,
        MOVE_STOP
    }

    static ExitPlaybookDecision hold(String reason) {
        return new ExitPlaybookDecision(Action.HOLD, reason, null, null, false, null, false, false);
    }

    static ExitPlaybookDecision holdBlocked(String reason) {
        return new ExitPlaybookDecision(Action.HOLD, reason, null, null, false, null, false, true);
    }

    static ExitPlaybookDecision closeFull(String reason) {
        return new ExitPlaybookDecision(Action.CLOSE_FULL, reason, null, null, false, null, false, false);
    }

    static ExitPlaybookDecision closePartial(BigDecimal closeQuantity, String reason) {
        return new ExitPlaybookDecision(Action.CLOSE_PARTIAL, reason, closeQuantity, null, false, null, false, false);
    }

    static ExitPlaybookDecision closePartial(BigDecimal closeQuantity, int partialMilestoneR, String reason) {
        return new ExitPlaybookDecision(Action.CLOSE_PARTIAL, reason, closeQuantity, null, false, partialMilestoneR, false, false);
    }

    static ExitPlaybookDecision closePartialAndMoveStop(BigDecimal closeQuantity,
                                                        BigDecimal stopLossPrice,
                                                        boolean markBreakevenDone,
                                                        boolean markMrMidlineHalfDone,
                                                        String reason) {
        return new ExitPlaybookDecision(Action.CLOSE_PARTIAL, reason, closeQuantity, stopLossPrice,
                markBreakevenDone, null, markMrMidlineHalfDone, false);
    }

    // TREND +3R：单 cycle 内既 partial 又把剩余 SL 抬到 ATR trail，避免间隙锁不住利润。
    static ExitPlaybookDecision closePartialAndMoveStop(BigDecimal closeQuantity,
                                                        BigDecimal stopLossPrice,
                                                        int partialMilestoneR,
                                                        String reason) {
        return new ExitPlaybookDecision(Action.CLOSE_PARTIAL, reason, closeQuantity, stopLossPrice,
                false, partialMilestoneR, false, false);
    }

    static ExitPlaybookDecision moveStop(BigDecimal stopLossPrice, String reason) {
        return new ExitPlaybookDecision(Action.MOVE_STOP, reason, null, stopLossPrice, false, null, false, false);
    }

    static ExitPlaybookDecision moveStop(BigDecimal stopLossPrice, boolean markBreakevenDone, String reason) {
        return new ExitPlaybookDecision(Action.MOVE_STOP, reason, null, stopLossPrice, markBreakevenDone, null, false, false);
    }

    boolean actionable() {
        return action != Action.HOLD;
    }

    /**
     * 兼容旧命名：含义是“这个 HOLD 会阻止后续开仓评估”，不是“阻止其它仓位继续盯盘”。
     */
    boolean blockedHold() {
        return entryEvaluationBlocked;
    }
}
