package com.mawai.wiibquant.agent.strategy.liq;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.strategy.core.StrategyMarketView;
import com.mawai.wiibquant.agent.strategy.core.StrategyRiskPolicy;
import com.mawai.wiibquant.agent.strategy.core.StrategySignal;
import com.mawai.wiibquant.agent.strategy.core.TradingOperations;
import com.mawai.wiibquant.agent.strategy.core.TradingStrategySpi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 强平瀑布 fade 策略（LONG-only，5m 决策口径；事件研究 1m 判决 + 5m 复验双 gate 立项）。
 *
 * <p>机制：强平瀑布的对手方是被强制市价卖出的杠杆多头——15m 深跌(flush) + perp 对指数深折价(premium)
 * + 15m taker 卖占比尖峰，三签名至少二 = 被迫抛售衰竭事件，次根 5m 开盘市价接多，吃错位修复。
 * SHORT 侧（接空拉升）五币两段皆负已否决，只做多是硬编码而非参数。</p>
 *
 * <p>premium/taker 不在 K 线里，经 {@link LiqSideData} 注入：回测由 runner 从 DB 装载，
 * 实盘由 feed 采样落 Redis 后适配（待接线）。任一签名数据缺失（NaN）该签名即不触发——
 * 降频不降质，永远不会因缺数据做错方向。</p>
 *
 * <p>出场 = holdBars 时间出场（经 onPositionBarClosed 钩子执行；出场消融采纳 1h——ETH 反弹 1h 后回吐、
 * 全池 5/5 币验证段 PF&gt;1），SL/TP 只做宽幅灾难保护（ATR 框消融被否决：SOL 劣化超线）。</p>
 */
public final class LiqFadeStrategy implements TradingStrategySpi {

    private static final String ID = "LIQFADE";
    private static final long BASE = StrategyMarketView.BASE_INTERVAL_MILLIS;

    private final LiqFadeParams params;
    private final List<String> symbols;
    private final LiqSideData sideData;
    private final StrategyRiskPolicy riskPolicy = StrategyRiskPolicy.defaults();
    // 每币最近一次触发的 bar 收盘时刻；冷却窗内不重复触发（同一场瀑布只打一枪，对齐事件研究口径）
    private final Map<String, Long> lastFireCloseTime = new ConcurrentHashMap<>();

    public LiqFadeStrategy(LiqFadeParams params, List<String> symbols, LiqSideData sideData) {
        this.params = params == null ? LiqFadeParams.defaults() : params;
        this.symbols = List.copyOf(symbols);
        this.sideData = sideData;
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
        LiqFadeParams.CoinGate gate = params.gates().get(symbol);
        if (gate == null) return Optional.empty();

        List<KlineBar> bars = view.closedBars(BASE, params.flushBars() + 1);
        if (bars.size() < params.flushBars() + 1) return Optional.empty();
        KlineBar last = bars.getLast();

        // 冷却：距上次触发不足 cooldownBars 根即静默；同 bar 重入（sinceFire=0）放行以保持幂等
        Long lastFire = lastFireCloseTime.get(symbol);
        if (lastFire != null) {
            long sinceFire = last.closeTime() - lastFire;
            if (sinceFire != 0 && sinceFire < (long) params.cooldownBars() * BASE) return Optional.empty();
        }

        // 窗口时间连续性守卫：跨断档不硬算（与研究工具 rollingRet 同款铁律）
        KlineBar first = bars.getFirst();
        if (last.openTime() - first.openTime() != (long) params.flushBars() * BASE) return Optional.empty();

        // 签名1：15m 滚动收益击穿瀑布阈值
        double basePx = first.close().doubleValue();
        if (basePx <= 0) return Optional.empty();
        double flushRet = last.close().doubleValue() / basePx - 1;
        boolean flushHit = flushRet <= gate.flushLe();

        // 签名2：premium 深折价（决策时点最新已闭合采样；NaN 比较自然为 false）
        double prem = sideData.premiumAt(symbol, last.closeTime());
        boolean premHit = prem <= gate.premLe();

        // 签名3：15m 量加权 taker 卖占比尖峰
        double sellShare = sellShare15(symbol, bars);
        boolean takerHit = sellShare >= gate.sellGe();

        int flags = (flushHit ? 1 : 0) + (premHit ? 1 : 0) + (takerHit ? 1 : 0);
        if (flags < params.minFlags()) return Optional.empty();

        lastFireCloseTime.put(symbol, last.closeTime());
        // 灾难框：宽幅 SL/TP 平时不咬合，主出场是 holdBars 时间出场；比值 1.25 过引擎 minRR=1.2 闸。
        // ATR 自适应框已消融否决（SOL 劣化超线），勿再加回。
        BigDecimal close = last.close();
        BigDecimal stop = close.multiply(BigDecimal.valueOf(1 - params.slPct())).setScale(8, RoundingMode.HALF_UP);
        BigDecimal tp = close.multiply(BigDecimal.valueOf(1 + params.tpPct())).setScale(8, RoundingMode.HALF_UP);
        String reason = String.format(Locale.ROOT, "LiqFade瀑布接多 flush=%.2f%% prem=%.1fbp sell=%s flags=%d",
                flushRet * 100, prem * 10_000,
                Double.isNaN(sellShare) ? "NaN" : String.format(Locale.ROOT, "%.3f", sellShare), flags);
        return Optional.of(new StrategySignal(ID, symbol, "LONG", true,
                close, stop, tp, flags / 3.0, reason, last.closeTime(), "MARKET"));
    }

    /** 基线时间出场：持仓满 holdBars 即市价平（taker 费+滑点由撮合层如实记账）。 */
    @Override
    public void onPositionBarClosed(String symbol, FuturesPositionDTO position,
                                    StrategyMarketView view, TradingOperations tools) {
        if (position.getCreatedAt() == null) return;
        long openedMs = position.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        if (view.nowMs() - openedMs >= (long) params.holdBars() * BASE) {
            tools.closePositionWithReason(position.getId(), position.getQuantity(), "TIME_EXIT");
        }
    }

    /** 15m 量加权卖占比 = 1 - Σtaker买量/Σ总量，窗口为最后 flushBars 根；任一根缺 taker 数据 → NaN。 */
    private double sellShare15(String symbol, List<KlineBar> bars) {
        double vol = 0, buy = 0;
        for (int i = 1; i < bars.size(); i++) {
            KlineBar b = bars.get(i);
            double tb = sideData.takerBuy(symbol, b.openTime());
            if (Double.isNaN(tb)) return Double.NaN;
            vol += b.volume().doubleValue();
            buy += tb;
        }
        return vol > 0 ? 1 - buy / vol : Double.NaN;
    }
}
