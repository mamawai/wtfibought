package com.mawai.wiibquant.agent.research.forecast;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VolStateClassifierTest {

    @Test
    void classifiesByHistoryTerciles() {
        // 历史 1..9 → 1/3 分位=3、2/3 分位=6
        VolStateClassifier c = VolStateClassifier.fromHistory(new double[]{1, 2, 3, 4, 5, 6, 7, 8, 9});
        assertThat(c.lowCut()).isEqualTo(3.0);
        assertThat(c.highCut()).isEqualTo(6.0);
        assertThat(c.classify(2)).isEqualTo(VolState.LOW);
        assertThat(c.classify(3)).isEqualTo(VolState.MID);   // 界点归中档
        assertThat(c.classify(5)).isEqualTo(VolState.MID);
        assertThat(c.classify(7)).isEqualTo(VolState.HIGH);
    }

    @Test
    void tercilesStayBalancedOnUniformHistory() {
        double[] hist = new double[300];
        for (int i = 0; i < 300; i++) hist[i] = i + 1;       // 1..300 唯一值
        VolStateClassifier c = VolStateClassifier.fromHistory(hist);
        int low = 0, mid = 0, high = 0;
        for (int i = 1; i <= 300; i++) {
            switch (c.classify(i)) {
                case LOW -> low++;
                case MID -> mid++;
                case HIGH -> high++;
            }
        }
        // 三档大致均衡(各约 100)：验证 tercile 不塌缩到某一档(否则 balanced accuracy 会失真)
        assertThat(low).isBetween(80, 120);
        assertThat(mid).isBetween(80, 120);
        assertThat(high).isBetween(80, 120);
    }

    @Test
    void percentileReflectsHistoryPosition() {
        VolStateClassifier c = VolStateClassifier.fromHistory(new double[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        assertThat(c.percentile(5)).isEqualTo(0.4);   // 4 个 <5 / 10
        assertThat(c.percentile(1)).isEqualTo(0.0);   // 无 <1
        assertThat(c.percentile(11)).isEqualTo(1.0);  // 全 <11
    }

    @Test
    void emptyHistoryDegradesToMid() {
        VolStateClassifier c = VolStateClassifier.fromHistory(new double[0]);
        assertThat(c.classify(0.05)).isEqualTo(VolState.MID);
        assertThat(c.classify(999)).isEqualTo(VolState.MID);
        assertThat(c.percentile(0.05)).isEqualTo(0.5);
    }
}
