package com.mawai.wiibservice.agent.trading;

import java.util.ArrayList;
import java.util.List;

final class MeanReversionConfluenceGate implements EntryConfluenceGate {

    @Override
    public ConfluenceGateResult evaluate(EntryStrategyContext context) {
        MarketContext ctx = context.market();
        boolean isLong = context.isLong();
        List<String> hits = new ArrayList<>();
        boolean deepGateStretch = bollStretchedForMeanReversion(ctx, isLong)
                && rsiStretchedForMeanReversion(ctx, isLong);
        int reversalVotes = reversalVoteCount(ctx, isLong);
        addHit(hits, bollStretchedForMeanReversion(ctx, isLong), "BB%B深度偏离");
        addHit(hits, rsiStretchedForMeanReversion(ctx, isLong), "RSI有效拉伸");
        addHit(hits, "RANGE".equals(ctx.regime) || "SQUEEZE".equals(ctx.regime)
                || "WEAKENING".equals(ctx.regimeTransition), "环境适合回归");
        addHit(hits, reversalVotes >= 2 || (deepGateStretch && reversalVotes >= 1), "反转票足够");
        addHit(hits, !EntryStrategySupport.microAgainst(ctx, isLong, 0.30), "微结构未强反向");
        addHit(hits, !hardTrendAgainst(ctx, isLong)
                || (deepGateStretch && "WEAKENING".equals(ctx.regimeTransition)), "无强趋势推进");
        return new ConfluenceGateResult(hits.size(), 6, 4, hits);
    }

    private void addHit(List<String> hits, boolean condition, String label) {
        if (condition) hits.add(label);
    }

    private boolean bollStretchedForMeanReversion(MarketContext ctx, boolean isLong) {
        if (ctx.bollPb5m == null) return false;
        return isLong ? ctx.bollPb5m <= 18.0 : ctx.bollPb5m >= 82.0;
    }

    private boolean rsiStretchedForMeanReversion(MarketContext ctx, boolean isLong) {
        if (ctx.rsi5m == null) return false;
        double rsi = ctx.rsi5m.doubleValue();
        return isLong ? rsi <= 42.0 : rsi >= 58.0;
    }

    private boolean hardTrendAgainst(MarketContext ctx, boolean isLong) {
        return EntryStrategySupport.directionConflicts(ctx.maAlignment1h, isLong)
                && EntryStrategySupport.directionConflicts(ctx.maAlignment15m, isLong)
                && EntryStrategySupport.isMacdHistAgainst(ctx.macdHistTrend5m, isLong);
    }

    private int reversalVoteCount(MarketContext ctx, boolean isLong) {
        int votes = 0;
        if (EntryStrategySupport.macdSupports(ctx, isLong)) votes++;
        if (EntryStrategySupport.closeTrendSupports(ctx.closeTrend5m, isLong)) votes++;
        if (EntryStrategySupport.microSupports(ctx, isLong)) votes++;
        if ("WEAKENING".equals(ctx.regimeTransition)) votes++;
        return votes;
    }
}
