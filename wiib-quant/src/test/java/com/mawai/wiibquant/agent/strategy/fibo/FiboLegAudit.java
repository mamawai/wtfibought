package com.mawai.wiibquant.agent.strategy.fibo;

import com.mawai.wiibcommon.market.KlineBar;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * ETH 腿审计工具（测试域）：枚举"所有的腿"（后见分形摆动点，非策略用的防重绘 ZigZag），
 * 逐腿模拟黄金口袋回撤的应然结果，供"命中 vs 没命中"因子挖掘。
 *
 * <p>后见(lookahead)在此是合法的——这是对"市场到底给过哪些回撤"的取证复盘，不是实盘信号
 * （实盘那套 {@link SwingDetector} 照旧防重绘）。</p>
 */
public final class FiboLegAudit {

    private FiboLegAudit() {
    }

    /**
     * 5m 基础 K 线滚动聚合到 tfMillis 高周期（对齐生产 WindowedMarketView 口径）：按 floor(openTime/tf) 分桶，
     * OHLC=首开/桶内最高/最低/末收，volume 累加；只输出"完整桶"（凑满 tf/base 根）模拟已闭合高周期 bar。
     */
    public static List<KlineBar> aggregate(List<KlineBar> base, long tfMillis) {
        List<KlineBar> out = new ArrayList<>();
        if (base == null || base.size() < 2) return out;
        long baseMs = base.get(1).openTime() - base.get(0).openTime();
        if (baseMs <= 0) return out;
        int need = (int) (tfMillis / baseMs);
        int i = 0, n = base.size();
        while (i < n) {
            long bucket = Math.floorDiv(base.get(i).openTime(), tfMillis) * tfMillis;
            BigDecimal open = base.get(i).open(), high = base.get(i).high(),
                    low = base.get(i).low(), close = base.get(i).close(), vol = BigDecimal.ZERO;
            long closeTime = base.get(i).closeTime();
            int cnt = 0, j = i;
            while (j < n && Math.floorDiv(base.get(j).openTime(), tfMillis) * tfMillis == bucket) {
                KlineBar b = base.get(j);
                if (b.high().compareTo(high) > 0) high = b.high();
                if (b.low().compareTo(low) < 0) low = b.low();
                close = b.close();
                closeTime = b.closeTime();
                vol = vol.add(b.volume());
                cnt++;
                j++;
            }
            if (cnt == need) out.add(new KlineBar(bucket, closeTime, open, high, low, close, vol));  // 只收完整桶
            i = j;
        }
        return out;
    }

    /**
     * 分形摆动点：bar i 的 high 严格大于 [i-k,i+k] 内其它 bar 的 high → 摆动高；low 对称 → 摆动低。
     * 端点不足 k 根的跳过。返回按索引升序的原始分形点（可能相邻同型，交替归并在 legs() 里做）。
     */
    public static List<SwingDetector.Pivot> fractalPivots(List<KlineBar> bars, int k) {
        List<SwingDetector.Pivot> pivots = new ArrayList<>();
        if (bars == null || bars.size() < 2 * k + 1) return pivots;
        for (int i = k; i < bars.size() - k; i++) {
            double hi = bars.get(i).high().doubleValue();
            double lo = bars.get(i).low().doubleValue();
            boolean isHigh = true, isLow = true;
            for (int j = i - k; j <= i + k; j++) {
                if (j == i) continue;
                if (bars.get(j).high().doubleValue() >= hi) isHigh = false;   // 严格：邻居持平也不算极值
                if (bars.get(j).low().doubleValue() <= lo) isLow = false;
            }
            if (isHigh) pivots.add(new SwingDetector.Pivot(i, bars.get(i).high(), SwingDetector.PivotType.HIGH));
            else if (isLow) pivots.add(new SwingDetector.Pivot(i, bars.get(i).low(), SwingDetector.PivotType.LOW));
        }
        return pivots;
    }

    /** 一条摆动腿：startIdx→endIdx，上行腿=起点低终点高。 */
    public record Leg2(int startIdx, int endIdx, BigDecimal startPrice, BigDecimal endPrice, boolean upLeg) {
    }

    /**
     * 一条腿的黄金口袋回撤应然结果。
     * touched=价是否回踩进口袋(可成交)；barsToPocket=腿终点到成交的根数；
     * outcome=WIN(先到延伸位)/LOSS(先到止损)/NONE(没回踩进口袋)/OPEN(窗口内未了结)；
     * mfeR/maeR=成交后最大顺/逆幅度(R)。
     */
    public record RetOutcome(boolean touched, int barsToPocket, String outcome, double mfeR, double maeR) {
    }

