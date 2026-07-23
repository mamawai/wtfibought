package com.mawai.wiibquant.agent.strategy.core;

import com.mawai.wiibcommon.market.TradeFilterDefaults;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定量出口的交易过滤器落地：步长向下对齐（不放大风险），
 * 对齐后不足最小数量/最小名义额返回 0 = 不开仓（sim 端同规则硬校验，出口不对齐必被拒单）。
 */
class PositionSizerFilterTest {

    private static final TradeFilterDefaults.Filter BTC = TradeFilterDefaults.futures("BTCUSDT");

    @Test
    void 步长向下对齐_不放大数量() {
        // BTC step 0.001：0.0234567 → 0.023
        assertThat(PositionSizer.applyFilter(new BigDecimal("0.0234567"), new BigDecimal("100000"), BTC))
                .isEqualByComparingTo("0.023");
    }

    @Test
    void 对齐后不足最小数量_返回0() {
        assertThat(PositionSizer.applyFilter(new BigDecimal("0.0009"), new BigDecimal("100000"), BTC))
                .isEqualByComparingTo("0");
    }

    @Test
    void 名义额不足minNotional_返回0() {
        // 0.001 × 40000 = 40 < 50
        assertThat(PositionSizer.applyFilter(new BigDecimal("0.001"), new BigDecimal("40000"), BTC))
                .isEqualByComparingTo("0");
    }

    @Test
    void 无过滤器_原样返回() {
        assertThat(PositionSizer.applyFilter(new BigDecimal("0.0234567"), new BigDecimal("100000"), null))
                .isEqualByComparingTo("0.0234567");
    }

    @Test
    void riskSizedQty_出口已对齐步长() {
        // 权益1万、entry 100000、止损 99000（1%距离）、风险默认1%、20x → 原始qty=10000×0.01/1000=0.1 → 已对齐
        BigDecimal qty = PositionSizer.riskSizedQty(new BigDecimal("10000"), new BigDecimal("100000"),
                new BigDecimal("99000"), StrategyRiskPolicy.defaults(), 20, BTC);
        assertThat(qty.remainder(BTC.stepSize())).isEqualByComparingTo("0");
        assertThat(qty.multiply(new BigDecimal("100000"))).isGreaterThanOrEqualTo(BTC.minNotional());
    }
}
