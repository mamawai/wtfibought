package com.mawai.wiibservice.agent.strategy.fibo;

import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.strategy.core.StrategyMarketView;
import com.mawai.wiibservice.agent.strategy.core.StrategyRiskPolicy;
import com.mawai.wiibservice.agent.strategy.core.StrategySignal;
import com.mawai.wiibservice.agent.strategy.core.TradingStrategySpi;
import com.mawai.wiibservice.agent.strategy.core.TradingOperations;

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

        // 挂单价 = entryFib 回撤位；现价已穿过则放弃（保证 maker 被动成交）
        BigDecimal limit = grid.retracement(params.entryFib());
        boolean alreadyPassed = leg.upLeg()
                ? last.close().compareTo(limit) <= 0
                : last.close().compareTo(limit) >= 0;
        if (alreadyPassed) return Optional.empty();

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
