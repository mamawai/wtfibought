package com.mawai.wiibquant.whale;

import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.Position;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 一个币的仓位列表 → 多空聚合 + 开仓价/强平价分桶，纯函数（docs/hyperliquid-whale.md §5.2）。
 * <p>
 * 仓位层过滤在这里：只算名义 ≥ minPositionValue 的仓位。
 * liquidationPx 为空的仓位进多空统计、不进强平分桶。
 * 桶宽跟当轮标记价走（开仓价 0.25%、强平价 0.5%），桶下界 = floor(px / 宽) × 宽，只存非空桶。
 */
public final class WhaleAggregator {

    static final BigDecimal ENTRY_WIDTH_PCT = new BigDecimal("0.0025");
    static final BigDecimal LIQ_WIDTH_PCT = new BigDecimal("0.005");

    /** 一个方向的统计；没仓位时 count=0、notional=0、其余 null。一个地址一个币只有一条仓位，count 就是地址数 */
    public record Side(int count, BigDecimal notional, BigDecimal wavgEntry, BigDecimal medianEntry,
                       BigDecimal top1Share, BigDecimal upnl) {
        static final Side EMPTY = new Side(0, BigDecimal.ZERO, null, null, null, null);
    }

    /** 一个币一轮的快照；两个 JSON 形状 {"width":195.625,"buckets":[[下界,多头名义,空头名义],...]} */
    public record CoinSnapshot(String coin, BigDecimal price, BigDecimal hlOpenInterest, Side longSide, Side shortSide,
                               String entryBucketsJson, String liqBucketsJson) {
    }

    private WhaleAggregator() {
    }

    /** positions 是池内全部地址在这个币上的仓位；openInterest 是币数量，全市场名义 = openInterest × markPx */
    public static CoinSnapshot aggregate(String coin, BigDecimal markPx, BigDecimal openInterest,
                                         List<Position> positions, BigDecimal minPositionValue) {
        List<Position> longs = new ArrayList<>();
        List<Position> shorts = new ArrayList<>();
        for (Position p : positions) {
            if (p.positionValue().compareTo(minPositionValue) < 0) {
                continue;
            }
            if (p.szi().signum() > 0) {
                longs.add(p);
            } else if (p.szi().signum() < 0) {
                shorts.add(p);
            }
        }
        return new CoinSnapshot(coin, markPx, openInterest.multiply(markPx).setScale(2, RoundingMode.HALF_UP),
                side(longs), side(shorts),
                buckets(markPx.multiply(ENTRY_WIDTH_PCT), longs, shorts, Position::entryPx),
                buckets(markPx.multiply(LIQ_WIDTH_PCT), longs, shorts, Position::liquidationPx));
    }

    static Side side(List<Position> ps) {
        if (ps.isEmpty()) {
            return Side.EMPTY;
        }
        BigDecimal notional = BigDecimal.ZERO;
        BigDecimal size = BigDecimal.ZERO;
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal upnl = BigDecimal.ZERO;
        BigDecimal max = BigDecimal.ZERO;
        List<BigDecimal> entries = new ArrayList<>(ps.size());
        for (Position p : ps) {
            BigDecimal abs = p.szi().abs();
            notional = notional.add(p.positionValue());
            size = size.add(abs);
            weighted = weighted.add(p.entryPx().multiply(abs));
            upnl = upnl.add(p.unrealizedPnl());
            max = max.max(p.positionValue());
            entries.add(p.entryPx());
        }
        return new Side(ps.size(), notional, weighted.divide(size, 8, RoundingMode.HALF_UP), median(entries),
                max.divide(notional, 4, RoundingMode.HALF_UP), upnl);
    }

    /** 按地址取中位数，不加权；偶数个取中间两个的平均 */
    static BigDecimal median(List<BigDecimal> xs) {
        List<BigDecimal> sorted = new ArrayList<>(xs);
        sorted.sort(null);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.TWO, 8, RoundingMode.HALF_UP);
    }

    /** 按 priceOf 取的价分桶，价为空的仓位不进桶；桶按下界升序 */
    static String buckets(BigDecimal width, List<Position> longs, List<Position> shorts, Function<Position, BigDecimal> priceOf) {
        TreeMap<BigDecimal, BigDecimal[]> map = new TreeMap<>();
        fill(map, width, longs, priceOf, 0);
        fill(map, width, shorts, priceOf, 1);
        StringBuilder sb = new StringBuilder("{\"width\":").append(plain(width)).append(",\"buckets\":[");
        boolean first = true;
        for (var e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            // 名义额保留到分
            sb.append('[').append(plain(e.getKey())).append(',').append(plain(e.getValue()[0].setScale(2, RoundingMode.HALF_UP)))
                    .append(',').append(plain(e.getValue()[1].setScale(2, RoundingMode.HALF_UP))).append(']');
        }
        return sb.append("]}").toString();
    }

    private static void fill(TreeMap<BigDecimal, BigDecimal[]> map, BigDecimal width, List<Position> ps,
                             Function<Position, BigDecimal> priceOf, int slot) {
        for (Position p : ps) {
            BigDecimal px = priceOf.apply(p);
            if (px == null) {
                continue;
            }
            BigDecimal lower = px.divide(width, 0, RoundingMode.FLOOR).multiply(width);
            BigDecimal[] cell = map.computeIfAbsent(lower, _ -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            cell[slot] = cell[slot].add(p.positionValue());
        }
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
