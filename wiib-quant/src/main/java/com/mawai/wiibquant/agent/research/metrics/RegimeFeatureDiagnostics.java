package com.mawai.wiibquant.agent.research.metrics;

import com.mawai.wiibquant.agent.research.factor.ContinuousFactorVector;
import com.mawai.wiibquant.agent.research.factor.FactorMath;
import com.mawai.wiibquant.agent.research.forecast.MarketRegime;
import com.mawai.wiibquant.agent.research.forecast.ResearchFeatures;
import com.mawai.wiibquant.agent.research.forecast.TrainingSample;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.research.stats.VolatilityEstimator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;

/**
 * regime 特征诊断：只回答“现有 point-in-time 特征按未来 regime 分组后有没有分离度”。
 * etaSquared 是单变量组间方差占比；不是模型分数，只用来决定下一步该看哪条腿。
 */
public final class RegimeFeatureDiagnostics {

    private static final long TRAILING_LOOKBACK_MILLIS = Duration.ofHours(6).toMillis();
    private static final long DEFAULT_BAR_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final double VAR_FLOOR = 1e-12;

    private RegimeFeatureDiagnostics() {
    }

    public static Report evaluate(List<TrainingSample> samples) {
        Map<MarketRegime, Integer> classCounts = new EnumMap<>(MarketRegime.class);
        Map<String, List<LabeledValue>> valuesByFeature = new LinkedHashMap<>();
        int n = 0;
        if (samples != null) {
            for (TrainingSample sample : samples) {
                if (sample == null || sample.features() == null || sample.realizedRegime() == null) {
                    continue;
                }
                MarketRegime regime = sample.realizedRegime();
                classCounts.merge(regime, 1, Integer::sum);
                n++;
                for (NamedValue v : extract(sample.features())) {
                    valuesByFeature.computeIfAbsent(v.name(), ignored -> new ArrayList<>())
                            .add(new LabeledValue(regime, v.value()));
                }
            }
        }

        List<FeatureReport> features = new ArrayList<>();
        for (Map.Entry<String, List<LabeledValue>> e : valuesByFeature.entrySet()) {
            features.add(toFeatureReport(e.getKey(), e.getValue()));
        }
        features.sort(Comparator.comparingDouble(FeatureReport::etaSquared).reversed()
                .thenComparing(FeatureReport::name));
        return new Report(n, completeCounts(classCounts), List.copyOf(features));
    }

    private static List<NamedValue> extract(ResearchFeatures features) {
        List<NamedValue> out = new ArrayList<>();
        add(out, "funding_rate", features.fundingRate());
        add(out, "fear_greed", features.fearGreed());
        add(out, "etf_flow", features.etfFlow());
        add(out, "stablecoin_delta", features.stablecoinDelta());

        ContinuousFactorVector c = features.continuousFactors();
        if (c != null) {
            add(out, "risk_adjusted_momentum", c.riskAdjustedMomentum());
            add(out, "short_reversal", c.shortReversal());
            add(out, "funding_carry", c.fundingCarry());
            add(out, "volume_zscore", c.volumeZScore());
            add(out, "amihud_illiquidity", c.amihudIlliquidity());
            add(out, "residual_momentum", c.residualMomentum());
        }

        addTrailingShape(out, features.barsUpToNow());
        return out;
    }

    private static void addTrailingShape(List<NamedValue> out, List<KlineBar> bars) {
        if (bars == null || bars.size() < 3) {
            return;
        }
        int lookbackBars = barsForTrailingLookback(bars);
        List<KlineBar> window = bars.subList(Math.max(0, bars.size() - lookbackBars), bars.size());
        double netReturn = FactorMath.logReturn(
                window.get(window.size() - 1).close().doubleValue(),
                window.get(0).close().doubleValue());
        double realizedVol = VolatilityEstimator.realizedVolatility(window);
        double directionality = realizedVol > 0.0 ? Math.abs(netReturn) / realizedVol : 0.0;
        double signedDirectionality = Math.copySign(directionality, netReturn);
        double lastAbsReturn = Math.abs(FactorMath.logReturn(
                bars.get(bars.size() - 1).close().doubleValue(),
                bars.get(bars.size() - 2).close().doubleValue()));
        double sigma = VolatilityEstimator.ewmaVolatility(bars, 0.94);
        double lastShockRatio = sigma > 0.0 ? lastAbsReturn / sigma : 0.0;

        add(out, "trailing_net_return", netReturn);
        add(out, "trailing_realized_vol", realizedVol);
        add(out, "trailing_directionality", directionality);
        add(out, "trailing_signed_directionality", signedDirectionality);
        add(out, "last_abs_return_over_sigma", lastShockRatio);
    }

