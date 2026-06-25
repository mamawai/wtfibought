package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.factor.FactorMath;
import com.mawai.wiibservice.agent.research.kline.KlineBar;

import java.util.ArrayList;
import java.util.List;

/**
 * 单点 vol 风险上下文：把 raw sigma 转成 agent/LLM 可读的风险字段。
 * 历史分位只用 barsUpToNow，样本不足标 UNKNOWN，避免把回测标签混进上下文。
 */
public record VolatilityRiskContext(
        ForecastHorizon horizon,
        double expectedVolatility,
        int expectedMoveBps,
        double trailingPercentile,
        VolatilityRiskTier riskTier,
        double riskBudgetHint,
        List<String> riskFlags,
        String llmContext
) {

    private static final int MIN_HISTORY_RETURNS = 20;

    public VolatilityRiskContext {
        if (horizon == null) throw new IllegalArgumentException("horizon 不能为空");
        if (!Double.isFinite(expectedVolatility) || expectedVolatility < 0.0) {
            throw new IllegalArgumentException("expectedVolatility 必须为非负有限数: " + expectedVolatility);
        }
        if (expectedMoveBps < 0) throw new IllegalArgumentException("expectedMoveBps 不能为负: " + expectedMoveBps);
        if (!Double.isFinite(trailingPercentile) || trailingPercentile < 0.0 || trailingPercentile > 1.0) {
            throw new IllegalArgumentException("trailingPercentile 须落在 [0,1]: " + trailingPercentile);
        }
        if (riskTier == null) throw new IllegalArgumentException("riskTier 不能为空");
        if (!Double.isFinite(riskBudgetHint) || riskBudgetHint < 0.0 || riskBudgetHint > 1.0) {
            throw new IllegalArgumentException("riskBudgetHint 须落在 [0,1]: " + riskBudgetHint);
        }
        riskFlags = riskFlags == null ? List.of() : List.copyOf(riskFlags);
        llmContext = llmContext == null ? "" : llmContext;
    }

    public static VolatilityRiskContext from(ForecastHorizon horizon, double expectedVolatility,
                                             List<KlineBar> barsUpToNow) {
        if (!Double.isFinite(expectedVolatility) || expectedVolatility < 0.0) {
            throw new IllegalArgumentException("expectedVolatility 必须为非负有限数: " + expectedVolatility);
        }
        int expectedMoveBps = (int) Math.round(expectedVolatility * 10_000.0);
        double[] absReturns = absoluteHorizonReturns(barsUpToNow, horizon);
        List<String> flags = new ArrayList<>();
        if (expectedMoveBps == 0) {
            flags.add("VOL_ZERO");
        }

        double percentile;
        VolatilityRiskTier tier;
        if (absReturns.length < MIN_HISTORY_RETURNS) {
            percentile = 0.5;
            tier = VolatilityRiskTier.UNKNOWN;
            flags.add("VOL_HISTORY_SHORT");
        } else {
            percentile = FactorMath.percentileRank(expectedVolatility, absReturns);
            tier = tier(percentile);
            if (tier == VolatilityRiskTier.ELEVATED) {
                flags.add("VOL_ELEVATED");
            } else if (tier == VolatilityRiskTier.STRESSED) {
                flags.add("VOL_STRESSED");
            }
        }

        double riskBudgetHint = switch (tier) {
            case STRESSED -> 0.50;
            case ELEVATED -> 0.75;
            default -> 1.00;
        };
        return new VolatilityRiskContext(horizon, expectedVolatility, expectedMoveBps, percentile, tier,
                riskBudgetHint, flags, llmLine(horizon, expectedMoveBps, percentile, tier, riskBudgetHint, flags));
    }

    /**
     * 历史每点的 |horizon 对数收益|，作 vol 分位/档判定的分布。
     * public：对账侧({@code VerificationService})复用，保证 point-in-time 历史口径与预测侧同一真相源。
     */
    public static double[] absoluteHorizonReturns(List<KlineBar> bars, ForecastHorizon horizon) {
        if (bars == null || bars.size() < 2) return new double[0];
        long barMillis = inferredBarMillis(bars);
        if (barMillis <= 0L || horizon == null) return new double[0];
        int horizonBars = Math.max(1, Math.toIntExact((horizon.millis() + barMillis - 1L) / barMillis));
        if (bars.size() <= horizonBars) return new double[0];

        double[] out = new double[bars.size() - horizonBars];
        for (int i = horizonBars; i < bars.size(); i++) {
            double start = bars.get(i - horizonBars).close().doubleValue();
            double end = bars.get(i).close().doubleValue();
            out[i - horizonBars] = Math.abs(FactorMath.logReturn(end, start));
        }
        return out;
    }

    private static long inferredBarMillis(List<KlineBar> bars) {
        if (bars == null || bars.size() < 2) return 0L;
        KlineBar last = bars.get(bars.size() - 1);
        KlineBar prev = bars.get(bars.size() - 2);
        long interval = last.openTime() - prev.openTime();
        return Math.max(interval, 0L);
    }

    private static VolatilityRiskTier tier(double percentile) {
        if (percentile >= 0.95) return VolatilityRiskTier.STRESSED;
        if (percentile >= 0.80) return VolatilityRiskTier.ELEVATED;
        if (percentile <= 0.20) return VolatilityRiskTier.QUIET;
        return VolatilityRiskTier.NORMAL;
    }

    private static String llmLine(ForecastHorizon horizon, int expectedMoveBps, double percentile,
                                  VolatilityRiskTier tier, double riskBudgetHint, List<String> flags) {
        return "vol_context{horizon=%dh,expected_move_bps=%d,trailing_percentile=%.1f%%,risk_tier=%s,risk_budget_hint=%.2f,flags=%s}"
                .formatted(horizon.hours(), expectedMoveBps, percentile * 100.0, tier, riskBudgetHint, flags);
    }
}
