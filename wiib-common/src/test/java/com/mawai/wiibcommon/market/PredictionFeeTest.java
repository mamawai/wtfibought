package com.mawai.wiibcommon.market;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 钉官方文档的例子：100 份 @0.50 收 1.75；两头对称、越极端越便宜 */
class PredictionFeeTest {

    @Test
    void 官方例子100份半价收1_75() {
        assertThat(PredictionFee.commission(new BigDecimal("100"), new BigDecimal("0.50")))
                .isEqualByComparingTo("1.75");
    }

    @Test
    void 两头对称且比中间便宜() {
        BigDecimal at30 = PredictionFee.commission(new BigDecimal("100"), new BigDecimal("0.30"));
        BigDecimal at70 = PredictionFee.commission(new BigDecimal("100"), new BigDecimal("0.70"));
        assertThat(at30).isEqualByComparingTo(at70).isEqualByComparingTo("1.47");
        assertThat(PredictionFee.commission(new BigDecimal("100"), new BigDecimal("0.10")))
                .isEqualByComparingTo("0.63");
    }
}
