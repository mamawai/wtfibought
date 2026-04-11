package com.mawai.wiibservice.agent.quant.risk;

import com.mawai.wiibservice.agent.quant.domain.*;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 风控硬裁剪：纯程序化规则，对HorizonForecast做最终裁剪。
 * <p>
 * 规则：
 * - disagreement > 0.35 → 仓位压缩+杠杆限制（不直接降级NO_TRADE）
 * - regime = SHOCK → 杠杆cap到2x
 * - 仓位 = basePct × confidence × (1-disagreement) × volatilityPenalty
 */
@Slf4j
public class RiskGate {

    private static final double MAX_DISAGREEMENT = 0.35;

    /**
     * 对全部HorizonForecast做风控裁剪。
     *
     * @param forecasts      原始裁决结果
     * @param regime         当前市场状态
     * @param atr5m          5m ATR，用于波动率惩罚
     * @param lastPrice      最新价，用于计算波动率百分比
     * @return 裁剪后的forecasts + 风控状态
     */
    public static RiskResult apply(List<HorizonForecast> forecasts, MarketRegime regime,
                                    BigDecimal atr5m, BigDecimal lastPrice,
                                    List<String> qualityFlags, int fearGreedIndex) {
        if (forecasts == null || forecasts.isEmpty()) {
            return new RiskResult(List.of(), "NO_DATA");
        }

        double volPenalty = calcVolatilityPenalty(atr5m, lastPrice);
        double dataPenalty = calcDataPenalty(qualityFlags);
        List<HorizonForecast> clipped = new ArrayList<>(forecasts.size());
        List<String> riskActions = new ArrayList<>();

        for (HorizonForecast f : forecasts) {
            HorizonForecast result = clipSingle(f, regime, volPenalty, dataPenalty, qualityFlags, fearGreedIndex, riskActions);
            clipped.add(result);
        }

        if (dataPenalty < 1.0) {
            riskActions.add("PARTIAL_DATA");
        }

        String status = riskActions.isEmpty()
                ? "NORMAL"
                : String.join(",", new LinkedHashSet<>(riskActions));
        return new RiskResult(clipped, status);
    }

    private static HorizonForecast clipSingle(HorizonForecast f, MarketRegime regime,
                                               double volPenalty,
                                               double dataPenalty,
                                               List<String> qualityFlags,
                                               int fearGreedIndex,
                                               List<String> actions) {
        if (f.direction() == Direction.NO_TRADE) return f;

        // 分歧过大 → 仓位压缩，不直接降级NO_TRADE
        boolean highDisagreement = f.disagreement() > MAX_DISAGREEMENT;

        int maxLev = f.maxLeverage();
        double maxPos = f.maxPositionPct();
        double confMultiplier = 1.0;

        // SHOCK → 杠杆cap 2x, 仓位减半
        if (regime == MarketRegime.SHOCK) {
            maxLev = Math.min(maxLev, 2);
            maxPos *= 0.5;
            actions.add("SHOCK_CAP_" + f.horizon());
        }

        // SQUEEZE → 仓位减30%
        if (regime == MarketRegime.SQUEEZE) {
            maxPos *= 0.7;
            actions.add("SQUEEZE_REDUCE_" + f.horizon());
        }

        // 高分歧 → 仓位压缩 + 杠杆限制
        if (highDisagreement) {
            maxPos *= 0.5;
            maxLev = Math.min(maxLev, 3);
            confMultiplier *= 0.7;
            actions.add("HIGH_DISAGREEMENT_PENALTY_" + f.horizon());
        }

        // 恐惧贪婪极端值 → 逆向仓位惩罚
        if (fearGreedIndex >= 0) {
            // 极度贪婪做多 / 极度恐惧做空 → 置信度打8折
            if (fearGreedIndex >= 80 && f.direction() == Direction.LONG) {
                confMultiplier *= 0.80;
                actions.add("EXTREME_GREED_LONG_PENALTY_" + f.horizon());
            } else if (fearGreedIndex <= 20 && f.direction() == Direction.SHORT) {
                confMultiplier *= 0.80;
                actions.add("EXTREME_FEAR_SHORT_PENALTY_" + f.horizon());
            }
        }

        // 动态仓位: basePct × confidence × (1-disagreement) × volatilityPenalty × confMultiplier
        double effectiveConfidence = f.confidence() * confMultiplier;
        double agreementFactor = Math.max(0.60, 1.0 - f.disagreement());
        double adjustedPos = maxPos * effectiveConfidence * agreementFactor * volPenalty * dataPenalty;
        adjustedPos = Math.clamp(adjustedPos, 0.01, maxPos);

        if (volPenalty < 0.8) {
            actions.add("HIGH_VOL_PENALTY_" + f.horizon());
        }
        if (dataPenalty < 1.0) {
            actions.add("DATA_PENALTY_" + f.horizon());
        }

        log.info("[Q5.clip] {} {} regime={} volPenalty={} dataPenalty={} qualityFlags={} → lev={} pos={}% ",
                f.horizon(), f.direction(), regime, String.format("%.2f", volPenalty), String.format("%.2f", dataPenalty),
                qualityFlags,
                maxLev, String.format("%.2f", adjustedPos * 100));

        return new HorizonForecast(
                f.horizon(), f.direction(), f.confidence(), f.weightedScore(),
                f.disagreement(), f.entryLow(), f.entryHigh(), f.invalidationPrice(),
                f.tp1(), f.tp2(), maxLev, adjustedPos);
    }

    /**
     * 波动率惩罚因子：ATR占价格比例越大，仓位惩罚越重。
     * atrPct < 0.3% → penalty=1.0（正常波动）
     * atrPct 0.3%-1.0% → 线性衰减到0.5
     * atrPct > 1.0% → penalty=0.3（极端波动）
     */
    private static double calcVolatilityPenalty(BigDecimal atr5m, BigDecimal lastPrice) {
        if (atr5m == null || lastPrice == null || lastPrice.signum() == 0) return 1.0;
        double atrPct = atr5m.doubleValue() / lastPrice.doubleValue() * 100;
        if (atrPct < 0.3) return 1.0;
        if (atrPct > 1.0) return 0.3;
        // 线性插值: 0.3% → 1.0, 1.0% → 0.5
        return 1.0 - (atrPct - 0.3) / 0.7 * 0.5;
    }

    private static double calcDataPenalty(List<String> qualityFlags) {
        if (qualityFlags == null || qualityFlags.isEmpty()) {
            return 1.0;
        }
        if (qualityFlags.stream().anyMatch(flag ->
                flag.equals("PARTIAL_KLINE_DATA")
                        || flag.equals("NO_ATR_5M")
                        || flag.equals("NO_BOLL_5M")
                        || flag.equals("MISSING_TF_5M")
                        || flag.equals("MISSING_TF_15M"))) {
            return 0.80;
        }
        if (qualityFlags.stream().anyMatch(flag -> flag.startsWith("MISSING_TF_")
                || flag.equals("NO_ATR_1M") || flag.equals("NO_BOLL_1M"))) {
            return 0.90;
        }
        return 1.0;
    }

    public record RiskResult(List<HorizonForecast> forecasts, String riskStatus) {}
}
