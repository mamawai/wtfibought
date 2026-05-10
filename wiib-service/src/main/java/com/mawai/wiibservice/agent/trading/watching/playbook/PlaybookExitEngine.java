package com.mawai.wiibservice.agent.trading.watching.playbook;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibservice.agent.trading.DeterministicTradingExecutor;
import com.mawai.wiibservice.agent.trading.ExitPath;
import com.mawai.wiibservice.agent.trading.ExitPlan;
import com.mawai.wiibservice.agent.trading.MarketContext;
import com.mawai.wiibservice.agent.trading.TradingDecisionContext;
import com.mawai.wiibservice.agent.trading.TradingDecisionSupport;
import com.mawai.wiibservice.agent.trading.TradingExecutionState;
import com.mawai.wiibservice.agent.trading.TradingOperations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.getCurrentStopLossPrice;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.getCurrentTakeProfitPrice;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.isTradeSuccess;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.isUpdateSuccess;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.prepareExitEvaluation;

/**
 * Playbook 版持仓退出引擎。
 *
 * <p>职责只限“有仓位时怎么管仓”：按仓位的 {@link ExitPlan#path()} 找到对应剧本，
 * 判断是否全平、部分平仓或移动止损。是否还能继续做开仓/加仓评估，不靠解析 reason 文本，
 * 而是通过 {@link PlaybookEvaluation#entryEvaluationAllowed()} 明确告诉上层执行器。</p>
 *
 * <p>硬 SL/TP 仍由交易/风控服务触发；这里处理的是软退出和风险单调整。</p>
 */
public final class PlaybookExitEngine {

    private static final String ACTION_HOLD = "HOLD";
    private static final String ACTION_EXIT_FULL = "PLAYBOOK_EXIT_FULL";
    private static final String ACTION_EXIT_PARTIAL = "PLAYBOOK_EXIT_PARTIAL";
    private static final String ACTION_MOVE_SL = "PLAYBOOK_MOVE_SL";
    private static final Duration FORECAST_STALE_LIMIT = Duration.ofMinutes(5);

    private final Map<ExitPath, ExitPlaybook> playbooks;

    /**
     * HOLD 的细分语义。
     *
     * <p>CLEAN 表示 Playbook 正常观察后无动作，允许继续走开仓/加仓评估；
     * BLOCKED 表示状态或数据不完整，只能观察；FAILED_ACTION 表示本来要执行动作但失败。
     * 后两者都不能继续开新仓。</p>
     */
    public enum HoldKind {
        CLEAN,
        BLOCKED,
        FAILED_ACTION,
        NOT_HOLD
    }

    /**
     * Playbook 的结构化结果。
     *
     * <p>{@code result} 保持旧执行结果格式给日志/接口使用；
     * {@code holdKind} 给执行器做流程控制，避免用中文 reason 做程序判断。</p>
     */
    public record PlaybookEvaluation(DeterministicTradingExecutor.ExecutionResult result, HoldKind holdKind) {
        public boolean entryEvaluationAllowed() {
            return holdKind == HoldKind.CLEAN;
        }
    }

    public PlaybookExitEngine() {
        this(List.of(
                new BreakoutExitPlaybook(),
                new TrendExitPlaybook(),
                new MeanReversionExitPlaybook()));
    }

    PlaybookExitEngine(Collection<ExitPlaybook> playbooks) {
        this.playbooks = playbooks.stream()
                .collect(Collectors.toMap(ExitPlaybook::path, Function.identity()));
    }

    public DeterministicTradingExecutor.ExecutionResult evaluate(TradingDecisionContext decision, boolean cleanupFromCurrentPositions) {
        return evaluateDetailed(decision, cleanupFromCurrentPositions).result();
    }

