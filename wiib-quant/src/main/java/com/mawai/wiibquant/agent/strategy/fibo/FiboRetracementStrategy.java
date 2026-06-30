package com.mawai.wiibquant.agent.strategy.fibo;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.strategy.core.StrategyMarketView;
import com.mawai.wiibquant.agent.strategy.core.StrategyRiskPolicy;
import com.mawai.wiibquant.agent.strategy.core.StrategySignal;
import com.mawai.wiibquant.agent.strategy.core.TradingStrategySpi;
import com.mawai.wiibquant.agent.strategy.core.TradingOperations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 斐波那契回撤策略（方向无关限价回踩）。
 *
 * <p>防重绘 ZigZag 定最近一条推动腿，在 entryFib 回撤位<b>挂限价单</b>等回踩触发(maker)，不追确认、不做方向过滤。
 * 一腿只交易一次；收盘破 invalidationRatio / 腿龄超窗 / 挂单超时 → 作废。成交后可选分批止盈(scaleOut)：
 * 浮盈达 scaleOutAtR×R 落袋 scaleOutFraction 仓位，剩余仓拉到 TP(止损不动)。</p>
 */
public final class FiboRetracementStrategy implements TradingStrategySpi {

    private static final String ID = "FIBO";

    // 高周期趋势过滤（trendFilterOn 开启时生效）：1h 收盘 vs SMA200。
    // 周期与 MA 长度刻意写死成常量、不进 FiboParams，从根上断"调趋势参数凑回测"的过拟合。
    private static final long TREND_TF_MILLIS = 60 * 60_000L;   // 趋势判定周期 1h（高于 15m 找腿周期）
    private static final int TREND_SMA_PERIOD = 200;            // 趋势判定 SMA 长度（最 conventional 的趋势线）
    private static final int TREND_FAST_SMA_PERIOD = 50;        // T1 多头排列用的快线（50/200 金叉是最 conventional 的排列）

    // 多周期Fib汇合（mtfConfluenceOn 开启时生效）：中期回撤带写死 [0.382,0.786]，不暴露成可调参。
    private static final double MTF_BAND_SHALLOW = 0.382;       // 中期回撤带浅端
    private static final double MTF_BAND_DEEP = 0.786;          // 中期回撤带深端

    private final FiboParams params;
    private final List<String> symbols;
    private final StrategyRiskPolicy riskPolicy = StrategyRiskPolicy.defaults();
    private final Map<String, SymbolState> states = new ConcurrentHashMap<>();

