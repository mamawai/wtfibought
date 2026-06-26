package com.mawai.wiibquant.agent.quant.domain.debate;

import com.mawai.wiibquant.agent.quant.domain.Direction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeakLeanTest {

    @Test
    void parsesLeanAndKeepsNarratives() {
        WeakLean wl = WeakLean.from("H6", "LONG", 45, 35, 20,
                "若多头拥挤兑现，12h或回落", "funding回正则作废");

        assertThat(wl.lean()).isEqualTo(Direction.LONG);
        assertThat(wl.bullPct()).isEqualTo(45);
        assertThat(wl.rangePct()).isEqualTo(35);
        assertThat(wl.bearPct()).isEqualTo(20);
        assertThat(wl.consequence()).contains("回落");
        assertThat(wl.invalidation()).contains("作废");
        assertThat(wl.label()).isEqualTo("低置信·非硬核预测");
    }

    @Test
    void nullOrUnknownLeanIsNoTradeNoLeanState() {
        assertThat(WeakLean.from("H12", null, 30, 40, 30, null, null).lean())
                .isEqualTo(Direction.NO_TRADE);
        assertThat(WeakLean.from("H12", "MAYBE", 30, 40, 30, null, null).lean())
                .isEqualTo(Direction.NO_TRADE);
    }

    @Test
    void missingDistributionFallsBackToEvenSplit() {
        WeakLean wl = WeakLean.from("H24", "SHORT", null, null, null, null, null);

        assertThat(wl.bullPct()).isEqualTo(33);
        assertThat(wl.rangePct()).isEqualTo(34);
        assertThat(wl.bearPct()).isEqualTo(33);
    }

    @Test
    void distributionNormalizesToHundred() {
        // 50/30/40 = 120 → 归一化后三者和必须为 100
        WeakLean wl = WeakLean.from("H6", "LONG", 50, 30, 40, null, null);

        assertThat(wl.bullPct() + wl.rangePct() + wl.bearPct()).isEqualTo(100);
    }

    @Test
    void blankNarrativesBecomeNull() {
        WeakLean wl = WeakLean.from("H6", "LONG", 45, 35, 20, "", "  ");

        assertThat(wl.consequence()).isNull();
        assertThat(wl.invalidation()).isNull();
    }
}