    /**
     * 评估当前 symbol 的全部持仓。
     *
     * <p>同一轮最多真实执行第一个 actionable 动作；其它仓位只记录“待下轮处理”。
     * 这样能避免同一 tick 连续发多笔平仓/改单，同时不会把多仓位场景静默吞掉。</p>
     *
     * <p>这里会继续扫描后续仓位，即使前面某个仓位数据异常。异常只影响“本轮是否继续评估新开仓”，
     * 不会改变其它仓位自己的 Playbook 判断。</p>
     */
    public PlaybookEvaluation evaluateDetailed(TradingDecisionContext decision, boolean cleanupFromCurrentPositions) {
        if (decision == null || decision.state() == null || decision.tools() == null) {
            return holdBlocked();
        }
        TradingDecisionSupport.ExitEvaluationContext prepared = prepareExitEvaluation(decision, cleanupFromCurrentPositions);
        TradingExecutionState state = prepared.state();
        List<FuturesPositionDTO> positions = prepared.positions();
        LocalDateTime now = prepared.now();

        boolean indicatorShielded = shouldShieldFromIndicatorExit(decision.market(), decision.forecastTime(), now);
        TradingDecisionContext playbookDecision = decision.withIndicatorExitShielded(indicatorShielded);
        StringBuilder reasons = new StringBuilder();
        DeterministicTradingExecutor.ExecutionResult primary = null;
        HoldKind primaryHoldKind = HoldKind.NOT_HOLD;
        boolean entryEvaluationAllowedAfterScan = true;

        // 同 symbol 多仓位时旧引擎逐个评估，新引擎也保持这个行为：扫完全部，第一个 actionable 真实执行交易，
        // 其余 actionable 写入 reasons 等下一轮处理。这样灰度切换时遗留的多仓不会被本轮静默跳过。
        for (FuturesPositionDTO pos : positions) {
            Long positionId = pos.getId();
            if (!"OPEN".equals(pos.getStatus())) {
                if (positionId != null) state.clearPosition(positionId);
                continue;
            }
            if (positionId == null) {
                reasons.append(pos.getSide()).append(" 缺positionId→Playbook只观察; ");
                entryEvaluationAllowedAfterScan = false;
                continue;
            }
            if (pos.getQuantity() == null || pos.getQuantity().signum() <= 0) {
                reasons.append(positionId).append(" 仓位数量异常→Playbook只观察; ");
                entryEvaluationAllowedAfterScan = false;
                continue;
            }
            ExitPlan plan = state.getExitPlan(positionId);
            if (plan == null) {
                reasons.append(positionId).append(" 缺ExitPlan→Playbook只观察; ");
                entryEvaluationAllowedAfterScan = false;
                continue;
            }

            // 仓位按入场时锁定的 path 走自己的剧本；不要用当前行情重新猜策略。
            ExitPlaybook playbook = playbooks.get(plan.path());
            if (playbook == null) {
                reasons.append(positionId).append(" 无Playbook path=").append(plan.path()).append("; ");
                entryEvaluationAllowedAfterScan = false;
                continue;
            }

            ExitPlaybookDecision playbookResult = playbook.evaluate(playbookDecision, pos, plan, now);
            if (playbookResult.actionable()) {
                if (primary == null) {
                    // 本轮只执行第一个真实动作；执行失败会转成 FAILED_ACTION，阻止后续开仓。
                    primary = executeDecision(decision.tools(), state, pos, plan, playbookResult, indicatorShielded);
                    primaryHoldKind = ACTION_HOLD.equals(primary.action()) ? HoldKind.FAILED_ACTION : HoldKind.NOT_HOLD;
                } else {
                    reasons.append("仓位").append(positionId).append(" 待下轮(")
                            .append(plan.path()).append(' ').append(playbookResult.reason()).append("); ");
                }
                entryEvaluationAllowedAfterScan = false;
                continue;
            }
            reasons.append(plan.path()).append(" ").append(playbookResult.reason());
            if (playbookResult.entryEvaluationBlocked()) {
                // 只阻止本轮新增开仓；循环仍会继续看后面的仓位。
                entryEvaluationAllowedAfterScan = false;
            }
            if (indicatorShielded) {
                // 行情/预测不可靠时，只允许纯价格类保护动作，禁止指标驱动退出，也禁止继续开仓。
                reasons.append(" [指标护盾]");
                entryEvaluationAllowedAfterScan = false;
            }
            reasons.append("; ");
        }

        if (primary != null) {
            String tail = reasons.toString().trim();
            String reasoning = tail.isEmpty() ? primary.reasoning() : primary.reasoning() + " | " + tail;
            DeterministicTradingExecutor.ExecutionResult result = new DeterministicTradingExecutor.ExecutionResult(
                    primary.action(), reasoning, primary.executionLog());
            return new PlaybookEvaluation(result, primaryHoldKind);
        }

        if (reasons.isEmpty()) reasons.append("无活跃持仓");
        // 只有所有仓位都正常走到非 actionable HOLD，才允许上层继续做开仓/加仓评估。
        HoldKind holdKind = entryEvaluationAllowedAfterScan ? HoldKind.CLEAN : HoldKind.BLOCKED;
        return new PlaybookEvaluation(
                new DeterministicTradingExecutor.ExecutionResult(ACTION_HOLD, reasons.toString().trim(), ""),
                holdKind);
    }

