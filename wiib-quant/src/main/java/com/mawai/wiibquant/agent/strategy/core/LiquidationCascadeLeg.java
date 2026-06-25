package com.mawai.wiibquant.agent.strategy.core;

import com.mawai.wiibcommon.entity.ForceOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * 爆仓级联确认腿（tag-only）。
 *
 * <p>做多信号看 SELL 爆仓（多单被强平），做空信号看 BUY 爆仓（空单被强平）。
 * forceOrder 是采样流，金额只当清洗强度下界；v1 只落标签，不拦截信号。</p>
 */
public final class LiquidationCascadeLeg {

    private LiquidationCascadeLeg() {
    }

    public static String evaluate(List<ForceOrder> recent, boolean signalIsLong, BigDecimal notionalThreshold) {
        if (recent == null || recent.isEmpty()) return "liq_cascade=ABSENT";

        String washSide = signalIsLong ? "SELL" : "BUY";
        BigDecimal threshold = notionalThreshold == null ? BigDecimal.ZERO : notionalThreshold;
        BigDecimal total = recent.stream()
                .filter(order -> order != null)
                .filter(order -> washSide.equals(normalizeSide(order.getSide())))
                .map(ForceOrder::getAmount)
                .filter(amount -> amount != null && amount.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return total.compareTo(threshold) >= 0 ? "liq_cascade=PASS" : "liq_cascade=FAIL";
    }

    private static String normalizeSide(String side) {
        return side == null ? "" : side.trim().toUpperCase(Locale.ROOT);
    }
}
