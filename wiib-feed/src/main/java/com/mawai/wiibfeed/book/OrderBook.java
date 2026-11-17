package com.mawai.wiibfeed.book;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 一个合约币的本地盘口，两路数据拼起来：
 * <ul>
 *   <li>depth20 每 100ms 一份最优 20 档的整份快照，这 20 档以它为准；</li>
 *   <li>20 档以外靠增量流一档档攒。增量只说"哪一档变成了多少"，没变过的档看不到，
 *       所以刚连上时远处是稀的，越跑越全。</li>
 * </ul>
 * 出给前端的是按几个价格单位归并好的买卖盘：买盘往下取整、卖盘往上取整，同一格的量加在一起。
 * 价乘 1e8 存成 long，量是 double：只拿来显示，不参与撮合。
 */
final class OrderBook {

    /** 每个价格单位买卖各出几行（交易页的盘口每边最多显示这么多） */
    static final int ROWS = 10;
    /** 价格单位 = 最小价位 × 这几个倍数 */
    static final long[] STEP_MULTS = {1, 10, 100, 1000};
    /** 增量攒多远（按最小价位数）：最大单位显示范围的 4 倍，再远的不留 */
    private static final long KEEP_TICKS = STEP_MULTS[STEP_MULTS.length - 1] * ROWS * 4;

    /** 一侧的若干档，最优价在前 */
    record Levels(long[] px, double[] qty) {
        static final Levels EMPTY = new Levels(new long[0], new double[0]);

        int size() {
            return px.length;
        }
    }

    /** 增量攒出来的买盘，价从高到低 */
    private final NavigableMap<Long, Double> bids = new TreeMap<>(Comparator.reverseOrder());
    /** 增量攒出来的卖盘，价从低到高 */
    private final NavigableMap<Long, Double> asks = new TreeMap<>();
    /** depth20 最新一帧 */
    private Levels topBids = Levels.EMPTY, topAsks = Levels.EMPTY;
    /** 上一条增量的最后更新号，0 = 还没收过 */
    private long lastU;
    /** 最小价位（乘过 1e8），0 = 还没算出来 */
    private long tick;

    /**
     * depth20 来了一帧：整份换掉。
     * 最小价位不用配：挂单价都落在最小价位的格子上，相邻档的价差取最大公约数就是它，每个币各算各的
     */
    synchronized void onTop(Levels b, Levels a) {
        topBids = b;
        topAsks = a;
        for (int i = 1; i < b.size(); i++) tick = gcd(tick, b.px()[i - 1] - b.px()[i]);
        for (int i = 1; i < a.size(); i++) tick = gcd(tick, a.px()[i] - a.px()[i - 1]);
    }

    /**
     * 增量来了一条：量为 0 是这一档没了。
     * pu 得接上上一条的 u，接不上说明中间漏了，攒的这些不能信，清掉从这条重新攒
     *
     * @return 接上了没有
     */
    synchronized boolean onDiff(long pu, long u, Levels b, Levels a) {
        boolean chained = lastU == 0 || pu == lastU;
        if (!chained) {
            bids.clear();
            asks.clear();
        }
        lastU = u;
        put(bids, b, true);
        put(asks, a, false);
        return chained;
    }

    /** 增量流重连：断开那段漏了多少不知道，从头攒 */
    synchronized void resetDiff() {
        bids.clear();
        asks.clear();
        lastU = 0;
    }

    /** depth20 断了：最优 20 档没了着落，重新连上前不出盘口 */
    synchronized void resetTop() {
        topBids = topAsks = Levels.EMPTY;
        tick = 0;
    }

    /**
     * 出一份给前端的 JSON：{"ladders":[{"step":"0.1","b":[[价,量],...],"a":[[价,量],...]},...]}，
     * 单位从小到大，每侧从最优价往外最多 {@link #ROWS} 行。depth20 还没来过返回 null
     */
    synchronized String snapshot() {
        if (tick == 0 || topBids.size() == 0 || topAsks.size() == 0) return null;
        // 价走远了，攒在范围外的档顺手扔掉
        bids.tailMap(keepLimit(true), false).clear();
        asks.tailMap(keepLimit(false), false).clear();

        StringBuilder sb = new StringBuilder(4096).append("{\"ladders\":[");
        for (int i = 0; i < STEP_MULTS.length; i++) {
            long step = tick * STEP_MULTS[i];
            if (i > 0) sb.append(',');
            sb.append("{\"step\":\"").append(decimal(step)).append("\",\"b\":[");
            ladder(sb, true, step);
            sb.append("],\"a\":[");
            ladder(sb, false, step);
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    private void put(NavigableMap<Long, Double> side, Levels levels, boolean bid) {
        long limit = keepLimit(bid);
        for (int i = 0; i < levels.size(); i++) {
            long px = levels.px()[i];
            double qty = levels.qty()[i];
            if (qty == 0 || (bid ? px < limit : px > limit)) side.remove(px);
            else side.put(px, qty);
        }
    }

    /** 这一侧攒到哪个价为止；最小价位或最优价还不知道时不设限 */
    private long keepLimit(boolean bid) {
        Levels top = bid ? topBids : topAsks;
        if (tick == 0 || top.size() == 0) return bid ? Long.MIN_VALUE : Long.MAX_VALUE;
        return bid ? top.px()[0] - tick * KEEP_TICKS : top.px()[0] + tick * KEEP_TICKS;
    }

    /** 一侧按 step 归并：先走 depth20 那 20 档，再接增量里比它们更远的 */
    private void ladder(StringBuilder sb, boolean bid, long step) {
        Levels top = bid ? topBids : topAsks;
        Merger m = new Merger(sb, step, bid);
        for (int i = 0; i < top.size(); i++) {
            if (!m.add(top.px()[i], top.qty()[i])) return;
        }
        // 买盘是倒序表，tailMap 取的是更低的价；卖盘取更高的价
        for (Map.Entry<Long, Double> e : (bid ? bids : asks).tailMap(top.px()[top.size() - 1], false).entrySet()) {
            if (!m.add(e.getKey(), e.getValue())) return;
        }
        m.flush();
    }

    /** 归并器：按从最优价往外的顺序喂档位，落在同一格的量加在一起，写满 ROWS 行就不再收 */
    private static final class Merger {
        private final StringBuilder sb;
        private final long step;
        private final boolean bid;
        private int rows;
        private long bucket;
        private double sum;
        private boolean open;

        Merger(StringBuilder sb, long step, boolean bid) {
            this.sb = sb;
            this.step = step;
            this.bid = bid;
        }

        /** @return 还收不收 */
        boolean add(long px, double qty) {
            long b = bid ? Math.floorDiv(px, step) * step : -Math.floorDiv(-px, step) * step;
            if (open && b != bucket) {
                flush();
                if (rows == ROWS) return false;
            }
            bucket = b;
            sum += qty;
            open = true;
            return true;
        }

        void flush() {
            if (!open) return;
            if (rows++ > 0) sb.append(',');
            sb.append('[').append(decimal(bucket)).append(',')
                    .append(BigDecimal.valueOf(Math.round(sum * 1e6), 6).stripTrailingZeros().toPlainString()).append(']');
            sum = 0;
            open = false;
        }
    }

    /** 乘过 1e8 的整数写回十进制，去掉尾零 */
    private static String decimal(long units) {
        return BigDecimal.valueOf(units, 8).stripTrailingZeros().toPlainString();
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }
}
