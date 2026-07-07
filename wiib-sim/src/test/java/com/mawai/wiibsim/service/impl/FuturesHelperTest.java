package com.mawai.wiibsim.service.impl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FuturesHelperTest {

    @Test
    void fundingTransfer_正费率_多付空收() {
        BigDecimal notional = new BigDecimal("10000");
        BigDecimal rate = new BigDecimal("0.0001");
        assertThat(FuturesHelper.fundingTransfer("LONG", notional, rate)).isEqualByComparingTo("1.00");
        assertThat(FuturesHelper.fundingTransfer("SHORT", notional, rate)).isEqualByComparingTo("-1.00");
    }

    @Test
    void fundingTransfer_负费率_空付多收() {
        BigDecimal notional = new BigDecimal("10000");
        BigDecimal rate = new BigDecimal("-0.0002");
        assertThat(FuturesHelper.fundingTransfer("LONG", notional, rate)).isEqualByComparingTo("-2.00");
        assertThat(FuturesHelper.fundingTransfer("SHORT", notional, rate)).isEqualByComparingTo("2.00");
    }

    @Test
    void fundingTransfer_零费率与分精度() {
        assertThat(FuturesHelper.fundingTransfer("LONG", new BigDecimal("10000"), BigDecimal.ZERO).signum()).isZero();
        assertThat(FuturesHelper.fundingTransfer("LONG", new BigDecimal("12345"), new BigDecimal("0.0001")))
                .isEqualByComparingTo("1.23");   // 1.2345 → 分精度 HALF_UP
    }
}