    private PlaybookEvaluation holdBlocked() {
        return new PlaybookEvaluation(
                new DeterministicTradingExecutor.ExecutionResult(ACTION_HOLD, "Playbook数据缺失", ""),
                HoldKind.BLOCKED);
    }

    /**
     * 指标护盾：预测过期或实时数据质量差时，不信指标反转类退出。
     *
     * <p>这不会屏蔽价格本身已经达到的保护动作，比如 1R 保本、ATR trail 等。</p>
     */
    public static boolean shouldShieldFromIndicatorExit(MarketContext ctx, LocalDateTime forecastTime, LocalDateTime now) {
        if (now != null && forecastTime != null
                && Duration.between(forecastTime, now).compareTo(FORECAST_STALE_LIMIT) > 0) {
            return true;
        }
        if (ctx == null || ctx.qualityFlags == null) {
            return false;
        }
        return ctx.qualityFlags.contains("STALE_AGG_TRADE")
                || ctx.qualityFlags.contains("LOW_CONFIDENCE");
    }

    private DeterministicTradingExecutor.ExecutionResult executeDecision(TradingOperations tools,
                                                                         TradingExecutionState state,
                                                                         FuturesPositionDTO pos,
                                                                         ExitPlan plan,
                                                                         ExitPlaybookDecision decision,
                                                                         boolean indicatorShielded) {
        return switch (decision.action()) {
            case CLOSE_FULL -> closeFull(tools, state, pos, plan, decision, indicatorShielded);
            case CLOSE_PARTIAL -> closePartial(tools, state, pos, plan, decision, indicatorShielded);
            case MOVE_STOP -> moveStop(tools, state, pos, plan, decision, indicatorShielded);
            case HOLD -> new DeterministicTradingExecutor.ExecutionResult(ACTION_HOLD, decision.reason(), "");
        };
    }

    /**
     * 全平成功后立即清理该仓位的内存计划，避免后续周期继续拿旧 plan 判断。
     */
    private DeterministicTradingExecutor.ExecutionResult closeFull(TradingOperations tools,
                                                                   TradingExecutionState state,
                                                                   FuturesPositionDTO pos,
                                                                   ExitPlan plan,
                                                                   ExitPlaybookDecision decision,
                                                                   boolean indicatorShielded) {
        String result = tools.closePosition(pos.getId(), pos.getQuantity());
        String execLog = "Playbook全平: " + result + "\n";
        if (isTradeSuccess(result)) {
            state.clearPosition(pos.getId());
            return new DeterministicTradingExecutor.ExecutionResult(
                    ACTION_EXIT_FULL,
                    formatReason(plan, decision.reason(), indicatorShielded) + "→全平",
                    execLog);
        }
        return new DeterministicTradingExecutor.ExecutionResult(
                ACTION_HOLD,
                formatReason(plan, decision.reason(), indicatorShielded) + "→全平失败，继续等硬SL/TP",
                execLog);
    }

