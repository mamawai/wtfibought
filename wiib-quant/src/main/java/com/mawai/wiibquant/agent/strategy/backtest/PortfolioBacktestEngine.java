package com.mawai.wiibquant.agent.strategy.backtest;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.strategy.core.PositionSizer;
import com.mawai.wiibquant.agent.strategy.core.StrategyRiskPolicy;
import com.mawai.wiibquant.agent.strategy.core.StrategySignal;
import com.mawai.wiibquant.agent.strategy.core.TradingOperations;
import com.mawai.wiibquant.agent.strategy.core.TradingStrategySpi;
import com.mawai.wiibquant.agent.strategy.core.WindowedMarketView;
import com.mawai.wiibquant.agent.strategy.turtle.TurtleParams;
import com.mawai.wiibquant.agent.strategy.turtle.TurtleStrategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 多币种组合回测引擎（turtle 专用：MARKET/STOP 单 + 风险定量、无固定 TP）。
 *
 * <p>一条 5m 主时钟按时间戳并集推进，所有币共用一个 {@link BacktestTradingTools} 权益池；
 * 每币独立 view+strategy，各自决策互不看对方。每笔按<b>组合总权益</b>×每笔风险% 定量，
 * 因此组合上涨时全体加码、下跌时全体缩量——这是共享权益复利的核心耦合，单币曲线拼不出来。</p>
 *
 * <p>N=1 时行为退化为 {@link StrategyKlineBacktestEngine} 的单币路径（同参数应逐笔一致），
 * 以此校验组合引擎正确性。</p>
 */
public final class PortfolioBacktestEngine {

    private static final int WINDOW_MAX_BARS = 50_000;

    private final List<String> symbols;
    private final Map<String, List<KlineBar>> barsBySymbol;
    private final BigDecimal initialBalance;
    private final int leverage;
    private final long tradingStartMs;
    private final long tradingEndMs;      // 独占上界：nowMs>=此值不再交易/推进
    private final TurtleParams params;

    private List<BacktestTradingTools.ClosedTrade> closedTrades = List.of();

    public PortfolioBacktestEngine(List<String> symbols, Map<String, List<KlineBar>> barsBySymbol,
                                   BigDecimal initialBalance, int leverage,
                                   long tradingStartMs, long tradingEndMs, TurtleParams params) {
        this.symbols = List.copyOf(symbols);
        this.barsBySymbol = Map.copyOf(barsBySymbol);
        this.initialBalance = initialBalance == null ? new BigDecimal("10000") : initialBalance;
        this.leverage = leverage;
        this.tradingStartMs = tradingStartMs;
        this.tradingEndMs = tradingEndMs;
        this.params = params == null ? TurtleParams.defaults() : params;
    }

    public BacktestResult run() {
        BacktestTradingTools tools = new BacktestTradingTools(initialBalance, symbols.get(0));
        BacktestResult result = new BacktestResult(initialBalance);

        Map<String, WindowedMarketView> views = new HashMap<>();
        Map<String, TradingStrategySpi> strategies = new HashMap<>();
        Map<String, StrategySignal> pending = new HashMap<>();
        Map<String, StrategySignal> pendingStops = new HashMap<>();   // 触价挂单：跨根等突破，reaffirm 续期
        Map<String, Integer> ptr = new HashMap<>();
        for (String s : symbols) {
            views.put(s, new WindowedMarketView(WINDOW_MAX_BARS));
            strategies.put(s, new TurtleStrategy(params, List.of(s)));
            ptr.put(s, 0);
        }

        // k 路归并：每步取所有币下一根中最早的 closeTime，处理该时刻到期的所有币，再记一次组合权益。
        while (true) {
            long minT = Long.MAX_VALUE;
            for (String s : symbols) {
                int p = ptr.get(s);
                List<KlineBar> bars = barsBySymbol.get(s);
                if (p < bars.size()) minT = Math.min(minT, bars.get(p).closeTime());
            }
            if (minT == Long.MAX_VALUE || minT >= tradingEndMs) break;

            for (String s : symbols) {
                int p = ptr.get(s);
                List<KlineBar> bars = barsBySymbol.get(s);
                if (p < bars.size() && bars.get(p).closeTime() == minT) {
                    processCoinBar(tools, s, bars.get(p), p, views.get(s), strategies.get(s),
                            pending, pendingStops);
                    ptr.put(s, p + 1);
                }
            }
            result.recordEquity(tools.getTotalEquity());
        }

        forceCloseAll(tools);
        result.recordEquity(tools.getTotalEquity());
        this.closedTrades = tools.getClosedTrades();
        copyClosedTrades(tools, result);
        return result;
    }

