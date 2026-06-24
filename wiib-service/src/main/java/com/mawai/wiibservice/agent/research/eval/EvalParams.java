package com.mawai.wiibservice.agent.research.eval;

import java.math.BigDecimal;

/**
 * 评估可配置参数（spec §5.3 默认值，评审可改）。
 * embargoBars 单位=5m/15m 决策点；horizon 只决定未来持仓验证窗口和 purge 长度。
 * costBps：方向腿单笔往返成本（bps）。本尺子把每个非 flat 信号模拟成一笔独立开/平仓交易，
 * 故成本按"每个活跃信号扣一次往返"计；0=维持旧口径（所有历史数字不变）。
 * 排列检验不扣成本——成本只依赖持仓次数、对洗牌不变，分位不受影响。
 */
public record EvalParams(
        double k,                          // 三隔栏栏宽倍数（不网格寻优）
        double lambda,                     // EWMA 波动率衰减
        int testSize,                      // 每个样本外块大小（决策点数）
        int embargoBars,                   // embargo（决策点数）
        int minTrain,                      // 最小训练规模
        int iterations,                    // 随机排列次数
        double naivePercentileThreshold,   // 超过此分位才算赢过瞎猜
        long seed,                         // 随机种子（可复现）
        double costBps                     // 单笔往返成本(bps)；0=无成本旧口径
) {
    public EvalParams {
        if (costBps < 0.0 || !Double.isFinite(costBps)) {
            throw new IllegalArgumentException("costBps 必须为非负有限值: " + costBps);
        }
    }

    /** 旧 8 参构造：costBps=0，既有调用点与历史口径完全不变。 */
    public EvalParams(double k, double lambda, int testSize, int embargoBars, int minTrain,
                      int iterations, double naivePercentileThreshold, long seed) {
        this(k, lambda, testSize, embargoBars, minTrain, iterations, naivePercentileThreshold, seed, 0.0);
    }

    public static EvalParams defaults() {
        // 默认按 5m 决策点：testSize=576≈2天，minTrain=2016≈7天；horizon purge 由评估器按 6/12/24h 自动补。
        return new EvalParams(1.5, 0.94, 576, 12, 2016, 1000, 0.95, 42L);
    }

    /** 成本率 = costBps / 1e4，方向腿单期收益直接减。 */
    public BigDecimal costRate() {
        return BigDecimal.valueOf(costBps).movePointLeft(4);
    }
}
