package com.mawai.wiibservice.agent.trading;

import java.util.ArrayList;
import java.util.List;

final class BreakoutConfluenceGate implements EntryConfluenceGate {

    @Override
    public ConfluenceGateResult evaluate(EntryStrategyContext context) {
        MarketContext ctx = context.market();
        boolean isLong = context.isLong();
        List<String> hits = new ArrayList<>();
        addHit(hits, bollNearBreakoutBand(ctx, isLong), "强突破位置");
        addHit(hits, volumeAtLeast(ctx, 1.25), "量能放大");
        addHit(hits, EntryStrategySupport.closeTrendSupports(ctx.closeTrend5m, isLong)
                || EntryStrategySupport.macdSupports(ctx, isLong), "突破动能同向");
        addHit(hits, ctx.bollSqueeze || volumeAtLeast(ctx, 1.45), "压缩/释放环境");
        addHit(hits, ctx.maAlignment15m != null
                && !EntryStrategySupport.directionConflicts(ctx.maAlignment15m, isLong), "中周期不反向");
        addHit(hits, !EntryStrategySupport.microAgainst(ctx, isLong, 0.30), "微结构未强反向");
        return new ConfluenceGateResult(hits.size(), 6, 4, hits);
    }

    private void addHit(List<String> hits, boolean condition, String label) {
        if (condition) hits.add(label);
    }

    private boolean volumeAtLeast(MarketContext ctx, double threshold) {
        return ctx.volumeRatio5m != null && ctx.volumeRatio5m >= threshold;
    }

    private boolean bollNearBreakoutBand(MarketContext ctx, boolean isLong) {
        if (ctx.bollPb5m == null) return false;
        return isLong ? ctx.bollPb5m >= 90.0 : ctx.bollPb5m <= 10.0;
    }
}
