package com.mawai.wiibquant.agent.research.metrics;

/**
 * 波动率预测评分：基于预测方差 σ² 与实现方差代理 r² 的稳健损失（Patton 2011）。损失越低越好。
 * - MSE   = mean((σ²_pred − r²)²)
 * - QLIKE = mean(r²/σ²_pred − ln(r²/σ²_pred) − 1)，对零值加方差下限防除零/ln 发散。
 * 评估编排里同口径再算一遍"朴素基准(上期 vol)"的损失对比，即知 vol 预测是否有增量。
 */
public record VolForecastScore(double mse, double qlike, int n) {

    private static final double VARIANCE_FLOOR = 1e-12; // 防 σ²=0 / r²=0 致除零或 ln(0)

    /** predictedSigma/realizedReturn 等长配对：predictedSigma[i]=决策点对未来周期波动(σ)的预测，realizedReturn[i]=该周期实际对数收益。 */
    public static VolForecastScore evaluate(double[] predictedSigma, double[] realizedReturn) {
        validate(predictedSigma, realizedReturn);
        int n = predictedSigma.length;
        if (n == 0) return new VolForecastScore(0, 0, 0);
        double sumMse = 0, sumQlike = 0;
        for (int i = 0; i < n; i++) {
            double h = Math.max(predictedSigma[i] * predictedSigma[i], VARIANCE_FLOOR); // 预测方差
            double x = Math.max(realizedReturn[i] * realizedReturn[i], VARIANCE_FLOOR); // 实现方差代理 r²
            sumMse += (h - x) * (h - x);
            sumQlike += qlikeLoss(h, x);
        }
        return new VolForecastScore(sumMse / n, sumQlike / n, n);
    }

    /** 单点 QLIKE loss 序列，供 DM/loss-diff 比较使用。 */
    public static double[] qlikeLosses(double[] predictedSigma, double[] realizedReturn) {
        validate(predictedSigma, realizedReturn);
        double[] out = new double[predictedSigma.length];
        for (int i = 0; i < predictedSigma.length; i++) {
            double h = Math.max(predictedSigma[i] * predictedSigma[i], VARIANCE_FLOOR);
            double x = Math.max(realizedReturn[i] * realizedReturn[i], VARIANCE_FLOOR);
            out[i] = qlikeLoss(h, x);
        }
        return out;
    }

    private static void validate(double[] predictedSigma, double[] realizedReturn) {
        if (predictedSigma == null || realizedReturn == null) {
            throw new IllegalArgumentException("预测/实现序列不能为空");
        }
        if (predictedSigma.length != realizedReturn.length) {
            throw new IllegalArgumentException("预测与实现长度不一致: "
                    + predictedSigma.length + " vs " + realizedReturn.length);
        }
    }

    private static double qlikeLoss(double predictedVariance, double realizedVariance) {
        return realizedVariance / predictedVariance - Math.log(realizedVariance / predictedVariance) - 1.0;
    }
}
