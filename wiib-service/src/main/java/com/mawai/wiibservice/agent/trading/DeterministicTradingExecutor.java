package com.mawai.wiibservice.agent.trading;

import com.mawai.wiibservice.agent.trading.entry.EntryDecisionEngine;
import com.mawai.wiibservice.agent.trading.ops.TradingOperations;
import com.mawai.wiibservice.agent.trading.runtime.MarketContext;
import com.mawai.wiibservice.agent.trading.runtime.SymbolProfile;
import com.mawai.wiibservice.agent.trading.runtime.TradingDecisionContext;
import com.mawai.wiibservice.agent.trading.runtime.TradingExecutionState;
import com.mawai.wiibservice.agent.trading.runtime.TradingRuntimeToggles;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTradingDecision;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibservice.agent.trading.exit.playbook.PlaybookExitEngine;
import com.mawai.wiibservice.agent.trading.exit.signal.ExitDecisionEngine;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 确定性交易执行器。
 *
 * <p>这里不写具体交易规则，只负责把一轮交易判断串起来：</p>
 * <p>1. 解析行情和运行开关；2. 清理已消失仓位的内存状态；3. 无持仓时直接评估开仓；
 * 4. 有持仓时先盯盘/平仓，再按退出结果决定是否继续评估开仓。</p>
 *
 * <p>旧退出引擎只通过 action 判断是否继续开仓；Playbook 新引擎会返回结构化 HoldKind，
 * 避免把“数据缺失看不了”的 HOLD 误当成“仓位正常无动作”的 HOLD。</p>
 */
@Slf4j
public class DeterministicTradingExecutor {

    public static final BigDecimal INITIAL_BALANCE = new BigDecimal("100000.00");

    /**
     * 低波动小仓位交易开关（Admin运行时切换）。
     */
    public static volatile boolean LOW_VOL_TRADING_ENABLED = true;

    /**
     * Playbook平仓新引擎灰度开关；默认打开，未显式打开时必须继续走旧ExitDecisionEngine。
     */
    public static volatile boolean PLAYBOOK_EXIT_ENABLED = true;

    private static final EntryDecisionEngine ENTRY_ENGINE = new EntryDecisionEngine();
    private static final ExitDecisionEngine EXIT_ENGINE = new ExitDecisionEngine();
    private static final PlaybookExitEngine PLAYBOOK_EXIT_ENGINE = new PlaybookExitEngine();

    /**
     * 一轮执行结果。
     *
     * <p>{@code action} 给调度/接口做流程判断；{@code reasoning} 给人看原因；
     * {@code executionLog} 记录实际交易 adapter 返回，方便排查下单或改单失败。</p>
     */
    public record ExecutionResult(String action, String reasoning, String executionLog) {
    }

    public static ExecutionResult execute(
            String symbol, User user,
            List<FuturesPositionDTO> symbolPositions,
            QuantForecastCycle forecast,
            List<QuantSignalDecision> signals,
            List<AiTradingDecision> recentDecisions,
            BigDecimal futuresPrice, BigDecimal markPrice,
            BigDecimal totalEquity,
            TradingOperations tools,
            TradingExecutionState state,
            TradingRuntimeToggles toggles) {
        return executeInternal(symbol, user, symbolPositions, forecast, signals, recentDecisions,
                futuresPrice, markPrice, totalEquity, tools, state, toggles, null, null);
    }

    /**
     * 支持自定义 SymbolProfile 的重载入口（回测参数扫描用）。
     */
    public static ExecutionResult execute(
            String symbol, User user,
            List<FuturesPositionDTO> symbolPositions,
            QuantForecastCycle forecast,
            List<QuantSignalDecision> signals,
            List<AiTradingDecision> recentDecisions,
            BigDecimal futuresPrice, BigDecimal markPrice,
            BigDecimal totalEquity,
            TradingOperations tools,
            SymbolProfile profileOverride,
            TradingExecutionState state,
            TradingRuntimeToggles toggles) {
        return executeInternal(symbol, user, symbolPositions, forecast, signals, recentDecisions,
                futuresPrice, markPrice, totalEquity, tools, state, toggles, profileOverride, null);
    }

    /**
     * 生产调度传入全量open仓位ID，清理状态时不误删其它symbol。
     */
    public static ExecutionResult execute(
            String symbol, User user,
            List<FuturesPositionDTO> symbolPositions,
            QuantForecastCycle forecast,
            List<QuantSignalDecision> signals,
            List<AiTradingDecision> recentDecisions,
            BigDecimal futuresPrice, BigDecimal markPrice,
            BigDecimal totalEquity,
            TradingOperations tools,
            Collection<Long> allOpenPositionIds,
            TradingExecutionState state,
            TradingRuntimeToggles toggles) {
        return executeInternal(symbol, user, symbolPositions, forecast, signals, recentDecisions,
                futuresPrice, markPrice, totalEquity, tools, state, toggles, null, allOpenPositionIds);
    }

