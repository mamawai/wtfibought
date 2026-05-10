package com.mawai.wiibservice.agent.trading.backtest;

import com.mawai.wiibservice.agent.trading.runtime.TradingRuntimeToggles;

import com.mawai.wiibservice.agent.trading.runtime.TradingExecutionState;

import com.mawai.wiibservice.agent.trading.DeterministicTradingExecutor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTradingDecision;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import com.mawai.wiibcommon.entity.User;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 信号回放回测引擎 — 读取数据库中线上真实产出的 {@link QuantForecastCycle} + {@link QuantSignalDecision}，
 * 按时间顺序回放给 {@link DeterministicTradingExecutor}，产出和实盘等价的回测结果。
 *
 * <p>与 {@link BacktestEngine} 的区别：
 * <ul>
 *   <li>{@code BacktestEngine}：K线→简化公式派生信号→Executor。信号和实盘不一致，参数优化结论不可用。</li>
 *   <li>{@code SignalReplayBacktestEngine}：直接读DB里的真实历史信号（已经过LLM+HorizonJudge+RiskGate）
 *       →Executor。信号和实盘100%等价，适合验证Executor规则变更对实盘的影响。</li>
 * </ul>
 *
 * <p><b>精度说明</b>：优先使用 snapshot 中可用的 high/low 字段检查SL/TP；
 * 历史快照缺失区间高低点时退回 close-only 判定，保持向后兼容。
 */
@Slf4j
public class SignalReplayBacktestEngine {

    private final String symbol;
    private final BigDecimal initialBalance;
    /** 按 forecastTime 升序 */
    private final List<QuantForecastCycle> cycles;
    /** cycleId → 该轮的3个 horizon 信号 */
    private final Map<String, List<QuantSignalDecision>> signalsByCycleId;

    public SignalReplayBacktestEngine(String symbol,
                                      List<QuantForecastCycle> cycles,
                                      Map<String, List<QuantSignalDecision>> signalsByCycleId,
                                      BigDecimal initialBalance) {
        this.symbol = symbol;
        this.cycles = cycles;
        this.signalsByCycleId = signalsByCycleId;
        this.initialBalance = initialBalance;
    }

    public BacktestResult run() {
        return run(TradingRuntimeToggles.fromStaticFields());
    }

    public BacktestResult run(TradingRuntimeToggles toggles) {
        if (cycles == null || cycles.isEmpty()) {
            throw new IllegalArgumentException("cycles为空，无可回放的历史数据");
        }
        TradingRuntimeToggles runtimeToggles = toggles != null
                ? toggles
                : TradingRuntimeToggles.fromStaticFields();

        BacktestTradingTools tools = new BacktestTradingTools(initialBalance, symbol);
        TradingExecutionState executionState = new TradingExecutionState();
        BacktestResult result = new BacktestResult(initialBalance);
        User mockUser = createMockUser(initialBalance);

        // recentDecisions 按最新在前的顺序维护（Executor的checkRiskLimits按此顺序）
        Deque<AiTradingDecision> recentDecisions = new ArrayDeque<>();

        log.info("[Replay] 开始 {} | cycle数={} | 初始资金={} | 时段=[{} ~ {}]",
                symbol, cycles.size(), initialBalance.toPlainString(),
                cycles.getFirst().getForecastTime(), cycles.getLast().getForecastTime());

        int skipped = 0;
        int index = 0;
        for (QuantForecastCycle cycle : cycles) {
            index++;
            // 回测时间推进：使用 cycle 的 forecastTime
            LocalDateTime mockNow = cycle.getForecastTime();
            if (cycle.getForecastTime() != null) {
                long mockNowMs = cycle.getForecastTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                executionState.setMockNowMs(mockNowMs);
            } else {
                executionState.setMockNowMs(null);
            }
            PriceBar bar = extractPriceBar(cycle);
            if (bar == null || bar.close() == null || bar.close().signum() <= 0) {
                skipped++;
                continue;
            }
            BigDecimal price = bar.close();

            // 1. 优先用区间高低点触发挂单；旧快照缺字段时 high/low 回落到 close。
            tools.setCurrentTime(mockNow);
            tools.tickBar(bar.high(), bar.low(), price, index);

            // 2. 取该cycle的3个horizon信号
            List<QuantSignalDecision> signals = signalsByCycleId.getOrDefault(cycle.getCycleId(), List.of());

            // 3. 同步mockUser余额
            syncMockUser(mockUser, tools);
            tools.setCurrentPrice(price);
            tools.setCurrentBarIndex(index);
            tools.setCurrentTime(mockNow);

            List<FuturesPositionDTO> positions = tools.getOpenPositions(symbol);
            BigDecimal equity = tools.getTotalEquity();

            // 4. 调用Executor
            DeterministicTradingExecutor.ExecutionResult exec =
                    DeterministicTradingExecutor.execute(
                            symbol, mockUser, positions, cycle, signals,
                            new ArrayList<>(recentDecisions), price, price, equity, tools,
                            executionState, runtimeToggles);

            if (exec.action() != null && exec.action().startsWith("OPEN_")) {
                tools.markOpenBarIndex(index);
            }

            // 5. 记录决策（Executor的日亏损上限依赖此列表）
            AiTradingDecision decision = new AiTradingDecision();
            decision.setAction(exec.action());
            decision.setReasoning(exec.reasoning());
            decision.setExecutionResult(exec.executionLog());
            decision.setBalanceBefore(equity);
            decision.setBalanceAfter(tools.getTotalEquity());
            decision.setCreatedAt(cycle.getForecastTime() != null ? cycle.getForecastTime() : LocalDateTime.now());
            recentDecisions.addFirst(decision);
            while (recentDecisions.size() > 20) recentDecisions.removeLast();

            // 6. 记录权益
            result.recordEquity(tools.getTotalEquity());

            if (index % 200 == 0) {
                log.info("[Replay] 进度 {}/{} | 权益={} | 持仓={}",
                        index, cycles.size(), tools.getTotalEquity().toPlainString(),
                        tools.getOpenPositions(symbol).size());
            }
        }

        // 强平剩余仓位（使用最后一个cycle价格）
        forceCloseAll(tools);

        // 聚合交易明细
        for (BacktestTradingTools.ClosedTrade ct : tools.getClosedTrades()) {
            result.addTrade(new BacktestResult.Trade(
                    ct.openBarIndex(), ct.closeBarIndex(), ct.side(), ct.strategy(),
                    ct.entryPrice(), ct.exitPrice(), ct.quantity(), ct.leverage(),
                    ct.pnl(), ct.fee(), ct.rMultiple(), ct.exitReason()));
        }

        log.info("[Replay] 回放完成 skipped={}条(缺lastPrice)\n{}", skipped, result);
        return result;
    }