    /**
     * 部分平仓后必须同步剩余仓位的 SL/TP 数量。
     *
     * <p>平仓成功是主动作；风险单同步失败只写入 reason，不回滚已经发生的部分平仓。</p>
     */
    private DeterministicTradingExecutor.ExecutionResult closePartial(TradingOperations tools,
                                                                      TradingExecutionState state,
                                                                      FuturesPositionDTO pos,
                                                                      ExitPlan plan,
                                                                      ExitPlaybookDecision decision,
                                                                      boolean indicatorShielded) {
        BigDecimal closeQty = decision.closeQuantity();
        BigDecimal originalQty = pos.getQuantity();
        if (closeQty == null || closeQty.signum() <= 0 || closeQty.compareTo(originalQty) >= 0) {
            return new DeterministicTradingExecutor.ExecutionResult(
                    ACTION_HOLD,
                    formatReason(plan, decision.reason(), indicatorShielded) + "→部分平仓数量异常",
                    "");
        }
        BigDecimal remainingQty = originalQty.subtract(closeQty).setScale(8, RoundingMode.HALF_DOWN);
        if (remainingQty.signum() <= 0) {
            return new DeterministicTradingExecutor.ExecutionResult(
                    ACTION_HOLD,
                    formatReason(plan, decision.reason(), indicatorShielded) + "→剩余仓位异常",
                    "");
        }

        String closeResult = tools.closePosition(pos.getId(), closeQty);
        StringBuilder execLog = new StringBuilder("Playbook部分平仓: ").append(closeResult).append("\n");
        if (!isTradeSuccess(closeResult)) {
            return new DeterministicTradingExecutor.ExecutionResult(
                    ACTION_HOLD,
                    formatReason(plan, decision.reason(), indicatorShielded) + "→部分平仓失败，继续等硬SL/TP",
                    execLog.toString());
        }

        if (decision.partialMilestoneR() != null) {
            plan.markPartialDoneAtR(decision.partialMilestoneR());
        }
        if (decision.markMrMidlineHalfDone()) {
            plan.markMrMidlineHalfDone();
        }
        state.clearReversalStreak(pos.getId());

        BigDecimal slToSync = decision.stopLossPrice() != null
                ? decision.stopLossPrice() : getCurrentStopLossPrice(pos);
        BigDecimal tpToSync = getCurrentTakeProfitPrice(pos);
        SyncResult sync = syncRemainingRiskOrders(tools, pos.getId(), slToSync, tpToSync, remainingQty, execLog);
        if (decision.markBreakevenDone()
                && (decision.stopLossPrice() == null || sync.stopLossUpdated())) {
            plan.markBreakevenDone();
        }

        String syncReason = sync.allSucceeded() ? "" : "，剩余风险单同步失败";
        return new DeterministicTradingExecutor.ExecutionResult(
                ACTION_EXIT_PARTIAL,
                formatReason(plan, decision.reason(), indicatorShielded)
                        + "→平" + closeQty.stripTrailingZeros().toPlainString() + syncReason,
                execLog.toString());
    }

    /**
     * 只移动止损，不改变仓位数量。
     */
    private DeterministicTradingExecutor.ExecutionResult moveStop(TradingOperations tools,
                                                                  TradingExecutionState state,
                                                                  FuturesPositionDTO pos,
                                                                  ExitPlan plan,
                                                                  ExitPlaybookDecision decision,
                                                                  boolean indicatorShielded) {
        if (decision.stopLossPrice() == null || decision.stopLossPrice().signum() <= 0) {
            return new DeterministicTradingExecutor.ExecutionResult(
                    ACTION_HOLD,
                    formatReason(plan, decision.reason(), indicatorShielded) + "→SL价格异常",
                    "");
        }
        String result = tools.setStopLoss(pos.getId(), decision.stopLossPrice(), pos.getQuantity());
        String execLog = "Playbook移动SL: " + result + "\n";
        if (isUpdateSuccess(result)) {
            if (decision.markBreakevenDone()) {
                plan.markBreakevenDone();
            }
            state.clearReversalStreak(pos.getId());
            return new DeterministicTradingExecutor.ExecutionResult(
                    ACTION_MOVE_SL,
                    formatReason(plan, decision.reason(), indicatorShielded)
                            + "→SL=" + decision.stopLossPrice().setScale(2, RoundingMode.HALF_UP),
                    execLog);
        }
        return new DeterministicTradingExecutor.ExecutionResult(
                ACTION_HOLD,
                formatReason(plan, decision.reason(), indicatorShielded) + "→移动SL失败，继续等硬SL/TP",
                execLog);
    }

    /**
     * 把部分平仓后的剩余仓位数量同步到硬 SL/TP。
     */
    private SyncResult syncRemainingRiskOrders(TradingOperations tools,
                                               Long positionId,
                                               BigDecimal stopLoss,
                                               BigDecimal takeProfit,
                                               BigDecimal remainingQty,
                                               StringBuilder execLog) {
        boolean stopOk = true;
        boolean tpOk = true;
        if (stopLoss != null && stopLoss.signum() > 0) {
            String result = tools.setStopLoss(positionId, stopLoss, remainingQty);
            execLog.append("同步剩余止损: ").append(result).append("\n");
            stopOk = isUpdateSuccess(result);
        }
        if (takeProfit != null && takeProfit.signum() > 0) {
            String result = tools.setTakeProfit(positionId, takeProfit, remainingQty);
            execLog.append("同步剩余止盈: ").append(result).append("\n");
            tpOk = isUpdateSuccess(result);
        }
        return new SyncResult(stopOk, tpOk);
    }

    private String formatReason(ExitPlan plan, String reason, boolean indicatorShielded) {
        return plan.path() + " " + reason + (indicatorShielded ? " [指标护盾]" : "");
    }

    private record SyncResult(boolean stopLossUpdated, boolean takeProfitUpdated) {
        boolean allSucceeded() {
            return stopLossUpdated && takeProfitUpdated;
        }
    }
}
