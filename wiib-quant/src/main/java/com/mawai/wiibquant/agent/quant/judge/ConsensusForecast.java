package com.mawai.wiibquant.agent.quant.judge;

import com.mawai.wiibquant.agent.quant.domain.Direction;

/**
 * 新架构裁决输出：只产方向/置信度/分歧，不含入场/止损/止盈/杠杆/仓位。
 *
 * <p>与旧 {@code HorizonForecast} 的关键区别：
 * 入场/止损/止盈下沉到交易执行层 {@code EntryStrategy/ExitPlaybook}，
 * 杠杆/仓位下沉到 {@code RiskGate}，预测层不再产出。</p>
 */
public record ConsensusForecast(
        String horizon,           // H6 / H12 / H24
        Direction direction,      // LONG / SHORT / NO_TRADE
        double confidence,        // 综合置信度 [0, 1]
        double disagreement       // 分歧度 [0, 1]，越高信号越分裂
) {
    public ConsensusForecast {
        if (horizon == null || horizon.isBlank()) {
            throw new IllegalArgumentException("horizon 不能为空");
        }
        if (direction == null) {
            direction = Direction.NO_TRADE;
        }
        confidence = Math.clamp(Double.isFinite(confidence) ? confidence : 0.0, 0.0, 1.0);
        disagreement = Math.clamp(Double.isFinite(disagreement) ? disagreement : 0.0, 0.0, 1.0);
    }

    public static ConsensusForecast noTrade(String horizon, double disagreement) {
        return new ConsensusForecast(horizon, Direction.NO_TRADE, 0.0, disagreement);
    }

    /** 从 research forecast 方向/置信度直接构建（无 evidence 辅助时的裁决） */
    public static ConsensusForecast fromResearch(String horizon, int directionSign,
                                                  double directionConfidence) {
        Direction dir = directionSign > 0 ? Direction.LONG
                : directionSign < 0 ? Direction.SHORT
                : Direction.NO_TRADE;
        return new ConsensusForecast(horizon, dir, directionConfidence, 0.0);
    }
}
