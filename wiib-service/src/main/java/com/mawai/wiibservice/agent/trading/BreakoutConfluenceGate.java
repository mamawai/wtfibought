package com.mawai.wiibservice.agent.trading;

import java.util.ArrayList;
import java.util.List;

final class BreakoutConfluenceGate implements EntryConfluenceGate {

    @Override
    public ConfluenceGateResult evaluate(EntryStrategyContext context) {
        MarketContext ctx = context.market();
        boolean isLong = context.isLong();
        List<String> hits = new ArrayList<>();
        // 粗筛已经确认BB%B和基础量能；细筛只看旁证，避免早段突破被二次加严挡掉。
        addHit(hits, directionalMomentum(ctx, isLong), "突破动能同向");
        addHit(hits, releaseEnvironment(ctx), "压缩/强释放环境");
        addHit(hits, middleTimeframeNotAgainst(ctx, isLong), "中周期不明确反向");
        addHit(hits, emaSameSide(ctx, isLong), "EMA20同侧");
        addHit(hits, microNotStrongAgainst(ctx, isLong), "微结构未强反向");
        return new ConfluenceGateResult(hits.size(), 5, 3, hits);
    }

    private void addHit(List<String> hits, boolean condition, String label) {
        if (condition) hits.add(label);
    }

    private boolean volumeAtLeast(MarketContext ctx, double threshold) {
        return ctx.volumeRatio5m != null && ctx.volumeRatio5m >= threshold;
    }

    private boolean directionalMomentum(MarketContext ctx, boolean isLong) {
        return EntryStrategySupport.closeTrendSupports(ctx.closeTrend5m, isLong)
                || EntryStrategySupport.macdSupports(ctx, isLong);
    }

    private boolean releaseEnvironment(MarketContext ctx) {
        return ctx.bollSqueeze || volumeAtLeast(ctx, 1.45);
    }

    private boolean middleTimeframeNotAgainst(MarketContext ctx, boolean isLong) {
        return ctx.maAlignment15m == null || !EntryStrategySupport.directionConflicts(ctx.maAlignment15m, isLong);
    }

    private boolean emaSameSide(MarketContext ctx, boolean isLong) {
        return EntryStrategySupport.priceAboveEmaSupports(ctx, isLong);
    }

    private boolean microNotStrongAgainst(MarketContext ctx, boolean isLong) {
        return !EntryStrategySupport.microAgainst(ctx, isLong, 0.30);
    }
}
