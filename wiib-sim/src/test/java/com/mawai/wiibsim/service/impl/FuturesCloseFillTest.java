package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.service.impl.FuturesHelper.CloseFill;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.mawai.wiibsim.service.impl.FuturesHelper.closeFill;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 平仓结算封顶：逐仓亏损封在这部分保证金以内，全仓原样。手续费固定 0.05。
 * 例：逐仓多 1 张 @100、保证金 10，破产价 90。
 */
class FuturesCloseFillTest {

    private static final TradingConfig TRADING = mock(TradingConfig.class);

    static {
        when(TRADING.calculateFuturesCommission(any(), anyBoolean(), anyBoolean())).thenReturn(new BigDecimal("0.05"));
    }

    private static FuturesPosition position(String side, String mode, String qty, String margin) {
        FuturesPosition p = new FuturesPosition();
        p.setSide(side);
        p.setMarginMode(mode);
        p.setEntryPrice(new BigDecimal("100"));
        p.setQuantity(new BigDecimal(qty));
        p.setMargin(new BigDecimal(margin));
        return p;
    }

    private static CloseFill fill(FuturesPosition p, String price, String qty, String marginPart) {
        return closeFill(p, new BigDecimal(price), new BigDecimal(qty), new BigDecimal(marginPart), TRADING, true);
    }

    @Test
    void 逐仓没越过破产价_原样() {
        CloseFill f = fill(position("LONG", FuturesPosition.ISOLATED, "1", "10"), "95", "1", "10");

        assertThat(f.price()).isEqualByComparingTo("95");
        assertThat(f.pnl()).isEqualByComparingTo("-5");
        assertThat(f.commission()).isEqualByComparingTo("0.05");
    }

    @Test
    void 逐仓剩下的不够付手续费_手续费收到剩下为止() {
        // 跌到 90.02：保证金只剩 0.02，手续费 0.05 只收 0.02
        CloseFill f = fill(position("LONG", FuturesPosition.ISOLATED, "1", "10"), "90.02", "1", "10");

        assertThat(f.pnl()).isEqualByComparingTo("-9.98");
        assertThat(f.commission()).isEqualByComparingTo("0.02");
    }

    @Test
    void 逐仓越过破产价_按破产价记_亏损等于保证金_不收手续费() {
        // 跳空到 85，越过破产价 90
        CloseFill f = fill(position("LONG", FuturesPosition.ISOLATED, "1", "10"), "85", "1", "10");

        assertThat(f.price()).isEqualByComparingTo("90");
        assertThat(f.pnl()).isEqualByComparingTo("-10");
        assertThat(f.commission()).isEqualByComparingTo("0");
        assertThat(f.value(BigDecimal.ONE)).isEqualByComparingTo("90");
    }

    @Test
    void 逐仓空头破产价在开仓价上方() {
        // 空 1 张 @100、保证金 10，破产价 110；跳空到 120
        CloseFill f = fill(position("SHORT", FuturesPosition.ISOLATED, "1", "10"), "120", "1", "10");

        assertThat(f.price()).isEqualByComparingTo("110");
        assertThat(f.pnl()).isEqualByComparingTo("-10");
    }

    @Test
    void 逐仓部分平仓按这部分保证金封顶() {
        // 多 2 张、保证金 20，破产价仍是 90；平 1 张，这部分保证金 10，跳空到 80
        CloseFill f = fill(position("LONG", FuturesPosition.ISOLATED, "2", "20"), "80", "1", "10");

        assertThat(f.price()).isEqualByComparingTo("90");
        assertThat(f.pnl()).isEqualByComparingTo("-10");
        assertThat(f.commission()).isEqualByComparingTo("0");
    }

    @Test
    void 全仓不封顶() {
        CloseFill f = fill(position("LONG", FuturesPosition.CROSS, "1", "10"), "85", "1", "10");

        assertThat(f.price()).isEqualByComparingTo("85");
        assertThat(f.pnl()).isEqualByComparingTo("-15");
        assertThat(f.commission()).isEqualByComparingTo("0.05");
    }
}
