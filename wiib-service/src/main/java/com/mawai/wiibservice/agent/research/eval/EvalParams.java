package com.mawai.wiibservice.agent.research.eval;

/**
 * 评估可配置参数（spec §5.3 默认值，评审可改）。
 * embargoBars 单位=5m/15m 决策点；horizon 只决定未来持仓验证窗口和 purge 长度。
 */
public record EvalParams(
        double k,                          // 三隔栏栏宽倍数（不网格寻优）
        double lambda,                     // EWMA 波动率衰减
        int testSize,                      // 每个样本外块大小（决策点数）
        int embargoBars,                   // embargo（决策点数）
        int minTrain,                      // 最小训练规模
        int iterations,                    // 随机排列次数
        double naivePercentileThreshold,   // 超过此分位才算赢过瞎猜
        long seed                          // 随机种子（可复现）
) {
    public static EvalParams defaults() {
        // 默认按 5m 决策点：testSize=576≈2天，minTrain=2016≈7天；horizon purge 由评估器按 6/12/24h 自动补。
        return new EvalParams(1.5, 0.94, 576, 12, 2016, 1000, 0.95, 42L);
    }
}