    public FiboRetracementStrategy(FiboParams params, List<String> symbols) {
        this.params = params == null ? FiboParams.defaults() : params;
        this.symbols = List.copyOf(symbols);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<String> symbols() {
        return symbols;
    }

    @Override
    public StrategyRiskPolicy riskPolicy() {
        return riskPolicy;
    }

    @Override
    public Optional<StrategySignal> onBarClosed(String symbol, StrategyMarketView view) {
        if (!symbols.contains(symbol)) return Optional.empty();
        if (view.hasBaseGap()) return Optional.empty();

        SymbolState state = states.computeIfAbsent(symbol, ignored -> new SymbolState());
        List<KlineBar> swingBars = view.closedBars(params.swingTfMillis(), params.swingLookbackBars());
        if (swingBars.size() <= params.atrPeriod() + 2) return Optional.empty();

        double atr = lastAtr(swingBars);
        // 选最新确认腿（latestConfirmedLeg 内含 minLeg 最小长度过滤）
        Optional<Leg> latest = latestConfirmedLeg(swingBars, atr);
        if (latest.isPresent()) {
            Leg cand = latest.get();
            if (cand.key() != state.consumedLegKey
                    && (state.activeLeg == null || cand.key() != state.activeLeg.key())) {
                state.activeLeg = cand;
                state.orderStartCloseTime = view.nowMs();   // 新腿：重置挂单超时计时
            }
        }

        Leg leg = state.activeLeg;
        if (leg == null || leg.key() == state.consumedLegKey) return Optional.empty();

        FiboLevels.FiboGrid grid = new FiboLevels.FiboGrid(leg.startPrice(), leg.endPrice(), leg.upLeg());
        KlineBar last = swingBars.getLast();

        // 失效：收盘破 0.786 / 腿龄超窗 / 挂单超时 → 作废本腿
        if (isInvalidated(leg, grid, last) || isLegExpired(leg, last)) {
            consume(state, leg);
            return Optional.empty();
        }
        long ageMs = view.nowMs() - state.orderStartCloseTime;
        if (ageMs > (long) params.orderTimeoutBars() * StrategyMarketView.BASE_INTERVAL_MILLIS) {
            consume(state, leg);
            return Optional.empty();
        }

        // 高周期趋势闸：开启时仅放行与 1h 趋势同向的腿（可叠 T1 多头排列 / T2 斜率），逆势腿不挂单。
        // 周期/MA/斜率回看全写死成常量、无可调参，过拟合面最小；砍掉的是结构性最差的逆势单，非随机减量。
        // 腿的失效/超时/消费生命周期已在上面处理完，这里只拦下单、不污染状态。
        if (params.trendFilterOn()) {
            List<KlineBar> htf = view.closedBars(TREND_TF_MILLIS, TREND_SMA_PERIOD + 1);
            if (!trendGate(htf, leg.upLeg(), params.trendAlignOn())) {
                return Optional.empty();
            }
        }

        // 挂单价 = entryFib 回撤位；现价已穿过则放弃（保证 maker 被动成交）
        BigDecimal limit = grid.retracement(params.entryFib());
        boolean alreadyPassed = leg.upLeg()
                ? last.close().compareTo(limit) <= 0
                : last.close().compareTo(limit) >= 0;
        if (alreadyPassed) return Optional.empty();

        // 多周期汇合闸：开启时，短期入场位须落在中期(1h/4h)推动腿的 [0.382,0.786] 回撤带内、且方向一致才放行。
        // 提质(只接中短共振区)+ 降频(共振才进)，正面打"薄 edge 被手续费吃"那个病。
        if (params.mtfConfluenceOn() && !legInMtfConfluence(leg, limit, view)) {
            return Optional.empty();
        }

        BigDecimal stop = bufferedStop(grid, params.slFibRatio(), slBuffer(atr), leg.upLeg());
        BigDecimal risk = limit.subtract(stop).abs();
        if (risk.signum() <= 0) return Optional.empty();
        // 止盈：tpRMultiple>0 用 R 倍(limit±R×risk，近 TP)；否则用斐波延伸位(默认=前高/前低)。
        BigDecimal takeProfit = params.tpRMultiple() > 0
                ? rMultipleTp(limit, risk, params.tpRMultiple(), leg.upLeg())
                : grid.extension(params.tpExtensionRatio());
        boolean sane = leg.upLeg()
                ? stop.compareTo(limit) < 0 && takeProfit.compareTo(limit) > 0
                : stop.compareTo(limit) > 0 && takeProfit.compareTo(limit) < 0;
        if (!sane) return Optional.empty();
        BigDecimal rr = takeProfit.subtract(limit).abs().divide(risk, 4, RoundingMode.HALF_UP);
        if (rr.compareTo(riskPolicy.minRiskReward()) < 0) return Optional.empty();

        String side = leg.upLeg() ? "LONG" : "SHORT";
        String reason = "Fibo黄金口袋" + side
                + " leg=" + leg.startPrice().toPlainString() + "->" + leg.endPrice().toPlainString()
                + " limit=" + limit.toPlainString() + " rr=" + rr.toPlainString();
        double score = Math.min(1.0, rr.doubleValue() / 3.0);
        // LIMIT 挂单信号：腿有效期间每根 reaffirm（幂等），引擎据此挂单；返回 empty 即撤单。
        return Optional.of(new StrategySignal(ID, symbol, side, leg.upLeg(),
                limit, stop, takeProfit, score, reason, last.closeTime(), "LIMIT"));
    }

    @Override
    public StrategySignal prepareEntry(String symbol, StrategySignal signal,
                                       BigDecimal actualEntryPrice, StrategyMarketView view) {
        // limit 成交价确定、SL/TP 已按腿固定，这里只做方向 + RR 的最后 sanity。
        if (signal == null || actualEntryPrice == null || actualEntryPrice.signum() <= 0) {
            return signal;
        }
        if (!directionalSane(signal.isLong(), actualEntryPrice, signal.stopLossPrice(), signal.takeProfitPrice())) {
            return null;
        }
        BigDecimal risk = actualEntryPrice.subtract(signal.stopLossPrice()).abs();
        if (risk.signum() <= 0) return null;
        BigDecimal rr = signal.takeProfitPrice().subtract(actualEntryPrice).abs().divide(risk, 4, RoundingMode.HALF_UP);
        if (rr.compareTo(riskPolicy.minRiskReward()) < 0) return null;
        return signal;
    }

    @Override
    public void onPositionOpened(String symbol, StrategySignal signal, Long positionId,
                                 BigDecimal actualEntryPrice, StrategyMarketView view,
                                 TradingOperations tools) {
        // 成交后消费当前腿，一腿只交易一次；SL/TP 已在挂单信号里固定。
        SymbolState state = states.computeIfAbsent(symbol, ignored -> new SymbolState());
        if (state.activeLeg != null) {
            consume(state, state.activeLeg);
        }
        // 分批止盈：在 +scaleOutAtR×R 处挂部分平仓单（落袋可靠的初始反弹，剩余仓拉到 TP）
        if (params.scaleOutOn() && positionId != null && signal != null
                && actualEntryPrice != null && tools != null) {
            BigDecimal risk = actualEntryPrice.subtract(signal.stopLossPrice()).abs();
            if (risk.signum() > 0) {
                BigDecimal offset = risk.multiply(BigDecimal.valueOf(params.scaleOutAtR()));
                BigDecimal trigger = signal.isLong()
                        ? actualEntryPrice.add(offset)
                        : actualEntryPrice.subtract(offset);
                tools.setScaleOut(positionId, trigger, params.scaleOutFraction());
            }
        }
    }

    private Optional<Leg> latestConfirmedLeg(List<KlineBar> swingBars, double atr) {
        List<SwingDetector.Pivot> pivots = SwingDetector.confirmedPivots(
                swingBars, params.atrPeriod(), params.reversalAtrMult());
        if (pivots.size() < 2) return Optional.empty();

        SwingDetector.Pivot start = pivots.get(pivots.size() - 2);
        SwingDetector.Pivot end = pivots.getLast();
        boolean upLeg = start.type() == SwingDetector.PivotType.LOW
                && end.type() == SwingDetector.PivotType.HIGH;
        boolean downLeg = start.type() == SwingDetector.PivotType.HIGH
                && end.type() == SwingDetector.PivotType.LOW;
        if (!upLeg && !downLeg) return Optional.empty();

        double legLen = end.price().subtract(start.price()).abs().doubleValue();
        if (Double.isNaN(atr) || atr <= 0.0 || legLen < params.minLegAtrMult() * atr) {
            return Optional.empty();
        }

        int endIdx = Math.min(end.barIndex(), swingBars.size() - 1);
        long endOpenTime = swingBars.get(endIdx).openTime();
        long key = endOpenTime * 10 + (upLeg ? 1 : 2);
        return Optional.of(new Leg(key, start.price(), end.price(), upLeg, endOpenTime));
    }

    private boolean isInvalidated(Leg leg, FiboLevels.FiboGrid grid, KlineBar bar) {
        BigDecimal invalidation = grid.retracement(params.invalidationRatio());
        return leg.upLeg()
                ? bar.close().compareTo(invalidation) < 0
                : bar.close().compareTo(invalidation) > 0;
    }

    /** 腿龄过期：腿终点距今超过 swing 回看窗口时长即视为过时结构，作废。 */
    private boolean isLegExpired(Leg leg, KlineBar last) {
        long maxAgeMs = (long) params.swingLookbackBars() * params.swingTfMillis();
        return last.closeTime() - leg.endOpenTime() > maxAgeMs;
    }

    private BigDecimal bufferedStop(FiboLevels.FiboGrid grid, double ratio, BigDecimal buffer, boolean upLeg) {
        BigDecimal anchor = grid.retracement(ratio);
        BigDecimal stop = upLeg ? anchor.subtract(buffer) : anchor.add(buffer);
        return stop.setScale(8, RoundingMode.HALF_UP);
    }

    /** R 倍止盈位：做多 entry+r×risk，做空 entry−r×risk。 */
    private static BigDecimal rMultipleTp(BigDecimal entry, BigDecimal risk, double r, boolean upLeg) {
        BigDecimal off = risk.multiply(BigDecimal.valueOf(r));
        return upLeg ? entry.add(off) : entry.subtract(off);
    }

    private boolean directionalSane(boolean isLong, BigDecimal entry, BigDecimal stop, BigDecimal takeProfit) {
        return isLong
                ? stop.compareTo(entry) < 0 && takeProfit.compareTo(entry) > 0
                : stop.compareTo(entry) > 0 && takeProfit.compareTo(entry) < 0;
    }

    private double lastAtr(List<KlineBar> bars) {
        double[] atr = SwingDetector.atrSeries(bars, params.atrPeriod());
        return atr[atr.length - 1];
    }

    /** 末 period 根收盘 SMA。 */
    private static double smaClose(List<KlineBar> bars, int period) {
        return avgClose(bars, bars.size() - period, bars.size());
    }

    /** 收盘均值，区间 [from, toExcl)。 */
    private static double avgClose(List<KlineBar> bars, int from, int toExcl) {
        double sum = 0.0;
        for (int i = from; i < toExcl; i++) {
            sum += bars.get(i).close().doubleValue();
        }
        return sum / (toExcl - from);
    }

    /**
     * 趋势闸：基础顺势（1h close vs SMA200，腿向须同向）+ 可选 T1 均线多头排列，全过才放行。
     * 纯函数（吃 htf bars + 开关），无可调参泄漏；不足 SMA200 根保守判否。
     */
    static boolean trendGate(List<KlineBar> htf, boolean wantUp, boolean alignOn) {
        if (htf.size() < TREND_SMA_PERIOD) return false;
        boolean htfUp = htf.getLast().close().doubleValue() > smaClose(htf, TREND_SMA_PERIOD);
        if (wantUp != htfUp) return false;                                          // 基础：腿向 = 1h 趋势向
        if (alignOn && !maAligned(htf, wantUp)) return false;                       // T1 均线多头排列
        return true;
    }

    /**
     * T1 高周期均线多头排列：1h SMA50>SMA200 且 close>SMA50（wantUp）；空头对称；不足 200 根保守判否。
     * 比"close>SMA200"更严——要求快线在慢线上、价在快线上，过滤"刚站上慢线但还没走出结构"的弱趋势。
     */
    static boolean maAligned(List<KlineBar> htf, boolean wantUp) {
        if (htf.size() < TREND_SMA_PERIOD) return false;
        double smaFast = smaClose(htf, TREND_FAST_SMA_PERIOD);
        double smaSlow = smaClose(htf, TREND_SMA_PERIOD);
        double close = htf.getLast().close().doubleValue();
        return wantUp ? (smaFast > smaSlow && close > smaFast)
                      : (smaFast < smaSlow && close < smaFast);
    }

    /**
     * 短期入场位是否落在中期(mtfTfMillis)推动腿的 [0.382,0.786] 回撤带内、且中短期方向一致。
     * 复用 latestConfirmedLeg 在中期K线上找腿；中期无腿/方向不一致/不在带内 → 不放行。
     */
    private boolean legInMtfConfluence(Leg shortLeg, BigDecimal entryLimit, StrategyMarketView view) {
        List<KlineBar> mtf = view.closedBars(params.mtfTfMillis(), params.swingLookbackBars());
        if (mtf.size() <= params.atrPeriod() + 2) return false;          // 中期历史不足，保守不放行
        Optional<Leg> medOpt = latestConfirmedLeg(mtf, lastAtr(mtf));
        if (medOpt.isEmpty()) return false;
        Leg med = medOpt.get();
        if (med.upLeg() != shortLeg.upLeg()) return false;              // 中短期方向须一致
        FiboLevels.FiboGrid medGrid = new FiboLevels.FiboGrid(med.startPrice(), med.endPrice(), med.upLeg());
        BigDecimal a = medGrid.retracement(MTF_BAND_SHALLOW);
        BigDecimal b = medGrid.retracement(MTF_BAND_DEEP);
        BigDecimal lo = a.min(b), hi = a.max(b);
        return entryLimit.compareTo(lo) >= 0 && entryLimit.compareTo(hi) <= 0;   // 入场位落在中期回撤带内
    }

    private BigDecimal slBuffer(double atr) {
        double raw = Double.isFinite(atr) && atr > 0.0 ? atr * params.slBufferAtrMult() : 0.0;
        return BigDecimal.valueOf(raw).setScale(8, RoundingMode.HALF_UP);
    }

    private void consume(SymbolState state, Leg leg) {
        state.consumedLegKey = leg.key();
        state.activeLeg = null;
    }

    private record Leg(long key, BigDecimal startPrice, BigDecimal endPrice, boolean upLeg, long endOpenTime) {
    }

    private static final class SymbolState {
        long consumedLegKey = Long.MIN_VALUE;
        Leg activeLeg;
        long orderStartCloseTime = Long.MIN_VALUE;
    }
}
