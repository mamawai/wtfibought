package com.mawai.wiibservice.agent.research.eval;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.forecast.ContinuousFactorForecaster;
import com.mawai.wiibservice.agent.research.forecast.Forecaster;
import com.mawai.wiibservice.agent.research.forecast.HorizonScaledVolForecaster;
import com.mawai.wiibservice.agent.research.forecast.MarketRegime;
import com.mawai.wiibservice.agent.research.forecast.MultiFactorForecaster;
import com.mawai.wiibservice.agent.research.forecast.MultiOutputForecaster;
import com.mawai.wiibservice.agent.research.forecast.QuantCoreForecaster;
import com.mawai.wiibservice.agent.research.forecast.TrainingSample;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.label.RegimeLabeler;
import com.mawai.wiibservice.agent.research.metrics.RegimeClassificationScore;
import com.mawai.wiibservice.agent.research.metrics.RegimeFeatureDiagnostics;
import com.mawai.wiibservice.agent.research.series.MarketSeriesPoint;
import com.mawai.wiibservice.agent.research.series.MarketSeriesStore;
import com.mawai.wiibservice.agent.research.series.SeriesCode;
import com.mawai.wiibservice.agent.research.stats.VolatilityEstimator;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多输出量化核心本地判决 runner：不启 Spring，直连本地 DB 读历史，调 {@link MultiOutputEvalService} 出
 * vol/regime/direction 三件一等输出的样本外判决（量化核心 alone，作 A4 LLM 增量的对照基线）。
 * 与 {@link ResearchEvalRunnerTest} 同构、共用 kline_history/factor_history 同一份无前视加载口径；
 * 每个 (symbol,horizon) 默认跑 raw EWMA + 两条 regime 腿候选，收三输出而非多策略方向对比。
 * 显式 -DmultiOutputEvalRun=true 才执行（避免普通测试误跑长任务）；库密码走 -Deval.db.password/WIIB_DB_PASSWORD，不入代码、打印掩码。
 */
class MultiOutputEvalRunnerTest {

