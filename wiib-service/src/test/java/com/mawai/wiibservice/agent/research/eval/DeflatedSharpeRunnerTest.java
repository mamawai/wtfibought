package com.mawai.wiibservice.agent.research.eval;

import com.alibaba.fastjson2.JSON;
import com.mawai.wiibservice.agent.research.metrics.DeflatedSharpe;
import com.mawai.wiibservice.agent.research.metrics.ReturnSeries;
import com.mawai.wiibservice.agent.research.metrics.RiskAdjustedMetrics;
import com.mawai.wiibservice.agent.research.metrics.SharpeStats;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DSR 多重比较重判 runner（gated -DdsrRun=true，不启 Spring/DB、零重跑）：
 * 默认读 target/research-eval/runs/<最新run>/*.json（兼容旧平铺目录）→ 对每个 (symbol×horizon×strategy) 试验算 Deflated Sharpe，
 * 回答"试了 N 组配置后，最好那条 Sharpe 还显不显著"。复用 ResearchEvalRunner 的 gated 惯例。
 * 核心数学已被 NormalDistribution/SharpeStats/DeflatedSharpe 单测覆盖；此处端到端验证读取+编排。
 */
class DeflatedSharpeRunnerTest {

    private static final double SIGNIFICANCE = 0.95; // 与 spec naive 门槛同口径

    /** 一个被搜过的配置：一条 (cell, 预测器) 的样本外收益。 */
    private record Trial(String symbol, int horizon, String name, int t,
                         double annualizedSharpe, double naivePercentile, SharpeStats stats) {
        String cell() {
            return symbol + "-" + horizon + "h";
        }

        String label() {
            return cell() + " " + name;
        }
    }

    private record LoadedTrials(Path inputDir, int jsonFiles, int validReports, int reportsUsed,
                                int ignoredDuplicateReports, List<Trial> trials) {
    }

    private record ReportCandidate(Path path, ComparisonReport report, FileTime modifiedTime) {
        String cellKey() {
            return report.symbol() + "|" + report.horizonHours();
        }
    }

    /** multi-*.json 的轻量投影：只取 DSR 所需字段，避开 MultiOutputReport 里枚举键 Map 等无关结构的反序列化。 */
    private record MultiReportView(String symbol, int horizonHours, String forecasterName,
                                   double directionNaivePercentile, ReturnSeries directionReturnSeries) {
    }

    private record MultiCandidate(Path path, MultiReportView view, FileTime modifiedTime) {
        String legKey() {
            return view.symbol() + "|" + view.horizonHours() + "|" + directionLegName(view.forecasterName());
        }
    }

    @Test
    void loadTrialsKeepsLatestReportPerCell(@TempDir Path dir) throws Exception {
        writeReport(dir.resolve("BTCUSDT-6h-old.json"),
                report("BTCUSDT", 6, "old_strategy", -0.02, 0.01, -0.01),
                Instant.parse("2026-01-01T00:00:00Z"));
        writeReport(dir.resolve("BTCUSDT-6h-new.json"),
                report("BTCUSDT", 6, "latest_strategy", 0.03, 0.02, 0.01),
                Instant.parse("2026-01-02T00:00:00Z"));
        writeReport(dir.resolve("ETHUSDT-6h.json"),
                report("ETHUSDT", 6, "eth_strategy", 0.01, -0.01, 0.02),
                Instant.parse("2026-01-01T12:00:00Z"));

        LoadedTrials loaded = loadTrials(dir);

        assertThat(loaded.jsonFiles()).isEqualTo(3);
        assertThat(loaded.validReports()).isEqualTo(3);
        assertThat(loaded.reportsUsed()).isEqualTo(2);
        assertThat(loaded.ignoredDuplicateReports()).isEqualTo(1);
        assertThat(loaded.trials()).extracting(Trial::label)
                .containsExactly("BTCUSDT-6h latest_strategy", "ETHUSDT-6h eth_strategy");
    }

