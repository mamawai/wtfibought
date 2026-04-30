package com.mawai.wiibservice.agent.trading;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTradingDecision;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import com.mawai.wiibcommon.entity.User;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 确定性交易执行器。
 * 公开入口保持不变；内部只编排上下文、平仓引擎和开仓引擎。
 */
@Slf4j
public class DeterministicTradingExecutor {

    public static final BigDecimal INITIAL_BALANCE = new BigDecimal("100000.00");

    /**
     * 低波动小仓位交易开关（Admin运行时切换）。
     */
    public static volatile boolean LOW_VOL_TRADING_ENABLED = true;

    private static final EntryDecisionEngine ENTRY_ENGINE = new EntryDecisionEngine();
    private static final ExitDecisionEngine EXIT_ENGINE = new ExitDecisionEngine();

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

        if (!positions.isEmpty()) {
            ExecutionResult exitResult = EXIT_ENGINE.evaluate(decision, allOpenPositionIds == null);
            if (!"HOLD".equals(exitResult.action())) {
                return exitResult;
            }
            ExecutionResult entryResult = ENTRY_ENGINE.evaluate(decision);
            return mergeExitAndEntry(exitResult, entryResult);
        }

        return ENTRY_ENGINE.evaluate(decision);
    }

    private static ExecutionResult mergeExitAndEntry(ExecutionResult exitResult, ExecutionResult entryResult) {
        String reasoning = exitResult.reasoning() + " | entry=" + entryResult.reasoning();
        String executionLog = exitResult.executionLog() + entryResult.executionLog();
        return new ExecutionResult(entryResult.action(), reasoning, executionLog);
    }
}
