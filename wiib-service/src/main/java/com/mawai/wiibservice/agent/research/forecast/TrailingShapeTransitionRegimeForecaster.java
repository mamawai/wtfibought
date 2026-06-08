package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.factor.FactorMath;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.stats.VolatilityEstimator;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * future-regime 候选腿：用训练窗学习「当前 trailing path shape → 下一 horizon actual regime」的转移分布。
 * 预测时只看 features.barsUpToNow；RANGING 常是多数类，所以非 RANGING 允许凭条件概率+相对先验 lift 覆盖多数类。
 */
public final class TrailingShapeTransitionRegimeForecaster implements RegimeForecaster {

    private static final double VAR_FLOOR = 1e-12;
    private static final double DEFAULT_DIRECTIONALITY_THRESHOLD = 1.25;
    private static final double DEFAULT_SHOCK_MULTIPLE = 3.0;
    private static final double DEFAULT_MIN_MINOR_PROB = 0.12;
    private static final double DEFAULT_MIN_MINOR_LIFT = 1.25;
    private static final long DEFAULT_LOOKBACK_MILLIS = Duration.ofHours(6).toMillis();
    private static final long DEFAULT_BAR_MILLIS = Duration.ofMinutes(5).toMillis();

    private final int lookbackBars;
    private final int minTransitions;
    private final double directionalityThreshold;
    private final double shockMultiple;
    private final double minMinorProbability;
    private final double minMinorLift;
    private final Map<MarketRegime, int[]> transitions;
    private final int[] priorCounts;
    private final int totalTrain;

    public TrailingShapeTransitionRegimeForecaster(int lookbackBars, int minTransitions) {
        this(lookbackBars, minTransitions, DEFAULT_DIRECTIONALITY_THRESHOLD, DEFAULT_SHOCK_MULTIPLE,
                DEFAULT_MIN_MINOR_PROB, DEFAULT_MIN_MINOR_LIFT, Map.of(), new int[MarketRegime.values().length], 0);
    }

    private TrailingShapeTransitionRegimeForecaster(int lookbackBars, int minTransitions,
                                                   double directionalityThreshold, double shockMultiple,
                                                   double minMinorProbability, double minMinorLift,
                                                   Map<MarketRegime, int[]> transitions,
                                                   int[] priorCounts,
                                                   int totalTrain) {
        if (lookbackBars < 3) {
            throw new IllegalArgumentException("lookbackBars 至少需要 3");
        }
        if (minTransitions <= 0) {
            throw new IllegalArgumentException("minTransitions 必须为正数");
        }
        this.lookbackBars = lookbackBars;
        this.minTransitions = minTransitions;
        this.directionalityThreshold = directionalityThreshold;
        this.shockMultiple = shockMultiple;
        this.minMinorProbability = minMinorProbability;
        this.minMinorLift = minMinorLift;
        this.transitions = copyTransitions(transitions);
        this.priorCounts = Arrays.copyOf(priorCounts, MarketRegime.values().length);
        this.totalTrain = totalTrain;
    }

    public static TrailingShapeTransitionRegimeForecaster defaults() {
        return defaults(DEFAULT_BAR_MILLIS);
    }

    public static TrailingShapeTransitionRegimeForecaster defaults(long barMillis) {
        return new TrailingShapeTransitionRegimeForecaster(barsForLookback(barMillis), 20);
    }

    @Override
    public RegimeForecaster fit(List<TrainingSample> trainSamples) {
        Map<MarketRegime, int[]> nextByShape = new EnumMap<>(MarketRegime.class);
        int[] priors = new int[MarketRegime.values().length];
        int total = 0;
        if (trainSamples != null) {
            for (TrainingSample sample : trainSamples) {
                if (sample == null || sample.features() == null || sample.realizedRegime() == null) {
                    continue;
                }
                MarketRegime shape = trailingShape(sample.features());
                MarketRegime actual = sample.realizedRegime();
                nextByShape.computeIfAbsent(shape, ignored -> new int[MarketRegime.values().length])[index(actual)]++;
                priors[index(actual)]++;
                total++;
            }
        }
        return new TrailingShapeTransitionRegimeForecaster(lookbackBars, minTransitions,
                directionalityThreshold, shockMultiple, minMinorProbability, minMinorLift,
                nextByShape, priors, total);
    }