    /**
     * 模拟一条腿在 entryFib 口袋挂限价回踩的应然结果（后见、纯几何，口径对齐生产策略）。
     * 止损=slFib 档 ∓ slBufAtr×atr；止盈=tpExt 延伸位(1.0=前高/前低)；同根 SL 优先(对齐引擎保守撮合)。
     */
    public static RetOutcome simulateRetracement(Leg2 leg, List<KlineBar> bars, double atrAtEnd,
                                                 double entryFib, double slFib, double slBufAtr,
                                                 double tpExt, int maxForwardBars) {
        FiboLevels.FiboGrid grid = new FiboLevels.FiboGrid(leg.startPrice(), leg.endPrice(), leg.upLeg());
        double pocket = grid.retracement(entryFib).doubleValue();
        double base = grid.retracement(slFib).doubleValue();
        double buf = slBufAtr * atrAtEnd;
        double stop = leg.upLeg() ? base - buf : base + buf;
        double tp = grid.extension(tpExt).doubleValue();
        double risk = Math.abs(pocket - stop);
        if (risk <= 0) return new RetOutcome(false, -1, "NONE", 0, 0);
        int limit = Math.min(bars.size() - 1, leg.endIdx() + maxForwardBars);

        // 相位1：腿终点后向前找第一根回踩进口袋的 bar（上行腿 low<=口袋 / 下行腿 high>=口袋）
        int fillIdx = -1;
        for (int j = leg.endIdx() + 1; j <= limit; j++) {
            KlineBar b = bars.get(j);
            boolean touch = leg.upLeg()
                    ? b.low().doubleValue() <= pocket
                    : b.high().doubleValue() >= pocket;
            if (touch) { fillIdx = j; break; }
        }
        if (fillIdx < 0) return new RetOutcome(false, -1, "NONE", 0, 0);

        // 相位2：成交后向前扫止损/止盈，累计 mfe/mae（同根 SL 优先）
        double mfe = 0, mae = 0;
        String outcome = "OPEN";
        for (int j = fillIdx; j <= limit; j++) {
            KlineBar b = bars.get(j);
            double hi = b.high().doubleValue(), lo = b.low().doubleValue();
            if (leg.upLeg()) {
                mfe = Math.max(mfe, (hi - pocket) / risk);
                mae = Math.min(mae, (lo - pocket) / risk);
            } else {
                mfe = Math.max(mfe, (pocket - lo) / risk);
                mae = Math.min(mae, (pocket - hi) / risk);
            }
            boolean stopHit = leg.upLeg() ? lo <= stop : hi >= stop;
            boolean tpHit = leg.upLeg() ? hi >= tp : lo <= tp;
            if (stopHit) { outcome = "LOSS"; break; }   // 同根 SL 优先
            if (tpHit) { outcome = "WIN"; break; }
        }
        return new RetOutcome(true, fillIdx - leg.endIdx(), outcome, mfe, mae);
    }

    /**
     * 把原始分形点归并成严格交替序列：遇到与上一个同型的点，保留更极值的（更高的高 / 更低的低）。
     * 保证输出 HIGH/LOW/HIGH/... 交替，供 legs() 直接取相邻对成腿。
     */
    public static List<SwingDetector.Pivot> alternate(List<SwingDetector.Pivot> raw) {
        List<SwingDetector.Pivot> out = new ArrayList<>();
        for (SwingDetector.Pivot p : raw) {
            if (out.isEmpty() || out.getLast().type() != p.type()) {
                out.add(p);
                continue;
            }
            SwingDetector.Pivot prev = out.getLast();   // 同型：留更极值的（更高的高 / 更低的低）
            boolean moreExtreme = p.type() == SwingDetector.PivotType.HIGH
                    ? p.price().compareTo(prev.price()) > 0
                    : p.price().compareTo(prev.price()) < 0;
            if (moreExtreme) out.set(out.size() - 1, p);
        }
        return out;
    }

    /** 枚举所有腿：分形点 → 交替归并 → 相邻对成腿（上/下）。 */
    public static List<Leg2> legs(List<KlineBar> bars, int k) {
        List<SwingDetector.Pivot> piv = alternate(fractalPivots(bars, k));
        List<Leg2> out = new ArrayList<>();
        for (int i = 0; i + 1 < piv.size(); i++) {
            SwingDetector.Pivot a = piv.get(i), b = piv.get(i + 1);
            boolean upLeg = a.type() == SwingDetector.PivotType.LOW && b.type() == SwingDetector.PivotType.HIGH;
            out.add(new Leg2(a.barIndex(), b.barIndex(), a.price(), b.price(), upLeg));
        }
        return out;
    }
}
