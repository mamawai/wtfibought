package com.mawai.wiibservice.agent.trading.entry.confluence;

import com.mawai.wiibservice.agent.trading.entry.strategy.EntryStrategySupport;

import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyContext;

import com.mawai.wiibservice.agent.trading.runtime.MarketContext;

import java.util.ArrayList;
import java.util.List;

public final class TrendConfluenceGate implements EntryConfluenceGate {

    @Override
    public ConfluenceGateResult evaluate(EntryStrategyContext context) {
        MarketContext ctx = context.market();
        boolean isLong = context.isLong();
        List<String> hits = new ArrayList<>();
        addHit(hits, EntryStrategySupport.regimeSupports(ctx, isLong)
                || EntryStrategySupport.directionAligns(ctx.maAlignment1h, isLong), "大级别方向");
        addHit(hits, EntryStrategySupport.directionAligns(ctx.maAlignment15m, isLong)
                || EntryStrategySupport.directionAligns(ctx.maAlignment5m, isLong)
                || EntryStrategySupport.priceAboveEmaSupports(ctx, isLong), "中短线结构");
        addHit(hits, EntryStrategySupport.macdSupports(ctx, isLong)
                || EntryStrategySupport.closeTrendSupports(ctx.closeTrend5m, isLong), "动能同向");
        addHit(hits, volumeAtLeast(ctx, 1.20), "量能有效");
        addHit(hits, !EntryStrategySupport.microAgainst(ctx, isLong, 0.30), "微结构未强反向");
        addHit(hits, rsiTrendHealthy(ctx, isLong), "RSI健康");
        return new ConfluenceGateResult(hits.size(), 6, 4, hits);
    }

    private void addHit(List<String> hits, boolean condition, String label) {
        if (condition) hits.add(label);
    }

    private boolean volumeAtLeast(MarketContext ctx, double threshold) {
        return ctx.volumeRatio5m != null && ctx.volumeRatio5m >= threshold;
    }

    private boolean rsiTrendHealthy(MarketContext ctx, boolean isLong) {
        if (ctx.rsi5m == null) return false;
        double rsi = ctx.rsi5m.doubleValue();
        return isLong ? rsi >= 42.0 && rsi < 78.0 : rsi > 22.0 && rsi <= 58.0;
    }
}