    @Override
    public RegimeVerdict forecastRegime(ResearchFeatures features) {
        MarketRegime shape = trailingShape(features);
        int[] row = transitions.get(shape);
        int rowTotal = sum(row);
        if (row == null || rowTotal < minTransitions || totalTrain <= 0) {
            return new RegimeVerdict(MarketRegime.RANGING, 0.0);
        }

        MarketRegime lifted = bestLiftedMinority(row, rowTotal);
        if (lifted != null) {
            return new RegimeVerdict(lifted, (double) row[index(lifted)] / rowTotal);
        }
        MarketRegime best = bestRegime(row);
        double probability = (double) row[index(best)] / rowTotal;
        return new RegimeVerdict(best, probability);
    }

    @Override
    public String name() {
        return "trailing_shape_transition(lb=" + lookbackBars + ",min=" + minTransitions + ")";
    }

    private MarketRegime bestLiftedMinority(int[] row, int rowTotal) {
        MarketRegime best = null;
        double bestLift = 0.0;
        double bestProbability = 0.0;
        for (MarketRegime regime : MarketRegime.values()) {
            if (regime == MarketRegime.RANGING) {
                continue;
            }
            int count = row[index(regime)];
            double probability = (double) count / rowTotal;
            double lift = lift(regime, probability);
            if (minorityHasLift(count, probability, lift)
                    && (lift > bestLift || (lift == bestLift && probability > bestProbability))) {
                best = regime;
                bestLift = lift;
                bestProbability = probability;
            }
        }
        return best;
    }

    private boolean minorityHasLift(int count, double probability, double lift) {
        int minMinorCount = Math.max(2, minTransitions / 4);
        return count >= minMinorCount && probability >= minMinorProbability && lift >= minMinorLift;
    }

    private double lift(MarketRegime regime, double probability) {
        double prior = Math.max(VAR_FLOOR, (double) priorCounts[index(regime)] / Math.max(1, totalTrain));
        return probability / prior;
    }

    private MarketRegime trailingShape(ResearchFeatures features) {
        List<KlineBar> bars = features == null ? List.of() : features.barsUpToNow();
        if (bars == null || bars.size() < lookbackBars) {
            return MarketRegime.RANGING;
        }
        List<KlineBar> window = bars.subList(bars.size() - lookbackBars, bars.size());
        double baselineSigma = VolatilityEstimator.ewmaVolatility(bars, 0.94);
        double lastAbsReturn = Math.abs(FactorMath.logReturn(
                window.get(window.size() - 1).close().doubleValue(),
                window.get(window.size() - 2).close().doubleValue()));
        if (baselineSigma > 0.0 && lastAbsReturn > shockMultiple * baselineSigma) {
            return MarketRegime.SHOCK;
        }

        double realizedVol = VolatilityEstimator.realizedVolatility(window);
        if (realizedVol <= 0.0) {
            return MarketRegime.RANGING;
        }
        double first = window.get(0).close().doubleValue();
        double last = window.get(window.size() - 1).close().doubleValue();
        double netReturn = FactorMath.logReturn(last, first);
        double directionality = Math.abs(netReturn) / realizedVol;
        if (directionality >= directionalityThreshold) {
            return netReturn >= 0.0 ? MarketRegime.TRENDING_UP : MarketRegime.TRENDING_DOWN;
        }
        return MarketRegime.RANGING;
    }

    private static MarketRegime bestRegime(int[] counts) {
        MarketRegime[] regimes = MarketRegime.values();
        int best = index(MarketRegime.RANGING);
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > counts[best]) {
                best = i;
            }
        }
        return regimes[best];
    }

    private static int sum(int[] counts) {
        if (counts == null) return 0;
        int out = 0;
        for (int c : counts) out += c;
        return out;
    }

    private static int index(MarketRegime regime) {
        return regime.ordinal();
    }

    private static int barsForLookback(long barMillis) {
        if (barMillis <= 0L) {
            barMillis = DEFAULT_BAR_MILLIS;
        }
        return Math.max(3, Math.toIntExact((DEFAULT_LOOKBACK_MILLIS + barMillis - 1L) / barMillis));
    }

    private static Map<MarketRegime, int[]> copyTransitions(Map<MarketRegime, int[]> raw) {
        Map<MarketRegime, int[]> out = new EnumMap<>(MarketRegime.class);
        if (raw != null) {
            for (Map.Entry<MarketRegime, int[]> e : raw.entrySet()) {
                out.put(e.getKey(), Arrays.copyOf(e.getValue(), MarketRegime.values().length));
            }
        }
        return out;
    }
}
