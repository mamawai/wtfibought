package com.mawai.wiibservice.agent.trading.backtest;

import com.mawai.wiibservice.agent.trading.runtime.TradingRuntimeToggles;

import com.mawai.wiibservice.agent.trading.runtime.TradingExecutionState;

import com.mawai.wiibservice.agent.trading.runtime.SymbolProfile;

import com.mawai.wiibservice.agent.trading.DeterministicTradingExecutor;

import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTradingDecision;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibservice.agent.tool.CryptoIndicatorCalculator;
import com.mawai.wiibservice.agent.trading.maslope.MaSlopeStateClassifier;
import com.mawai.wiibservice.agent.trading.maslope.MaSlopeStateClassifier.MaState;
import com.mawai.wiibservice.agent.trading.runtime.MarketContext;
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
 *   <li>多周期指标：主决策周期K线是基准周期，15m/1h 由基准K线聚合而来</li>
 *   <li>信号生成：回测不使用LLM，而是从技术指标直接衍生简化信号（方向+置信度+杠杆）</li>
 *   <li>Regime判断：基于ATR变化率 + BB bandwidth + ADX确定 RANGE/SHOCK/SQUEEZE/TREND</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * List<BigDecimal[]> klines = loadHistoricalKlines("BTCUSDT", KlineInterval.M5);
 * BacktestEngine engine = new BacktestEngine("BTCUSDT", klines, new BigDecimal("100000"), KlineInterval.M5);
 * BacktestResult result = engine.run();
 * System.out.println(result);
 * }</pre>
 */
@Slf4j
public class BacktestEngine {

    /** 指标计算所需最少K线数（CryptoIndicatorCalculator内部需要≥30根） */
    private static final int MIN_KLINES_FOR_INDICATOR = 100;

    private final String symbol;
    private final List<BigDecimal[]> baseKlines; // [high, low, close, volume]
    private final BigDecimal initialBalance;
    private final KlineInterval baseInterval;

    // 可调参数（用于参数扫描）
    private SymbolProfile profileOverride;

    public BacktestEngine(String symbol, List<BigDecimal[]> baseKlines, BigDecimal initialBalance) {
        this(symbol, baseKlines, initialBalance, KlineInterval.M5);
    }

    public BacktestEngine(String symbol, List<BigDecimal[]> baseKlines, BigDecimal initialBalance,
                          KlineInterval baseInterval) {
        this.symbol = symbol;
        this.baseKlines = baseKlines;
        this.initialBalance = initialBalance;
        this.baseInterval = baseInterval != null ? baseInterval : KlineInterval.M5;
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
        if (baseKlines.size() < MIN_KLINES_FOR_INDICATOR) {
            throw new IllegalArgumentException(
                    "K线数量不足: " + baseKlines.size() + " < " + MIN_KLINES_FOR_INDICATOR);
        }
        TradingRuntimeToggles runtimeToggles = toggles != null
                ? toggles
                : TradingRuntimeToggles.fromStaticFields();
        int barsPer15m = baseInterval.aggregateRatioTo(KlineInterval.M15);
        int barsPer1h = baseInterval.aggregateRatioTo(KlineInterval.H1);

        BacktestTradingTools tools = new BacktestTradingTools(initialBalance, symbol);
        TradingExecutionState executionState = new TradingExecutionState();
        BacktestResult result = new BacktestResult(initialBalance);
        User mockUser = createMockUser(initialBalance);

        List<AiTradingDecision> recentDecisions = new ArrayList<>();

        log.info("[Backtest] 开始回测 {} | baseInterval={} | K线数={} | 初始资金={}",
                symbol, baseInterval.getCode(), baseKlines.size(), initialBalance.toPlainString());

        long fallbackBaseTimeMs = System.currentTimeMillis();
        for (int i = MIN_KLINES_FOR_INDICATOR; i < baseKlines.size(); i++) {
            BigDecimal[] bar = baseKlines.get(i);
            // 用交易所K线 closeTime 作为信号成交时间；无时间字段的旧测试数据才退回等间隔模拟时间。
            long mockNowMs = klineCloseTimeMs(bar, i, fallbackBaseTimeMs);
            executionState.setMockNowMs(mockNowMs);
            LocalDateTime mockNow = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(mockNowMs), ZoneId.systemDefault());

            BigDecimal high = bar[0];
            BigDecimal low = bar[1];
            BigDecimal close = bar[2];

            // 1. 先检查SL/TP触发（用本根K线的high/low）
            tools.setCurrentTime(mockNow);
            tools.tickBar(high, low, close, i);

            // 2. 计算多周期技术指标
            List<BigDecimal[]> baseWindow = baseKlines.subList(0, i + 1);
            Map<String, Object> indicatorsPrimary = CryptoIndicatorCalculator.calcAll(baseWindow, true);

            List<BigDecimal[]> klines15m = aggregate(baseWindow, barsPer15m, KlineInterval.M15);
            Map<String, Object> indicators15m = klines15m.size() >= 30
                    ? CryptoIndicatorCalculator.calcAll(klines15m, true) : Collections.emptyMap();

            List<BigDecimal[]> klines1h = aggregate(baseWindow, barsPer1h, KlineInterval.H1);
            Map<String, Object> indicators1h = klines1h.size() >= 30
                    ? CryptoIndicatorCalculator.calcAll(klines1h, true) : Collections.emptyMap();

            // 3. 构建 snapshotJson（与 parseMarketContext 格式对齐）
            QuantForecastCycle forecast = buildForecast(indicatorsPrimary, indicators15m, indicators1h,
                    high, low, close, mockNow);

            // 4. 从指标衍生简化信号
            List<QuantSignalDecision> signals = deriveSignals(indicatorsPrimary, indicators15m, indicators1h);

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
                            profileOverride, executionState, runtimeToggles,
                            baseInterval);