    private static final String DEFAULT_INTERVAL = "1m";
    private static final String DEFAULT_DECISION_INTERVAL = "5m";
    private static final long ONE_MINUTE_MS = Duration.ofMinutes(1).toMillis();
    private static final long DAILY_FACTOR_AVAILABILITY_LAG_MS = Duration.ofDays(1).toMillis();
    private static final int LOGIN_TIMEOUT_SECONDS = 15;
    private static final int QUERY_TIMEOUT_SECONDS = 180;
    private static final int FETCH_SIZE = 10_000;
    private static final long PROGRESS_PRINT_INTERVAL_MS = 10_000L;
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    @Test
    void runMultiOutputEval() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("multiOutputEvalRun"),
                "skip: add -DmultiOutputEvalRun=true to run multi-output eval");

        EvalRunConfig cfg = loadConfig();
        EvalParams params = EvalParams.defaults();
        Path runDir = ResearchEvalArtifacts.runDir();
        System.out.println("[multiOutput.evalRun] config=" + JSON.toJSONString(cfg.masked(), JSONWriter.Feature.PrettyFormat));
        System.out.println("[multiOutput.evalRun] params=" + JSON.toJSONString(params, JSONWriter.Feature.PrettyFormat));
        System.out.println("[multiOutput.evalRun] reportDir=" + runDir);

        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        List<EvalRunResult> results = new ArrayList<>();
        int total = 0;
        for (String ignored : cfg.symbols()) {
            for (ForecastHorizon horizon : cfg.horizons()) {
                total += forecastersFor(horizon, cfg.decisionBarMillis(),
                        cfg.volCandidates(), cfg.directionCandidates()).size();
            }
        }
        AtomicInteger idx = new AtomicInteger();
        for (String symbol : cfg.symbols()) {
            try (Connection connection = openConnection(cfg)) {
                long latestOpenTime = latestKlineOpenTime(connection, symbol, cfg.interval());
                long effectiveToMs = Math.min(cfg.toMs(), latestOpenTime + ONE_MINUTE_MS);
                long effectiveFromMs = cfg.fromMs() != null
                        ? cfg.fromMs()
                        : effectiveToMs - Duration.ofDays(cfg.days()).toMillis();
                System.out.println("[multiOutput.evalRun] " + symbol + " window=["
                        + Instant.ofEpochMilli(effectiveFromMs) + ", " + Instant.ofEpochMilli(effectiveToMs) + ")");
                results.addAll(runSymbolHorizons(cfg, symbol, effectiveFromMs, effectiveToMs,
                        params, runDir, idx, total));
            }
        }

        System.out.println();
        System.out.println("========== Multi-Output Eval Runner (量化核心 alone) ==========");
        for (EvalRunResult result : results) {
            System.out.println(result.summary());
            System.out.println("report=" + result.reportFile());
        }
        System.out.println("===============================================================");
        System.out.println(JSON.toJSONString(results, JSONWriter.Feature.PrettyFormat));
        System.out.println();
    }

    private List<EvalRunResult> runSymbolHorizons(EvalRunConfig cfg, String symbol, long fromMs, long toMs,
                                                  EvalParams params, Path runDir,
                                                  AtomicInteger idx, int total) throws Exception {
        if (!cfg.parallelHorizons() || cfg.horizons().size() <= 1) {
            List<EvalRunResult> out = new ArrayList<>();
            for (ForecastHorizon horizon : cfg.horizons()) {
                out.addAll(runHorizon(cfg, symbol, horizon, fromMs, toMs, params, runDir, idx, total));
            }
            return out;
        }

        int threads = Math.min(3, cfg.horizons().size());
        System.out.println("[multiOutput.evalRun] parallel horizons enabled: symbol=" + symbol
                + " threads=" + threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<EvalRunResult>>> futures = new ArrayList<>();
            for (ForecastHorizon horizon : cfg.horizons()) {
                futures.add(executor.submit(() -> runHorizon(cfg, symbol, horizon, fromMs, toMs, params,
                        runDir, idx, total)));
            }
            List<EvalRunResult> out = new ArrayList<>();
            for (Future<List<EvalRunResult>> future : futures) {
                out.addAll(future.get());
            }
            return out;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw e;
        } finally {
            executor.shutdownNow();
        }
    }

    private List<EvalRunResult> runHorizon(EvalRunConfig cfg, String symbol, ForecastHorizon horizon,
                                           long fromMs, long toMs, EvalParams params, Path runDir,
                                           AtomicInteger idx, int total) throws Exception {
        List<EvalRunResult> out = new ArrayList<>();
        try (Connection connection = openConnection(cfg)) {
            for (MultiOutputForecaster forecaster : forecastersFor(
                    horizon, cfg.decisionBarMillis(), cfg.volCandidates(), cfg.directionCandidates())) {
                int runIndex = idx.incrementAndGet();
                long startMs = System.currentTimeMillis();
                System.out.println("[multiOutput.evalRun] (" + runIndex + "/" + total + ") start "
                        + symbol + " " + cfg.decisionBarMinutes() + "m→"
                        + horizon.hours() + "h " + forecaster.name() + " ...");
                EvalProgress progress = consoleProgress(runIndex, total,
                        symbol + " " + cfg.decisionBarMinutes() + "m->" + horizon.hours()
                                + "h " + shortForecasterName(forecaster.name()),
                        startMs);
                EvalRunResult result = runOne(connection, symbol, horizon, forecaster,
                        fromMs, toMs, params, runDir, cfg.interval(), cfg.decisionBarMillis(), progress);
                out.add(result);
                long elapsedSec = (System.currentTimeMillis() - startMs) / 1000;
                System.out.println("[multiOutput.evalRun] (" + runIndex + "/" + total + ") done "
                        + symbol + " " + horizon.hours() + "h " + forecaster.name()
                        + " | " + elapsedSec + "s | testPoints=" + result.testPoints());
                System.out.println(result.summary());
                System.out.println("    report=" + result.reportFile());
            }
        }
        return out;
    }

    /** 待扫描的 directionality 阈值 q（保守端→激进端）；SHOCK 永远优先于 q。 */
    private static final double[] SCAN_Q = {1.25, 1.5, 1.75, 2.0, 2.25, 2.5, 3.0};

    /**
     * regime 标签 q 扫描诊断：对 BTC/ETH×6/12/24h，复用 eval 同一份无前视装配+walk-forward OOS 样本，
     * 每个样本外点算一次 (directionality, shock, netSign, 预测regime)，再对 7 个 q 重建标签看分布/混淆矩阵。
     * 目的：据真实 directionality 分布 + 跨币种/周期稳定性 + 偏斜稳健指标定默认 q——不为贴 ADX 预测调参。
     * 显式 -DregimeQScan=true 才执行；库密码走 -Deval.db.password/WIIB_DB_PASSWORD，不入代码、打印掩码。
     */
    @Test
    void runRegimeQScan() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("regimeQScan"),
                "skip: add -DregimeQScan=true to run regime q-scan diagnostics");

        EvalRunConfig cfg = loadConfig();
        EvalParams params = EvalParams.defaults();
        System.out.println("[regimeQScan] config=" + JSON.toJSONString(cfg.masked(), JSONWriter.Feature.PrettyFormat));
        System.out.println("[regimeQScan] params=" + JSON.toJSONString(params, JSONWriter.Feature.PrettyFormat));
        System.out.println("[regimeQScan] q=" + Arrays.toString(SCAN_Q)
                + " shockMultiple=" + RegimeLabeler.DEFAULT_SHOCK_VOL_MULTIPLE);

        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        List<QScanCell> cells = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(cfg.jdbcUrl(), cfg.dbUser(), cfg.dbPassword())) {
            connection.setAutoCommit(false);
            configureSession(connection);
            for (String symbol : cfg.symbols()) {
                long latestOpenTime = latestKlineOpenTime(connection, symbol, cfg.interval());
                long effectiveToMs = Math.min(cfg.toMs(), latestOpenTime + ONE_MINUTE_MS);
                long effectiveFromMs = cfg.fromMs() != null
                        ? cfg.fromMs()
                        : effectiveToMs - Duration.ofDays(cfg.days()).toMillis();
                System.out.println("[regimeQScan] " + symbol + " window=["
                        + Instant.ofEpochMilli(effectiveFromMs) + ", " + Instant.ofEpochMilli(effectiveToMs) + ")");
                for (ForecastHorizon horizon : cfg.horizons()) {
                    long startMs = System.currentTimeMillis();
                    QScanCell cell = sampleCell(connection, symbol, horizon, effectiveFromMs, effectiveToMs,
                            params, cfg.interval(), cfg.decisionBarMillis());
                    cells.add(cell);
                    System.out.println("[regimeQScan] sampled " + symbol + " " + horizon.hours() + "h | OOS="
                            + cell.n() + " | " + (System.currentTimeMillis() - startMs) / 1000 + "s");
                }
            }
        }

        for (QScanCell cell : cells) {
            printCell(cell);
        }
        printStabilityTables(cells);
    }

    /**
     * regime 特征可分性诊断：复用 eval 同一份 OOS 样本，把现有 point-in-time 特征按未来 actual regime 分组。
     * 这一步不训练模型，只看哪条腿有组间分离度，避免继续在无信号特征上调阈值。
     */
    @Test
    void runRegimeFeatureDiagnostics() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("regimeFeatureDiagnostics"),
                "skip: add -DregimeFeatureDiagnostics=true to run regime feature diagnostics");

        EvalRunConfig cfg = loadConfig();
        EvalParams params = EvalParams.defaults();
        Path runDir = ResearchEvalArtifacts.runDir();
        System.out.println("[regimeFeatureDiag] config=" + JSON.toJSONString(cfg.masked(), JSONWriter.Feature.PrettyFormat));
        System.out.println("[regimeFeatureDiag] params=" + JSON.toJSONString(params, JSONWriter.Feature.PrettyFormat));
        System.out.println("[regimeFeatureDiag] reportDir=" + runDir);

        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        List<RegimeFeatureRunResult> results = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(cfg.jdbcUrl(), cfg.dbUser(), cfg.dbPassword())) {
            connection.setAutoCommit(false);
            configureSession(connection);
            for (String symbol : cfg.symbols()) {
                long latestOpenTime = latestKlineOpenTime(connection, symbol, cfg.interval());
                long effectiveToMs = Math.min(cfg.toMs(), latestOpenTime + ONE_MINUTE_MS);
                long effectiveFromMs = cfg.fromMs() != null
                        ? cfg.fromMs()
                        : effectiveToMs - Duration.ofDays(cfg.days()).toMillis();
                for (ForecastHorizon horizon : cfg.horizons()) {
                    long startMs = System.currentTimeMillis();
                    RegimeFeatureDiagnostics.Report report = diagnoseRegimeFeatures(
                            connection, symbol, horizon, effectiveFromMs, effectiveToMs,
                            params, cfg.interval(), cfg.decisionBarMillis());
                    Path file = writeRegimeFeatureReport(symbol, horizon, report, effectiveToMs,
                            cfg.decisionBarMinutes(), runDir);
                    RegimeFeatureRunResult result = new RegimeFeatureRunResult(
                            symbol, horizon.hours(), cfg.decisionBarMinutes(),
                            Instant.ofEpochMilli(effectiveFromMs).toString(),
                            Instant.ofEpochMilli(effectiveToMs).toString(),
                            report.n(), report.classCounts(), report.topFeatures(10), file.toString());
                    results.add(result);
                    long elapsedSec = (System.currentTimeMillis() - startMs) / 1000;
                    System.out.println("[regimeFeatureDiag] done " + symbol + " " + horizon.hours()
                            + "h | " + elapsedSec + "s | OOS=" + report.n() + " | report=" + file);
                    printRegimeFeatureReport(report, 8);
                }
            }
        }

        System.out.println();
        System.out.println("========== Regime Feature Diagnostics ==========");
        System.out.println(JSON.toJSONString(results, JSONWriter.Feature.PrettyFormat));
        System.out.println("================================================");
        System.out.println();
    }

    /** 采样 OOS test 点：保持和 MultiOutputEvalService 相同 train/test 切分，只把 test 样本交给诊断。 */
    private RegimeFeatureDiagnostics.Report diagnoseRegimeFeatures(Connection connection, String symbol,
                                                                   ForecastHorizon horizon,
                                                                   long fromMs, long toMs,
                                                                   EvalParams params, String interval,
                                                                   long decisionBarMillis) throws Exception {
        List<KlineBar> oneMin = loadKlines(connection, symbol, interval, fromMs, toMs);
        List<KlineBar> benchmarkOneMin = "BTCUSDT".equalsIgnoreCase(symbol)
                ? List.of()
                : loadOptionalKlines(connection, "BTCUSDT", interval, fromMs, toMs);
        List<MarketSeriesPoint> funding = loadSeries(connection, symbol, SeriesCode.FUNDING, fromMs, toMs);
        List<MarketSeriesPoint> fearGreed = loadSeries(connection, MarketSeriesStore.GLOBAL, SeriesCode.FEAR_GREED, fromMs, toMs);
        List<MarketSeriesPoint> etfFlow = loadSeries(connection, symbol, SeriesCode.ETF_FLOW, fromMs, toMs);
        List<MarketSeriesPoint> stablecoin = loadSeries(connection, symbol, SeriesCode.STABLECOIN_DELTA, fromMs, toMs);

        AssembledPoints a = ResearchEvalService.assemblePoints(horizon, oneMin, benchmarkOneMin,
                funding, fearGreed, etfFlow, stablecoin, params,
                ResearchEvalService.featureLookbackBars(decisionBarMillis), decisionBarMillis);
        List<WalkForwardWindow> wins = WalkForwardEvaluator.windows(
                a.points(), params.testSize(), a.horizonDecisionBars(),
                params.embargoBars(), params.minTrain());
        List<TrainingSample> oosSamples = new ArrayList<>();
        for (WalkForwardWindow w : wins) {
            for (int i = w.testStart(); i < w.testEnd(); i++) {
                oosSamples.add(a.samplesByPoint().get(i));
            }
        }
        return RegimeFeatureDiagnostics.evaluate(oosSamples);
    }

    /** 复用 eval 同一份装配+OOS 样本，逐样本外点采 (directionality, shock, netSign, 预测regime)。 */
    private QScanCell sampleCell(Connection connection, String symbol, ForecastHorizon horizon,
                                 long fromMs, long toMs, EvalParams params, String interval,
                                 long decisionBarMillis) throws Exception {
        List<KlineBar> oneMin = loadKlines(connection, symbol, interval, fromMs, toMs);
        List<KlineBar> benchmarkOneMin = "BTCUSDT".equalsIgnoreCase(symbol)
                ? List.of()
                : loadOptionalKlines(connection, "BTCUSDT", interval, fromMs, toMs);
        List<MarketSeriesPoint> funding = loadSeries(connection, symbol, SeriesCode.FUNDING, fromMs, toMs);
        List<MarketSeriesPoint> fearGreed = loadSeries(connection, MarketSeriesStore.GLOBAL, SeriesCode.FEAR_GREED, fromMs, toMs);
        List<MarketSeriesPoint> etfFlow = loadSeries(connection, symbol, SeriesCode.ETF_FLOW, fromMs, toMs);
        List<MarketSeriesPoint> stablecoin = loadSeries(connection, symbol, SeriesCode.STABLECOIN_DELTA, fromMs, toMs);

        AssembledPoints a = ResearchEvalService.assemblePoints(horizon, oneMin, benchmarkOneMin,
                funding, fearGreed, etfFlow, stablecoin, params,
                ResearchEvalService.featureLookbackBars(decisionBarMillis), decisionBarMillis);
        List<KlineBar> decisionBars = a.decisionBars();
        int points = a.points();
        double lambda = params.lambda();

        // ---- 每点原子量（与 q 无关）：directionality / shock / 净收益符号；与 RegimeLabeler 同口径 ----
        double[] directionality = new double[points];
        boolean[] shock = new boolean[points];
        int[] sign = new int[points];
        for (int i = 0; i < points; i++) {
            double baselineSigma = HorizonScaledVolForecaster.scale(
                    VolatilityEstimator.ewmaVolatility(a.featuresByPoint().get(i).barsUpToNow(), lambda),
                    a.featuresByPoint().get(i), horizon);
            List<KlineBar> futurePath = ResearchEvalService.pathBars(
                    oneMin, decisionBars.get(i).closeTime(), horizon.millis());
            double realizedVol = VolatilityEstimator.realizedVolatility(futurePath);
            double netReturn = futurePath.size() >= 2
                    ? Math.log(futurePath.get(futurePath.size() - 1).close().doubleValue() / futurePath.get(0).close().doubleValue())
                    : 0.0;
            shock[i] = baselineSigma > 0 && realizedVol > RegimeLabeler.DEFAULT_SHOCK_VOL_MULTIPLE * baselineSigma;
            directionality[i] = realizedVol > 0 ? Math.abs(netReturn) / realizedVol : 0.0;
            sign[i] = netReturn >= 0 ? 1 : -1;
        }

        // ---- 同 eval 的 walk-forward：逐窗 fit、逐 test 点取预测 regime（与 MultiOutputEvalService 镜像，OOS 样本一致）----
        List<WalkForwardWindow> wins = WalkForwardEvaluator.windows(
                points, params.testSize(), a.horizonDecisionBars(), params.embargoBars(), params.minTrain());
        MultiOutputForecaster base = QuantCoreForecaster.defaults(horizon);
        List<Double> dirOos = new ArrayList<>();
        List<Boolean> shockOos = new ArrayList<>();
        List<Integer> signOos = new ArrayList<>();
        List<MarketRegime> predicted = new ArrayList<>();
        for (WalkForwardWindow w : wins) {
            MultiOutputForecaster trained = base.fit(List.copyOf(a.samplesByPoint().subList(w.trainStart(), w.trainEnd())));
            for (int i = w.testStart(); i < w.testEnd(); i++) {
                dirOos.add(directionality[i]);
                shockOos.add(shock[i]);
                signOos.add(sign[i]);
                predicted.add(trained.forecast(a.featuresByPoint().get(i)).regime());
            }
        }
        int n = predicted.size();
        double[] dir = new double[n];
        boolean[] shk = new boolean[n];
        int[] sgn = new int[n];
        for (int j = 0; j < n; j++) {
            dir[j] = dirOos.get(j);
            shk[j] = shockOos.get(j);
            sgn[j] = signOos.get(j);
        }
        return new QScanCell(symbol, horizon.hours(), n, dir, shk, sgn, predicted);
    }

    /** 按 q 重建 actual 标签、与预测一起喂生产 regime 尺子，拿 accuracy/balAcc/macroF1/confusion（与 eval 同一套指标代码）。 */
    private static RegimeClassificationScore scoreAt(QScanCell c, double q) {
        MarketRegime[] predicted = c.predicted().toArray(new MarketRegime[0]);
        MarketRegime[] actual = new MarketRegime[c.n()];
        for (int j = 0; j < c.n(); j++) {
            actual[j] = RegimeQScan.labelAt(c.directionality()[j], c.shock()[j], c.sign()[j], q);
        }
        return RegimeClassificationScore.evaluate(predicted, actual);
    }

    /** 某 actual 类别计数 = 该类在混淆矩阵的行和（confusionMatrix 已补 0）。 */
    private static int actualCount(RegimeClassificationScore s, MarketRegime regime) {
        return s.confusionMatrix().get(regime).values().stream().mapToInt(Integer::intValue).sum();
    }

    private void printCell(QScanCell c) {
        int n = c.n();
        long shockCount = 0;
        for (boolean s : c.shock()) if (s) shockCount++;
        // q 只划分非 shock 点，故 directionality 分位也只取非 shock（决定 q 的真实总体）。
        double[] nonShock = new double[n - (int) shockCount];
        int k = 0;
        for (int j = 0; j < n; j++) if (!c.shock()[j]) nonShock[k++] = c.directionality()[j];

        System.out.println();
        System.out.println("---- " + c.symbol() + " " + c.horizonHours() + "h | OOS=" + n
                + " | shock=" + String.format("%.1f", pct((int) shockCount, n)) + "% ----");
        System.out.printf("  directionality pctl(ex-shock): p10=%.2f p25=%.2f p50=%.2f p75=%.2f p90=%.2f p95=%.2f p99=%.2f%n",
                RegimeQScan.percentile(nonShock, 0.10), RegimeQScan.percentile(nonShock, 0.25),
                RegimeQScan.percentile(nonShock, 0.50), RegimeQScan.percentile(nonShock, 0.75),
                RegimeQScan.percentile(nonShock, 0.90), RegimeQScan.percentile(nonShock, 0.95),
                RegimeQScan.percentile(nonShock, 0.99));
        System.out.println("  q      R%    U%    D%    S%  trend%   acc  balAcc macroF1");
        for (double q : SCAN_Q) {
            RegimeClassificationScore s = scoreAt(c, q);
            int up = actualCount(s, MarketRegime.TRENDING_UP), down = actualCount(s, MarketRegime.TRENDING_DOWN),
                    rng = actualCount(s, MarketRegime.RANGING), shk = actualCount(s, MarketRegime.SHOCK);
            System.out.printf("  %.2f %5.1f %5.1f %5.1f %5.1f  %5.1f  %.3f %.3f  %.3f%n",
                    q, pct(rng, n), pct(up, n), pct(down, n), pct(shk, n), pct(up + down, n),
                    s.accuracy(), s.balancedAccuracy(), s.macroF1());
        }
        // 默认 q=2.0 的混淆矩阵：行=actual(directionality 标签)，列=predicted(ADX 分类器)，体检两侧是否鸡同鸭讲。
        printConfusionAt(c, RegimeLabeler.DEFAULT_DIRECTIONALITY_THRESHOLD);
    }

    private void printConfusionAt(QScanCell c, double q) {
        Map<MarketRegime, Map<MarketRegime, Integer>> cm = scoreAt(c, q).confusionMatrix();
        System.out.printf("  confusion @q=%.2f (row=actual, col=pred):  predU   predD   predR   predS%n", q);
        MarketRegime[] order = {MarketRegime.TRENDING_UP, MarketRegime.TRENDING_DOWN, MarketRegime.RANGING, MarketRegime.SHOCK};
        String[] names = {"actU", "actD", "actR", "actS"};
        for (int a = 0; a < order.length; a++) {
            Map<MarketRegime, Integer> row = cm.get(order[a]);
            System.out.printf("    %-6s %7d %7d %7d %7d%n", names[a] + ":",
                    row.get(MarketRegime.TRENDING_UP), row.get(MarketRegime.TRENDING_DOWN),
                    row.get(MarketRegime.RANGING), row.get(MarketRegime.SHOCK));
        }
    }

    /** 跨币种/周期稳定性：同一指标在 cell×q 上铺开，便于一眼看 q 选择稳不稳（不追 ADX）。 */
    private void printStabilityTables(List<QScanCell> cells) {
        System.out.println();
        System.out.println("===== stability: trend%(U+D) by cell x q =====");
        printQHeader();
        for (QScanCell c : cells) {
            StringBuilder sb = new StringBuilder(String.format("  %-9s", c.symbol() + c.horizonHours() + "h"));
            for (double q : SCAN_Q) {
                RegimeClassificationScore s = scoreAt(c, q);
                int trend = actualCount(s, MarketRegime.TRENDING_UP) + actualCount(s, MarketRegime.TRENDING_DOWN);
                sb.append(String.format(" %6.1f", pct(trend, c.n())));
            }
            System.out.println(sb);
        }
        System.out.println();
        System.out.println("===== stability: balancedAccuracy by cell x q =====");
        printQHeader();
        for (QScanCell c : cells) {
            StringBuilder sb = new StringBuilder(String.format("  %-9s", c.symbol() + c.horizonHours() + "h"));
            for (double q : SCAN_Q) {
                sb.append(String.format(" %6.3f", scoreAt(c, q).balancedAccuracy()));
            }
            System.out.println(sb);
        }
        System.out.println();
    }

    private void printQHeader() {
        StringBuilder sb = new StringBuilder(String.format("  %-9s", "cell\\q"));
        for (double q : SCAN_Q) sb.append(String.format(" %6.2f", q));
        System.out.println(sb);
    }

    private static double pct(int part, int total) {
        return total > 0 ? 100.0 * part / total : 0.0;
    }

    private List<MultiOutputForecaster> forecastersFor(ForecastHorizon horizon, long decisionBarMillis,
                                                       VolCandidateMode volCandidates,
                                                       DirectionCandidateMode directionCandidates) {
        List<MultiOutputForecaster> out = new ArrayList<>();
        for (Forecaster direction : directionForecasters(directionCandidates)) {
            if (volCandidates.includeRaw()) {
                out.add(QuantCoreForecaster.defaults(horizon, direction));
                out.add(QuantCoreForecaster.trailingShapeRegime(horizon, decisionBarMillis, direction));
            }
            if (volCandidates.includeShrinkage()) {
                out.add(QuantCoreForecaster.varianceShrinkageEwma(horizon, direction));
                out.add(QuantCoreForecaster.varianceShrinkageTrailingShapeRegime(horizon, decisionBarMillis, direction));
            }
            if (volCandidates.includeClimatology()) {
                out.add(QuantCoreForecaster.climatologyVol(horizon, direction));
                out.add(QuantCoreForecaster.climatologyTrailingShapeRegime(horizon, decisionBarMillis, direction));
            }
        }
        return List.copyOf(out);
    }

    private List<Forecaster> directionForecasters(DirectionCandidateMode directionCandidates) {
        List<Forecaster> out = new ArrayList<>();
        if (directionCandidates.includeFixed()) {
            out.add(MultiFactorForecaster.defaults());
        }
        if (directionCandidates.includeAll5()) {
            out.add(MultiFactorForecaster.allFactors());
        }
        if (directionCandidates.includeContinuous()) {
            out.add(ContinuousFactorForecaster.defaults());
        }
        return List.copyOf(out);
    }

    private EvalRunResult runOne(Connection connection, String symbol, ForecastHorizon horizon,
                                 MultiOutputForecaster forecaster,
                                 long fromMs, long toMs, EvalParams params, Path runDir, String interval,
                                 long decisionBarMillis, EvalProgress progress) throws Exception {
        progress.update("load-klines", 0, 5);
        List<KlineBar> oneMin = loadKlines(connection, symbol, interval, fromMs, toMs);
        progress.update("load-klines", 1, 5);
        List<KlineBar> benchmarkOneMin = "BTCUSDT".equalsIgnoreCase(symbol)
                ? List.of()
                : loadOptionalKlines(connection, "BTCUSDT", interval, fromMs, toMs);
        progress.update("load-benchmark", 2, 5);
        List<MarketSeriesPoint> funding = loadSeries(connection, symbol, SeriesCode.FUNDING, fromMs, toMs);
        progress.update("load-funding", 3, 5);
        List<MarketSeriesPoint> fearGreed = loadSeries(connection, MarketSeriesStore.GLOBAL, SeriesCode.FEAR_GREED, fromMs, toMs);
        List<MarketSeriesPoint> etfFlow = loadSeries(connection, symbol, SeriesCode.ETF_FLOW, fromMs, toMs);
        List<MarketSeriesPoint> stablecoin = loadSeries(connection, symbol, SeriesCode.STABLECOIN_DELTA, fromMs, toMs);
        progress.update("load-series", 5, 5);

        // 量化核心 alone：每个 (symbol,horizon,forecaster) 三输出各自对基准判决。
        MultiOutputReport report = MultiOutputEvalService.evaluateBars(
                symbol, horizon, oneMin, benchmarkOneMin, funding, fearGreed, etfFlow, stablecoin,
                forecaster, params, ResearchEvalService.featureLookbackBars(decisionBarMillis), decisionBarMillis,
                progress);
        progress.update("write-report", 0, 1);
        Path reportFile = writeReport(report, toMs, runDir);
        progress.update("write-report", 1, 1);
        return new EvalRunResult(
                report.forecasterName(),
                symbol,
                horizon.hours(),
                report.decisionBarMinutes(),
                Instant.ofEpochMilli(fromMs).toString(),
                Instant.ofEpochMilli(toMs).toString(),
                oneMin.size(),
                funding.size(),
                fearGreed.size(),
                etfFlow.size(),
                stablecoin.size(),
                report.testPoints(),
                report.directionReturn(),
                report.buyAndHoldReturn(),
                report.directionBeatsBuyHold(),
                report.directionNaivePercentile(),
                report.directionBeatsNaive(),
                report.directionMetrics().annualizedSharpe(),
                report.directionPathSummary().tradedPoints(),
                report.directionPathSummary().avgDirectionalChangeBps(),
                report.directionPathSummary().avgMaxFavorableBps(),
                report.directionPathSummary().avgMaxAdverseBps(),
                report.volScore().qlike(),
                report.volBaselines().values().stream().mapToDouble(s -> s.qlike()).min().orElse(Double.NaN),
                report.bestVolBaselineName(),
                report.volQlikeVsBestBaseline().meanLossDiff(),
                report.volQlikeVsBestBaseline().pValue(),
                report.volCalibration().realizedToPredictedRatio(),
                report.volRiskSummary().medianExpectedMoveBps(),
                report.volRiskSummary().p90ExpectedMoveBps(),
                report.volRiskSummary().elevatedOrStressedShare(),
                report.volRiskSummary().stressedShare(),
                report.volRiskSummary().llmContext(),
                report.volBeatsAllBaselines(),
                report.regimeScore().accuracy(),
                report.regimeScore().naiveAccuracy(),
                report.regimeScore().balancedAccuracy(),
                report.regimeScore().macroF1(),
                report.regimeScore().beatsNaive(),
                report.summary(),
                reportFile.toString());
    }

    private List<KlineBar> loadOptionalKlines(Connection connection, String symbol, String interval,
                                              long fromMs, long toMs) throws Exception {
        try {
            return loadKlines(connection, symbol, interval, fromMs, toMs);
        } catch (IllegalStateException e) {
            System.out.println("[multiOutput.evalRun] optional benchmark missing: " + e.getMessage());
            return List.of();
        }
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

    private Path writeReport(MultiOutputReport report, long toMs, Path runDir) throws Exception {
        Files.createDirectories(runDir);
        // multi- 前缀：与 ResearchEvalRunnerTest 的方向多策略报告同目录但不撞名。
        Path file = runDir.resolve(String.format("multi-%s-%dm-%dh-%s-%s.json",
                report.symbol(), report.decisionBarMinutes(), report.horizonHours(), sanitize(report.forecasterName()),
                FILE_STAMP.format(Instant.ofEpochMilli(toMs))));
        Files.writeString(file, JSON.toJSONString(report, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8);
        return file;
    }

    private Path writeRegimeFeatureReport(String symbol, ForecastHorizon horizon,
                                          RegimeFeatureDiagnostics.Report report,
                                          long toMs, int decisionBarMinutes, Path runDir) throws Exception {
        Files.createDirectories(runDir);
        Path file = runDir.resolve(String.format("regime-features-%s-%dm-%dh-%s.json",
                symbol, decisionBarMinutes, horizon.hours(), FILE_STAMP.format(Instant.ofEpochMilli(toMs))));
        Files.writeString(file, JSON.toJSONString(report, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8);
        return file;
    }

    private void printRegimeFeatureReport(RegimeFeatureDiagnostics.Report report, int topN) {
        System.out.println("    classCounts=" + report.classCounts());
        System.out.println("    top features by etaSquared:");
        for (RegimeFeatureDiagnostics.FeatureReport f : report.topFeatures(topN)) {
            System.out.printf("      %-34s eta=%.4f n=%d | R=%s U=%s D=%s S=%s%n",
                    f.name(), f.etaSquared(), f.n(),
                    fmtStat(f, MarketRegime.RANGING),
                    fmtStat(f, MarketRegime.TRENDING_UP),
                    fmtStat(f, MarketRegime.TRENDING_DOWN),
                    fmtStat(f, MarketRegime.SHOCK));
        }
    }

    private String fmtStat(RegimeFeatureDiagnostics.FeatureReport f, MarketRegime regime) {
        RegimeFeatureDiagnostics.GroupStats s = f.byRegime().get(regime);
        if (s == null || s.n() == 0) {
            return "-";
        }
        return String.format("n=%d,p50=%.4g", s.n(), s.p50());
    }

    private EvalProgress consoleProgress(int runIndex, int totalRuns, String label, long startedAtMs) {
        return new EvalProgress() {
            private String lastStage = "";
            private int lastBucket = -1;
            private long lastPrintedAt = 0L;

            @Override
            public void update(String stage, int done, int total) {
                long now = System.currentTimeMillis();
                int safeTotal = Math.max(0, total);
                int safeDone = safeTotal > 0 ? Math.max(0, Math.min(done, safeTotal)) : Math.max(0, done);
                int bucket = safeTotal > 0 ? (int) Math.floor((double) safeDone * 20.0 / safeTotal) : safeDone;
                boolean stageChanged = !stage.equals(lastStage);
                boolean finished = safeTotal > 0 && safeDone >= safeTotal;
                boolean due = now - lastPrintedAt >= PROGRESS_PRINT_INTERVAL_MS;
                if (!stageChanged && !finished && bucket == lastBucket && !due) {
                    return;
                }
                lastStage = stage;
                lastBucket = bucket;
                lastPrintedAt = now;
                double pct = safeTotal > 0 ? 100.0 * safeDone / safeTotal : 0.0;
                long elapsedSec = (now - startedAtMs) / 1000;
                System.out.printf("[multiOutput.progress] (%d/%d) %-44s | %-16s [%s] %6.2f%% %d/%d elapsed=%ds%n",
                        runIndex, totalRuns, label, stage,
                        MultiOutputEvalService.progressBar(safeDone, safeTotal),
                        pct, safeDone, safeTotal, elapsedSec);
                System.out.flush();
            }
        };
    }

    private String shortForecasterName(String name) {
        if (name != null && name.contains("trailing_shape_transition")) {
            return "trailing_shape";
        }
        if (name != null && name.contains("adx_atr_current")) {
            return "adx_atr";
        }
        return name == null ? "unknown" : sanitize(name);
    }

    private String sanitize(String raw) {
        return raw == null ? "unknown" : raw.replaceAll("[^A-Za-z0-9._-]+", "_");
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
                ResearchEvalService.parseDecisionBarMillis(
                        propertyOrEnv("eval.decisionInterval", "EVAL_DECISION_INTERVAL", DEFAULT_DECISION_INTERVAL)),
                from == null ? null : from.toEpochMilli(),
                to.toEpochMilli(),
                Integer.getInteger("eval.days", 365),
                propertyOrEnv("eval.db.url", "WIIB_DB_URL",
                        "jdbc:postgresql://localhost:5432/wiib?reWriteBatchedInserts=true"),
                propertyOrEnv("eval.db.user", "WIIB_DB_USER", "mawai"),
                propertyOrEnv("eval.db.password", "WIIB_DB_PASSWORD", ""),
                VolCandidateMode.parse(propertyOrEnv("eval.volCandidates", "EVAL_VOL_CANDIDATES", "raw")),
                DirectionCandidateMode.parse(propertyOrEnv("eval.directionCandidates", "EVAL_DIRECTION_CANDIDATES", "fixed")),
                booleanPropertyOrEnv("eval.parallelHorizons", "EVAL_PARALLEL_HORIZONS", false)
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

    private boolean booleanPropertyOrEnv(String property, String env, boolean defaultValue) {
        String raw = propertyOrEnv(property, env, null);
        return raw == null ? defaultValue : Boolean.parseBoolean(raw);
    }

    private Connection openConnection(EvalRunConfig cfg) throws Exception {
        Connection connection = DriverManager.getConnection(cfg.jdbcUrl(), cfg.dbUser(), cfg.dbPassword());
        connection.setAutoCommit(false);
        configureSession(connection);
        return connection;
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
            long decisionBarMillis,
            Long fromMs,
            long toMs,
            int days,
            String jdbcUrl,
            String dbUser,
            String dbPassword,
            VolCandidateMode volCandidates,
            DirectionCandidateMode directionCandidates,
            boolean parallelHorizons
    ) {
        EvalRunConfig masked() {
            return new EvalRunConfig(symbols, horizons, interval, decisionBarMillis, fromMs, toMs,
                    days, maskJdbcUrl(jdbcUrl), dbUser, "***", volCandidates, directionCandidates, parallelHorizons);
        }

        int decisionBarMinutes() {
            return ResearchEvalService.minutes(decisionBarMillis);
        }

        private static String maskJdbcUrl(String jdbcUrl) {
            int queryIndex = jdbcUrl.indexOf('?');
            return queryIndex >= 0 ? jdbcUrl.substring(0, queryIndex) + "?..." : jdbcUrl;
        }
    }

    private enum VolCandidateMode {
        RAW,
        SHRINKAGE,
        CLIMATOLOGY,
        EXTENDED;

        static VolCandidateMode parse(String raw) {
            String value = raw == null || raw.isBlank()
                    ? "raw"
                    : raw.trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "raw" -> RAW;
                case "shrinkage", "variance_shrinkage" -> SHRINKAGE;
                case "climatology", "constant" -> CLIMATOLOGY;
                case "extended", "all" -> EXTENDED;
                default -> throw new IllegalArgumentException(
                        "eval.volCandidates 只支持 raw|shrinkage|climatology|extended，实际=" + raw);
            };
        }

        boolean includeRaw() {
            return this == RAW || this == EXTENDED;
        }

        boolean includeShrinkage() {
            return this == SHRINKAGE || this == EXTENDED;
        }

        boolean includeClimatology() {
            return this == CLIMATOLOGY || this == EXTENDED;
        }
    }

    private enum DirectionCandidateMode {
        FIXED,
        ALL5,
        CONTINUOUS,
        EXTENDED;

        static DirectionCandidateMode parse(String raw) {
            String value = raw == null || raw.isBlank()
                    ? "fixed"
                    : raw.trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "fixed", "default", "multi_factor" -> FIXED;
                case "all5", "recommended", "best" -> ALL5;
                case "continuous", "continuous_factor" -> CONTINUOUS;
                case "extended", "all", "scan" -> EXTENDED;
                default -> throw new IllegalArgumentException(
                        "eval.directionCandidates 只支持 fixed|all5|continuous|extended，实际=" + raw);
            };
        }

        boolean includeFixed() {
            return this == FIXED || this == EXTENDED;
        }

        boolean includeAll5() {
            return this == ALL5 || this == EXTENDED;
        }

        boolean includeContinuous() {
            return this == CONTINUOUS || this == EXTENDED;
        }
    }

    private record EvalRunResult(
            String forecasterName,
            String symbol,
            int horizonHours,
            int decisionBarMinutes,
            String from,
            String to,
            int klineRows,
            int fundingRows,
            int fearGreedRows,
            int etfRows,
            int stablecoinRows,
            int testPoints,
            // 方向腿判决
            BigDecimal directionReturn,
            BigDecimal buyAndHold,
            boolean directionBeatsBuyHold,
            double directionNaivePercentile,
            boolean directionBeatsNaive,
            double directionSharpe,
            int directionTradedPoints,
            double directionAvgChangeBps,
            double directionAvgMfeBps,
            double directionAvgMaeBps,
            // 波动率腿判决（model QLIKE vs 多基准，beatsAll 以最难基准为准）
            double volQlike,
            double volBestBaselineQlike,
            String volBestBaselineName,
            double volBestBaselineMeanLossDiff,
            double volBestBaselinePValue,
            double volCalibrationRatio,
            int volMedianExpectedMoveBps,
            int volP90ExpectedMoveBps,
            double volElevatedOrStressedShare,
            double volStressedShare,
            String volRiskLlmContext,
            boolean volBeatsAllBaselines,
            // regime 腿判决（vs persistence）
            double regimeAccuracy,
            double regimeNaiveAccuracy,
            double regimeBalancedAccuracy,
            double regimeMacroF1,
            boolean regimeBeatsNaive,
            String summary,
            String reportFile
    ) {}

    private record RegimeFeatureRunResult(
            String symbol,
            int horizonHours,
            int decisionBarMinutes,
            String from,
            String to,
            int oosSamples,
            Map<MarketRegime, Integer> classCounts,
            List<RegimeFeatureDiagnostics.FeatureReport> topFeatures,
            String reportFile
    ) {}

    /** q 扫描单格：一个 (symbol,horizon) 的 OOS 样本原子量 + 预测 regime（按 q 重建标签用）。 */
    private record QScanCell(
            String symbol,
            int horizonHours,
            int n,
            double[] directionality,
            boolean[] shock,
            int[] sign,
            List<MarketRegime> predicted
    ) {}
}
