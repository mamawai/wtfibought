package com.mawai.wiibservice.agent.research.eval;

/**
 * 评估可配置参数（spec §5.3 默认值，评审可改）。
 * embargoBars 单位=决策点（H-bar）；本刀决策周期=horizon，故标签前视=1 个 H-bar。
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
        return new EvalParams(1.5, 0.94, 50, 1, 100, 1000, 0.95, 42L);
    }
}
