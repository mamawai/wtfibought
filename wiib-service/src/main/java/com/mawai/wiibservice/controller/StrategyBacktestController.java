package com.mawai.wiibservice.controller;

import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import com.mawai.wiibservice.agent.strategy.backtest.StrategyKlineBacktestEngine;
import com.mawai.wiibservice.agent.strategy.core.WindowedMarketView;
import com.mawai.wiibservice.agent.strategy.fibo.FiboParams;
import com.mawai.wiibservice.agent.strategy.fibo.FiboRetracementStrategy;
import com.mawai.wiibservice.agent.trading.backtest.BacktestResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 插拔式策略回测 API；数据只读本地 kline_history，不触发外部行情下载。 */
@Slf4j
@Tag(name = "插拔式策略回测")
@RestController
@RequestMapping("/api/strategy-backtest")
@RequiredArgsConstructor
public class StrategyBacktestController {

    private static final String FIBO_ID = "FIBO";

    private final KlineHistoryStore klineHistoryStore;

    @Operation(summary = "Fibo 黄金口袋纯价格策略回测")
    @PostMapping("/fibo")
    public Result<Map<String, Object>> runFibo(
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam(defaultValue = "730") int days,
            @RequestParam(defaultValue = "100000") BigDecimal initialBalance,
            @RequestParam(defaultValue = "5") int leverage) {

        try {
            String normalized = QuantConstants.normalizeSymbol(symbol);
            if (!QuantConstants.WATCH_SYMBOLS.contains(normalized)) {
                return Result.fail("fibo v1 仅支持 BTCUSDT/ETHUSDT");
            }
            if (days <= 0 || leverage <= 0) {
                return Result.fail("days/leverage 必须为正数");
            }

            Long latestCloseTime = klineHistoryStore.latestCloseTime(normalized, KlineHistoryStore.DEFAULT_INTERVAL);
            if (latestCloseTime == null) {
                return Result.fail("本地 kline_history 没有最新 5m K线: " + normalized);
            }
            long toMs = latestCloseTime;
            long tradeWindowMs = (long) days * 86_400_000L;
            long tradingStartMs = toMs - tradeWindowMs;
            FiboParams params = FiboParams.defaults();
            long warmupMs = warmupMs(params);
            long fromMs = tradingStartMs - warmupMs;

            List<KlineBar> bars = klineHistoryStore.load(
                    normalized, KlineHistoryStore.DEFAULT_INTERVAL, fromMs, toMs);
            if (bars.isEmpty()) {
                return Result.fail("本地 kline_history 没有可用 5m K线: " + normalized);
            }
            String gap = WindowedMarketView.firstBaseGapDescription(bars).orElse(null);
            if (gap != null) {
                return Result.fail("本地 kline_history 5m K线不连续: " + normalized + " " + gap);
            }
            int warmupBars = (int) bars.stream()
                    .filter(b -> b.closeTime() < tradingStartMs)
                    .count();

            log.info("[StrategyBacktest] fibo symbol={} days={} bars={} warmupBars={} balance={} leverage={}",
                    normalized, days, bars.size(), warmupBars, initialBalance, leverage);

            FiboRetracementStrategy strategy = new FiboRetracementStrategy(params, List.of(normalized));
            BacktestResult result = new StrategyKlineBacktestEngine(
                    strategy, normalized, bars, initialBalance, leverage, warmupBars, tradingStartMs, null)
                    .run();

            return Result.ok(toResponse(normalized, days, leverage, fromMs, tradingStartMs, toMs,
                    bars.size(), warmupBars, result));
        } catch (Exception e) {
            log.error("[StrategyBacktest] fibo 回测失败", e);
            return Result.fail("fibo 回测失败: " + e.getMessage());
        }
    }

    /** 预热毫秒：覆盖找腿窗 + ATR 预热。 */
    private static long warmupMs(FiboParams p) {
        return (long) (p.swingLookbackBars() + p.atrPeriod() + 16) * p.swingTfMillis();
    }

    private Map<String, Object> toResponse(String symbol, int days, int leverage,
                                           long fromMs, long tradingStartMs, long latestCloseTime,
                                           int bars, int warmupBars, BacktestResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategy", FIBO_ID);
        out.put("symbol", symbol);
        out.put("days", days);
        out.put("leverage", leverage);
        out.put("fromMs", fromMs);
        out.put("tradingStartMs", tradingStartMs);
        out.put("latestCloseTime", latestCloseTime);
        out.put("bars", bars);
        out.put("warmupBars", warmupBars);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalTrades", r.totalTrades());
        summary.put("wins", r.wins());
        summary.put("losses", r.losses());
        summary.put("winRate", r.winRate());
        summary.put("profitFactor", r.profitFactor());
        summary.put("netProfit", r.netProfit());
        summary.put("totalFees", r.totalFees());
        summary.put("sharpeRatio", r.sharpeRatio());
        summary.put("maxDrawdownPct", r.maxDrawdownPct());
        summary.put("avgHoldBars", r.avgHoldBars());
        summary.put("avgR", r.avgR());
        summary.put("returnPct", r.returnPct());
        summary.put("finalEquity", r.finalEquity());
        out.put("summary", summary);

        out.put("trades", r.getTrades().stream().limit(200).map(this::tradeRow).toList());
        return out;
    }

    private Map<String, Object> tradeRow(BacktestResult.Trade t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("openTime", t.openTime());
        row.put("closeTime", t.closeTime());
        row.put("side", t.side());
        row.put("entryPrice", t.entryPrice());
        row.put("exitPrice", t.exitPrice());
        row.put("quantity", t.quantity());
        row.put("leverage", t.leverage());
        row.put("pnl", t.pnl());
        row.put("fee", t.fee());
        row.put("rMultiple", t.rMultiple());
        row.put("exitReason", t.exitReason());
        return row;
    }
}
