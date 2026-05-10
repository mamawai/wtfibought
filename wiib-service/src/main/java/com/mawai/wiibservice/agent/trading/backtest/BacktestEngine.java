package com.mawai.wiibservice.agent.trading.backtest;

import com.mawai.wiibservice.agent.trading.TradingRuntimeToggles;

import com.mawai.wiibservice.agent.trading.TradingExecutionState;

import com.mawai.wiibservice.agent.trading.SymbolProfile;

import com.mawai.wiibservice.agent.trading.DeterministicTradingExecutor;

import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTradingDecision;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibservice.agent.tool.CryptoIndicatorCalculator;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 回测引擎 — 逐K线回放历史数据，通过 DeterministicTradingExecutor 做决策。
 * <p>
 * <b>设计思想</b>：
 * <ul>
 *   <li>不改动 DeterministicTradingExecutor 任何代码，通过 {@link TradingOperations} 接口注入模拟交易</li>
 *   <li>多周期指标：5m K线是基准周期，15m/1h 由5m K线聚合而来</li>
 *   <li>信号生成：回测不使用LLM，而是从技术指标直接衍生简化信号（方向+置信度+杠杆）</li>
 *   <li>Regime判断：基于ATR变化率 + BB bandwidth + ADX确定 RANGE/SHOCK/SQUEEZE/TREND</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * List<BigDecimal[]> klines5m = loadHistorical5mKlines("BTCUSDT");
 * BacktestEngine engine = new BacktestEngine("BTCUSDT", klines5m, new BigDecimal("100000"));
 * BacktestResult result = engine.run();
 * System.out.println(result);
 * }</pre>
 */
@Slf4j
public class BacktestEngine {

    /** 指标计算所需最少K线数（CryptoIndicatorCalculator内部需要≥30根） */
    private static final int MIN_KLINES_FOR_INDICATOR = 100;

    /** 15m K线由3根5m K线聚合 */
    private static final int BARS_PER_15M = 3;
    /** 1h K线由12根5m K线聚合 */
    private static final int BARS_PER_1H = 12;

    private final String symbol;
    private final List<BigDecimal[]> klines5m; // [high, low, close, volume]
    private final BigDecimal initialBalance;

    // 可调参数（用于参数扫描）
    private SymbolProfile profileOverride;

    public BacktestEngine(String symbol, List<BigDecimal[]> klines5m, BigDecimal initialBalance) {
        this.symbol = symbol;
        this.klines5m = klines5m;
        this.initialBalance = initialBalance;
    }

    /**
     * 允许覆盖 SymbolProfile 参数（用于参数扫描）。
     */
    public BacktestEngine withProfile(SymbolProfile profile) {
        this.profileOverride = profile;
        return this;
    }

    /**
     * 运行回测。
     */
    public BacktestResult run() {
        return run(TradingRuntimeToggles.fromStaticFields());
    }

