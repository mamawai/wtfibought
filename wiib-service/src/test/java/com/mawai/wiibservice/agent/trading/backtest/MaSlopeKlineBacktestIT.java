package com.mawai.wiibservice.agent.trading.backtest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibservice.agent.tool.CryptoIndicatorCalculator;
import com.mawai.wiibservice.config.BinanceProperties;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.config.CoinDeskProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.PATH_MA_SLOPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * MaSlope 独立 K 线通道手动回测入口。
 *
 * <p>默认禁用。运行示例：
 * mvn -pl wiib-service -Dtest=MaSlopeKlineBacktestIT -Dmaslope.kline.backtest=true
 * -Dmaslope.days=60 -Dmaslope.interval=3m -Dmaslope.initialBalance=1000
 * -Dmaslope.margin=400 -Dmaslope.leverage=50 test</p>
 */
@EnabledIfSystemProperty(named = "maslope.kline.backtest", matches = "true")
class MaSlopeKlineBacktestIT {

    private static final Path REPORT_DIR = Path.of("target");
    private static final int MAX_KLINES_PER_REQUEST = 1500;

    @Test
    void runKlineOnlyBacktestAndWriteReports() {
        int days = Integer.parseInt(System.getProperty("maslope.days", "60"));
        DateRange range = dateRange();
        int reportDays = range != null ? Math.toIntExact(ChronoUnit.DAYS.between(range.from(), range.to())) : days;
        KlineInterval interval = decisionInterval();
        BigDecimal initialBalance = new BigDecimal(System.getProperty("maslope.initialBalance", "1000"));
        BigDecimal margin = new BigDecimal(System.getProperty("maslope.margin", "400"));
        int leverage = Integer.parseInt(System.getProperty("maslope.leverage", "50"));
        BinanceRestClient client = binanceClient();

        List<Path> reports = symbols().parallelStream()
                .map(symbol -> runOne(client, symbol, interval, reportDays, range, initialBalance, margin, leverage))
                .toList();

        assertThat(reports).hasSize(symbols().size());
        reports.forEach(path -> assertThat(path).exists());
    }

    private Path runOne(BinanceRestClient client,
                        String symbol,
                        KlineInterval interval,
                        int days,
                        DateRange range,
                        BigDecimal initialBalance,
                        BigDecimal margin,
                        int leverage) {
        List<BigDecimal[]> klines = range != null
                ? loadHistoricalKlines(client, symbol, range, interval)
                : loadHistoricalKlines(client, symbol, days, interval);
        MaSlopeKlineBacktestEngine engine = new MaSlopeKlineBacktestEngine(
                symbol, klines, interval, initialBalance, margin, leverage,
                range != null ? range.fromMs() : null,
                range != null ? range.toMs() : null);
        BacktestResult result = engine.run();
        try {
            return writeReport(symbol, days, range, interval, initialBalance, margin, leverage, result);
        } catch (IOException e) {
            throw new IllegalStateException("写回测报告失败: " + symbol, e);
        }
    }

    private List<BigDecimal[]> loadHistoricalKlines(BinanceRestClient client,
                                                   String symbol,
                                                   DateRange range,
                                                   KlineInterval interval) {
        int barsPerDay = 24 * 60 / interval.getMinutes();
        int rangeBars = Math.toIntExact(ChronoUnit.DAYS.between(range.from(), range.to())) * barsPerDay;
        int warmupDays = Integer.parseInt(System.getProperty("maslope.warmupDays", "5"));
        int totalBars = rangeBars + warmupDays * barsPerDay;
        List<BigDecimal[]> allKlines = new ArrayList<>(totalBars);
        Long endTime = range.toMs() - 1;
        int remaining = totalBars;

        while (remaining > 0) {
            int batch = Math.min(remaining, MAX_KLINES_PER_REQUEST);
            String json = client.getFuturesKlines(symbol, interval.getCode(), batch, endTime);
            List<BigDecimal[]> parsed = CryptoIndicatorCalculator.parseKlines(json);
            if (parsed.isEmpty()) {
                break;
            }
            allKlines.addAll(0, parsed);
            endTime = extractFirstOpenTime(json);
            if (endTime == null) {
                break;
            }
            endTime -= 1;
            remaining -= parsed.size();
        }
        return allKlines;
    }

    private List<BigDecimal[]> loadHistoricalKlines(BinanceRestClient client,
                                                   String symbol,
                                                   int days,
                                                   KlineInterval interval) {
        int totalBars = days * (24 * 60 / interval.getMinutes());
        List<BigDecimal[]> allKlines = new ArrayList<>(totalBars);
        Long endTime = null;
        int remaining = totalBars;

        while (remaining > 0) {
            int batch = Math.min(remaining, MAX_KLINES_PER_REQUEST);
            String json = client.getFuturesKlines(symbol, interval.getCode(), batch, endTime);
            List<BigDecimal[]> parsed = CryptoIndicatorCalculator.parseKlines(json);
            if (parsed.isEmpty()) {
                break;
            }
            allKlines.addAll(0, parsed);
            endTime = extractFirstOpenTime(json);
            if (endTime == null) {
                break;
            }
            endTime -= 1;
            remaining -= parsed.size();
        }
        return allKlines;
    }