    /** 单币单根处理：与单币引擎每根流程一致（填 pending→触价撮合→撮合 SL/TP→持仓管理→评估新信号）。 */
    private void processCoinBar(BacktestTradingTools tools, String symbol, KlineBar bar, int barIndex,
                                WindowedMarketView view, TradingStrategySpi strategy,
                                Map<String, StrategySignal> pending, Map<String, StrategySignal> pendingStops) {
        long nowMs = bar.closeTime();
        tools.setCurrentSymbol(symbol);
        tools.setCurrentTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.of("UTC")));

        StrategySignal pend = pending.remove(symbol);
        if (pend != null) openFill(tools, symbol, pend, bar.open(), barIndex, view, strategy);
        // 触价挂单：本根突破触发价即成交，开盘跳空穿越按开盘劣价（同单币引擎口径）
        StrategySignal stop = pendingStops.get(symbol);
        if (stop != null && tools.getOpenPositions(symbol).isEmpty() && stopTouched(stop, bar)) {
            openFill(tools, symbol, stop, stopFillPrice(stop, bar), barIndex, view, strategy);
            pendingStops.remove(symbol);
        }

        tools.tickBar(bar.open(), bar.high(), bar.low(), bar.close(), barIndex);
        view.append(bar);

        for (FuturesPositionDTO pos : tools.getOpenPositions(symbol)) {
            strategy.onPositionBarClosed(symbol, pos, view, tools);
        }

        Optional<StrategySignal> signal = strategy.onBarClosed(symbol, view);
        boolean canTrade = nowMs >= tradingStartMs && nowMs < tradingEndMs
                && tools.getOpenPositions(symbol).isEmpty();
        // turtle 只发 MARKET / STOP 单
        if (signal.isPresent() && "STOP".equals(signal.get().orderType())) {
            if (canTrade) pendingStops.put(symbol, signal.get());   // reaffirm：腿有效即续期
            else pendingStops.remove(symbol);                       // 有仓/不可交易清挂单
        } else if (signal.isPresent() && "MARKET".equals(signal.get().orderType()) && canTrade) {
            pending.put(symbol, signal.get());
        } else if (signal.isEmpty()) {
            pendingStops.remove(symbol);                            // 无信号=腿失效，撤挂单
        }
    }

    /** 触价单是否被本根触发：做多看 high≥触发价，做空看 low≤触发价。 */
    private static boolean stopTouched(StrategySignal s, KlineBar bar) {
        return s.isLong()
                ? bar.high().compareTo(s.entryRefPrice()) >= 0
                : bar.low().compareTo(s.entryRefPrice()) <= 0;
    }

    /** 触价单成交价：正常=触发价；开盘已跳空穿越时按开盘劣价（多头取大、空头取小）。 */
    private static BigDecimal stopFillPrice(StrategySignal s, KlineBar bar) {
        BigDecimal trigger = s.entryRefPrice();
        return s.isLong() ? bar.open().max(trigger) : bar.open().min(trigger);
    }

    /** 成交建仓：按组合总权益风险定量、下一根开盘价成交。 */
    private void openFill(BacktestTradingTools tools, String symbol, StrategySignal signal,
                          BigDecimal fillPrice, int barIndex, WindowedMarketView view, TradingStrategySpi strategy) {
        if (signal == null || fillPrice == null || fillPrice.signum() <= 0) return;
        if (!stopStillValid(signal, fillPrice)) return;   // 跳空穿越止损/方向失效则弃单（保守，同单币引擎）

        StrategyRiskPolicy policy = strategy.riskPolicy() == null
                ? StrategyRiskPolicy.defaults() : strategy.riskPolicy();
        int effLev = PositionSizer.effectiveLeverage(leverage, policy);
        // 组合总权益 = 共享余额 + 全币未实现，riskSizedQty 因此在组合层面复利
        BigDecimal qty = PositionSizer.riskSizedQty(tools.getTotalEquity(), fillPrice,
                signal.stopLossPrice(), policy, effLev);
        if (qty.signum() <= 0) return;

        tools.setCurrentPrice(fillPrice);
        TradingOperations.OpenResult opened = tools.openPositionWithResult(signal.side(), qty, effLev,
                "MARKET", fillPrice, signal.stopLossPrice(), signal.takeProfitPrice(), signal.strategyId());
        if (opened.success()) {
            tools.markOpenBarIndex(barIndex);
            strategy.onPositionOpened(symbol, signal, opened.positionId(), fillPrice, view, tools);
        }
    }

    /** turtle 无 TP：止损须在成交价正确一侧（多头 stop<entry、空头 stop>entry），否则弃单。 */
    private boolean stopStillValid(StrategySignal s, BigDecimal entry) {
        if (s.stopLossPrice() == null) return false;
        return s.isLong()
                ? s.stopLossPrice().compareTo(entry) < 0
                : s.stopLossPrice().compareTo(entry) > 0;
    }

    private void forceCloseAll(BacktestTradingTools tools) {
        for (String s : symbols) {
            tools.setCurrentSymbol(s);
            for (FuturesPositionDTO pos : new ArrayList<>(tools.getOpenPositions(s))) {
                tools.closePositionWithReason(pos.getId(), pos.getQuantity(), "FORCE_CLOSE");
            }
        }
    }

    private void copyClosedTrades(BacktestTradingTools tools, BacktestResult result) {
        for (BacktestTradingTools.ClosedTrade ct : tools.getClosedTrades()) {
            result.addTrade(new BacktestResult.Trade(
                    ct.openBarIndex(), ct.closeBarIndex(), ct.openTime(), ct.closeTime(),
                    ct.side(), ct.strategy(),
                    ct.entryPrice(), ct.exitPrice(), ct.quantity(), ct.leverage(),
                    ct.pnl(), ct.fee(), ct.rMultiple(), ct.exitReason(),
                    ct.maxFavorableR(), ct.maxAdverseR()));
        }
    }

    /** 运行后取原始平仓记录（含 symbol），供逐币贡献归因。 */
    public List<BacktestTradingTools.ClosedTrade> closedTrades() {
        return closedTrades;
    }
}