    /**
     * 使用指定退出引擎开关运行回测，便于同一段历史数据做新旧引擎对照。
     */
    public BacktestResult run(TradingRuntimeToggles toggles) {
        if (klines5m.size() < MIN_KLINES_FOR_INDICATOR) {
            throw new IllegalArgumentException(
                    "K线数量不足: " + klines5m.size() + " < " + MIN_KLINES_FOR_INDICATOR);
        }
        TradingRuntimeToggles runtimeToggles = toggles != null
                ? toggles
                : TradingRuntimeToggles.fromStaticFields();

        BacktestTradingTools tools = new BacktestTradingTools(initialBalance, symbol);
        TradingExecutionState executionState = new TradingExecutionState();
        BacktestResult result = new BacktestResult(initialBalance);
        User mockUser = createMockUser(initialBalance);

        List<AiTradingDecision> recentDecisions = new ArrayList<>();

        log.info("[Backtest] 开始回测 {} | K线数={} | 初始资金={}",
                symbol, klines5m.size(), initialBalance.toPlainString());

        long baseTimeMs = System.currentTimeMillis();
        for (int i = MIN_KLINES_FOR_INDICATOR; i < klines5m.size(); i++) {
            // 回测时间推进：每根 bar 5 分钟
            long mockNowMs = baseTimeMs + (i - MIN_KLINES_FOR_INDICATOR) * 5L * 60_000L;
            executionState.setMockNowMs(mockNowMs);
            LocalDateTime mockNow = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(mockNowMs), ZoneId.systemDefault());

            BigDecimal[] bar = klines5m.get(i);
            BigDecimal high = bar[0];
            BigDecimal low = bar[1];
            BigDecimal close = bar[2];

            // 1. 先检查SL/TP触发（用本根K线的high/low）
            tools.setCurrentTime(mockNow);
            tools.tickBar(high, low, close, i);

            // 2. 计算多周期技术指标
            List<BigDecimal[]> window5m = klines5m.subList(0, i + 1);
            Map<String, Object> indicators5m = CryptoIndicatorCalculator.calcAll(window5m);

            List<BigDecimal[]> klines15m = aggregate(window5m, BARS_PER_15M);
            Map<String, Object> indicators15m = klines15m.size() >= 30
                    ? CryptoIndicatorCalculator.calcAll(klines15m) : Collections.emptyMap();

            List<BigDecimal[]> klines1h = aggregate(window5m, BARS_PER_1H);
            Map<String, Object> indicators1h = klines1h.size() >= 30
                    ? CryptoIndicatorCalculator.calcAll(klines1h) : Collections.emptyMap();

            // 3. 构建 snapshotJson（与 parseMarketContext 格式对齐）
            QuantForecastCycle forecast = buildForecast(indicators5m, indicators15m, indicators1h,
                    high, low, close, mockNow);

            // 4. 从指标衍生简化信号
            List<QuantSignalDecision> signals = deriveSignals(indicators5m, indicators15m, indicators1h);

            // 5. 准备调用参数
            tools.setCurrentPrice(close);
            tools.setCurrentBarIndex(i);
            tools.setCurrentTime(mockNow);
            syncMockUser(mockUser, tools);

            List<FuturesPositionDTO> positions = tools.getOpenPositions(symbol);
            BigDecimal equity = tools.getTotalEquity();

            // 6. 调用执行器（传入profileOverride以支持参数扫描）
            DeterministicTradingExecutor.ExecutionResult execResult =
                    DeterministicTradingExecutor.execute(
                            symbol, mockUser, positions, forecast, signals,
                            recentDecisions, close, close, equity, tools,
                            profileOverride, executionState, runtimeToggles);

            // 7. 标记开仓bar index（如果开了新仓）
            if (execResult.action().startsWith("OPEN_")) {
                tools.markOpenBarIndex(i);
            }

            // 8. 记录决策
            AiTradingDecision decision = new AiTradingDecision();
            decision.setAction(execResult.action());
            decision.setReasoning(execResult.reasoning());
            decision.setExecutionResult(execResult.executionLog());
            decision.setBalanceBefore(equity);
            decision.setBalanceAfter(tools.getTotalEquity());
            decision.setCreatedAt(mockNow);
            recentDecisions.addFirst(decision);
            if (recentDecisions.size() > 20) {
                recentDecisions.removeLast();
            }

            // 9. 记录权益曲线
            result.recordEquity(tools.getTotalEquity());

            // 每500根K线输出进度
            if (i % 500 == 0) {
                log.info("[Backtest] 进度 {}/{} | 权益={} | 持仓={}",
                        i, klines5m.size(), tools.getTotalEquity().toPlainString(),
                        tools.getOpenPositions(symbol).size());
            }
        }

        // 强制平掉剩余仓位
        forceCloseAll(tools);

        // 汇总交易记录
        for (BacktestTradingTools.ClosedTrade ct : tools.getClosedTrades()) {
            result.addTrade(new BacktestResult.Trade(
                    ct.openBarIndex(), ct.closeBarIndex(), ct.side(), ct.strategy(),
                    ct.entryPrice(), ct.exitPrice(), ct.quantity(), ct.leverage(),
                    ct.pnl(), ct.fee(), ct.rMultiple(), ct.exitReason()
            ));
        }