    @Test
    void resolveInputDirDefaultsToNewestRun(@TempDir Path baseDir) throws Exception {
        Path older = ResearchEvalArtifacts.runDir(baseDir, "20260101000000");
        Path newer = ResearchEvalArtifacts.runDir(baseDir, "20260102000000");
        writeReport(older.resolve("BTCUSDT-6h.json"), report("BTCUSDT", 6, "old", 0.01, 0.02),
                Instant.parse("2026-01-01T00:00:00Z"));
        writeReport(newer.resolve("BTCUSDT-6h.json"), report("BTCUSDT", 6, "new", 0.02, 0.03),
                Instant.parse("2026-01-02T00:00:00Z"));
        Files.setLastModifiedTime(older, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(newer, FileTime.from(Instant.parse("2026-01-02T00:00:00Z")));

        assertThat(resolveInputDir(null, null, baseDir)).isEqualTo(newer);
        assertThat(resolveInputDir(baseDir.resolve("legacy").toString(), null, baseDir))
                .isEqualTo(baseDir.resolve("legacy"));
        assertThat(resolveInputDir(null, "custom:run", baseDir))
                .isEqualTo(baseDir.resolve("runs").resolve("custom_run"));
    }

    @Test
    void deflatedSharpeAcrossAllReports() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("dsrRun"),
                "skip: add -DdsrRun=true to run DSR multiple-comparison reframe");

        LoadedTrials loaded = loadTrials(resolveInputDir());
        List<Trial> trials = loaded.trials();
        assertThat(trials).as("应从已落地报告读到试验: " + loaded.inputDir()).isNotEmpty();

        // —— 主口径 N=全体试验数：跨所有 cell 的 per-period Sharpe 方差 → SR0 → 每条 DSR ——
        int n = trials.size();
        SharpeStats[] all = trials.stream().map(Trial::stats).toArray(SharpeStats[]::new);
        double v = DeflatedSharpe.varianceOfSharpes(all);
        double sr0 = DeflatedSharpe.expectedMaxSharpe(v, n);

        List<Trial> ranked = trials.stream()
                .sorted(Comparator.comparingDouble((Trial t) -> t.stats().sharpe()).reversed())
                .toList();

        System.out.println();
        System.out.println("========== Deflated Sharpe 多重比较重判 ==========");
        System.out.printf("输入目录=%s | JSON文件=%d | 有效报告=%d | 使用报告=%d | 忽略重复cell报告=%d%n",
                loaded.inputDir(), loaded.jsonFiles(), loaded.validReports(), loaded.reportsUsed(),
                loaded.ignoredDuplicateReports());
        System.out.printf("试验数 N=%d | Sharpe方差 V=%.6f | 期望最大Sharpe SR0(每期)=%.4f | 显著门槛 DSR≥%.2f%n",
                n, v, sr0, SIGNIFICANCE);
        System.out.println("(per-period 口径；N=全部 cell，跨周期、相关且不等长——标准 DSR 简化，方向正确不夸大)");
        System.out.println("--------------------------------------------------");
        System.out.printf("%-36s %5s %9s %10s %7s %10s %s%n",
                "cell/strategy", "T", "SR/期", "年化Sharpe", "naive%", "DSR(N=" + n + ")", "判定");
        for (Trial tr : ranked) {
            double dsr = DeflatedSharpe.deflatedSharpe(tr.stats(), sr0);
            assertThat(dsr).as(tr.label()).isBetween(0.0, 1.0);
            System.out.printf("%-36s %5d %9.4f %10.2f %7.1f %10.3f %s%n",
                    tr.label(), tr.t(), tr.stats().sharpe(), tr.annualizedSharpe(),
                    tr.naivePercentile() * 100, dsr, dsr >= SIGNIFICANCE ? "显著" : "不显著");
        }

