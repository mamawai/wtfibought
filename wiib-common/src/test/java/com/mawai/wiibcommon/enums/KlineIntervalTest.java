package com.mawai.wiibcommon.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KlineIntervalTest {

    @Test
    void aggregateRatioToReturnsExactBarCount() {
        assertThat(KlineInterval.M1.aggregateRatioTo(KlineInterval.H1)).isEqualTo(60);
        assertThat(KlineInterval.M3.aggregateRatioTo(KlineInterval.M15)).isEqualTo(5);
        assertThat(KlineInterval.M5.aggregateRatioTo(KlineInterval.M15)).isEqualTo(3);
        assertThat(KlineInterval.M15.aggregateRatioTo(KlineInterval.M15)).isEqualTo(1);
    }

    @Test
    void aggregateRatioToRejectsNonDivisibleTarget() {
        assertThatThrownBy(() -> KlineInterval.M5.aggregateRatioTo(KlineInterval.M3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法整除聚合");
    }

    @Test
    void fromCodeReturnsMatchingInterval() {
        assertThat(KlineInterval.fromCode("1m")).isEqualTo(KlineInterval.M1);
        assertThat(KlineInterval.fromCode("3m")).isEqualTo(KlineInterval.M3);
        assertThat(KlineInterval.fromCode("5m")).isEqualTo(KlineInterval.M5);
        assertThat(KlineInterval.fromCode("15m")).isEqualTo(KlineInterval.M15);
        assertThat(KlineInterval.fromCode("1h")).isEqualTo(KlineInterval.H1);
    }

    @Test
    void fromCodeRejectsUnknownCode() {
        assertThatThrownBy(() -> KlineInterval.fromCode("4h"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知周期");
    }

    @Test
    void h1KeepsEnoughHistoryForMaSlopeM15Confirm() {
        assertThat(KlineInterval.H1.getHistoryLimit()).isEqualTo(72);
    }
}
