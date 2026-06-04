package com.mawai.wiibservice.agent.research.metrics;

import com.mawai.wiibservice.agent.research.forecast.MarketRegime;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * regime 分类评分：模型准确率 vs persistence 朴素基准（"预测下期=上期实际"，regime 有持续性故是强基准）。
 * - accuracy         = #{predicted[i]==actual[i]} / n
 * - naiveAccuracy    = #{actual[i]==actual[i-1]} / (n-1)（i≥1）
 * - beatsNaive       = accuracy &gt; naiveAccuracy
 * - balancedAccuracy = 各 actual 出现类的召回率均值（macro recall），防止 RANGING 多数类抬高 accuracy
 * - macroF1          = 各 actual 出现类的 F1 均值
 * - confusionMatrix  = 行 actual、列 predicted；四类补齐 0，便于报告直接诊断类别塌缩
 */
public record RegimeClassificationScore(double accuracy, double naiveAccuracy, boolean beatsNaive,
                                        double balancedAccuracy, double macroF1, int n,
                                        Map<MarketRegime, Map<MarketRegime, Integer>> confusionMatrix) {

    public RegimeClassificationScore {
        confusionMatrix = completeConfusion(confusionMatrix);
    }

    /** 保留旧 4 参构造，避免已有调用源码级断裂；新指标未知时置 0。 */
    public RegimeClassificationScore(double accuracy, double naiveAccuracy, boolean beatsNaive, int n) {
        this(accuracy, naiveAccuracy, beatsNaive, 0.0, 0.0, n);
    }

    /** 保留旧 6 参构造；混淆矩阵未知时补四类 0。 */
    public RegimeClassificationScore(double accuracy, double naiveAccuracy, boolean beatsNaive,
                                     double balancedAccuracy, double macroF1, int n) {
        this(accuracy, naiveAccuracy, beatsNaive, balancedAccuracy, macroF1, n, Map.of());
    }

    public static RegimeClassificationScore evaluate(MarketRegime[] predicted, MarketRegime[] actual) {
        if (predicted == null || actual == null) {
            throw new IllegalArgumentException("预测/实际序列不能为空");
        }
        if (predicted.length != actual.length) {
            throw new IllegalArgumentException("预测与实际长度不一致: "
                    + predicted.length + " vs " + actual.length);
        }
        int n = predicted.length;
        if (n == 0) return new RegimeClassificationScore(0, 0, false, 0, 0, 0);
        int[][] confusion = new int[MarketRegime.values().length][MarketRegime.values().length];
        for (int i = 0; i < n; i++) {
            confusion[indexOf(actual[i])][indexOf(predicted[i])]++;
        }
        int correct = 0;
        for (int c = 0; c < confusion.length; c++) correct += confusion[c][c];
        double accuracy = (double) correct / n;
        double naiveAccuracy = 0.0;
        if (n >= 2) {
            int naiveCorrect = 0;
            for (int i = 1; i < n; i++) {
                if (actual[i] == actual[i - 1]) naiveCorrect++;
            }
            naiveAccuracy = (double) naiveCorrect / (n - 1);
        }
        return new RegimeClassificationScore(accuracy, naiveAccuracy, accuracy > naiveAccuracy,
                balancedAccuracy(confusion), macroF1(confusion), n, toConfusionMap(confusion));
    }

    private static Map<MarketRegime, Map<MarketRegime, Integer>> toConfusionMap(int[][] confusion) {
        Map<MarketRegime, Map<MarketRegime, Integer>> raw = new EnumMap<>(MarketRegime.class);
        MarketRegime[] regimes = MarketRegime.values();
        for (int a = 0; a < regimes.length; a++) {
            Map<MarketRegime, Integer> row = new EnumMap<>(MarketRegime.class);
            for (int p = 0; p < regimes.length; p++) {
                row.put(regimes[p], confusion[a][p]);
            }
            raw.put(regimes[a], row);
        }
        return raw;
    }

    private static Map<MarketRegime, Map<MarketRegime, Integer>> completeConfusion(
            Map<MarketRegime, Map<MarketRegime, Integer>> raw) {
        Map<MarketRegime, Map<MarketRegime, Integer>> out = new EnumMap<>(MarketRegime.class);
        for (MarketRegime actual : MarketRegime.values()) {
            Map<MarketRegime, Integer> rawRow = raw != null ? raw.get(actual) : null;
            Map<MarketRegime, Integer> row = new EnumMap<>(MarketRegime.class);
            for (MarketRegime predicted : MarketRegime.values()) {
                Integer count = rawRow != null ? rawRow.get(predicted) : null;
                row.put(predicted, count != null ? count : 0);
            }
            out.put(actual, Collections.unmodifiableMap(row));
        }
        return Collections.unmodifiableMap(out);
    }

    private static double balancedAccuracy(int[][] confusion) {
        double sum = 0.0;
        int present = 0;
        for (int c = 0; c < confusion.length; c++) {
            int rowSum = rowSum(confusion, c);
            if (rowSum > 0) {
                sum += (double) confusion[c][c] / rowSum;
                present++;
            }
        }
        return present == 0 ? 0.0 : sum / present;
    }

    private static double macroF1(int[][] confusion) {
        double sum = 0.0;
        int present = 0;
        for (int c = 0; c < confusion.length; c++) {
            int rowSum = rowSum(confusion, c);
            if (rowSum == 0) continue;
            int colSum = colSum(confusion, c);
            double recall = (double) confusion[c][c] / rowSum;
            double precision = colSum > 0 ? (double) confusion[c][c] / colSum : 0.0;
            double f1 = (precision + recall) > 0 ? 2 * precision * recall / (precision + recall) : 0.0;
            sum += f1;
            present++;
        }
        return present == 0 ? 0.0 : sum / present;
    }

    private static int rowSum(int[][] confusion, int c) {
        int sum = 0;
        for (int p = 0; p < confusion[c].length; p++) sum += confusion[c][p];
        return sum;
    }

    private static int colSum(int[][] confusion, int c) {
        int sum = 0;
        for (int a = 0; a < confusion.length; a++) sum += confusion[a][c];
        return sum;
    }

    private static int indexOf(MarketRegime regime) {
        return switch (regime) {
            case TRENDING_UP -> 0;
            case TRENDING_DOWN -> 1;
            case RANGING -> 2;
            case SHOCK -> 3;
        };
    }
}
