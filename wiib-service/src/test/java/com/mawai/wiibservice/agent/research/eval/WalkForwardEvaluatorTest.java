package com.mawai.wiibservice.agent.research.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WalkForwardEvaluatorTest {

    @Test
    void windowsHaveNoLabelLeakageAndRespectEmbargo() {
        int total = 1000, testSize = 100, horizonBars = 12, embargo = 12, minTrain = 200;

        List<WalkForwardWindow> wins = WalkForwardEvaluator.windows(total, testSize, horizonBars, embargo, minTrain);

        assertThat(wins).isNotEmpty();
        int prevTestEnd = -1;
        for (WalkForwardWindow w : wins) {
            // 1) 无标签泄漏：最后一个训练样本的标签窗(trainEnd-1 + horizonBars)不得触及 testStart
            assertThat(w.trainEnd() + horizonBars).isLessThanOrEqualTo(w.testStart());
            // 2) embargo 生效：train↔test 间隔严格 = horizonBars + embargo
            assertThat(w.testStart() - w.trainEnd()).isEqualTo(horizonBars + embargo);
            // 3) train 不少于下限、test 在界内、test 块不重叠且前进
            assertThat(w.trainSize()).isGreaterThanOrEqualTo(minTrain);
            assertThat(w.testStart()).isGreaterThanOrEqualTo(prevTestEnd); // 半开区间相接=不重叠
            assertThat(w.testEnd()).isLessThanOrEqualTo(total);
            assertThat(w.testSize()).isEqualTo(testSize);
            prevTestEnd = w.testEnd();
        }
    }

    @Test
    void tooSmallTotalGivesNoWindows() {
        // total 不足以容纳 minTrain+gap+testSize
        assertThat(WalkForwardEvaluator.windows(100, 50, 12, 12, 200)).isEmpty();
    }
}
