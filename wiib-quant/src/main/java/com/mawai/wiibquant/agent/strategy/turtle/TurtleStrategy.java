package com.mawai.wiibquant.agent.strategy.turtle;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.strategy.core.StrategyMarketView;
import com.mawai.wiibquant.agent.strategy.core.StrategyRiskPolicy;
import com.mawai.wiibquant.agent.strategy.core.StrategySignal;
import com.mawai.wiibquant.agent.strategy.core.SwingDetector;
import com.mawai.wiibquant.agent.strategy.core.TradingOperations;
import com.mawai.wiibquant.agent.strategy.core.TradingStrategySpi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TURTLE 多空趋势突破策略（默认 4H 90入场/15退出）。
 *
 * <p>入场两种模式：收盘确认（最新已闭合4H桶的闭合价突破此前N桶极值后，下一根5m开盘市价入场）
 * 或盘中触价（stopEntry=true，触价单挂在通道价、价格穿越即成交，同原版海龟）。多空完全对称。
 * 入场即挂 2×ATR 固定灾难止损，不设固定止盈。持仓期间只在4H桶闭合点检查反向退出通道，
 * 确认趋势结构破坏后按当根5m闭合价市价退出。</p>
 */
public final class TurtleStrategy implements TradingStrategySpi {

    private static final String ID = "TURTLE";

    private final TurtleParams params;
    private final List<String> symbols;
    // 风险定量(每笔1%)下杠杆只改占用保证金、不改仓位与盈亏；上限放到20x兑现回测20x配置。minRR对无TP的turtle不生效。
    private final StrategyRiskPolicy riskPolicy = new StrategyRiskPolicy(new BigDecimal("1.2"), 20, 0.01);

    /** 触价模式的通道 memo：桶未闭合期间通道不变，无需每根5m重算。 */
    private record ChannelCache(long bucketId, BigDecimal upper, BigDecimal lower, double atr) {}
    private final Map<String, ChannelCache> channelCacheBySymbol = new ConcurrentHashMap<>();

    public TurtleStrategy(TurtleParams params, List<String> symbols) {
        this.params = params == null ? TurtleParams.defaults() : params;
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
        if (!symbols.contains(symbol) || view.hasBaseGap()) return Optional.empty();
        if (params.stopEntry()) return stopEntrySignal(symbol, view);
        if (!decisionBucketClosed(view)) return Optional.empty();

        int need = Math.max(params.entryLookback() + 1, params.atrPeriod() + 1);
        List<KlineBar> bars = view.closedBars(params.decisionTfMillis(), need);
        if (bars.size() < need) return Optional.empty();

        KlineBar trigger = bars.getLast();
        List<KlineBar> history = bars.subList(bars.size() - params.entryLookback() - 1, bars.size() - 1);
        BigDecimal upper = maxHigh(history);
        BigDecimal lower = minLow(history);
        double atr = lastAtr(bars);
        if (!(atr > 0) || !Double.isFinite(atr)) return Optional.empty();

        if (trigger.close().compareTo(upper) > 0) {
            return Optional.of(signal(symbol, true, trigger.close(), upper, atr, trigger.closeTime(), "MARKET"));
        }
        if (trigger.close().compareTo(lower) < 0) {
            return Optional.of(signal(symbol, false, trigger.close(), lower, atr, trigger.closeTime(), "MARKET"));
        }
        return Optional.empty();
    }

