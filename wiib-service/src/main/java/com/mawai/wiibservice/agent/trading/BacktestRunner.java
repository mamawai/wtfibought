package com.mawai.wiibservice.agent.trading;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import com.mawai.wiibservice.agent.tool.CryptoIndicatorCalculator;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.mapper.QuantForecastCycleMapper;
import com.mawai.wiibservice.mapper.QuantSignalDecisionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 回测执行服务 — 从 Binance 拉取历史K线并运行回测。
 * <p>
 * 提供便捷入口，隐藏数据加载和分页细节。
 *
 * <h3>用法</h3>
 * <pre>{@code
 * @Autowired BacktestRunner runner;
 * BacktestResult result = runner.runBacktest("BTCUSDT", 7, new BigDecimal("100000"));
 * System.out.println(result);
 * }</pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestRunner {

    private final BinanceRestClient binanceRestClient;
    private final QuantForecastCycleMapper forecastCycleMapper;
    private final QuantSignalDecisionMapper signalDecisionMapper;

    /** Binance klines API 每次最多1500根 */
    private static final int MAX_KLINES_PER_REQUEST = 1500;

    /**
     * 运行回测。
     *
     * @param symbol         交易对（如 "BTCUSDT"）
     * @param days           回测天数
     * @param initialBalance 初始资金
     * @return 回测结果
     */
    public BacktestResult runBacktest(String symbol, int days, BigDecimal initialBalance) {
        return runBacktest(symbol, days, initialBalance, null);
    }

    /**
     * 运行回测（可覆盖参数）。
     *
     * @param symbol         交易对
     * @param days           回测天数
     * @param initialBalance 初始资金
     * @param profileOverride 覆盖的 SymbolProfile（用于参数扫描），null则使用默认
     * @return 回测结果
     */
    public BacktestResult runBacktest(String symbol, int days, BigDecimal initialBalance,
                                       SymbolProfile profileOverride) {
        log.info("[BacktestRunner] 开始加载 {} 历史K线，{}天", symbol, days);

        List<BigDecimal[]> klines = loadHistoricalKlines(symbol, days);
        log.info("[BacktestRunner] 加载完成，共{}根5m K线", klines.size());

        if (klines.size() < 200) {
            throw new IllegalStateException("K线数据不足: " + klines.size() + " (需要至少200根)");
        }

        BacktestEngine engine = new BacktestEngine(symbol, klines, initialBalance);
        if (profileOverride != null) {
            engine.withProfile(profileOverride);
        }

        return engine.run();
    }

    /**
     * 同一份 K 线历史数据分别跑 legacy / playbook exit，用于灰度前识别系统性早平、晚出。
     */
    public BacktestComparisonResult compareBacktest(String symbol, int days, BigDecimal initialBalance) {
        return compareBacktest(symbol, days, initialBalance, null);
    }

    public BacktestComparisonResult compareBacktest(String symbol, int days, BigDecimal initialBalance,
                                                    SymbolProfile profileOverride) {
        log.info("[BacktestCompare] 开始加载 {} 历史K线，{}天", symbol, days);

        List<BigDecimal[]> klines = loadHistoricalKlines(symbol, days);
        if (klines.size() < 200) {
            throw new IllegalStateException("K线数据不足: " + klines.size() + " (需要至少200根)");
        }

        BacktestEngine legacyEngine = new BacktestEngine(symbol, klines, initialBalance);
        BacktestEngine playbookEngine = new BacktestEngine(symbol, klines, initialBalance);
        if (profileOverride != null) {
            legacyEngine.withProfile(profileOverride);
            playbookEngine.withProfile(profileOverride);
        }

        BacktestResult legacy = legacyEngine.run(legacyToggles());
        BacktestResult playbook = playbookEngine.run(playbookToggles());
        return BacktestComparisonResult.compare(symbol, "KLINE", legacy, playbook);
    }

    /**
     * 参数扫描：对指定参数组合批量回测。
     */
    public List<ScanResult> parameterScan(String symbol, int days, BigDecimal initialBalance) {
        log.info("[BacktestRunner] 开始参数扫描 {} {}天", symbol, days);

        List<BigDecimal[]> klines = loadHistoricalKlines(symbol, days);
        log.info("[BacktestRunner] K线加载完成，共{}根", klines.size());

        // SL/TP ATR倍数网格
        double[] slAtrValues = {1.0, 1.5, 2.0, 2.5};
        double[] tpAtrValues = {2.0, 3.0, 4.0, 5.0};

        List<ScanResult> results = new ArrayList<>();

        for (double sl : slAtrValues) {
            for (double tp : tpAtrValues) {
                if (tp <= sl) continue; // TP必须大于SL

                SymbolProfile profile = new SymbolProfile(
                        sl, tp,     // trend SL/TP
                        sl, sl, tp, // revert SL/TPmin/TPmax
                        sl * 1.3, tp, // breakout SL/TP (略宽)
                        sl * 0.67, sl * 1.33, // trail breakeven/lock
                        0.005, 0.10, // SL min/max pct
                        sl * 1.0, sl * 0.53 // partialTpAtr(≈1×SL)、trailGapAtr(≈0.8/1.5×SL)
                );

                try {
                    BacktestEngine engine = new BacktestEngine(symbol, klines, initialBalance)
                            .withProfile(profile);
                    // 参数扫描的变量是入场 SL/TP ATR；Playbook 会用 hard TP 覆盖趋势/突破 TP，固定走 legacy 语义。
                    BacktestResult result = engine.run(legacyToggles());

                    results.add(new ScanResult(sl, tp, result));
                    log.info("[Scan] SL={} TP={} | trades={} winRate={}% PF={} return={}%",
                            sl, tp, result.totalTrades(),
                            String.format("%.1f", result.winRate() * 100),
                            String.format("%.2f", result.profitFactor()),
                            String.format("%.2f", result.returnPct() * 100));
                } catch (Exception e) {
                    log.warn("[Scan] SL={} TP={} 失败: {}", sl, tp, e.getMessage());
                }
            }
        }

        // 按收益率排序
        results.sort((a, b) -> Double.compare(b.result().returnPct(), a.result().returnPct()));
        return results;
    }

    public record ScanResult(double slAtr, double tpAtr, BacktestResult result) {
        @Override
        public @NonNull String toString() {
            return String.format("SL=%.1f TP=%.1f | return=%.2f%% WR=%.1f%% PF=%.2f trades=%d",
                    slAtr, tpAtr, result.returnPct() * 100, result.winRate() * 100,
                    result.profitFactor(), result.totalTrades());
        }
    }

    // ==================== 信号回放回测（真票回放）====================

    /**
     * 基于数据库中已持久化的历史信号回放。和实盘100%等价。
     *
     * @param symbol         交易对
     * @param from           起始时间（含）
     * @param to             结束时间（不含）
     * @param initialBalance 初始资金
     */
    public BacktestResult runReplay(String symbol, LocalDateTime from, LocalDateTime to,
                                    BigDecimal initialBalance) {
        log.info("[Replay] 加载 {} 历史cycles [{} ~ {}]", symbol, from, to);

        List<QuantForecastCycle> cycles =
                forecastCycleMapper.selectBySymbolAndTimeRange(symbol, from, to);
        if (cycles.isEmpty()) {
            throw new IllegalStateException("该时间段内无历史cycle记录: " + symbol);
        }

        List<String> cycleIds = cycles.stream().map(QuantForecastCycle::getCycleId).toList();
        LambdaQueryWrapper<QuantSignalDecision> q = new LambdaQueryWrapper<>();
        q.in(QuantSignalDecision::getCycleId, cycleIds);
        List<QuantSignalDecision> allSignals = signalDecisionMapper.selectList(q);

        Map<String, List<QuantSignalDecision>> signalsByCycle = allSignals.stream()
                .collect(Collectors.groupingBy(QuantSignalDecision::getCycleId));

        log.info("[Replay] 加载完成 cycle={} signal={}", cycles.size(), allSignals.size());

        SignalReplayBacktestEngine engine = new SignalReplayBacktestEngine(
                symbol, cycles, signalsByCycle, initialBalance);
        return engine.run();
    }

    /**
     * 基于线上真实历史信号的新旧退出引擎对比；优先用于正式灰度前验收。
     */
    public BacktestComparisonResult compareReplay(String symbol, LocalDateTime from, LocalDateTime to,
                                                  BigDecimal initialBalance) {
        log.info("[ReplayCompare] 加载 {} 历史cycles [{} ~ {}]", symbol, from, to);

        List<QuantForecastCycle> cycles =
                forecastCycleMapper.selectBySymbolAndTimeRange(symbol, from, to);
        if (cycles.isEmpty()) {
            throw new IllegalStateException("该时间段内无历史cycle记录: " + symbol);
        }

        List<String> cycleIds = cycles.stream().map(QuantForecastCycle::getCycleId).toList();
        LambdaQueryWrapper<QuantSignalDecision> q = new LambdaQueryWrapper<>();
        q.in(QuantSignalDecision::getCycleId, cycleIds);
        List<QuantSignalDecision> allSignals = signalDecisionMapper.selectList(q);

        Map<String, List<QuantSignalDecision>> signalsByCycle = allSignals.stream()
                .collect(Collectors.groupingBy(QuantSignalDecision::getCycleId));

        BacktestResult legacy = new SignalReplayBacktestEngine(
                symbol, cycles, signalsByCycle, initialBalance).run(legacyToggles());
        BacktestResult playbook = new SignalReplayBacktestEngine(
                symbol, cycles, signalsByCycle, initialBalance).run(playbookToggles());
        return BacktestComparisonResult.compare(symbol, "REPLAY", legacy, playbook);
    }

    private TradingRuntimeToggles legacyToggles() {
        return new TradingRuntimeToggles(DeterministicTradingExecutor.LOW_VOL_TRADING_ENABLED, false);
    }

    private TradingRuntimeToggles playbookToggles() {
        return new TradingRuntimeToggles(DeterministicTradingExecutor.LOW_VOL_TRADING_ENABLED, true);
    }

    // ==================== 数据加载 ====================

    /**
     * 从 Binance 分页加载历史5m K线。
     * 5m K线: 每天288根，每次最多1500根(约5.2天)。
     */
    private List<BigDecimal[]> loadHistoricalKlines(String symbol, int days) {
        int totalBars = days * 288; // 每天288根5m K线
        List<BigDecimal[]> allKlines = new ArrayList<>(totalBars);

        Long endTime = null; // null = 从当前时间开始向前加载
        int remaining = totalBars;

        while (remaining > 0) {
            int batch = Math.min(remaining, MAX_KLINES_PER_REQUEST);
            String json = binanceRestClient.getFuturesKlines(symbol, "5m", batch, endTime);
            List<BigDecimal[]> parsed = CryptoIndicatorCalculator.parseKlines(json);

            if (parsed.isEmpty()) break;

            allKlines.addAll(0, parsed); // 前插保持时间顺序

            // 下一批：用第一根K线的时间作为endTime
            // Binance klines JSON: [[openTime, open, high, low, close, volume, closeTime, ...], ...]
            // parseKlines只取了 [high, low, close, volume, takerBuy]
            // 需要从原始JSON中提取openTime来做分页
            endTime = extractFirstOpenTime(json);
            if (endTime == null) break;
            endTime -= 1; // 避免重复

            remaining -= parsed.size();

            log.debug("[BacktestRunner] 加载中... 已获取{}/{}根K线", allKlines.size(), totalBars);
        }

        return allKlines;
    }

    /**
     * 从 Binance klines JSON 中提取第一根K线的 openTime。
     */
    private Long extractFirstOpenTime(String json) {
        try {
            com.alibaba.fastjson2.JSONArray arr = com.alibaba.fastjson2.JSON.parseArray(json);
            if (arr.isEmpty()) return null;
            return arr.getJSONArray(0).getLong(0);
        } catch (Exception e) {
            log.warn("[BacktestRunner] 解析K线时间戳失败", e);
            return null;
        }
    }
}
