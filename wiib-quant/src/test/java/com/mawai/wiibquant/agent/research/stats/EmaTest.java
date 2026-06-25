package com.mawai.wiibquant.agent.research.stats;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EmaTest {

    @Test
    void seedIsSmaWhenNoExtraValues() {
        // period=3, values=[2,4,6] → seed=SMA=4，无后续 → 4
        assertThat(Ema.ema(bd("2", "4", "6"), 3)).isEqualByComparingTo("4");
    }

    @Test
    void recursesWithSmoothingFactor() {
        // period=3 → k=2/(3+1)=0.5；seed=4；i=3: ema=4+0.5*(8-4)=6
        assertThat(Ema.ema(bd("2", "4", "6", "8"), 3)).isEqualByComparingTo("6");
    }

    @Test
    void insufficientDataGivesNull() {
        assertThat(Ema.ema(bd("1", "2"), 3)).isNull();
    }

    static List<BigDecimal> bd(String... v) {
        return Stream.of(v).map(BigDecimal::new).toList();
    }
}
