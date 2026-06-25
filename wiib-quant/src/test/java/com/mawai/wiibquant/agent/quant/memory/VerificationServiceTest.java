package com.mawai.wiibquant.agent.quant.memory;

import com.mawai.wiibquant.agent.quant.memory.VerificationService.VolStateOutcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账纯函数单测：纯方向命中 + vol 档判定，全合成数据，不连库可独立跑。
 * 覆盖 review 钉死的两个要点：纯方向不掺路径、历史不足不判档(防假命中)。
 */
class VerificationServiceTest {

    // ==================== 纯方向命中 ====================

    @Test
    void directionHitLongShort() {
        assertThat(VerificationService.judgeDirectionHit("LONG", 30)).isTrue();   // 看涨且涨
        assertThat(VerificationService.judgeDirectionHit("LONG", -30)).isFalse(); // 看涨却跌
        assertThat(VerificationService.judgeDirectionHit("LONG", 0)).isFalse();   // 没动不算方向对
        assertThat(VerificationService.judgeDirectionHit("SHORT", -30)).isTrue(); // 看跌且跌
        assertThat(VerificationService.judgeDirectionHit("SHORT", 30)).isFalse(); // 看跌却涨
    }

    @Test
    void directionHitNoTrade() {
        assertThat(VerificationService.judgeDirectionHit("NO_TRADE", 5)).isTrue();   // 够静(|5|<10)
        assertThat(VerificationService.judgeDirectionHit("NO_TRADE", 0)).isTrue();   // 完全没动
        assertThat(VerificationService.judgeDirectionHit("NO_TRADE", 20)).isFalse(); // 错过行情(|20|>=10)
        assertThat(VerificationService.judgeDirectionHit("NO_TRADE", -20)).isFalse();
    }

    @Test
    void directionHitUnknownFallsFalse() {
        assertThat(VerificationService.judgeDirectionHit("WHATEVER", 30)).isFalse();
    }

    // ==================== vol 档判定 ====================

    /** [1..n] 升序：tercile 切点 ≈ n/3、2n/3，便于构造落档样本。 */
    private static double[] ramp(int n) {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) a[i] = i + 1;
        return a;
    }

    @Test
    void volOutcomeMissWhenDifferentTier() {
        // [1..300] → lowCut≈100, highCut≈200；50→LOW，250→HIGH
        VolStateOutcome o = VerificationService.classifyVolOutcome(ramp(300), 50, 250, 100);
        assertThat(o.predictedState()).isEqualTo("LOW");
        assertThat(o.actualState()).isEqualTo("HIGH");
        assertThat(o.hit()).isFalse();
    }

    @Test
    void volOutcomeHitWhenSameTier() {
        // 150、160 都落中档
        VolStateOutcome o = VerificationService.classifyVolOutcome(ramp(300), 150, 160, 100);
        assertThat(o.predictedState()).isEqualTo("MID");
        assertThat(o.actualState()).isEqualTo("MID");
        assertThat(o.hit()).isTrue();
    }

    @Test
    void volOutcomeNullWhenHistoryTooShort() {
        // 50 < minHistory 100 → 不判，防 classifier 退化全 MID 假命中
        VolStateOutcome o = VerificationService.classifyVolOutcome(ramp(50), 10, 40, 100);
        assertThat(o.predictedState()).isNull();
        assertThat(o.actualState()).isNull();
        assertThat(o.hit()).isNull();
    }

    @Test
    void volOutcomeNullWhenHistoryNull() {
        VolStateOutcome o = VerificationService.classifyVolOutcome(null, 10, 40, 100);
        assertThat(o.hit()).isNull();
    }
}