        // —— 敏感性：族内 N=4（同一 cell 的 4 预测器各自做 DSR）——
        System.out.println("--------------------------------------------------");
        System.out.println("族内敏感性 N=4 (每 cell 的 4 预测器):");
        Map<String, List<Trial>> byCell = new TreeMap<>();
        for (Trial tr : trials) byCell.computeIfAbsent(tr.cell(), k -> new ArrayList<>()).add(tr);
        for (Map.Entry<String, List<Trial>> e : byCell.entrySet()) {
            SharpeStats[] fam = e.getValue().stream().map(Trial::stats).toArray(SharpeStats[]::new);
            double sr0f = DeflatedSharpe.expectedMaxSharpe(DeflatedSharpe.varianceOfSharpes(fam), fam.length);
            for (Trial tr : e.getValue()) {
                double dsrF = DeflatedSharpe.deflatedSharpe(tr.stats(), sr0f);
                System.out.printf("  %-36s SR0(N=%d)=%.4f DSR=%.3f %s%n",
                        tr.label(), fam.length, sr0f, dsrF, dsrF >= SIGNIFICANCE ? "显著" : "不显著");
            }
        }

        // —— 点名候选：BTC-6h onchain_etf_stablecoin（前评估里 Sharpe1.42 / naive94.4% 最接近过线）——
        trials.stream()
                .filter(tr -> "BTCUSDT".equals(tr.symbol()) && tr.horizon() == 6
                        && "onchain_etf_stablecoin".equals(tr.name()))
                .findFirst()
                .ifPresent(tr -> {
                    double dsr = DeflatedSharpe.deflatedSharpe(tr.stats(), sr0);
                    System.out.println("--------------------------------------------------");
                    System.out.printf("候选 %s：naive分位=%.1f%%，但 DSR(N=%d)=%.3f → %s%n",
                            tr.label(), tr.naivePercentile() * 100, n, dsr,
                            dsr >= SIGNIFICANCE ? "扛住多重检验" : "扛不住多重检验(多看了N组,单条naive门槛失效)");
                });
        System.out.println("==================================================");
    }

    @Test
    void loadTrialsReadsMultiReportsDedupedByDirectionLeg(@TempDir Path dir) throws Exception {
        ReturnSeries all5 = new ReturnSeries("all5", List.of(bd(0.01), bd(-0.005), bd(0.02), bd(0.0)), 1460);
        ReturnSeries fixed3 = new ReturnSeries("fixed3", List.of(bd(0.001), bd(0.0), bd(-0.001), bd(0.002)), 1460);
        // 同一 cell 的 dir=all5 配两条不同 vol 腿 → 必须去重成一条试验，否则 N 与 Sharpe 方差被重复污染
        writeMultiReport(dir.resolve("multi-BTCUSDT-5m-6h-all5-ewma.json"),
                "quant_core[vol=ewma,regime=adx_atr_current,dir=multi_factor_all5]", all5,
                Instant.parse("2026-01-01T00:00:00Z"));
        writeMultiReport(dir.resolve("multi-BTCUSDT-5m-6h-all5-clim.json"),
                "quant_core[vol=climatology,regime=adx_atr_current,dir=multi_factor_all5]", all5,
                Instant.parse("2026-01-02T00:00:00Z"));
        writeMultiReport(dir.resolve("multi-BTCUSDT-5m-6h-fixed3.json"),
                "quant_core[vol=ewma,regime=adx_atr_current,dir=multi_factor_trend_funding_fng]", fixed3,
                Instant.parse("2026-01-01T06:00:00Z"));

        LoadedTrials loaded = loadTrials(dir);

        assertThat(loaded.jsonFiles()).isEqualTo(3);
        assertThat(loaded.reportsUsed()).isEqualTo(2);                 // all5 去重后 1 条 + fixed3 1 条
        assertThat(loaded.ignoredDuplicateReports()).isEqualTo(1);     // 被去重的那条 all5
        assertThat(loaded.trials()).extracting(Trial::name)
                .containsExactlyInAnyOrder("multi_factor_all5", "multi_factor_trend_funding_fng");
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    /** 写一份只含 DSR 所需字段的 multi-*.json（投影解析忽略其余字段）。 */
    private static void writeMultiReport(Path file, String forecasterName, ReturnSeries series, Instant modifiedTime)
            throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", "BTCUSDT");
        m.put("horizonHours", 6);
        m.put("forecasterName", forecasterName);
        m.put("directionNaivePercentile", 1.0);
        m.put("directionReturnSeries", series);
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.toJSONString(m), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.from(modifiedTime));
    }

    private static Path resolveInputDir() throws IOException {
        return resolveInputDir(System.getProperty("dsr.dir"), System.getProperty("dsr.runId"),
                ResearchEvalArtifacts.baseDir());
    }

    private static Path resolveInputDir(String explicitDir, String runId, Path baseDir) throws IOException {
        if (explicitDir != null && !explicitDir.isBlank()) {
            return Path.of(explicitDir.trim());
        }
        if (runId != null && !runId.isBlank()) {
            return ResearchEvalArtifacts.runDir(baseDir, runId);
        }
        return latestRunDir(baseDir).orElse(baseDir);
    }

    private static Optional<Path> latestRunDir(Path baseDir) throws IOException {
        Path runsDir = ResearchEvalArtifacts.runsDir(baseDir);
        if (!Files.isDirectory(runsDir)) {
            return Optional.empty();
        }
        try (Stream<Path> dirs = Files.list(runsDir)) {
            return dirs.filter(Files::isDirectory)
                    .filter(DeflatedSharpeRunnerTest::hasJson)
                    .max(DeflatedSharpeRunnerTest::compareRunDir);
        }
    }

    private static boolean hasJson(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(p -> p.toString().endsWith(".json"));
        } catch (IOException e) {
            return false;
        }
    }

    private static int compareRunDir(Path left, Path right) {
        try {
            int byTime = Files.getLastModifiedTime(left).compareTo(Files.getLastModifiedTime(right));
            if (byTime != 0) return byTime;
        } catch (IOException ignored) {
            // 拿不到 mtime 时退回 runId 字符序；默认 runId 是 UTC 时间戳，仍可稳定选最新。
        }
        return left.getFileName().toString().compareTo(right.getFileName().toString());
    }

    /** 读目录下报告 JSON → ComparisonReport 每 cell 取最新、摊平为试验；multi-*.json 按 (cell×方向腿) 去重各出一条试验。 */
    private static LoadedTrials loadTrials(Path dir) throws Exception {
        List<Trial> trials = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return new LoadedTrials(dir, 0, 0, 0, 0, trials);
        }
        List<Path> jsons;
        try (Stream<Path> files = Files.list(dir)) {
            jsons = files.filter(f -> f.toString().endsWith(".json")).sorted().toList();
        }

        Map<String, ReportCandidate> latestByCell = new TreeMap<>();
        int comparisonValid = 0;
        // multi-*.json：同一方向腿会配多条 vol/regime 腿重复落盘，按 (cell×方向腿) 只计最新一条，避免污染 DSR 的 N 与方差
        Map<String, MultiCandidate> latestMultiByLeg = new TreeMap<>();
        int multiValid = 0;
        for (Path p : jsons) {
            String json = Files.readString(p, StandardCharsets.UTF_8);
            if (isMultiReport(p)) {
                MultiReportView view = JSON.parseObject(json, MultiReportView.class); // 轻量投影，忽略 vol/regime 等无关字段
                if (view == null || view.symbol() == null || view.directionReturnSeries() == null
                        || view.directionReturnSeries().periodReturns() == null) {
                    continue;
                }
                multiValid++;
                MultiCandidate candidate = new MultiCandidate(p, view, Files.getLastModifiedTime(p));
                MultiCandidate current = latestMultiByLeg.get(candidate.legKey());
                if (current == null || compareMtime(candidate.modifiedTime(), candidate.path(),
                        current.modifiedTime(), current.path()) > 0) {
                    latestMultiByLeg.put(candidate.legKey(), candidate);
                }
                continue;
            }
            ComparisonReport report = JSON.parseObject(json, ComparisonReport.class);
            if (report == null || report.symbol() == null || report.strategies() == null) continue; // 跳过非报告 JSON
            comparisonValid++;
            ReportCandidate candidate = new ReportCandidate(p, report, Files.getLastModifiedTime(p));
            ReportCandidate current = latestByCell.get(candidate.cellKey());
            if (current == null || compareReportCandidate(candidate, current) > 0) {
                latestByCell.put(candidate.cellKey(), candidate);
            }
        }

        // 重复 cell 只保留最新报告，避免复跑历史把同一策略重复计入 N。
        for (ReportCandidate candidate : latestByCell.values()) {
            ComparisonReport report = candidate.report();
            for (StrategyLine s : report.strategies()) {
                if (s == null || s.returnSeries() == null || s.returnSeries().periodReturns() == null
                        || s.metrics() == null) {
                    continue;
                }
                double[] r = s.returnSeries().periodReturns().stream()
                        .mapToDouble(BigDecimal::doubleValue).toArray();
                SharpeStats stats = SharpeStats.of(r);                        // per-period SR/skew/kurt
                trials.add(new Trial(report.symbol(), report.horizonHours(), s.name(),
                        r.length, s.metrics().annualizedSharpe(), s.naivePercentile(), stats));
            }
        }
        // multi 报告：每个去重后的方向腿一条试验；年化 Sharpe 由 per-period 矩回推（与 RiskAdjustedMetrics 同口径）
        for (MultiCandidate candidate : latestMultiByLeg.values()) {
            MultiReportView v = candidate.view();
            double[] r = v.directionReturnSeries().periodReturns().stream()
                    .mapToDouble(BigDecimal::doubleValue).toArray();
            SharpeStats stats = SharpeStats.of(r);
            double annualized = stats.sharpe() * Math.sqrt(Math.max(1, v.directionReturnSeries().periodsPerYear()));
            trials.add(new Trial(v.symbol(), v.horizonHours(), directionLegName(v.forecasterName()),
                    r.length, annualized, v.directionNaivePercentile(), stats));
        }
        int validReports = comparisonValid + multiValid;
        int reportsUsed = latestByCell.size() + latestMultiByLeg.size();
        return new LoadedTrials(dir, jsons.size(), validReports, reportsUsed,
                validReports - reportsUsed, trials);
    }

    private static final Pattern DIRECTION_LEG = Pattern.compile("dir=([^\\]]+)");

    private static boolean isMultiReport(Path p) {
        return p.getFileName().toString().startsWith("multi-");
    }

    /** 从 quant_core[...,dir=NAME] 抽出方向腿名；同名方向腿（不同 vol/regime 配对）视为同一条试验。 */
    private static String directionLegName(String forecasterName) {
        if (forecasterName == null) return "unknown";
        Matcher m = DIRECTION_LEG.matcher(forecasterName);
        return m.find() ? m.group(1) : forecasterName;
    }

    private static int compareMtime(FileTime leftTime, Path leftPath, FileTime rightTime, Path rightPath) {
        int byTime = leftTime.compareTo(rightTime);
        if (byTime != 0) return byTime;
        return leftPath.getFileName().toString().compareTo(rightPath.getFileName().toString());
    }

    private static int compareReportCandidate(ReportCandidate left, ReportCandidate right) {
        int byTime = left.modifiedTime().compareTo(right.modifiedTime());
        if (byTime != 0) return byTime;
        return left.path().getFileName().toString().compareTo(right.path().getFileName().toString());
    }

    private static void writeReport(Path file, ComparisonReport report, Instant modifiedTime) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.toJSONString(report), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.from(modifiedTime));
    }

    private static ComparisonReport report(String symbol, int horizon, String strategy, double... returns) {
        List<BigDecimal> periodReturns = new ArrayList<>(returns.length);
        for (double r : returns) {
            periodReturns.add(BigDecimal.valueOf(r));
        }
        ReturnSeries series = new ReturnSeries(strategy, periodReturns, 365);
        StrategyLine line = new StrategyLine(strategy, RiskAdjustedMetrics.from(series), BigDecimal.ZERO,
                false, 0.5, false, series);
        return new ComparisonReport(symbol, horizon, 5, 100, periodReturns.size(), BigDecimal.ZERO, List.of(line));
    }
}
