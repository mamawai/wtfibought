package com.mawai.wiibcommon.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KlineIntervalTest {

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
}
