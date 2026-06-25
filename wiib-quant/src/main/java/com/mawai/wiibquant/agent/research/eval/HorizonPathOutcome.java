package com.mawai.wiibquant.agent.research.eval;

import com.mawai.wiibcommon.market.KlineBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 单个预测 horizon 的真实路径结果：收盘涨跌 + 区间最高/最低相对 entry 的 bps。 */
public record HorizonPathOutcome(int actualChangeBps, int maxUpBps, int maxDownBps) {

    public int directionalChangeBps(int direction) {
        return direction > 0 ? actualChangeBps : direction < 0 ? -actualChangeBps : 0;
    }

    public int maxFavorableBps(int direction) {
        return direction > 0 ? maxUpBps : direction < 0 ? maxDownBps : 0;
    }

    public int maxAdverseBps(int direction) {
        return direction > 0 ? maxDownBps : direction < 0 ? maxUpBps : 0;
    }

    public static HorizonPathOutcome from(BigDecimal entry, BigDecimal fallbackClose, List<KlineBar> path) {
        if (entry == null || entry.signum() <= 0) {
            return new HorizonPathOutcome(0, 0, 0);
        }
        BigDecimal high = fallbackClose != null ? fallbackClose : entry;
        BigDecimal low = fallbackClose != null ? fallbackClose : entry;
        BigDecimal close = fallbackClose != null ? fallbackClose : entry;
        if (path != null && !path.isEmpty()) {
            close = path.get(path.size() - 1).close();
            high = path.get(0).high();
            low = path.get(0).low();
            for (KlineBar b : path) {
                if (b.high().compareTo(high) > 0) high = b.high();
                if (b.low().compareTo(low) < 0) low = b.low();
            }
        }
        return new HorizonPathOutcome(
                bps(entry, close),
                Math.max(0, bps(entry, high)),
                Math.max(0, -bps(entry, low)));
    }

    static HorizonPathOutcome fromStats(BigDecimal entry, BigDecimal close, BigDecimal high, BigDecimal low) {
        if (entry == null || entry.signum() <= 0) {
            return new HorizonPathOutcome(0, 0, 0);
        }
        BigDecimal safeClose = close != null ? close : entry;
        BigDecimal safeHigh = high != null ? high : safeClose;
        BigDecimal safeLow = low != null ? low : safeClose;
        return new HorizonPathOutcome(
                bps(entry, safeClose),
                Math.max(0, bps(entry, safeHigh)),
                Math.max(0, -bps(entry, safeLow)));
    }

    private static int bps(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.signum() == 0) return 0;
        return to.subtract(from)
                .multiply(BigDecimal.valueOf(10_000))
                .divide(from, 0, RoundingMode.HALF_UP)
                .intValue();
    }
}
