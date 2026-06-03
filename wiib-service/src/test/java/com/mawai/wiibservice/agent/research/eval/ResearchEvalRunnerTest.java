package com.mawai.wiibservice.agent.research.eval;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.forecast.EwmaMomentumForecaster;
import com.mawai.wiibservice.agent.research.forecast.Forecaster;
import com.mawai.wiibservice.agent.research.forecast.MultiFactorForecaster;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.series.MarketSeriesPoint;
import com.mawai.wiibservice.agent.research.series.MarketSeriesStore;
import com.mawai.wiibservice.agent.research.series.SeriesCode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * research eval 本地 runner：不启动 Spring，直接从本地 DB 读历史数据，调用纯核心 evaluateBars。
 * 显式传 -DresearchEvalRun=true 才会执行，避免普通测试误跑长任务。
 */
class ResearchEvalRunnerTest {

    private static final String DEFAULT_INTERVAL = "1m";
    private static final long ONE_MINUTE_MS = Duration.ofMinutes(1).toMillis();
    private static final long DAILY_FACTOR_AVAILABILITY_LAG_MS = Duration.ofDays(1).toMillis();
    private static final int LOGIN_TIMEOUT_SECONDS = 15;
    private static final int QUERY_TIMEOUT_SECONDS = 180;
    private static final int FETCH_SIZE = 10_000;
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    @Test
    void runResearchEval() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("researchEvalRun"),
                "skip: add -DresearchEvalRun=true to run research eval");

        EvalRunConfig cfg = loadConfig();
        EvalParams params = EvalParams.defaults();
        Path runDir = ResearchEvalArtifacts.runDir();
        System.out.println("[research.evalRun] config=" + JSON.toJSONString(cfg.masked(), JSONWriter.Feature.PrettyFormat));
        System.out.println("[research.evalRun] params=" + JSON.toJSONString(params, JSONWriter.Feature.PrettyFormat));
        System.out.println("[research.evalRun] reportDir=" + runDir);

        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        List<EvalRunResult> results = new ArrayList<>();
        int total = cfg.symbols().size() * cfg.horizons().size();
        int idx = 0;
        try (Connection connection = DriverManager.getConnection(cfg.jdbcUrl(), cfg.dbUser(), cfg.dbPassword())) {
            connection.setAutoCommit(false);
            configureSession(connection);

            for (String symbol : cfg.symbols()) {
                long latestOpenTime = latestKlineOpenTime(connection, symbol, cfg.interval());
                long effectiveToMs = Math.min(cfg.toMs(), latestOpenTime + ONE_MINUTE_MS);
                long effectiveFromMs = cfg.fromMs() != null
                        ? cfg.fromMs()
                        : effectiveToMs - Duration.ofDays(cfg.days()).toMillis();
                System.out.println("[research.evalRun] " + symbol + " window=["
                        + Instant.ofEpochMilli(effectiveFromMs) + ", " + Instant.ofEpochMilli(effectiveToMs) + ")");
                for (ForecastHorizon horizon : cfg.horizons()) {
                    idx++;
                    long startMs = System.currentTimeMillis();
                    System.out.println("[research.evalRun] (" + idx + "/" + total + ") start "
                            + symbol + " " + horizon.hours() + "h ...");
                    EvalRunResult result = runOne(connection, symbol, horizon, effectiveFromMs, effectiveToMs, params, runDir, cfg.interval());
                    results.add(result);
                    long elapsedSec = (System.currentTimeMillis() - startMs) / 1000;
                    System.out.println("[research.evalRun] (" + idx + "/" + total + ") done "
                            + symbol + " " + horizon.hours() + "h | " + elapsedSec + "s | testPoints=" + result.testPoints());
                    System.out.println("    " + result.summary());
                    System.out.println("    report=" + result.reportFile());
                }
            }
        }

        System.out.println();
        System.out.println("========== Research Eval Runner ==========");
        for (EvalRunResult result : results) {
            System.out.println(result.summary());
            System.out.println("report=" + result.reportFile());
        }
        System.out.println("==========================================");
        System.out.println(JSON.toJSONString(results, JSONWriter.Feature.PrettyFormat));
        System.out.println();
    }

    private EvalRunResult runOne(Connection connection, String symbol, ForecastHorizon horizon,
                                 long fromMs, long toMs, EvalParams params, Path runDir, String interval) throws Exception {
        List<KlineBar> oneMin = loadKlines(connection, symbol, interval, fromMs, toMs);
        List<MarketSeriesPoint> funding = loadSeries(connection, symbol, SeriesCode.FUNDING, fromMs, toMs);
        List<MarketSeriesPoint> fearGreed = loadSeries(connection, MarketSeriesStore.GLOBAL, SeriesCode.FEAR_GREED, fromMs, toMs);
        List<MarketSeriesPoint> etfFlow = loadSeries(connection, symbol, SeriesCode.ETF_FLOW, fromMs, toMs);
        List<MarketSeriesPoint> stablecoin = loadSeries(connection, symbol, SeriesCode.STABLECOIN_DELTA, fromMs, toMs);

        List<Forecaster> forecasters = List.of(
                new EwmaMomentumForecaster(12, 26),
                MultiFactorForecaster.defaults(),
                MultiFactorForecaster.onChainOnly(),
                MultiFactorForecaster.allFactors());

        ComparisonReport report = ResearchEvalService.evaluateBars(
                symbol, horizon, oneMin, funding, fearGreed, etfFlow, stablecoin, forecasters, params);
        Path reportFile = writeReport(report, toMs, runDir);
        return new EvalRunResult(
                symbol,
                horizon.hours(),
                Instant.ofEpochMilli(fromMs).toString(),
                Instant.ofEpochMilli(toMs).toString(),
                oneMin.size(),
                funding.size(),
                fearGreed.size(),
                etfFlow.size(),
                stablecoin.size(),
                report.testPoints(),
                report.summary(),
                reportFile.toString());
    }

    private List<KlineBar> loadKlines(Connection connection, String symbol, String interval, long fromMs, long toMs) throws Exception {
        String sql = """
                SELECT open_time, close_time, open, high, low, close, volume
                FROM kline_history
                WHERE symbol = ?
                  AND interval_code = ?
                  AND open_time >= ?
                  AND open_time < ?
                ORDER BY open_time ASC
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            ps.setFetchSize(FETCH_SIZE);
            ps.setString(1, symbol);
            ps.setString(2, interval);
            ps.setLong(3, fromMs);
            ps.setLong(4, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                List<KlineBar> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new KlineBar(
                            rs.getLong("open_time"),
                            rs.getLong("close_time"),
                            rs.getBigDecimal("open"),
                            rs.getBigDecimal("high"),
                            rs.getBigDecimal("low"),
                            rs.getBigDecimal("close"),
                            rs.getBigDecimal("volume")));
                }
                if (rows.isEmpty()) {
                    throw new IllegalStateException("no kline_history rows for " + symbol
                            + " [" + Instant.ofEpochMilli(fromMs) + ", " + Instant.ofEpochMilli(toMs) + ")");
                }
                return rows;
            }
        }
    }

    private List<MarketSeriesPoint> loadSeries(Connection connection, String symbol, SeriesCode code,
                                               long fromMs, long toMs) throws Exception {
        long lagMs = availabilityLagMs(code);
        String sql = """
                SELECT factor_value, observed_at
                FROM factor_history
                WHERE symbol = ?
                  AND factor_name = ?
                  AND observed_at >= ?
                  AND observed_at < ?
                ORDER BY observed_at ASC
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            ps.setFetchSize(FETCH_SIZE);
            ps.setString(1, symbol);
            ps.setString(2, code.factorName());
            ps.setTimestamp(3, Timestamp.valueOf(toLdt(fromMs - lagMs)));
            ps.setTimestamp(4, Timestamp.valueOf(toLdt(toMs)));
            try (ResultSet rs = ps.executeQuery()) {
                List<MarketSeriesPoint> rows = new ArrayList<>();
                while (rs.next()) {
                    BigDecimal value = rs.getBigDecimal("factor_value");
                    long availableAt = toMs(rs.getTimestamp("observed_at").toLocalDateTime()) + lagMs;
                    if (availableAt >= fromMs && availableAt < toMs) {
                        rows.add(new MarketSeriesPoint(availableAt, value));
                    }
                }
                return rows;
            }
        }
    }

    private long latestKlineOpenTime(Connection connection, String symbol, String interval) throws Exception {
        String sql = """
                SELECT MAX(open_time)
                FROM kline_history
                WHERE symbol = ?
                  AND interval_code = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            ps.setString(1, symbol);
            ps.setString(2, interval);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("no kline_history rows for " + symbol);
                }
                long latest = rs.getLong(1);
                if (rs.wasNull()) {
                    throw new IllegalStateException("no kline_history rows for " + symbol);
                }
                return latest;
            }
        }
    }

    private Path writeReport(ComparisonReport report, long toMs, Path runDir) throws Exception {
        Files.createDirectories(runDir);
        Path file = runDir.resolve(String.format("%s-%dh-%s.json",
                report.symbol(), report.horizonHours(), FILE_STAMP.format(Instant.ofEpochMilli(toMs))));
        Files.writeString(file, JSON.toJSONString(report, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8);
        return file;
    }

    private void configureSession(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET statement_timeout = '180s'");
        }
    }

    private EvalRunConfig loadConfig() {
        Instant to = parseInstant(propertyOrEnv("eval.to", "EVAL_TO", null), Instant.now());
        Instant from = parseInstant(propertyOrEnv("eval.from", "EVAL_FROM", null), null);
        return new EvalRunConfig(
                splitSymbols(propertyOrEnv("eval.symbols", "EVAL_SYMBOLS", "BTCUSDT,ETHUSDT")),
                splitHorizons(propertyOrEnv("eval.horizons", "EVAL_HORIZONS", "6,12,24")),
                propertyOrEnv("eval.interval", "EVAL_INTERVAL", DEFAULT_INTERVAL),
                from == null ? null : from.toEpochMilli(),
                to.toEpochMilli(),
                Integer.getInteger("eval.days", 365),
                propertyOrEnv("eval.db.url", "WIIB_DB_URL",
                        "jdbc:postgresql://localhost:5432/wiib?reWriteBatchedInserts=true"),
                propertyOrEnv("eval.db.user", "WIIB_DB_USER", "mawai"),
                propertyOrEnv("eval.db.password", "WIIB_DB_PASSWORD", "")
        );
    }

    private List<String> splitSymbols(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private List<ForecastHorizon> splitHorizons(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .map(ForecastHorizon::fromHours)
                .distinct()
                .toList();
    }

    private Instant parseInstant(String raw, Instant defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String value = raw.trim();
        if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        if (value.contains(" ")) {
            value = value.replace(' ', 'T');
        }
        if (value.length() == 16) {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        }
        return Instant.parse(value);
    }

    private String propertyOrEnv(String property, String env, String defaultValue) {
        String propertyValue = System.getProperty(property);
        if (propertyValue != null) {
            return propertyValue;
        }
        String envValue = System.getenv(env);
        return envValue != null ? envValue : defaultValue;
    }

    private static long availabilityLagMs(SeriesCode code) {
        return switch (code) {
            case FUNDING -> 0L;
            case FEAR_GREED, ETF_FLOW, STABLECOIN_DELTA -> DAILY_FACTOR_AVAILABILITY_LAG_MS;
        };
    }

    private static LocalDateTime toLdt(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
    }

    private static long toMs(LocalDateTime ldt) {
        return ldt.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private record EvalRunConfig(
            List<String> symbols,
            List<ForecastHorizon> horizons,
            String interval,
            Long fromMs,
            long toMs,
            int days,
            String jdbcUrl,
            String dbUser,
            String dbPassword
    ) {
        EvalRunConfig masked() {
            return new EvalRunConfig(symbols, horizons, interval, fromMs, toMs, days, maskJdbcUrl(jdbcUrl), dbUser, "***");
        }

        private static String maskJdbcUrl(String jdbcUrl) {
            int queryIndex = jdbcUrl.indexOf('?');
            return queryIndex >= 0 ? jdbcUrl.substring(0, queryIndex) + "?..." : jdbcUrl;
        }
    }

    private record EvalRunResult(
            String symbol,
            int horizonHours,
            String from,
            String to,
            int klineRows,
            int fundingRows,
            int fearGreedRows,
            int etfRows,
            int stablecoinRows,
            int testPoints,
            String summary,
            String reportFile
    ) {}
}
