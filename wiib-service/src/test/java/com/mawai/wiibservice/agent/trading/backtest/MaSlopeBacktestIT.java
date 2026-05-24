package com.mawai.wiibservice.agent.trading.backtest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibservice.agent.trading.DeterministicTradingExecutor;
import com.mawai.wiibservice.agent.trading.entry.EntryDecisionEngine;
import com.mawai.wiibservice.agent.trading.entry.strategy.MaSlopeEntryStrategy;
import com.mawai.wiibservice.config.BinanceProperties;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.config.CoinDeskProperties;
import com.mawai.wiibservice.config.TradingConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.PATH_MA_SLOPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地手动回测入口。
 *
 * <p>默认禁用，避免普通测试/CI 访问 Binance。需要本地验证时运行：
 * mvn -pl wiib-service -Dtest=MaSlopeBacktestIT -Dmaslope.backtest=true test</p>
 */
@EnabledIfSystemProperty(named = "maslope.backtest", matches = "true")
class MaSlopeBacktestIT {

    private static final Path REPORT_DIR = Path.of("target");

    @Test
    void runMaSlopeBacktestAndWriteReports() throws IOException {
        List<String> oldPaths = EntryDecisionEngine.enabledStrategyPaths();
        Set<String> oldSymbols = MaSlopeEntryStrategy.enabledSymbols();
        boolean oldPlaybookExitEnabled = DeterministicTradingExecutor.PLAYBOOK_EXIT_ENABLED;

        try {
            EntryDecisionEngine.setEnabledStrategyPaths(List.of(PATH_MA_SLOPE));
            MaSlopeEntryStrategy.setEnabledSymbols(List.of("BTCUSDT", "ETHUSDT"));
            DeterministicTradingExecutor.PLAYBOOK_EXIT_ENABLED = true;

            BacktestRunner runner = new BacktestRunner(binanceClient(), null, null, tradingConfig());
            int days = Integer.parseInt(System.getProperty("maslope.days", "30"));
            BigDecimal initialBalance = new BigDecimal(System.getProperty("maslope.initialBalance", "1000"));

            for (String symbol : symbols()) {
                BacktestResult result = runner.runBacktest(symbol, days, initialBalance);
                Path reportFile = writeReport(symbol, days, tradingConfig().getDecisionInterval(), initialBalance, result);
                assertThat(reportFile).exists();
            }
        } finally {
            EntryDecisionEngine.setEnabledStrategyPaths(oldPaths);
            MaSlopeEntryStrategy.setEnabledSymbols(oldSymbols);
            DeterministicTradingExecutor.PLAYBOOK_EXIT_ENABLED = oldPlaybookExitEnabled;
        }
    }

    private Path writeReport(String symbol, int days, KlineInterval interval,
                             BigDecimal initialBalance, BacktestResult result)
            throws IOException {
        Files.createDirectories(REPORT_DIR);
        Path reportFile = REPORT_DIR.resolve("backtest-report-" + symbol + "-" + days
                + "d-" + interval.getCode() + ".json");
        JSONObject report = report(symbol, days, initialBalance, result);
        Files.writeString(reportFile, JSON.toJSONString(report, JSONWriter.Feature.PrettyFormat));
        return reportFile;
    }

    private JSONObject report(String symbol, int days, BigDecimal initialBalance, BacktestResult result) {
        JSONObject json = new JSONObject();
        json.put("symbol", symbol);
        json.put("days", days);
        json.put("decisionInterval", tradingConfig().getDecisionInterval().getCode());
        json.put("initialBalance", initialBalance);
        json.put("finalEquity", result.finalEquity());
        json.put("returnPct", pct(result.returnPct()));
        json.put("totalTrades", result.totalTrades());
        json.put("wins", result.wins());
        json.put("losses", result.losses());
        json.put("winRatePct", pct(result.winRate()));
        json.put("profitFactor", finiteDouble(result.profitFactor()));
        json.put("avgR", round(result.avgR()));
        json.put("maxDrawdownPct", pct(result.maxDrawdownPct()));
        json.put("totalFees", result.totalFees());
        json.put("netProfit", result.netProfit());
        json.put("avgHoldBars", round(result.avgHoldBars()));
        json.put("maxConsecutiveLosses", maxConsecutiveLosses(result.getTrades()));
        json.put("strategyStats", strategyStats(result));
        json.put("exitReasonStats", exitReasonStats(result.getTrades()));
        json.put("equityCurve", result.getEquityCurve());
        json.put("trades", trades(result.getTrades()));
        json.put("notes", List.of("Playbook 主动平仓会按具体 reason 归类；硬止损/止盈分别为 SL/TP。"));
        return json;
    }