    /** 从 cycle.snapshotJson 提取 close/high/low；旧数据没有 high/low 时用 close 兼容。 */
    public PriceBar extractPriceBar(QuantForecastCycle cycle) {
        String json = cycle.getSnapshotJson();
        if (json == null || json.isBlank()) return null;
        try {
            JSONObject snap = JSON.parseObject(json);
            BigDecimal p = snap.getBigDecimal("lastPrice");
            if (p == null) p = snap.getBigDecimal("price");
            if (p == null) return null;
            BigDecimal high = firstNonNull(
                    snap.getBigDecimal("high"),
                    snap.getBigDecimal("barHigh"),
                    snap.getBigDecimal("highPrice"),
                    snap.getBigDecimal("lastHigh"));
            BigDecimal low = firstNonNull(
                    snap.getBigDecimal("low"),
                    snap.getBigDecimal("barLow"),
                    snap.getBigDecimal("lowPrice"),
                    snap.getBigDecimal("lastLow"));
            if (high == null || high.signum() <= 0) high = p;
            if (low == null || low.signum() <= 0) low = p;
            if (high.compareTo(low) < 0) {
                BigDecimal tmp = high;
                high = low;
                low = tmp;
            }
            if (high.compareTo(p) < 0) high = p;
            if (low.compareTo(p) > 0) low = p;
            return new PriceBar(high, low, p);
        } catch (Exception e) {
            log.warn("[Replay] snapshotJson解析失败 cycleId={}: {}", cycle.getCycleId(), e.getMessage());
            return null;
        }
    }

    private BigDecimal firstNonNull(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private User createMockUser(BigDecimal balance) {
        User user = new User();
        user.setId(0L);
        user.setUsername("replay-backtest");
        user.setBalance(balance);
        user.setFrozenBalance(BigDecimal.ZERO);
        user.setMarginLoanPrincipal(BigDecimal.ZERO);
        user.setMarginInterestAccrued(BigDecimal.ZERO);
        user.setIsBankrupt(false);
        user.setBankruptCount(0);
        return user;
    }

    private void syncMockUser(User user, BacktestTradingTools tools) {
        user.setBalance(tools.getBalance());
        user.setFrozenBalance(tools.getFrozenBalance());
    }

    private void forceCloseAll(BacktestTradingTools tools) {
        List<FuturesPositionDTO> remaining = new ArrayList<>(tools.getOpenPositions(symbol));
        for (FuturesPositionDTO pos : remaining) {
            tools.closePosition(pos.getId(), pos.getQuantity());
        }
    }

    public record PriceBar(BigDecimal high, BigDecimal low, BigDecimal close) {}
}