        log.info("[Backtest] 回测完成\n{}", result);
        return result;
    }

    // ==================== K线聚合 ====================

    /**
     * 将5m K线聚合为更高周期。
     * [high, low, close, volume, takerBuyVol] → 取period根合并为一根。
     */
    private static List<BigDecimal[]> aggregate(List<BigDecimal[]> base, int period) {
        List<BigDecimal[]> result = new ArrayList<>();
        int totalBars = base.size() / period;
        for (int i = 0; i < totalBars; i++) {
            int start = i * period;
            BigDecimal high = base.get(start)[0];
            BigDecimal low = base.get(start)[1];
            BigDecimal close = base.get(start + period - 1)[2];
            BigDecimal volume = BigDecimal.ZERO;
            BigDecimal takerBuyVol = BigDecimal.ZERO;
            for (int j = start; j < start + period; j++) {
                BigDecimal[] bar = base.get(j);
                if (bar[0].compareTo(high) > 0) high = bar[0];
                if (bar[1].compareTo(low) < 0) low = bar[1];
                volume = volume.add(bar[3]);
                if (bar.length > 4) takerBuyVol = takerBuyVol.add(bar[4]);
            }
            result.add(new BigDecimal[]{high, low, close, volume, takerBuyVol});
        }
        return result;
    }

    // ==================== 构建 QuantForecastCycle ====================

    private QuantForecastCycle buildForecast(Map<String, Object> ind5m,
                                             Map<String, Object> ind15m,
                                             Map<String, Object> ind1h,
                                             BigDecimal high,
                                             BigDecimal low,
                                             BigDecimal currentPrice,
                                             LocalDateTime forecastTime) {
        JSONObject snap = new JSONObject();
        snap.put("barHigh", high);
        snap.put("barLow", low);
        snap.put("lastPrice", currentPrice);

        // regime 判断
        String regime = determineRegime(ind5m);
        snap.put("regime", regime);
        snap.put("regimeTransition", null);

        // ATR
        BigDecimal atr = getBd(ind5m, "atr14");
        snap.put("atr5m", atr);

        // BB squeeze
        boolean squeeze = isBollSqueeze(ind5m);
        snap.put("bollSqueeze", squeeze);

        // 微结构（回测中无盘口数据，置null让执行器忽略）
        snap.put("bidAskImbalance", null);
        snap.put("takerBuySellPressure", null);
        snap.put("oiChangeRate", null);
        snap.put("fundingDeviation", null);
        snap.put("lsrExtreme", null);

        // indicatorsByTimeframe
        JSONObject indicators = new JSONObject();

        // 5m
        JSONObject tf5m = new JSONObject();
        tf5m.put("rsi14", getBd(ind5m, "rsi14"));
        tf5m.put("macd_cross", getStr(ind5m, "macd_cross"));
        tf5m.put("macd_hist_trend", getStr(ind5m, "macd_hist_trend"));
        tf5m.put("macd_dif", getBd(ind5m, "macd_dif"));
        tf5m.put("macd_dea", getBd(ind5m, "macd_dea"));
        tf5m.put("ma_alignment", getInt(ind5m, "ma_alignment"));
        tf5m.put("close_trend", getStr(ind5m, "close_trend"));
        tf5m.put("close_trend_recent_3_closed", getStr(ind5m, "close_trend_recent_3_closed"));
        tf5m.put("boll_pb", getDbl(ind5m, "boll_pb"));
        tf5m.put("boll_bandwidth", getDbl(ind5m, "boll_bandwidth"));
        tf5m.put("volume_ratio", getDbl(ind5m, "volume_ratio"));
        tf5m.put("volume_ratio_recent_5_closed", ind5m.get("volume_ratio_recent_5_closed"));
        tf5m.put("ema20", getBd(ind5m, "ema20"));
        indicators.put("5m", tf5m);

        // 15m
        JSONObject tf15m = new JSONObject();
        tf15m.put("ma_alignment", getInt(ind15m, "ma_alignment"));
        tf15m.put("rsi14", getBd(ind15m, "rsi14"));
        indicators.put("15m", tf15m);

        // 1h
        JSONObject tf1h = new JSONObject();
        tf1h.put("ma_alignment", getInt(ind1h, "ma_alignment"));
        indicators.put("1h", tf1h);

        snap.put("indicatorsByTimeframe", indicators);

        QuantForecastCycle forecast = new QuantForecastCycle();
        forecast.setCycleId("backtest-" + System.nanoTime());
        forecast.setSymbol(symbol);
        forecast.setSnapshotJson(snap.toJSONString());
        forecast.setOverallDecision(regime); // 非FLAT
        forecast.setForecastTime(forecastTime);
        return forecast;
    }

    // ==================== Regime 判断 ====================

    /**
     * 简化的regime判定（无需LLM）。
     * <ul>
     *   <li>SHOCK: ATR突然放大（当前ATR > MA20(ATR) × 2）或 BB bandwidth > 8%</li>
     *   <li>SQUEEZE: BB bandwidth < 1.5%</li>
     *   <li>TREND: ADX > 25 且 MA排列一致</li>
     *   <li>RANGE: 默认</li>
     * </ul>
     */
    private static String determineRegime(Map<String, Object> ind) {
        Double bandwidth = getDbl(ind, "boll_bandwidth");
        BigDecimal adx = getBd(ind, "adx");
        Integer maAlign = getInt(ind, "ma_alignment");

        if (bandwidth != null && bandwidth > 8.0) return "SHOCK";
        if (bandwidth != null && bandwidth < 1.5) return "SQUEEZE";
        if (adx != null && adx.doubleValue() > 25 && maAlign != null && maAlign != 0) return "TREND";
        return "RANGE";
    }

    private static boolean isBollSqueeze(Map<String, Object> ind) {
        Double bandwidth = getDbl(ind, "boll_bandwidth");
        return bandwidth != null && bandwidth < 1.5;
    }

    // ==================== 信号衍生（替代LLM Agent投票）====================

    /**
     * 从技术指标衍生简化信号。
     * <p>
     * 在真实交易中，6个AI Agent投票 → HorizonJudge加权 → QuantSignalDecision。
     * 回测中跳过Agent，直接用指标规则产生方向+置信度。
     *
     * <h4>方向决定规则</h4>
     * <ol>
     *   <li>1h MA排列方向是基础（≠0时采用）</li>
     *   <li>MACD交叉方向确认</li>
     *   <li>RSI偏向确认</li>
     * </ol>
     *
     * <h4>置信度计算</h4>
     * 每个支持因素加权累加（类似confluence但用于信号层）。
     */
    private List<QuantSignalDecision> deriveSignals(Map<String, Object> ind5m,
                                                     Map<String, Object> ind15m,
                                                     Map<String, Object> ind1h) {
        // 方向投票
        int longVotes = 0, shortVotes = 0;

        // 1h MA排列（权重2x）
        Integer ma1h = getInt(ind1h, "ma_alignment");
        if (ma1h != null) {
            if (ma1h > 0) longVotes += 2;
            else if (ma1h < 0) shortVotes += 2;
        }

        // 15m MA排列
        Integer ma15m = getInt(ind15m, "ma_alignment");
        if (ma15m != null) {
            if (ma15m > 0) longVotes++;
            else if (ma15m < 0) shortVotes++;
        }

        // 5m MACD交叉
        String macdCross = getStr(ind5m, "macd_cross");
        if ("golden".equals(macdCross)) longVotes++;
        else if ("death".equals(macdCross)) shortVotes++;

        // 5m MACD柱状图趋势
        String histTrend = getStr(ind5m, "macd_hist_trend");
        if (histTrend != null) {
            if (histTrend.startsWith("rising") || "mostly_up".equals(histTrend)) longVotes++;
            else if (histTrend.startsWith("falling") || "mostly_down".equals(histTrend)) shortVotes++;
        }

        // RSI偏向
        BigDecimal rsi = getBd(ind5m, "rsi14");
        if (rsi != null) {
            double r = rsi.doubleValue();
            if (r < 40) longVotes++;
            else if (r > 60) shortVotes++;
        }

        // 成交量确认
        Double volRatio = getDbl(ind5m, "volume_ratio");
        if (volRatio != null && volRatio >= 1.2) {
            // 放量时看close trend决定加给哪边
            String closeTrend = getStr(ind5m, "close_trend");
            if (closeTrend != null && (closeTrend.startsWith("rising") || "mostly_up".equals(closeTrend))) {
                longVotes++;
            } else if (closeTrend != null && (closeTrend.startsWith("falling") || "mostly_down".equals(closeTrend))) {
                shortVotes++;
            }
        }

        // 决定方向和置信度
        int totalVotes = longVotes + shortVotes;
        if (totalVotes == 0) {
            return List.of(makeSignal("NO_TRADE", 0.0, 10));
        }

        String direction;
        int dominantVotes;
        if (longVotes > shortVotes) {
            direction = "LONG";
            dominantVotes = longVotes;
        } else if (shortVotes > longVotes) {
            direction = "SHORT";
            dominantVotes = shortVotes;
        } else {
            return List.of(makeSignal("NO_TRADE", 0.0, 10));
        }

        // 置信度 = 主导票 / 总可能票 (max=7)，映射到0.20~0.85区间
        double rawConf = (double) dominantVotes / 7.0;
        double confidence = 0.20 + rawConf * 0.65;
        confidence = Math.min(0.85, Math.max(0.20, confidence));

        // 杠杆根据ADX和MA对齐强度
        int maxLeverage = 20;
        BigDecimal adx = getBd(ind5m, "adx");
        if (adx != null && adx.doubleValue() > 30 && ma1h != null && ma1h != 0) {
            maxLeverage = 30;
        }

        return List.of(makeSignal(direction, confidence, maxLeverage));
    }

    private QuantSignalDecision makeSignal(String direction, double confidence, int maxLeverage) {
        QuantSignalDecision signal = new QuantSignalDecision();
        signal.setHorizon("0_10");
        signal.setDirection(direction);
        signal.setConfidence(BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP));
        signal.setMaxLeverage(maxLeverage);
        signal.setMaxPositionPct(new BigDecimal("0.25"));
        signal.setCreatedAt(LocalDateTime.now());
        return signal;
    }

    // ==================== 辅助方法 ====================

    private User createMockUser(BigDecimal balance) {
        User user = new User();
        user.setId(0L);
        user.setUsername("backtest");
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

    // ==================== Map取值工具 ====================

    private static BigDecimal getBd(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }

    private static Double getDbl(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        if (v instanceof Number n) return n.doubleValue();
        return null;
    }

    private static Integer getInt(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        return null;
    }

    private static String getStr(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
