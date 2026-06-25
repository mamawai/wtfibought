package com.mawai.wiibquant.agent.research.eval;

import com.mawai.wiibquant.agent.research.ForecastHorizon;
import com.mawai.wiibquant.agent.research.forecast.ContinuousFactorForecaster;
import com.mawai.wiibquant.agent.research.forecast.EwmaMomentumForecaster;
import com.mawai.wiibquant.agent.research.forecast.Forecaster;
import com.mawai.wiibquant.agent.research.forecast.IndicatorAdapter;
import com.mawai.wiibquant.agent.research.forecast.MultiFactorForecaster;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.research.series.MarketSeriesPoint;
import com.mawai.wiibquant.agent.research.series.SeriesCode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 手动 direction DB 运行器（非 *Test 命名，默认/全量构建不会跑）。
 * 显式 -Dtest=DirectionResearchDbRun 时，直连 Postgres 读 kline/factor_history，绕开 Spring Boot/Redis。
 *
 * 跑法：
 *   mvn -pl wiib-service -am -DskipTests=false -Dtest=DirectionResearchDbRun \
 *       -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false \
 *       -Ddirection.diag.toDate=2026-06-02 test
 */
class DirectionResearchDbRun {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/wiib";
    private static final String DEFAULT_USER = "mawai";
    private static final String DEFAULT_PASSWORD = "LOCAL_PASSWORD";
    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final String GLOBAL_SYMBOL = "GLOBAL";
    private static final long DAY_MS = Duration.ofDays(1).toMillis();

    @Test
    void runDirectionResearch() throws Exception {
        String symbol = System.getProperty("direction.diag.symbol", DEFAULT_SYMBOL).trim().toUpperCase();
        int days = Integer.getInteger("direction.diag.days", 180);
        String horizonsCsv = System.getProperty("direction.diag.horizons", "12");
        String toDate = System.getProperty("direction.diag.toDate", "").trim();
        List<String> forecasterNames = forecasters().stream().map(Forecaster::name).toList();
        EvalParams params = evalParams();

        Connection con;
        try {
            con = DriverManager.getConnection(
                    System.getProperty("direction.diag.dbUrl", DEFAULT_URL),
                    System.getProperty("direction.diag.dbUser", DEFAULT_USER),
                    System.getProperty("direction.diag.dbPassword", DEFAULT_PASSWORD));
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过 direction DB 运行器: " + e.getMessage());
            return;
        }

        try (con) {
            printFactorCoverage(con);

            long max = maxOpenTime(con, symbol);
            Assumptions.assumeTrue(max > 0, "库里无 " + symbol + " 5m 数据");
            long latestTo = max + 1;
            long to = toDate.isBlank() ? latestTo : Math.min(latestTo, inclusiveDateEndUtc(toDate));
            long from = to - days * DAY_MS;

            long t0 = System.nanoTime();
            List<KlineBar> bars = loadBars(con, symbol, from, to);
            List<KlineBar> benchmarkBars = DEFAULT_SYMBOL.equals(symbol)
                    ? List.of()
                    : loadBars(con, DEFAULT_SYMBOL, from, to);

            List<MarketSeriesPoint> funding = loadSeries(con, symbol, SeriesCode.FUNDING, from, to);
            List<MarketSeriesPoint> fearGreed = loadSeries(con, GLOBAL_SYMBOL, SeriesCode.FEAR_GREED, from, to);
            List<MarketSeriesPoint> etfFlow = loadSeries(con, symbol, SeriesCode.ETF_FLOW, from, to);
            List<MarketSeriesPoint> stablecoin = loadSeries(con, symbol, SeriesCode.STABLECOIN_DELTA, from, to);

            System.out.printf("%n加载 %s 5m bars=%d，benchmarkBars=%d，最近 %d 天，耗时 %.1fs%n",
                    symbol, bars.size(), benchmarkBars.size(), days, (System.nanoTime() - t0) / 1e9);
            System.out.printf("评估区间 UTC [%s, %s)%n", Instant.ofEpochMilli(from), Instant.ofEpochMilli(to));
            if (!toDate.isBlank()) {
                System.out.printf("K线终点已按 direction.diag.toDate=%s 截断；该日期按 UTC 全日包含%n", toDate);
            }
            System.out.printf("as-of 因子点数 funding=%d, fearGreed=%d, etfFlow=%d, stablecoin=%d%n",
                    funding.size(), fearGreed.size(), etfFlow.size(), stablecoin.size());
            System.out.printf("EvalParams testSize=%d, minTrain=%d, embargo=%d, iterations=%d%n",
                    params.testSize(), params.minTrain(), params.embargoBars(), params.iterations());
            System.out.printf("Forecasters=%s%n", forecasterNames);

            for (String h : horizonsCsv.split(",")) {
                int horizonHours = Integer.parseInt(h.trim());
                ForecastHorizon horizon = ForecastHorizon.fromHours(horizonHours);
                List<Forecaster> selectedForecasters = forecasters();
                try {
                    AssembledPoints assembled = ResearchEvalService.assemblePoints(horizon, bars, benchmarkBars,
                            funding, fearGreed, etfFlow, stablecoin,
                            params, ResearchEvalService.FEATURE_LOOKBACK_BARS);
                    ComparisonReport report = ResearchEvalService.evaluateAssembled(
                            symbol, horizon, assembled, selectedForecasters, params);

                    printReport(report);
                    printDiagnostics(horizon, assembled, selectedForecasters, params);
                    assertThat(report.testPoints()).isGreaterThan(0);
                } finally {
                    IndicatorAdapter.clearThreadCache();
                }
            }
        }
    }

