package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.forecast.MarketRegime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * q 扫描特有纯函数单测：按 q 重建标签 + directionality 分位。
 * 混淆矩阵/偏斜稳健指标的数学由 RegimeClassificationScoreTest 守，此处不重复。
 */
class RegimeQScanTest {

    @Test
    void labelAtShockTakesPriorityOverDirectionality() {
        // SHOCK 优先：即便 directionality 很高，shock=true 也判 SHOCK（与 RegimeLabeler 同语义）
        assertThat(RegimeQScan.labelAt(10.0, true, 1, 2.0)).isEqualTo(MarketRegime.SHOCK);
    }

    @Test
    void labelAtAboveThresholdUsesNetSign() {
        assertThat(RegimeQScan.labelAt(3.0, false, 1, 2.0)).isEqualTo(MarketRegime.TRENDING_UP);
        assertThat(RegimeQScan.labelAt(3.0, false, -1, 2.0)).isEqualTo(MarketRegime.TRENDING_DOWN);
    }

    @Test
    void labelAtBelowThresholdIsRanging() {
        assertThat(RegimeQScan.labelAt(1.5, false, 1, 2.0)).isEqualTo(MarketRegime.RANGING);
    }

    @Test
    void labelAtThresholdIsInclusive() {
        // directionality == q 取趋势（≥），与 RegimeLabeler 的 >= 一致
        assertThat(RegimeQScan.labelAt(2.0, false, 1, 2.0)).isEqualTo(MarketRegime.TRENDING_UP);
    }

    @Test
    void percentileLinearInterpolation() {
        double[] v = {5, 1, 4, 2, 3}; // 乱序，内部应排序
        assertThat(RegimeQScan.percentile(v, 0.0)).isCloseTo(1.0, within(1e-9));
        assertThat(RegimeQScan.percentile(v, 0.5)).isCloseTo(3.0, within(1e-9));
        assertThat(RegimeQScan.percentile(v, 1.0)).isCloseTo(5.0, within(1e-9));
        assertThat(RegimeQScan.percentile(v, 0.25)).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void percentileHandlesSingleAndEmpty() {
        assertThat(RegimeQScan.percentile(new double[]{7.0}, 0.9)).isCloseTo(7.0, within(1e-9));
        assertThat(RegimeQScan.percentile(new double[]{}, 0.5)).isNaN();
    }
}