    private static FeatureReport toFeatureReport(String name, List<LabeledValue> values) {
        Map<MarketRegime, List<Double>> grouped = new EnumMap<>(MarketRegime.class);
        double globalSum = 0.0;
        int globalN = 0;
        for (LabeledValue v : values) {
            grouped.computeIfAbsent(v.regime(), ignored -> new ArrayList<>()).add(v.value());
            globalSum += v.value();
            globalN++;
        }
        double globalMean = globalN > 0 ? globalSum / globalN : 0.0;
        double between = 0.0;
        double total = 0.0;
        Map<MarketRegime, GroupStats> stats = new EnumMap<>(MarketRegime.class);
        for (MarketRegime regime : MarketRegime.values()) {
            List<Double> raw = grouped.getOrDefault(regime, List.of());
            GroupStats s = toStats(raw);
            stats.put(regime, s);
            between += raw.size() * square(s.mean() - globalMean);
            for (double value : raw) {
                total += square(value - globalMean);
            }
        }
        double etaSquared = total > VAR_FLOOR ? between / total : 0.0;
        return new FeatureReport(name, globalN, etaSquared, immutableEnumMap(stats));
    }

    private static GroupStats toStats(List<Double> raw) {
        if (raw == null || raw.isEmpty()) {
            return new GroupStats(0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
        double[] values = new double[raw.size()];
        double sum = 0.0;
        for (int i = 0; i < raw.size(); i++) {
            values[i] = raw.get(i);
            sum += values[i];
        }
        Arrays.sort(values);
        double mean = sum / values.length;
        double sumSq = 0.0;
        for (double value : values) {
            sumSq += square(value - mean);
        }
        double std = values.length >= 2 ? Math.sqrt(sumSq / (values.length - 1)) : 0.0;
        return new GroupStats(values.length, mean, std,
                percentile(values, 0.25), percentile(values, 0.50), percentile(values, 0.75));
    }

    private static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) return 0.0;
        if (sorted.length == 1) return sorted[0];
        double pos = Math.clamp(p, 0.0, 1.0) * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return sorted[lo];
        double w = pos - lo;
        return sorted[lo] * (1.0 - w) + sorted[hi] * w;
    }

    private static Map<MarketRegime, Integer> completeCounts(Map<MarketRegime, Integer> raw) {
        Map<MarketRegime, Integer> out = new EnumMap<>(MarketRegime.class);
        for (MarketRegime regime : MarketRegime.values()) {
            out.put(regime, raw.getOrDefault(regime, 0));
        }
        return immutableEnumMap(out);
    }

    private static <T> Map<MarketRegime, T> immutableEnumMap(Map<MarketRegime, T> raw) {
        return Collections.unmodifiableMap(new EnumMap<>(raw));
    }

    private static void add(List<NamedValue> out, String name, double value) {
        if (Double.isFinite(value)) {
            out.add(new NamedValue(name, value));
        }
    }

    private static double square(double value) {
        return value * value;
    }

    private static int barsForTrailingLookback(List<KlineBar> bars) {
        long barMillis = inferBarMillis(bars);
        return Math.max(3, Math.toIntExact((TRAILING_LOOKBACK_MILLIS + barMillis - 1L) / barMillis));
    }

    private static long inferBarMillis(List<KlineBar> bars) {
        if (bars == null || bars.size() < 2) {
            return DEFAULT_BAR_MILLIS;
        }
        KlineBar last = bars.get(bars.size() - 1);
        KlineBar prev = bars.get(bars.size() - 2);
        long interval = last.openTime() - prev.openTime();
        return interval > 0L ? interval : DEFAULT_BAR_MILLIS;
    }

    private record NamedValue(String name, double value) {
    }

    private record LabeledValue(MarketRegime regime, double value) {
    }

    public record Report(int n, Map<MarketRegime, Integer> classCounts, List<FeatureReport> features) {
        public List<FeatureReport> topFeatures(int limit) {
            return features.subList(0, Math.clamp(limit, 0, features.size()));
        }
    }

    public record FeatureReport(String name, int n, double etaSquared, Map<MarketRegime, GroupStats> byRegime) {
    }

    public record GroupStats(int n, double mean, double std, double p25, double p50, double p75) {
    }
}