    /**
     * 盘中触价模式：通道只含已闭合决策桶，每根5m重申触价单（引擎 reaffirm 语义，不刷即撤）。
     * 引擎只支持单挂单，价格离哪侧通道近就挂哪侧；远侧在本桶内被触发的概率可忽略。
     * 通道/ATR 只在桶闭合时变化，按桶边界 memo，避免每根5m全量重聚合。
     */
    private Optional<StrategySignal> stopEntrySignal(String symbol, StrategyMarketView view) {
        long bucketId = (view.nowMs() + 1) / params.decisionTfMillis();   // 已闭合桶计数，桶闭合时+1
        ChannelCache cache = channelCacheBySymbol.get(symbol);
        if (cache == null || cache.bucketId() != bucketId) {
            int need = Math.max(params.entryLookback(), params.atrPeriod() + 1);
            List<KlineBar> buckets = view.closedBars(params.decisionTfMillis(), need);
            if (buckets.size() < need) return Optional.empty();
            List<KlineBar> history = buckets.subList(buckets.size() - params.entryLookback(), buckets.size());
            double atr = lastAtr(buckets);
            if (!(atr > 0) || !Double.isFinite(atr)) return Optional.empty();
            cache = new ChannelCache(bucketId, maxHigh(history), minLow(history), atr);
            channelCacheBySymbol.put(symbol, cache);
        }

        KlineBar last5m = view.closedBars(StrategyMarketView.BASE_INTERVAL_MILLIS, 1).getLast();
        BigDecimal close = last5m.close();
        BigDecimal upper = cache.upper();
        BigDecimal lower = cache.lower();
        boolean nearLong = upper.subtract(close).abs().compareTo(close.subtract(lower).abs()) <= 0;
        return Optional.of(nearLong
                ? signal(symbol, true, upper, upper, cache.atr(), last5m.closeTime(), "STOP")
                : signal(symbol, false, lower, lower, cache.atr(), last5m.closeTime(), "STOP"));
    }

    @Override
    public void onPositionBarClosed(String symbol, FuturesPositionDTO position,
                                    StrategyMarketView view, TradingOperations tools) {
        if (!symbols.contains(symbol) || view.hasBaseGap() || !decisionBucketClosed(view)) return;
        List<KlineBar> bars = view.closedBars(params.decisionTfMillis(), params.exitLookback() + 1);
        if (bars.size() < params.exitLookback() + 1) return;

        KlineBar current = bars.getLast();
        List<KlineBar> history = bars.subList(bars.size() - params.exitLookback() - 1, bars.size() - 1);
        boolean isLong = "LONG".equalsIgnoreCase(position.getSide());
        boolean exit = isLong
                ? current.close().compareTo(minLow(history)) < 0
                : current.close().compareTo(maxHigh(history)) > 0;
        if (exit) {
            tools.closePositionWithReason(position.getId(), position.getQuantity(), "CHANNEL_EXIT");
        }
    }

    private StrategySignal signal(String symbol, boolean isLong, BigDecimal entryRef,
                                  BigDecimal channel, double atr, long barCloseTime, String orderType) {
        BigDecimal distance = BigDecimal.valueOf(atr * params.stopAtrMult());
        BigDecimal stop = (isLong ? entryRef.subtract(distance) : entryRef.add(distance))
                .setScale(8, RoundingMode.HALF_UP);
        String side = isLong ? "LONG" : "SHORT";
        String reason = String.format(Locale.ROOT,
                "Turtle%s %d桶%s channel=%s atr=%.8f",
                side, params.entryLookback(), "STOP".equals(orderType) ? "盘中触价" : "突破",
                channel.toPlainString(), atr);
        return new StrategySignal(ID, symbol, side, isLong,
                entryRef, stop, null, 1.0, reason, barCloseTime, orderType);
    }

    private boolean decisionBucketClosed(StrategyMarketView view) {
        return view.nowMs() > 0 && (view.nowMs() + 1) % params.decisionTfMillis() == 0;
    }

    private double lastAtr(List<KlineBar> bars) {
        double[] atr = SwingDetector.atrSeries(bars, params.atrPeriod());
        return atr[atr.length - 1];
    }

    private static BigDecimal maxHigh(List<KlineBar> bars) {
        BigDecimal max = bars.getFirst().high();
        for (int i = 1; i < bars.size(); i++) max = max.max(bars.get(i).high());
        return max;
    }

    private static BigDecimal minLow(List<KlineBar> bars) {
        BigDecimal min = bars.getFirst().low();
        for (int i = 1; i < bars.size(); i++) min = min.min(bars.get(i).low());
        return min;
    }
}
