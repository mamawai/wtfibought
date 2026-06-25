package com.mawai.wiibquant.agent.strategy.core;

import com.mawai.wiibcommon.entity.ForceOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiquidationCascadeLegTest {

    @Test
    void passWhenSameSideLiquidationExceedsThreshold() {
        List<ForceOrder> recent = List.of(
                forceOrder("SELL", 300_000),
                forceOrder("SELL", 250_000),
                forceOrder("BUY", 10_000));

        assertEquals("liq_cascade=PASS",
                LiquidationCascadeLeg.evaluate(recent, true, new BigDecimal("500000")));
    }

    @Test
    void failWhenBelowThreshold() {
        List<ForceOrder> recent = List.of(forceOrder("SELL", 100_000));

        assertEquals("liq_cascade=FAIL",
                LiquidationCascadeLeg.evaluate(recent, true, new BigDecimal("500000")));
    }

    @Test
    void absentWhenNoData() {
        assertEquals("liq_cascade=ABSENT",
                LiquidationCascadeLeg.evaluate(List.of(), true, new BigDecimal("500000")));
        assertEquals("liq_cascade=ABSENT",
                LiquidationCascadeLeg.evaluate(null, false, new BigDecimal("500000")));
    }

    private static ForceOrder forceOrder(String side, double amount) {
        ForceOrder order = new ForceOrder();
        order.setSide(side);
        order.setAmount(BigDecimal.valueOf(amount));
        return order;
    }
}
