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
 * - disagreement > 0.35 → 杠杆限制（仓位压缩由agreementFactor处理）
 * - regime = SHOCK → 杠杆cap到2x
 * - 仓位 = basePct × confidence × agreementFactor × envFactor（环境因子加法合并）
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
                                    List<String> qualityFlags, int fearGreedIndex,
                                    double dvolIndex) {
        return apply(forecasts, regime, atr5m, lastPrice, qualityFlags, fearGreedIndex, dvolIndex, null);
    }

    public static RiskResult apply(List<HorizonForecast> forecasts, MarketRegime regime,
                                    BigDecimal atr5m, BigDecimal lastPrice,
                                    List<String> qualityFlags, int fearGreedIndex,
                                    double dvolIndex, String symbol) {
        if (forecasts == null || forecasts.isEmpty()) {
            return new RiskResult(List.of(), "NO_DATA");
        }

        double volPenalty = calcVolatilityPenalty(atr5m, lastPrice, symbol);
        double dataPenalty = calcDataPenalty(qualityFlags);
        double ivPenalty = calcIvPenalty(dvolIndex);
        List<HorizonForecast> clipped = new ArrayList<>(forecasts.size());
        List<String> riskActions = new ArrayList<>();

        for (HorizonForecast f : forecasts) {
            HorizonForecast result = clipSingle(f, regime, volPenalty, dataPenalty, ivPenalty, qualityFlags, fearGreedIndex, riskActions);
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
                                               double ivPenalty,
                                               List<String> qualityFlags,
                                               int fearGreedIndex,
                                               List<String> actions) {
        if (f.direction() == Direction.NO_TRADE) return f;

        // 分歧过大 → 仓位压缩，不直接降级NO_TRADE
        boolean highDisagreement = f.disagreement() > MAX_DISAGREEMENT;

        int maxLev = f.maxLeverage();
        double maxPos = f.maxPositionPct();
        double confMultiplier = 1.0;

        // SHOCK → 杠杆cap 5x, 仓位减30%（允许交易但缩减规模）
        if (regime == MarketRegime.SHOCK) {
            maxLev = Math.min(maxLev, 5);
            maxPos *= 0.7;
            actions.add("SHOCK_CAP_" + f.horizon());
        }

        // SQUEEZE → 仓位减15%（预留突破机会）
        if (regime == MarketRegime.SQUEEZE) {
            maxPos *= 0.85;
            actions.add("SQUEEZE_REDUCE_" + f.horizon());
        }

        // 高分歧 → 杠杆限制（仓位压缩已由 agreementFactor 处理，不再重复 ×0.5）
        if (highDisagreement) {
            maxLev = Math.clamp(maxLev / 2, 5, maxLev);
            actions.add("HIGH_DISAGREEMENT_PENALTY_" + f.horizon());
        }

        // 恐惧贪婪极端值 → regime感知惩罚
        // TREND中：顺势FGI极端值是正常的，不惩罚；RANGE中：保留逆向逻辑；SHOCK中：加大惩罚
        if (fearGreedIndex >= 0) {
            boolean isTrend = regime == MarketRegime.TREND_UP || regime == MarketRegime.TREND_DOWN;
            boolean isShock = regime == MarketRegime.SHOCK;
            double fgiPenalty = isShock ? 0.80 : 0.90;

            if (fearGreedIndex >= 80 && f.direction() == Direction.LONG) {
                // 极度贪婪做多：趋势上涨中不惩罚（贪婪是正常情绪），其他regime惩罚
                if (!isTrend || regime != MarketRegime.TREND_UP) {
                    confMultiplier *= fgiPenalty;
                    actions.add("EXTREME_GREED_LONG_PENALTY_" + f.horizon());
                }
            } else if (fearGreedIndex <= 20 && f.direction() == Direction.SHORT) {
                // 极度恐惧做空：趋势下跌中不惩罚（恐惧是正常情绪），其他regime惩罚
                if (!isTrend || regime != MarketRegime.TREND_DOWN) {
                    confMultiplier *= fgiPenalty;
                    actions.add("EXTREME_FEAR_SHORT_PENALTY_" + f.horizon());
                }
            }
        }

        // 动态仓位: basePct × confidence × (1-disagreement)，环境因子用加法惩罚避免级联衰减
        double effectiveConfidence = f.confidence() * confMultiplier;
        double agreementFactor = Math.max(0.70, 1.0 - f.disagreement());
        // 环境惩罚：vol/data/iv 改为加法（它们高度相关，乘法会导致过度衰减）
        double envPenalty = (1.0 - volPenalty) + (1.0 - dataPenalty) + (1.0 - ivPenalty);
        double envFactor = Math.max(0.55, 1.0 - envPenalty);
        double adjustedPos = maxPos * effectiveConfidence * agreementFactor * envFactor;
        adjustedPos = Math.clamp(adjustedPos, 0.08, maxPos);

        if (volPenalty < 0.8) {
            actions.add("HIGH_VOL_PENALTY_" + f.horizon());
        }
        if (dataPenalty < 1.0) {
            actions.add("DATA_PENALTY_" + f.horizon());
        }
        if (ivPenalty < 1.0) {
            actions.add("HIGH_IV_PENALTY_" + f.horizon());
        }

        log.info("[Q5.clip] {} {} regime={} envFactor={} (vol={} data={} iv={}) qualityFlags={} → lev={} pos={}% ",
                f.horizon(), f.direction(), regime, String.format("%.2f", envFactor),
                String.format("%.2f", volPenalty), String.format("%.2f", dataPenalty),
                String.format("%.2f", ivPenalty), qualityFlags,
                maxLev, String.format("%.2f", adjustedPos * 100));

        return new HorizonForecast(
                f.horizon(), f.direction(), f.confidence(), f.weightedScore(),
                f.disagreement(), f.entryLow(), f.entryHigh(), f.invalidationPrice(),
                f.tp1(), f.tp2(), maxLev, adjustedPos);
    }

    /**
     * 波动率惩罚因子：ATR占价格比例越大，仓位惩罚越重。
     * 阈值按币种差异化：BTC波动小用0.5%，ETH用0.7%，PAXG稳定用0.3%。
     */
    private static double calcVolatilityPenalty(BigDecimal atr5m, BigDecimal lastPrice, String symbol) {
        if (atr5m == null || lastPrice == null || lastPrice.signum() == 0) return 1.0;
        double atrPct = atr5m.doubleValue() / lastPrice.doubleValue() * 100;

        // 按币种设置正常波动阈值和极端阈值
        double normalThreshold = 0.3;
        double extremeThreshold = 1.0;
        if (symbol != null) {
            if (symbol.contains("BTC")) { normalThreshold = 0.5; extremeThreshold = 1.5; }
            else if (symbol.contains("ETH")) { normalThreshold = 0.7; extremeThreshold = 2.0; }
            else if (symbol.contains("PAXG")) {extremeThreshold = 0.8; }
        }

        if (atrPct < normalThreshold) return 1.0;
        if (atrPct > extremeThreshold) return 0.3;
        return 1.0 - (atrPct - normalThreshold) / (extremeThreshold - normalThreshold) * 0.5;
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
        if (qualityFlags.stream().anyMatch(flag ->
                flag.equals("NO_SPOT_KLINE_1M")
                        || flag.equals("NO_SPOT_TICKER")
                        || flag.equals("NO_SPOT_ORDERBOOK"))) {
            return 0.95;
        }
        return 1.0;
    }

    /**
     * 期权IV惩罚因子：DVOL越高，仓位越保守。
     * dvol < 50 → 1.0（正常）
     * dvol 50-80 → 线性衰减到0.7
     * dvol > 80 → 0.5（极端恐慌）
     * dvol=0（无数据） → 1.0（不惩罚）
     */
    private static double calcIvPenalty(double dvolIndex) {
        if (dvolIndex <= 0) return 1.0;
        if (dvolIndex < 50) return 1.0;
        if (dvolIndex > 80) return 0.5;
        return 1.0 - (dvolIndex - 50) / 30.0 * 0.3;
    }

    public record RiskResult(List<HorizonForecast> forecasts, String riskStatus) {}
}
