package com.mawai.wiibquant.agent.research.metrics;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RiskAdjustedMetricsTest {

    @Test
    void annualizedSharpeAndHitRate() {
        // returns=[0.03,0.01,0.02,0.02], periodsPerYear=365
        // mean=0.02; 样本方差(n-1)=((0.01)²+(-0.01)²+0+0)/3=6.6667e-5; sd=0.00816497
        // per-period Sharpe=0.02/0.00816497=2.44949; 年化×sqrt(365)=2.44949*19.10497=46.797
        RiskAdjustedMetrics m = RiskAdjustedMetrics.from(series(365, "0.03", "0.01", "0.02", "0.02"));

        assertThat(m.annualizedSharpe()).isCloseTo(46.80, within(0.1));
        assertThat(m.hitRate()).isCloseTo(1.0, within(1e-9));     // 4 期全正
        assertThat(m.maxDrawdown()).isCloseTo(0.0, within(1e-9)); // 单调上行无回撤
        assertThat(m.periods()).isEqualTo(4);
    }

    @Test
    void maxDrawdownAndCalmar() {
        // returns=[0.10,-0.20,0.05], periodsPerYear=365
        // equity: 1.10, 0.88, 0.924；peak=1.10；MaxDD=(1.10-0.88)/1.10=0.2
        // years=3/365；CAGR=0.924^(365/3)-1≈-0.99993；Calmar=CAGR/MaxDD≈-5.0
        RiskAdjustedMetrics m = RiskAdjustedMetrics.from(series(365, "0.10", "-0.20", "0.05"));

        assertThat(m.maxDrawdown()).isCloseTo(0.20, within(1e-9));
        assertThat(m.calmar()).isCloseTo(-5.0, within(0.01));
        assertThat(m.hitRate()).isCloseTo(2.0 / 3.0, within(1e-6)); // 2/3 正
    }

    @Test
    void zeroStdGivesZeroSharpe() {
        // 常数收益 → sd=0 → Sharpe=0（与现有 BacktestTradingTools 口径一致）
        RiskAdjustedMetrics m = RiskAdjustedMetrics.from(series(365, "0.02", "0.02", "0.02"));
        assertThat(m.annualizedSharpe()).isZero();
    }

    static ReturnSeries series(int ppy, String... rs) {
        List<BigDecimal> list = Stream.of(rs).map(BigDecimal::new).toList();
        return new ReturnSeries("test", list, ppy);
    }
}