    private static ExecutionResult executeInternal(
            String symbol, User user,
            List<FuturesPositionDTO> symbolPositions,
            QuantForecastCycle forecast,
            List<QuantSignalDecision> signals,
            List<AiTradingDecision> recentDecisions,
            BigDecimal futuresPrice, BigDecimal markPrice,
            BigDecimal totalEquity,
            TradingOperations tools,
            TradingExecutionState state,
            TradingRuntimeToggles toggles,
            SymbolProfile profileOverride,
            Collection<Long> allOpenPositionIds) {

        if (user == null || futuresPrice == null || futuresPrice.signum() <= 0 || tools == null) {
            return new ExecutionResult("HOLD", "数据缺失", "");
        }

        TradingExecutionState executionState = state != null ? state : new TradingExecutionState();
        TradingRuntimeToggles runtimeToggles = toggles != null ? toggles : TradingRuntimeToggles.fromStaticFields();
        List<FuturesPositionDTO> positions = symbolPositions != null ? symbolPositions : List.of();
        SymbolProfile profile = profileOverride != null ? profileOverride : SymbolProfile.of(symbol);
        MarketContext market = MarketContext.parse(forecast, futuresPrice);
        LocalDateTime forecastTime = forecast != null ? forecast.getForecastTime() : null;

        log.info("[Executor] {} regime={} atr={} rsi5m={} maAlign1h={} macdCross={} bbPb={} vol={} squeeze={} equity={}",
                symbol, market.regime, market.atr5m, market.rsi5m, market.maAlignment1h, market.macdCross5m,
                market.bollPb5m, market.volumeRatio5m, market.bollSqueeze, totalEquity);

        if (allOpenPositionIds != null) {
            executionState.cleanupPositionMemory(allOpenPositionIds);
        }

        TradingDecisionContext decision = new TradingDecisionContext(
                symbol, user, positions, forecast, signals, recentDecisions,
                futuresPrice, markPrice, totalEquity, tools, executionState,
                runtimeToggles, profile, market, forecastTime);

        // 无持仓没有盯盘负担，直接进入开仓评估。
        if (positions.isEmpty()) {
            return evaluateEntry(decision);
        }
        // 生产入口会传全账户OPEN仓位ID，外层已按全局ID清理state，避免按当前symbol误删其它symbol记忆。
        // 旧入口/回测未传全量ID时，退出引擎只能按当前symbol仓位做局部清理。
        boolean cleanupMemoryByCurrentSymbolPositions = allOpenPositionIds == null;
        return evaluatePositionCycle(decision, cleanupMemoryByCurrentSymbolPositions);
    }

    /**
     * 持仓周期统一编排。
     *
     * <p>两条退出路径都遵守同一个顺序：先处理已有仓位，再决定是否追加开仓评估。</p>
     */
    private static ExecutionResult evaluatePositionCycle(TradingDecisionContext decision,
                                                         boolean cleanupMemoryByCurrentSymbolPositions) {
        if (decision.toggles().playbookExitEnabled()) {
            return evaluatePlaybookExitThenMaybeEntry(decision, cleanupMemoryByCurrentSymbolPositions);
        }
        return evaluateLegacyExitThenMaybeEntry(decision, cleanupMemoryByCurrentSymbolPositions);
    }

    /**
     * Playbook 新引擎：只有 CLEAN HOLD 才继续评估开仓。
     *
     * <p>如果 Playbook 有真实平仓/改单动作、动作失败、指标护盾或某个仓位数据缺失，
     * 本轮都只返回盯盘结果，不叠加新开仓。</p>
     */
    private static ExecutionResult evaluatePlaybookExitThenMaybeEntry(
            TradingDecisionContext decision,
            boolean cleanupMemoryByCurrentSymbolPositions) {
        PlaybookExitEngine.PlaybookEvaluation exitEvaluation = PLAYBOOK_EXIT_ENGINE.evaluateDetailed(
                decision, cleanupMemoryByCurrentSymbolPositions);
        if (!exitEvaluation.entryEvaluationAllowed()) {
            return exitEvaluation.result();
        }
        return mergeExitAndEntry(exitEvaluation.result(), evaluateEntry(decision));
    }

    /**
     * 旧退出引擎：只能看 action 是否为 HOLD。
     *
     * <p>只要旧引擎返回收紧 SL、部分平仓、全平或时间退出，本轮就结束；
     * 只有普通 HOLD 才把开仓评估拼到同一轮结果里。</p>
     */
    private static ExecutionResult evaluateLegacyExitThenMaybeEntry(TradingDecisionContext decision,
                                                                    boolean cleanupMemoryByCurrentSymbolPositions) {
        ExecutionResult exitResult = EXIT_ENGINE.evaluate(decision, cleanupMemoryByCurrentSymbolPositions);
        if (!"HOLD".equals(exitResult.action())) {
            return exitResult;
        }
        return mergeExitAndEntry(exitResult, evaluateEntry(decision));
    }

    private static ExecutionResult evaluateEntry(TradingDecisionContext decision) {
        return ENTRY_ENGINE.evaluate(decision);
    }

    private static ExecutionResult mergeExitAndEntry(ExecutionResult exitResult, ExecutionResult entryResult) {
        String reasoning = exitResult.reasoning() + " | entry=" + entryResult.reasoning();
        String executionLog = exitResult.executionLog() + entryResult.executionLog();
        return new ExecutionResult(entryResult.action(), reasoning, executionLog);
    }
}