            // 7. 标记开仓bar index（如果开了新仓）
            if (execResult.action().startsWith("OPEN_")) {
                tools.markOpenBarIndex(i, entryDiagnosticsJson(forecast, close));
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
                        i, baseKlines.size(), tools.getTotalEquity().toPlainString(),
                        tools.getOpenPositions(symbol).size());
            }
        }

        // 强制平掉剩余仓位
        forceCloseAll(tools);
        // 强平发生在最后一根K线之后，必须补记一次权益，否则 finalEquity 会停在强平前的浮盈亏状态。
        result.recordEquity(tools.getTotalEquity());

        // 汇总交易记录
        for (BacktestTradingTools.ClosedTrade ct : tools.getClosedTrades()) {
            result.addTrade(new BacktestResult.Trade(
                    ct.openBarIndex(), ct.closeBarIndex(), ct.openTime(), ct.closeTime(),
                    ct.entryDiagnosticsJson(), ct.side(), ct.strategy(),
                    ct.entryPrice(), ct.exitPrice(), ct.quantity(), ct.leverage(),
                    ct.pnl(), ct.fee(), ct.rMultiple(), ct.exitReason()
            ));
        }

        log.info("[Backtest] 回测完成\n{}", result);
        return result;
    }

    private String entryDiagnosticsJson(QuantForecastCycle forecast, BigDecimal price) {
        try {
            MarketContext ctx = MarketContext.parse(forecast, price, baseInterval);
            MaState primary = MaSlopeStateClassifier.classifyPrimary(ctx);
            MaState previous = MaSlopeStateClassifier.classifyPreviousPrimary(ctx);
            MaState confirm = MaSlopeStateClassifier.classifyConfirm(ctx);

            JSONObject json = new JSONObject();
            json.put("decisionInterval", baseInterval.getCode());
            json.put("price", price);
            json.put("regime", ctx.regime);
            json.put("adx", ctx.adx);
            json.put("macdCross", ctx.macdCross);
            json.put("macdHistTrend", ctx.macdHistTrend);
            json.put("closeTrend", ctx.closeTrend);
            json.put("closeTrendClosed3", ctx.closeTrendClosed3);
            json.put("closeTail", tail(ctx.closeSeriesClosed, 9));
            json.put("closePositionClosed", ctx.closePositionClosed);
            json.put("rangeAtrClosed", ctx.rangeAtrClosed);
            json.put("closeBreakoutHigh10Closed", ctx.closeBreakoutHigh10Closed);
            json.put("closeBreakdownLow10Closed", ctx.closeBreakdownLow10Closed);
            json.put("volumeRatioClosedAvg", ctx.volumeRatioClosedSeries.stream()
                    .filter(v -> v != null && Double.isFinite(v))
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0));
            json.put("maAlignment", ctx.maAlignment);
            json.put("maAlignment15m", ctx.maAlignment15m);
            json.put("maAlignment1h", ctx.maAlignment1h);
            json.put("primaryMa7Tail", tail(ctx.ma7SeriesClosed, 9));
            json.put("primaryMa25Tail", tail(ctx.ma25SeriesClosed, 9));
            json.put("confirmMa7Tail", tail(ctx.confirmMa7SeriesClosed, 9));
            json.put("confirmMa25Tail", tail(ctx.confirmMa25SeriesClosed, 9));
            putState(json, "primary", primary);
            putState(json, "previousPrimary", previous);
            putState(json, "confirm", confirm);
            return json.toJSONString();
        } catch (Exception e) {
            JSONObject json = new JSONObject();
            json.put("error", e.getMessage());
            return json.toJSONString();
        }
    }

    private void putState(JSONObject json, String prefix, MaState state) {
        if (state == null) return;
        json.put(prefix + "State", state.state().name());
        json.put(prefix + "Ma7SlopeAtr", state.ma7SlopeAtr());
        json.put(prefix + "Ma7PrevSlopeAtr", state.ma7PrevSlopeAtr());
        json.put(prefix + "Ma25SlopeAtr", state.ma25SlopeAtr());
        json.put(prefix + "SpreadAtr", state.spreadAtr());
        json.put(prefix + "SpreadDeltaAtr", state.spreadDeltaAtr());
        json.put(prefix + "PriceDistanceAtr", state.priceDistanceAtr());
    }

    private List<BigDecimal> tail(List<BigDecimal> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, values.size() - limit);
        return values.subList(from, values.size());
    }

    private long klineCloseTimeMs(BigDecimal[] bar, int index, long fallbackBaseTimeMs) {
        if (bar != null && bar.length > 6 && bar[6] != null && bar[6].signum() > 0) {
            return bar[6].longValue();
        }
        if (bar != null && bar.length > 5 && bar[5] != null && bar[5].signum() > 0) {
            return bar[5].longValue() + (long) baseInterval.getMinutes() * 60_000L - 1;
        }
        return fallbackBaseTimeMs
                + (index - MIN_KLINES_FOR_INDICATOR) * (long) baseInterval.getMinutes() * 60_000L;
    }

    // ==================== K线聚合 ====================

    /**
     * 将基准周期 K 线聚合为更高周期。
     * [high, low, close, volume, takerBuyVol, openTime, closeTime] → 取period根合并为一根。
     */
    static List<BigDecimal[]> aggregate(List<BigDecimal[]> base, int period, KlineInterval targetInterval) {
        if (hasKlineTimestamps(base)) {
            return aggregateByExchangeTime(base, period, targetInterval);
        }
        return aggregateByCount(base, period);
    }

    private static List<BigDecimal[]> aggregateByCount(List<BigDecimal[]> base, int period) {
        List<BigDecimal[]> result = new ArrayList<>();
        int totalBars = base.size() / period;
        for (int i = 0; i < totalBars; i++) {
            int start = i * period;
            BigDecimal high = base.get(start)[0];
            BigDecimal low = base.get(start)[1];
            BigDecimal close = base.get(start + period - 1)[2];
            BigDecimal volume = BigDecimal.ZERO;
            BigDecimal takerBuyVol = BigDecimal.ZERO;
            BigDecimal openTime = base.get(start).length > 5 ? base.get(start)[5] : null;
            BigDecimal closeTime = base.get(start + period - 1).length > 6
                    ? base.get(start + period - 1)[6] : null;
            for (int j = start; j < start + period; j++) {
                BigDecimal[] bar = base.get(j);
                if (bar[0].compareTo(high) > 0) high = bar[0];
                if (bar[1].compareTo(low) < 0) low = bar[1];
                volume = volume.add(bar[3]);
                if (bar.length > 4) takerBuyVol = takerBuyVol.add(bar[4]);
            }
            if (openTime != null && closeTime != null) {
                result.add(new BigDecimal[]{high, low, close, volume, takerBuyVol, openTime, closeTime});
            } else {
                result.add(new BigDecimal[]{high, low, close, volume, takerBuyVol});
            }
        }
        return result;
    }

    private static List<BigDecimal[]> aggregateByExchangeTime(List<BigDecimal[]> base, int period,
                                                              KlineInterval targetInterval) {
        List<BigDecimal[]> result = new ArrayList<>();
        long targetMs = targetInterval.getMinutes() * 60_000L;
        Bucket bucket = null;
        Long currentBucketStart = null;

        for (BigDecimal[] bar : base) {
            long openTime = bar[5].longValue();
            long bucketStart = Math.floorDiv(openTime, targetMs) * targetMs;
            if (currentBucketStart == null || bucketStart != currentBucketStart) {
                addFullBucket(result, bucket, period);
                bucket = new Bucket(bar);
                currentBucketStart = bucketStart;
                continue;
            }
            bucket.add(bar);
        }
        addFullBucket(result, bucket, period);
        return result;
    }

    private static boolean hasKlineTimestamps(List<BigDecimal[]> base) {
        return base != null && !base.isEmpty() && base.getFirst().length > 6
                && base.getFirst()[5] != null && base.getFirst()[6] != null;
    }

    private static void addFullBucket(List<BigDecimal[]> result, Bucket bucket, int period) {
        if (bucket != null && bucket.count == period) {
            result.add(bucket.toKline());
        }
    }

    private static final class Bucket {
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private BigDecimal volume;
        private BigDecimal takerBuyVol;
        private final BigDecimal openTime;
        private BigDecimal closeTime;
        private int count;

        private Bucket(BigDecimal[] first) {
            high = first[0];
            low = first[1];
            close = first[2];
            volume = first[3];
            takerBuyVol = first.length > 4 ? first[4] : BigDecimal.ZERO;
            openTime = first[5];
            closeTime = first[6];
            count = 1;
        }

        private void add(BigDecimal[] bar) {
            if (bar[0].compareTo(high) > 0) high = bar[0];
            if (bar[1].compareTo(low) < 0) low = bar[1];
            close = bar[2];
            volume = volume.add(bar[3]);
            if (bar.length > 4 && bar[4] != null) {
                takerBuyVol = takerBuyVol.add(bar[4]);
            }
            closeTime = bar[6];
            count++;
        }

        private BigDecimal[] toKline() {
            return new BigDecimal[]{high, low, close, volume, takerBuyVol, openTime, closeTime};
        }
    }

    // ==================== 构建 QuantForecastCycle ====================

    private QuantForecastCycle buildForecast(Map<String, Object> indPrimary,
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
        String regime = determineRegime(indPrimary);
        snap.put("regime", regime);
        snap.put("regimeTransition", null);

        // ATR
        BigDecimal atr = getBd(indPrimary, "atr14");
        snap.put("atr", atr);

        // BB squeeze
        boolean squeeze = isBollSqueeze(indPrimary);
        snap.put("bollSqueeze", squeeze);

        // 微结构（回测中无盘口数据，置null让执行器忽略）
        snap.put("bidAskImbalance", null);
        snap.put("takerBuySellPressure", null);
        snap.put("oiChangeRate", null);
        snap.put("fundingDeviation", null);
        snap.put("fundingRateExtreme", null);
        snap.put("lsrExtreme", null);

        // indicatorsByTimeframe
        JSONObject indicators = new JSONObject();

        JSONObject primaryTf = new JSONObject();
        primaryTf.put("rsi14", getBd(indPrimary, "rsi14"));
        primaryTf.put("macd_cross", getStr(indPrimary, "macd_cross"));
        primaryTf.put("macd_hist_trend", getStr(indPrimary, "macd_hist_trend"));
        primaryTf.put("macd_dif", getBd(indPrimary, "macd_dif"));
        primaryTf.put("macd_dea", getBd(indPrimary, "macd_dea"));
        primaryTf.put("ma_alignment", getInt(indPrimary, "ma_alignment"));
        primaryTf.put("close_trend", getStr(indPrimary, "close_trend"));
        primaryTf.put("close_trend_recent_3_closed", getStr(indPrimary, "close_trend_recent_3_closed"));
        primaryTf.put("close_series_closed", indPrimary.get("close_series_closed"));
        primaryTf.put("close_position_closed", getDbl(indPrimary, "close_position_closed"));
        primaryTf.put("range_atr_closed", getDbl(indPrimary, "range_atr_closed"));
        primaryTf.put("close_breakout_high_10_closed", indPrimary.get("close_breakout_high_10_closed"));
        primaryTf.put("close_breakdown_low_10_closed", indPrimary.get("close_breakdown_low_10_closed"));
        primaryTf.put("boll_pb", getDbl(indPrimary, "boll_pb"));
        primaryTf.put("boll_bandwidth", getDbl(indPrimary, "boll_bandwidth"));
        primaryTf.put("volume_ratio", getDbl(indPrimary, "volume_ratio"));
        primaryTf.put("volume_ratio_recent_5_closed", indPrimary.get("volume_ratio_recent_5_closed"));
        primaryTf.put("ema20", getBd(indPrimary, "ema20"));
        primaryTf.put("ma7", getBd(indPrimary, "ma7"));
        primaryTf.put("ma25", getBd(indPrimary, "ma25"));
        primaryTf.put("ma7_series_closed", indPrimary.get("ma7_series_closed"));
        primaryTf.put("ma25_series_closed", indPrimary.get("ma25_series_closed"));
        primaryTf.put("atr_series_closed", indPrimary.get("atr_series_closed"));
        primaryTf.put("adx", getBd(indPrimary, "adx"));
        primaryTf.put("plus_di", getBd(indPrimary, "plus_di"));
        primaryTf.put("minus_di", getBd(indPrimary, "minus_di"));
        primaryTf.put("atr14", getBd(indPrimary, "atr14"));
        primaryTf.put("atr14_closed", getBd(indPrimary, "atr14_closed"));
        primaryTf.put("atr_mean_30_closed", getBd(indPrimary, "atr_mean_30_closed"));
        primaryTf.put("atr_spike_ratio", getDbl(indPrimary, "atr_spike_ratio"));
        primaryTf.put("last_closed_bar_key", getStr(indPrimary, "last_closed_bar_key"));
        primaryTf.put("boll_expanding_5", indPrimary.get("boll_expanding_5"));
        indicators.put(baseInterval.getCode(), primaryTf);

        putAuxiliaryTimeframe(indicators, KlineInterval.M15.getCode(), ind15m);
        putAuxiliaryTimeframe(indicators, KlineInterval.H1.getCode(), ind1h);

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
    private List<QuantSignalDecision> deriveSignals(Map<String, Object> indPrimary,
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

        // 主周期 MACD交叉
        String macdCross = getStr(indPrimary, "macd_cross");
        if ("golden".equals(macdCross)) longVotes++;
        else if ("death".equals(macdCross)) shortVotes++;

        // 主周期 MACD柱状图趋势
        String histTrend = getStr(indPrimary, "macd_hist_trend");
        if (histTrend != null) {
            if (histTrend.startsWith("rising") || "mostly_up".equals(histTrend)) longVotes++;
            else if (histTrend.startsWith("falling") || "mostly_down".equals(histTrend)) shortVotes++;
        }

        // RSI偏向
        BigDecimal rsi = getBd(indPrimary, "rsi14");
        if (rsi != null) {
            double r = rsi.doubleValue();
            if (r < 40) longVotes++;
            else if (r > 60) shortVotes++;
        }

        // 成交量确认
        Double volRatio = getDbl(indPrimary, "volume_ratio");
        if (volRatio != null && volRatio >= 1.2) {
            // 放量时看close trend决定加给哪边
            String closeTrend = getStr(indPrimary, "close_trend");
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
        BigDecimal adx = getBd(indPrimary, "adx");
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

    private static void putAuxiliaryTimeframe(JSONObject indicators, String code, Map<String, Object> ind) {
        JSONObject tf = indicators.getJSONObject(code);
        if (tf == null) {
            tf = new JSONObject();
            indicators.put(code, tf);
        }
        tf.put("ma_alignment", getInt(ind, "ma_alignment"));
        tf.put("rsi14", getBd(ind, "rsi14"));
        tf.put("ma7", getBd(ind, "ma7"));
        tf.put("ma25", getBd(ind, "ma25"));
        tf.put("ma7_series_closed", ind.get("ma7_series_closed"));
        tf.put("ma25_series_closed", ind.get("ma25_series_closed"));
        tf.put("atr_series_closed", ind.get("atr_series_closed"));
        tf.put("atr14", getBd(ind, "atr14"));
        tf.put("atr14_closed", getBd(ind, "atr14_closed"));
        tf.put("last_closed_bar_key", getStr(ind, "last_closed_bar_key"));
        tf.put("adx", getBd(ind, "adx"));
        tf.put("plus_di", getBd(ind, "plus_di"));
        tf.put("minus_di", getBd(ind, "minus_di"));
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
            tools.closePositionWithReason(pos.getId(), pos.getQuantity(), "FORCE_CLOSE");
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