    private static List<Forecaster> forecasters() {
        String raw = System.getProperty("direction.diag.forecasters",
                "ewma,multi,onchain,all");
        List<Forecaster> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            switch (token.trim().toLowerCase(Locale.ROOT)) {
                case "ewma" -> out.add(new EwmaMomentumForecaster(12, 26));
                case "multi" -> out.add(MultiFactorForecaster.defaults());
                case "onchain" -> out.add(MultiFactorForecaster.onChainOnly());
                case "all" -> out.add(MultiFactorForecaster.allFactors());
                case "continuous" -> out.add(ContinuousFactorForecaster.defaults());
                case "" -> { }
                default -> throw new IllegalArgumentException("未知 direction.diag.forecasters token: " + token);
            }
        }
        return out.isEmpty() ? List.of(new EwmaMomentumForecaster(12, 26)) : List.copyOf(out);
    }

    private static EvalParams evalParams() {
        EvalParams d = EvalParams.defaults();
        return new EvalParams(
                doubleProperty("direction.diag.k", d.k()),
                doubleProperty("direction.diag.lambda", d.lambda()),
                Integer.getInteger("direction.diag.testSize", d.testSize()),
                Integer.getInteger("direction.diag.embargoBars", d.embargoBars()),
                Integer.getInteger("direction.diag.minTrain", d.minTrain()),
                Integer.getInteger("direction.diag.iterations", d.iterations()),
                doubleProperty("direction.diag.naivePercentileThreshold", d.naivePercentileThreshold()),
                Long.getLong("direction.diag.seed", d.seed()));
    }

    private static double doubleProperty(String key, double fallback) {
        String raw = System.getProperty(key);
        return raw == null || raw.isBlank() ? fallback : Double.parseDouble(raw);
    }

    private static void printFactorCoverage(Connection con) throws Exception {
        String sql = """
                SELECT symbol, factor_name, COUNT(*) AS n, MIN(observed_at) AS first_at, MAX(observed_at) AS last_at
                FROM factor_history
                WHERE factor_name IN (?, ?, ?, ?)
                GROUP BY symbol, factor_name
                ORDER BY factor_name, symbol
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, SeriesCode.FUNDING.factorName());
            ps.setString(2, SeriesCode.FEAR_GREED.factorName());
            ps.setString(3, SeriesCode.ETF_FLOW.factorName());
            ps.setString(4, SeriesCode.STABLECOIN_DELTA.factorName());
            try (ResultSet rs = ps.executeQuery()) {
                System.out.println("\n==== factor_history 覆盖率（observed_at 原始观测日/时点） ====");
                System.out.printf("%-12s %-28s %8s %-22s %-22s%n", "symbol", "factor", "n", "first_at", "last_at");
                while (rs.next()) {
                    System.out.printf("%-12s %-28s %8d %-22s %-22s%n",
                            rs.getString(1), rs.getString(2), rs.getLong(3),
                            rs.getTimestamp(4), rs.getTimestamp(5));
                }
            }
        }
    }

    private static long maxOpenTime(Connection con, String symbol) throws Exception {
        String sql = "SELECT max(open_time) FROM kline_history WHERE symbol=? AND interval_code='5m'";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static List<KlineBar> loadBars(Connection con, String symbol, long fromMs, long toMs) throws Exception {
        String sql = "SELECT open_time, close_time, open, high, low, close, volume FROM kline_history "
                + "WHERE symbol=? AND interval_code='5m' AND open_time>=? AND open_time<? ORDER BY open_time";
        List<KlineBar> bars = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setLong(2, fromMs);
            ps.setLong(3, toMs);
            ps.setFetchSize(5000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    bars.add(new KlineBar(rs.getLong(1), rs.getLong(2),
                            rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5),
                            rs.getBigDecimal(6), rs.getBigDecimal(7)));
                }
            }
        }
        return bars;
    }

    private static List<MarketSeriesPoint> loadSeries(Connection con, String symbol, SeriesCode code,
                                                      long fromMs, long toMs) throws Exception {
        long lagMs = availabilityLagMs(code);
        String sql = "SELECT observed_at, factor_value FROM factor_history "
                + "WHERE symbol=? AND factor_name=? AND observed_at>=? AND observed_at<? ORDER BY observed_at";
        List<MarketSeriesPoint> points = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setString(2, code.factorName());
            ps.setTimestamp(3, Timestamp.valueOf(toUtcLocalDateTime(fromMs - lagMs)));
            ps.setTimestamp(4, Timestamp.valueOf(toUtcLocalDateTime(toMs)));
            ps.setFetchSize(5000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long availableAt = toEpochMsUtc(rs.getTimestamp(1)) + lagMs;
                    // 日级慢因子 observed_at 存原始日期；策略只能在 T+1 看到，和 MarketSeriesStore.load 保持一致。
                    if (availableAt >= fromMs && availableAt < toMs) {
                        BigDecimal value = rs.getBigDecimal(2);
                        if (value != null) points.add(new MarketSeriesPoint(availableAt, value));
                    }
                }
            }
        }
        return points;
    }

    private static long availabilityLagMs(SeriesCode code) {
        return code == SeriesCode.FUNDING ? 0L : DAY_MS;
    }

    private static LocalDateTime toUtcLocalDateTime(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
    }

    private static long inclusiveDateEndUtc(String yyyyMmDd) {
        // toDate 是“跑到某天”为人读语义；内部仍用 [from,to)，所以终点取次日 00:00 UTC。
        return LocalDate.parse(yyyyMmDd).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static long toEpochMsUtc(Timestamp ts) {
        // factor_history 是 UTC LocalDateTime；别走 Timestamp.toInstant()，否则本机时区会偷偷参与。
        return ts.toLocalDateTime().toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /** 只打印市场背景一行；方向预测的判分全在 printDiagnostics（纯命中率口径），收益不参与评判。 */
    private static void printReport(ComparisonReport report) {
        System.out.printf("%n==== DirectionResearch %s H%d | decision=%dm | oos=%d | 市场buyHold=%.4f(仅背景) ====%n",
                report.symbol(), report.horizonHours(), report.decisionBarMinutes(),
                report.testPoints(), report.buyAndHoldReturn().doubleValue());
    }

    private static void printDiagnostics(ForecastHorizon horizon,
                                         AssembledPoints a,
                                         List<Forecaster> forecasters,
                                         EvalParams params) {
        List<DirectionDiagnostics> rows = directionDiagnostics(horizon, a, forecasters, params);
        System.out.printf("%n==== 方向命中率(纯方向，非重叠独立样本) H%d | nonOverlapStep=%d bars ====%n",
                horizon.hours(), a.horizonDecisionBars());
        System.out.println("  hit%=出手命中率(不扣成本，纯看涨跌猜没猜中) balAcc%=涨跌平衡命中(防全押多数类) z=对抛硬币的显著性(>1.96显著) cover%=出手率");
        System.out.printf("%-28s %7s %7s %7s %8s %8s %9s %7s %7s %7s%n",
                "strategy", "long%", "short%", "flat%", "cover%", "hit%", "balAcc%", "z", "nonN", "actN");
        for (DirectionDiagnostics d : rows) {
            System.out.printf("%-28s %7.2f %7.2f %7.2f %8.2f %8.2f %9.2f %7.2f %7d %7d%n",
                    d.name(),
                    pct(d.longCount(), d.points()),
                    pct(d.shortCount(), d.points()),
                    pct(d.flatCount(), d.points()),
                    d.coverage() * 100,
                    d.hitRate() * 100,
                    d.balancedAccuracy() * 100,
                    d.hitZScore(),
                    d.nonOverlapPoints(),
                    d.nonOverlapActive());
        }
    }

    private static List<DirectionDiagnostics> directionDiagnostics(ForecastHorizon horizon,
                                                                   AssembledPoints a,
                                                                   List<Forecaster> forecasters,
                                                                   EvalParams params) {
        List<WalkForwardWindow> windows = WalkForwardEvaluator.windows(
                a.points(), params.testSize(), a.horizonDecisionBars(), params.embargoBars(), params.minTrain());
        List<DirectionDiagnostics> out = new ArrayList<>(forecasters.size());
        for (Forecaster fc : forecasters) {
            out.add(diagnoseForecaster(horizon, a, windows, fc));
        }
        return out;
    }

    private static DirectionDiagnostics diagnoseForecaster(ForecastHorizon horizon,
                                                          AssembledPoints a,
                                                          List<WalkForwardWindow> windows,
                                                          Forecaster fc) {
        int total = 0;
        int longCount = 0;
        int shortCount = 0;
        int flatCount = 0;
        int positionChanges = 0;
        int previousDirection = 0;
        boolean hasPrevious = false;
        int nextNonOverlapBarIndex = Integer.MIN_VALUE;

        // 纯方向命中只在非重叠独立样本上统计：相邻重叠点高度相关，会把命中率显著性算虚高。
        int nonPoints = 0, nonActive = 0, nonHits = 0;
        int upActive = 0, upHit = 0, downActive = 0, downHit = 0;

        for (WalkForwardWindow w : windows) {
            Forecaster trained = fc.fit(List.copyOf(a.samplesByPoint().subList(w.trainStart(), w.trainEnd())));
            for (int i = w.testStart(); i < w.testEnd(); i++) {
                int direction = normalizeDirection(trained.forecast(a.featuresByPoint().get(i)).direction());
                total++;
                if (direction > 0) {
                    longCount++;
                } else if (direction < 0) {
                    shortCount++;
                } else {
                    flatCount++;
                }
                if (hasPrevious && direction != previousDirection) {
                    positionChanges++;
                }
                previousDirection = direction;
                hasPrevious = true;

                int decisionBarIndex = a.decisionBarIndexes().get(i);
                if (decisionBarIndex >= nextNonOverlapBarIndex) {
                    nextNonOverlapBarIndex = decisionBarIndex + a.horizonDecisionBars();
                    nonPoints++;
                    int actualSign = a.longBarrierReturnsByPoint().get(i).signum(); // 纯方向：实际涨跌符号，不看幅度、不扣成本
                    if (direction != 0) {
                        nonActive++;
                        if ((direction > 0 && actualSign > 0) || (direction < 0 && actualSign < 0)) {
                            nonHits++;
                        }
                        if (actualSign > 0) {
                            upActive++;
                            if (direction > 0) upHit++;
                        } else if (actualSign < 0) {
                            downActive++;
                            if (direction < 0) downHit++;
                        }
                    }
                }
            }
        }

        double coverage = nonPoints == 0 ? 0.0 : (double) nonActive / nonPoints;
        double hitRate = nonActive == 0 ? 0.0 : (double) nonHits / nonActive;
        double balancedAccuracy = balancedAccuracy(upHit, upActive, downHit, downActive);
        // 对"抛硬币 50%"的 z 值(独立样本)：z=(p-0.5)/sqrt(0.25/n)=(p-0.5)*2*sqrt(n)；>1.96 即显著优于随机。
        double hitZScore = nonActive == 0 ? 0.0 : (hitRate - 0.5) * 2.0 * Math.sqrt(nonActive);

        return new DirectionDiagnostics(fc.name(), total, longCount, shortCount, flatCount,
                positionChanges, nonPoints, nonActive, coverage, hitRate, balancedAccuracy, hitZScore);
    }

    /** 涨样本召回与跌样本召回的均值；防"全押多数类"刷高 raw 命中。缺一类则取另一类。 */
    private static double balancedAccuracy(int upHit, int upActive, int downHit, int downActive) {
        boolean hasUp = upActive > 0;
        boolean hasDown = downActive > 0;
        if (!hasUp && !hasDown) return 0.0;
        double up = hasUp ? (double) upHit / upActive : 0.0;
        double down = hasDown ? (double) downHit / downActive : 0.0;
        if (hasUp && hasDown) return (up + down) / 2.0;
        return hasUp ? up : down;
    }

    private record DirectionDiagnostics(String name,
                                        int points,
                                        int longCount,
                                        int shortCount,
                                        int flatCount,
                                        int positionChanges,
                                        int nonOverlapPoints,
                                        int nonOverlapActive,
                                        double coverage,
                                        double hitRate,
                                        double balancedAccuracy,
                                        double hitZScore) {
    }

    private static int normalizeDirection(int direction) {
        return Integer.compare(direction, 0);
    }

    private static double pct(int part, int total) {
        return total <= 0 ? 0.0 : part * 100.0 / total;
    }
}