    private Path writeReport(String symbol,
                             int days,
                             DateRange range,
                             KlineInterval interval,
                             BigDecimal initialBalance,
                             BigDecimal margin,
                             int leverage,
                             BacktestResult result) throws IOException {
        Files.createDirectories(REPORT_DIR);
        String window = range != null
                ? range.from() + "_to_" + range.to()
                : days + "d";
        Path reportFile = REPORT_DIR.resolve("kline-maslope-backtest-report-" + symbol + "-"
                + window + "-" + interval.getCode() + "-" + leverage + "x-"
                + margin.stripTrailingZeros().toPlainString() + "u.json");
        Files.writeString(reportFile, JSON.toJSONString(
                report(symbol, days, range, interval, initialBalance, margin, leverage, result),
                JSONWriter.Feature.PrettyFormat));
        return reportFile;
    }

    private JSONObject report(String symbol,
                              int days,
                              DateRange range,
                              KlineInterval interval,
                              BigDecimal initialBalance,
                              BigDecimal margin,
                              int leverage,
                              BacktestResult result) {
        JSONObject json = new JSONObject();
        json.put("mode", "KLINE_ONLY_MASLOPE");
        json.put("symbol", symbol);
        json.put("days", days);
        if (range != null) {
            json.put("from", range.from().toString());
            json.put("to", range.to().toString());
        }
        json.put("decisionInterval", interval.getCode());
        json.put("initialBalance", initialBalance);
        json.put("marginPerPosition", margin);
        json.put("leverage", leverage);
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
        json.put("positions", positions(result.getTrades()));
        json.put("trades", trades(result.getTrades()));
        json.put("notes", List.of("纯K线MaSlope：不构造forecast/referenceSignal/riskStatus/overallDecision。"));
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
            JSONObject row = tradeJson(trade);
            rows.add(row);
        }
        return rows;
    }

    private JSONArray positions(List<BacktestResult.Trade> trades) {
        Map<String, List<BacktestResult.Trade>> byPosition = new LinkedHashMap<>();
        for (BacktestResult.Trade trade : trades) {
            String key = trade.openTime() + "|" + trade.side() + "|" + trade.entryPrice() + "|" + trade.leverage();
            byPosition.computeIfAbsent(key, ignored -> new ArrayList<>()).add(trade);
        }
        JSONArray rows = new JSONArray();
        for (List<BacktestResult.Trade> group : byPosition.values()) {
            BacktestResult.Trade first = group.getFirst();
            JSONObject row = tradeJson(first);
            BigDecimal pnl = group.stream().map(BacktestResult.Trade::pnl).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal fee = group.stream().map(BacktestResult.Trade::fee).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal quantity = group.stream().map(BacktestResult.Trade::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal margin = first.entryPrice().multiply(quantity)
                    .divide(BigDecimal.valueOf(first.leverage()), 2, RoundingMode.HALF_UP);
            row.put("quantity", quantity);
            row.put("margin", margin);
            row.put("pnl", pnl);
            row.put("fee", fee);
            row.put("exitReason", group.stream().map(BacktestResult.Trade::exitReason).distinct().toList());
            row.put("closeTime", group.getLast().closeTime() != null ? group.getLast().closeTime().toString() : null);
            rows.add(row);
        }
        return rows;
    }

    private JSONObject tradeJson(BacktestResult.Trade trade) {
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
        row.put("entryMode", trade.entryMode());
        row.put("failScoreAtExit", trade.failScoreAtExit());
        row.put("maxFavorableR", trade.maxFavorableR());
        row.put("maxAdverseR", trade.maxAdverseR());
        row.put("wasLateContinuation", trade.wasLateContinuation());
        if (trade.entryDiagnosticsJson() != null && !trade.entryDiagnosticsJson().isBlank()) {
            row.put("entryDiagnostics", JSON.parseObject(trade.entryDiagnosticsJson()));
        }
        return row;
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

    private KlineInterval decisionInterval() {
        String raw = System.getProperty("maslope.interval", KlineInterval.M3.getCode()).trim();
        for (KlineInterval interval : KlineInterval.values()) {
            if (interval.getCode().equalsIgnoreCase(raw) || interval.name().equalsIgnoreCase(raw)) {
                return interval;
            }
        }
        throw new IllegalArgumentException("Unsupported maslope.interval=" + raw);
    }

    private DateRange dateRange() {
        String fromRaw = System.getProperty("maslope.from");
        String toRaw = System.getProperty("maslope.to");
        if (fromRaw == null || fromRaw.isBlank() || toRaw == null || toRaw.isBlank()) {
            return null;
        }
        LocalDate from = LocalDate.parse(fromRaw.trim());
        LocalDate to = LocalDate.parse(toRaw.trim());
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("maslope.to must be after maslope.from");
        }
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        return new DateRange(from, to,
                from.atStartOfDay(zone).toInstant().toEpochMilli(),
                to.atStartOfDay(zone).toInstant().toEpochMilli());
    }

    private Long extractFirstOpenTime(String json) {
        try {
            JSONArray arr = JSON.parseArray(json);
            if (arr.isEmpty()) return null;
            return arr.getJSONArray(0).getLong(0);
        } catch (Exception e) {
            return null;
        }
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

    private record DateRange(LocalDate from, LocalDate to, long fromMs, long toMs) {
    }
}
