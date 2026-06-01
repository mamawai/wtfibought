package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.forecast.EwmaMomentumForecaster;
import com.mawai.wiibservice.agent.research.forecast.Forecaster;
import com.mawai.wiibservice.agent.research.forecast.MultiFactorForecaster;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.series.MarketSeriesPoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchEvalServiceTest {

    @Test
    void evaluateBarsProducesMultiStrategyOutOfSampleReport() {
        // 小样本验证"多策略同框编排"：MultiFactor 在 <30 H-bar 下暖机 flat（不影响编排验证）
        EvalParams params = new EvalParams(1.5, 0.94, 3, 0, 2, 200, 0.95, 42L);
        List<KlineBar> oneMin = uptrend1m(8 * 360); // 8 个 6h 桶
        List<Forecaster> fcs = List.of(new EwmaMomentumForecaster(2, 4), MultiFactorForecaster.defaults());

        ComparisonReport r = ResearchEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, oneMin, List.of(), List.of(), fcs, params);

        assertThat(r).isNotNull();
        assertThat(r.symbol()).isEqualTo("BTCUSDT");
        assertThat(r.horizonHours()).isEqualTo(6);
        assertThat(r.testPoints()).isGreaterThan(0);
        assertThat(r.buyAndHoldReturn().doubleValue()).isGreaterThan(0); // 上行趋势 buy&hold 为正
        assertThat(r.strategies()).hasSize(2);
        assertThat(r.strategies()).extracting(StrategyLine::name)
                .containsExactly("ewma_momentum_2_4", "multi_factor_trend_funding_fng");
        for (StrategyLine s : r.strategies()) {
            assertThat(s.metrics().periods()).isEqualTo(r.testPoints()); // 每策略 periods 与 test 点数一致
            assertThat(s.naivePercentile()).isBetween(0.0, 1.0);
        }
        assertThat(r.summary()).contains("BTCUSDT").contains("buy&hold");
    }

    @Test
    void offChainSeriesAreAsOfAlignedAndAffectMultiFactorDirection() {
        // 足够长上涨样本(趋势腿=+1) + 全程极端正资金费 + 极贪 → off-chain 应把 MultiFactor 压成做空，与趋势相反。
        // 证明 fundingSeries/fearGreedSeries 经 SeriesAligner as-of 真装配进了预测（链下"穿过尺子"）。
        EvalParams params = new EvalParams(1.5, 0.94, 5, 0, 30, 100, 0.95, 42L);
        List<KlineBar> oneMin = uptrend1m(40 * 360); // 40 个 H6 桶 → 决策点 subList ≥30，ma_alignment 可算
        // 单点序列(ts=0)：任何决策点 as-of 都取到它（floor）
        List<MarketSeriesPoint> funding = List.of(new MarketSeriesPoint(0L, BigDecimal.valueOf(0.002)));  // 极端正→偏空
        List<MarketSeriesPoint> fearGreed = List.of(new MarketSeriesPoint(0L, BigDecimal.valueOf(95)));   // 极贪→偏空

        ComparisonReport r = ResearchEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, oneMin, funding, fearGreed,
                List.of(new EwmaMomentumForecaster(2, 4), MultiFactorForecaster.defaults()), params);

        StrategyLine ewma = r.strategies().get(0);
        StrategyLine multi = r.strategies().get(1);
        // 上涨中：EWMA 顺势做多→收益>0；MultiFactor 被 off-chain 压成做空→收益<0
        assertThat(ewma.strategyReturn().doubleValue()).isGreaterThan(0);
        assertThat(multi.strategyReturn().doubleValue()).isLessThan(0);
    }

    /** 生成 count 根 1m bar，价格每根 +0.1，时间从 0 起每根 +60_000ms。 */
    static List<KlineBar> uptrend1m(int count) {
        List<KlineBar> bars = new ArrayList<>(count);
        double price = 100.0;
        for (int i = 0; i < count; i++) {
            long t = i * 60_000L;
            BigDecimal o = BigDecimal.valueOf(price);
            BigDecimal c = BigDecimal.valueOf(price + 0.1);
            BigDecimal hi = BigDecimal.valueOf(price + 0.15);
            BigDecimal lo = BigDecimal.valueOf(price - 0.05);
            bars.add(new KlineBar(t, t + 59_999L, o, hi, lo, c, BigDecimal.ONE));
            price += 0.1;
        }
        return bars;
    }
}
