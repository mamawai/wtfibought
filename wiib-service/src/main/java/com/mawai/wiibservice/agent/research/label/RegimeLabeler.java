package com.mawai.wiibservice.agent.research.label;

import com.mawai.wiibservice.agent.research.factor.FactorMath;
import com.mawai.wiibservice.agent.research.forecast.MarketRegime;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.stats.VolatilityEstimator;

import java.util.List;

/**
 * regime 事后标签（ground truth）：用决策点之后的实际路径定 regime——它是 label 不是 feature，故可用未来、无泄漏。
 * 判定（SHOCK 优先）：
 *  1) 整段已实现波动 realizedVol(path) &gt; shockVolMultiple × baselineSigma → SHOCK（剧烈异常波动，不论方向）；
 *  2) 路径效率 directionality = |净对数收益 ln(last/first)| / realizedVol(path) ≥ q → 趋势（按净收益符号定上/下）；
 *  3) 否则 → RANGING。
 * directionality 量「这段路径有多大比例是同向净走、而非来回抵消」：纯随机游走≈|N(0,1)|(~0.8)，持续同向吃 √N 杠杆。
 * 关键：分母用二次变差 realizedVol=√Σr²（非旧 ER 的总行程 Σ|r|），固定时窗下收敛、不随 1m/5m bar 数机械膨胀；
 * 且趋势判定与 baseline 无关（baseline 只用于 SHOCK），故不会像 net/σ 那样被漂移灌进 baseline 而失去分辨力。
 * 阈值是开放参数：q/shockVolMultiple 的默认由 BTC/ETH×6/12/24h 真实分布 + confusion matrix 扫描后定。
 */
public final class RegimeLabeler {

    private RegimeLabeler() {
    }

    // q-scan(BTC/ETH×6/12/24h, 5m决策)显示 1.25 的趋势占比更稳(约14-17%)，更适合作 future-regime 训练标签。
    public static final double DEFAULT_DIRECTIONALITY_THRESHOLD = 1.25;
    public static final double DEFAULT_SHOCK_VOL_MULTIPLE = 3.0;

    public static MarketRegime label(List<KlineBar> futurePath, double baselineSigma) {
        return label(futurePath, baselineSigma, DEFAULT_DIRECTIONALITY_THRESHOLD, DEFAULT_SHOCK_VOL_MULTIPLE);
    }

    public static MarketRegime label(List<KlineBar> futurePath, double baselineSigma,
                                     double directionalityThreshold, double shockVolMultiple) {
        if (futurePath == null || futurePath.size() < 2) {
            return MarketRegime.RANGING; // 无足够路径信息 → 中性
        }
        // 整段已实现波动(horizon 口径) 对 单期 baseline σ：同量纲才可比。SHOCK 只看波动幅度、优先于趋势。
        double realizedVol = VolatilityEstimator.realizedVolatility(futurePath);
        if (baselineSigma > 0 && realizedVol > shockVolMultiple * baselineSigma) {
            return MarketRegime.SHOCK;
        }
        if (realizedVol <= 0) {
            return MarketRegime.RANGING; // 路径全平：无收益、无方向
        }
        // 趋势：路径效率 = 净对数收益 / 整段已实现波动。高=同向净走，低=来回抵消。与 baseline 无关、bar 数稳健。
        double first = futurePath.getFirst().close().doubleValue();
        double last = futurePath.getLast().close().doubleValue();
        double netReturn = FactorMath.logReturn(last, first);
        double directionality = Math.abs(netReturn) / realizedVol;
        if (directionality >= directionalityThreshold) {
            return netReturn >= 0 ? MarketRegime.TRENDING_UP : MarketRegime.TRENDING_DOWN;
        }
        return MarketRegime.RANGING;
    }
}