    private JSONArray strategyStats(BacktestResult result) {
        JSONArray rows = new JSONArray();
        for (BacktestResult.StrategyStats stat : result.allStrategyStats()) {
            if (!PATH_MA_SLOPE.equals(stat.strategy()) && stat.trades() == 0) {
                continue;
            }
            JSONObject row = new JSONObject();
            row.put("strategy", stat.strategy());
            row.put("trades", stat.trades());
            row.put("wins", stat.wins());
            row.put("losses", stat.losses());
            row.put("winRatePct", pct(stat.winRate()));
            row.put("netPnl", stat.netPnl());
            row.put("ev", stat.ev());
            row.put("avgR", round(stat.avgR()));
            row.put("profitFactor", finiteDouble(stat.profitFactor()));
            row.put("maxDrawdownPct", pct(stat.maxDrawdownPct()));
            row.put("avgHoldBars", round(stat.avgHoldBars()));
            row.put("maxConsecutiveLosses", stat.maxConsecutiveLosses());
            rows.add(row);
        }
        return rows;
    }

    private JSONArray exitReasonStats(List<BacktestResult.Trade> trades) {
        Map<String, List<BacktestResult.Trade>> byReason = new LinkedHashMap<>();
        for (BacktestResult.Trade trade : trades) {
            String reason = trade.exitReason() != null ? trade.exitReason() : "UNKNOWN";
            byReason.computeIfAbsent(reason, ignored -> new ArrayList<>()).add(trade);
        }

        JSONArray rows = new JSONArray();
        for (Map.Entry<String, List<BacktestResult.Trade>> entry : byReason.entrySet()) {
            List<BacktestResult.Trade> rowsForReason = entry.getValue();
            BigDecimal pnl = rowsForReason.stream().map(BacktestResult.Trade::pnl)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            double avgR = rowsForReason.stream()
                    .map(BacktestResult.Trade::rMultiple)
                    .filter(v -> v != null)
                    .mapToDouble(BigDecimal::doubleValue)
                    .average()
                    .orElse(0.0);

            JSONObject row = new JSONObject();
            row.put("exitReason", entry.getKey());
            row.put("count", rowsForReason.size());
            row.put("pct", trades.isEmpty() ? BigDecimal.ZERO
                    : pct((double) rowsForReason.size() / trades.size()));
            row.put("avgR", round(avgR));
            row.put("netPnl", pnl);
            rows.add(row);
        }
        return rows;
    }

    private JSONArray trades(List<BacktestResult.Trade> trades) {
        JSONArray rows = new JSONArray();
        for (BacktestResult.Trade trade : trades) {
            JSONObject row = new JSONObject();
            row.put("openBar", trade.barIndex());
            row.put("closeBar", trade.closeBarIndex());
            row.put("openTime", trade.openTime() != null ? trade.openTime().toString() : null);
            row.put("closeTime", trade.closeTime() != null ? trade.closeTime().toString() : null);
            row.put("side", trade.side());
            row.put("strategy", trade.strategy());
            row.put("entryPrice", trade.entryPrice());
            row.put("exitPrice", trade.exitPrice());
            row.put("quantity", trade.quantity());
            row.put("leverage", trade.leverage());
            row.put("pnl", trade.pnl());
            row.put("fee", trade.fee());
            row.put("rMultiple", trade.rMultiple());
            row.put("exitReason", trade.exitReason());
            if (trade.entryDiagnosticsJson() != null && !trade.entryDiagnosticsJson().isBlank()) {
                row.put("entryDiagnostics", JSON.parseObject(trade.entryDiagnosticsJson()));
            }
            rows.add(row);
        }
        return rows;
    }

    private int maxConsecutiveLosses(List<BacktestResult.Trade> trades) {
        int current = 0;
        int max = 0;
        for (BacktestResult.Trade trade : trades) {
            if (trade.pnl().signum() < 0) {
                current++;
                max = Math.max(max, current);
            } else {
                current = 0;
            }
        }
        return max;
    }

    private List<String> symbols() {
        String raw = System.getProperty("maslope.symbols", "BTCUSDT,ETHUSDT");
        List<String> result = new ArrayList<>();
        for (String symbol : raw.split(",")) {
            String normalized = symbol.trim().toUpperCase(Locale.ROOT);
            if (!normalized.isBlank()) {
                result.add(normalized);
            }
        }
        return result.isEmpty() ? List.of("BTCUSDT", "ETHUSDT") : result;
    }

    private BinanceRestClient binanceClient() {
        BinanceProperties binance = new BinanceProperties();
        binance.setRestBaseUrl("https://api.binance.com");
        binance.setFuturesRestBaseUrl("https://fapi.binance.com");
        return new BinanceRestClient(binance, new CoinDeskProperties());
    }

    private TradingConfig tradingConfig() {
        TradingConfig config = new TradingConfig();
        config.setDecisionInterval(decisionInterval());
        return config;
    }

    private KlineInterval decisionInterval() {
        String raw = System.getProperty("maslope.interval", KlineInterval.M5.getCode()).trim();
        for (KlineInterval interval : KlineInterval.values()) {
            if (interval.getCode().equalsIgnoreCase(raw) || interval.name().equalsIgnoreCase(raw)) {
                return interval;
            }
        }
        throw new IllegalArgumentException("Unsupported maslope.interval=" + raw);
    }

    private BigDecimal pct(double ratio) {
        return BigDecimal.valueOf(ratio * 100).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private Object finiteDouble(double value) {
        if (Double.isInfinite(value) || value == Double.MAX_VALUE) {
            return "INF";
        }
        if (Double.isNaN(value)) {
            return null;
        }
        return round(value);
    }
}
