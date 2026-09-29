package com.mawai.wiibagent.jev.predictor;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.OrderFlowAggregator;
import com.mawai.wiibcommon.market.OrderFlowAggregator.Metrics;
import com.mawai.wiibcommon.market.TimeWeightedAverage;
import com.mawai.wiibcommon.market.TimeWeightedAverage.Point;
import com.mawai.wiibagent.jev.predictor.PredictionRules.Book;
import com.mawai.wiibquant.market.service.KlineFetcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 预测员的眼睛：把这一局写成 Jev 读得懂的英文短句，要比的数（美元、秒数、¢ 价）由代码算好写进句子，不写结论。
 * 数学估计不给 Jev，只记分。UP / DOWN 对调时句子跟着对调：涨 ↔ 跌、above ↔ below、买占比 p ↔ 100 − p、UP ↔ DOWN。
 * <ul>
 *   <li>market：怎么赢</li>
 *   <li>clock：早段 / 中段 / 最后一分钟锁了多少，加上离结算均价截止还有几秒</li>
 *   <li>btc_now：Chainlink 现价离开盘均价多少美元</li>
 *   <li>lead：谁领先、领先相对剩余时间的正常波动有多大（末分钟含已锁定部分）</li>
 *   <li>settlement_so_far：末分钟已锁定部分相对开盘均价，只在末分钟给</li>
 *   <li>story：开盘以来每 30 秒一步，每步 BTC 怎么动、停在哪、Binance 主动成交谁占上风，旧的在前</li>
 *   <li>odds_history：每步结束时市场给两边的价，和 story 的步一一对应</li>
 *   <li>jump：只在突变唤醒时给，突变那一边从多少跳到多少、另一边跟着掉了多少、两边现在的价</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PredictionStateWriter {

    static final String SYMBOL = "BTCUSDT";
    static final int WINDOW_SECONDS = 300;
    /** 近一小时已收盘的 60 根 + 正在走的 1 根 */
    static final int KLINE_BARS = 61;
    /** 相对开盘均价"算不算在开盘价上"的死区，按 1 分钟典型波动的比例 */
    static final double AT_OPEN_RATIO = 0.05;
    /** 最近实际波动：看最近这么久，每 10 秒取一个价 */
    static final long RECENT_VOL_SPAN_MS = 180_000L;
    static final long RECENT_VOL_STEP_MS = 10_000L;
    /** Chainlink 最后一跳旧过这么久就不问 */
    static final long TICK_MAX_AGE_MS = 5_000L;
    /** Binance 逐笔流超过这么久没更新就不写主动成交 */
    static final long FLOW_MAX_AGE_MS = 30_000L;
    /** 一步里不到这么多笔成交就不写这一步的主动成交 */
    static final int FLOW_MIN_TRADES = 10;
    /** story 一步 30 秒，从现在往回切；开头那段不到 15 秒就并进下一步 */
    static final long STEP_MS = 30_000L;
    static final long FIRST_STEP_MIN_MS = 15_000L;

    static final String GAME = "Polymarket 5-minute BTC market. UP wins if BTC's average price over the final minute "
            + "is at or above its average at the open; otherwise DOWN wins.";

    private final CacheService cacheService;
    private final KlineFetcher klineFetcher;
    private final OrderFlowAggregator orderFlowAggregator;

    /** 墙钟注入点 */
    LongSupplier nowMs = System::currentTimeMillis;

    /** 一次唤醒：给 Jev 的 state + 代码自己留的数 */
    public record Snapshot(Map<String, Object> state, Raw raw) {
    }

    /**
     * @param zModel          纯数学的 z，正 = 偏 UP
     * @param pModel          纯数学的上涨概率
     * @param book            写 state 那一刻的盘口，按这份定买不买
     * @param bookUpdatedAtMs 盘口最近一次变化的时刻（Polymarket 那边的时间）；没有为 null
     * @param chainlinkAgeMs  Chainlink 最后一跳离现在多久；超过 {@link #TICK_MAX_AGE_MS} 不问
     * @param jump            这次唤醒的突变；整点唤醒为 null
     */
    public record Raw(double zModel, double pModel, Book book, Long bookUpdatedAtMs, long chainlinkAgeMs, OddsJump jump) {
    }

    /** 一次突变：起点、终点的时刻和 UP 中间价 */
    record OddsJump(long fromMs, long toMs, BigDecimal from, BigDecimal to) {

        /** 涨的那一边：UP 中间价涨了是 UP，跌了是 DOWN */
        String side() {
            return to.compareTo(from) > 0 ? "UP" : "DOWN";
        }

        /** 涨的那一边自己的起跳价，DOWN 是 1 − UP 的价 */
        BigDecimal sideFrom() {
            return "UP".equals(side()) ? from : BigDecimal.ONE.subtract(from);
        }

        /** 涨的那一边跳到的价 */
        BigDecimal sideTo() {
            return "UP".equals(side()) ? to : BigDecimal.ONE.subtract(to);
        }

        /**
         * 比 best 更该算的：幅度大的；一样大取用时短的（同一次突变相邻几对采样都看得到，最短那段最准），再一样取后发生的
         */
        boolean beats(OddsJump best) {
            if (best == null) return true;
            int cmp = to.subtract(from).abs().compareTo(best.to.subtract(best.from).abs());
            if (cmp != 0) return cmp > 0;
            long span = toMs - fromMs;
            long bestSpan = best.toMs - best.fromMs;
            if (span != bestSpan) return span < bestSpan;
            return toMs >= best.toMs;
        }
    }

    /**
     * 缺开盘价、缺 K 线、本回合还没有 tick 都抛 IllegalStateException：看不全就别问。Chainlink 停了不抛，年龄记在 Raw 里由回路跳过
     *
     * @param jump 突变唤醒时的那次突变，写进 jump；整点唤醒传 null
     */
    public Snapshot write(long windowStart, OddsJump jump) {
        long now = nowMs.getAsLong();
        long windowStartMs = windowStart * 1000L;
        // 结算均价截止时刻：收盘前 3 秒
        long settleEndMs = windowStartMs + (WINDOW_SECONDS - PredictionModel.SETTLE_LAG_SECONDS) * 1000L;
        double untilSettleEnd = (settleEndMs - now) / 1000.0;
        BigDecimal open = cacheService.getPolymarketOpenPrice(windowStart);
        if (open == null) {
            throw new IllegalStateException("开盘价未到 windowStart=" + windowStart);
        }
        List<Point> ticks = cacheService.getBtcPricePoints(Math.min(windowStartMs, now - RECENT_VOL_SPAN_MS)).stream()
                .filter(p -> p.timeMs() <= now).toList();
        List<Point> inWindow = ticks.stream().filter(p -> p.timeMs() >= windowStartMs).toList();
        if (inWindow.isEmpty()) {
            throw new IllegalStateException("本回合还没有 Chainlink tick windowStart=" + windowStart);
        }
        long chainlinkAgeMs = now - inWindow.getLast().timeMs();
        BigDecimal last = inWindow.getLast().price();
        List<KlineBar> bars = klineFetcher.fetch(SYMBOL, "1m", KLINE_BARS);
        if (bars.isEmpty()) {
            throw new IllegalStateException("1m K 线取不到");
        }
        double sigma1h = sigma1mPct(bars);
        // 刚起波的时候一小时典型值偏小，取大的那个公平价才不会过于自信
        double sigmaEff = Math.max(sigma1h, recentSigma1mPct(ticks, now));
        // 进了末分钟（收盘前 63 秒起）：已走过部分的均价已经算进结算价了
        BigDecimal twapSoFar = null;
        long twapStartMs = settleEndMs - PredictionModel.TWAP_SECONDS * 1000L;
        if (now > twapStartMs) {
            twapSoFar = TimeWeightedAverage.of(inWindow, twapStartMs, now);
        }
        double z = PredictionModel.z(pct(open, last), twapSoFar == null ? null : pct(open, twapSoFar), sigmaEff, untilSettleEnd);
        double pModel = PredictionModel.p(z);
        Book book = new Book(cacheService.getPredictionAsk("UP"), cacheService.getPredictionBid("UP"),
                cacheService.getPredictionAsk("DOWN"), cacheService.getPredictionBid("DOWN"));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("market", GAME);
        state.put("clock", clockPhrase(untilSettleEnd) + "; " + Math.round(untilSettleEnd)
                + " seconds until the settlement average is fixed");
        state.put("btc_now", "BTC is " + posPhrase(open, last, sigma1h));
        state.put("lead", leadPhrase(z));
        if (twapSoFar != null) {
            state.put("settlement_so_far", "the part of the settlement average already set is " + posPhrase(open, twapSoFar, sigma1h));
        }
        long[] bounds = storyBounds(windowStartMs, now);
        // 30 秒的正常波动折成美元，分涨跌档用
        double normalUsd = sigmaEff / 100 * open.doubleValue() * Math.sqrt(0.5);
        state.put("story", story(bounds, inWindow, open, sigma1h, normalUsd, stepFlows(bounds, now)));
        // 本回合 UP 中间价每秒一笔
        List<Point> upMids = cacheService.getPredictionUpMidPoints(windowStartMs).stream()
                .filter(p -> p.timeMs() <= now).toList();
        state.put("odds_history", oddsHistory(bounds, upMids));
        if (jump != null) {
            state.put("jump", jumpPhrase(jump, upMids.getLast().price(), now));
        }

        return new Snapshot(state, new Raw(z, pModel, book, cacheService.getPredictionBookUpdatedAt(), chainlinkAgeMs, jump));
    }

    /** 每步的 Binance 主动成交，整份 state 只读一次；逐笔流停了回 null，各步都不写这半句 */
    private List<Metrics> stepFlows(long[] bounds, long now) {
        if (now - orderFlowAggregator.getLastUpdateMs(SYMBOL) > FLOW_MAX_AGE_MS) {
            return null;
        }
        return orderFlowAggregator.getMetricsBetween(SYMBOL, bounds);
    }

    // ==================== story 与赔率 ====================

    /**
     * story 的步：从现在往回每 30 秒切一刀直到开盘，开头那段不到 15 秒就并进下一步。
     * 返回升序的边界时刻，首个是开盘时刻、末个是现在，相邻两个之间是一步
     */
    static long[] storyBounds(long windowStartMs, long now) {
        // 离开盘至少 15 秒的切点才留：now − k × 30 秒 ≥ 开盘 + 15 秒
        int cuts = (int) Math.max(0, (now - windowStartMs - FIRST_STEP_MIN_MS) / STEP_MS);
        long[] bounds = new long[cuts + 2];
        bounds[0] = windowStartMs;
        for (int k = 1; k <= cuts; k++) {
            bounds[k] = now - (cuts + 1 - k) * STEP_MS;
        }
        bounds[cuts + 1] = now;
        return bounds;
    }

    /**
     * story 各步一句，旧的在前：这一步 BTC 怎么动（第一步跟开盘均价比）、停在离开盘均价多远、Binance 主动成交谁占上风。
     * 价取每步结束时刻的 Chainlink tick；flows 为 null 或这一步成交不到 {@link #FLOW_MIN_TRADES} 笔就不写主动成交
     *
     * @param normalUsd 30 秒的正常波动（美元）
     */
    static List<String> story(long[] bounds, List<Point> inWindow, BigDecimal open, double sigma1m, double normalUsd,
                              List<Metrics> flows) {
        int n = bounds.length - 1;
        List<String> out = new ArrayList<>(n);
        BigDecimal prev = open;
        for (int k = 0; k < n; k++) {
            BigDecimal end = priceAt(inWindow, bounds[k + 1]);
            String s = stepName(k, n) + " (" + (k == 0 ? "first " : "next ") + seconds(bounds[k + 1] - bounds[k]) + "): BTC "
                    + movePhrase(end.subtract(prev).doubleValue(), normalUsd) + ", ending " + posPhrase(open, end, sigma1m);
            if (flows != null && flows.get(k).tradeCount() >= FLOW_MIN_TRADES) {
                s += "; " + takersPhrase(flows.get(k).tradeDelta());
            }
            out.add(s);
            prev = end;
        }
        return out;
    }

    /** 每步结束时刻的 UP 中间价，DOWN = 100 − UP；那一刻之前本回合还没有采样就跳过这一步 */
    static List<String> oddsHistory(long[] bounds, List<Point> upMids) {
        int n = bounds.length - 1;
        List<String> out = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            BigDecimal up = sampleAt(upMids, bounds[0], bounds[k + 1]);
            if (up != null) {
                out.add(stepName(k, n) + ": UP " + cents(up) + ", DOWN " + cents(BigDecimal.ONE.subtract(up)));
            }
        }
        return out;
    }

    /** Step 2；最后一步是 Step 4, the latest */
    static String stepName(int k, int count) {
        return "Step " + (k + 1) + (k == count - 1 ? ", the latest" : "");
    }

    /** 一步的涨跌：美元变化 ÷ 30 秒正常波动分四档，<0.3 算没怎么动，加涨跌多少美元 */
    static String movePhrase(double usd, double normalUsd) {
        double r = Math.abs(usd) / normalUsd;
        String amount = " (" + signedUsd(usd) + ")";
        if (r < 0.3) return "barely moved" + amount;
        String word = usd > 0 ? "rose" : "fell";
        if (r < 1) return word + " a little" + amount;
        if (r < 2) return word + amount;
        return word + " sharply" + amount;
    }

    /** 主动成交谁占上风：按买占比取整，≥ 60% 买方占上风、≤ 40% 卖方占上风，其余持平 */
    static String takersPhrase(double tradeDelta) {
        // 买占比 = 50% + 50% × tradeDelta；按离 50 多远取整，两边对调时正好 p ↔ 100 − p
        long lean = Math.round(Math.abs(tradeDelta) * 50);
        long buys = tradeDelta < 0 ? 50 - lean : 50 + lean;
        String share = " (" + buys + "% buys)";
        if (buys >= 60) return "buyers ahead among Binance takers" + share;
        if (buys <= 40) return "sellers ahead among Binance takers" + share;
        return "Binance takers balanced" + share;
    }

    /** 这次突变按涨的那一边写：从多少跳到多少、用了几秒、几秒前，另一边跟着掉了多少，再加两边现在的价 */
    static String jumpPhrase(OddsJump j, BigDecimal upNow, long now) {
        String side = j.side();
        boolean up = "UP".equals(side);
        String other = up ? "DOWN" : "UP";
        BigDecimal sideNow = up ? upNow : BigDecimal.ONE.subtract(upNow);
        return side + "'s price jumped from " + cents(j.sideFrom()) + " to " + cents(j.sideTo())
                + " within " + seconds(j.toMs() - j.fromMs()) + " (" + seconds(now - j.toMs()) + " ago); "
                + other + "'s price dropped from " + cents(BigDecimal.ONE.subtract(j.sideFrom()))
                + " to " + cents(BigDecimal.ONE.subtract(j.sideTo())) + ". "
                + side + " is " + cents(sideNow) + " now and " + other + " is " + cents(BigDecimal.ONE.subtract(sideNow));
    }

    // ==================== 词 ====================

    /** 按离结算均价截止的秒数分段，末分钟起点与 PredictionModel 一致 */
    static String clockPhrase(double untilSettleEnd) {
        if (untilSettleEnd > 180) return "early: more than three minutes left";
        if (untilSettleEnd >= PredictionModel.TWAP_SECONDS) return "middle: one to three minutes left";
        double locked = (PredictionModel.TWAP_SECONDS - untilSettleEnd) / (double) PredictionModel.TWAP_SECONDS;
        if (locked < 0.2) return "final minute: little of the settlement average is set yet";
        if (locked < 0.4) return "final minute: about a third of the settlement average is already set";
        if (locked < 0.7) return "final minute: about half of the settlement average is already set";
        return "final minute: most of the settlement average is already set";
    }

    /** 离开盘均价多远：死区里说在开盘价上，否则 $N above / below */
    static String posPhrase(BigDecimal open, BigDecimal price, double sigma1m) {
        if (Math.abs(pct(open, price)) < AT_OPEN_RATIO * sigma1m) return "at the opening average";
        BigDecimal d = price.subtract(open);
        return usd(d.abs().doubleValue()) + (d.signum() > 0 ? " above" : " below") + " the opening average";
    }

    /** 谁领先、领先相对剩余时间的正常波动有多大：用含时间、含锁定的 z 分桶 */
    static String leadPhrase(double z) {
        double r = Math.abs(z);
        if (r < 0.5) return "neither side clearly ahead: the gap is well inside normal noise for the time left";
        String side = z > 0 ? "UP" : "DOWN";
        if (r < 1.5) return side + " ahead by about one normal move for the time left";
        if (r < 3) return side + " ahead by a couple of normal moves for the time left";
        return side + " ahead by several normal moves for the time left";
    }

    /** 毫秒写成整秒，至少 1 秒 */
    static String seconds(long ms) {
        long n = Math.max(1, Math.round(ms / 1000.0));
        return n == 1 ? "1 second" : n + " seconds";
    }

    /** 价格写成美分：0.62 → 62¢ */
    static String cents(BigDecimal price) {
        return price.movePointRight(2).stripTrailingZeros().toPlainString() + "¢";
    }

    static String usd(double amount) {
        return "$" + Math.round(amount);
    }

    /** 带正负号的整数美元：+$18 / -$7，按绝对值取整，两边对调时对称；不到半美元写 $0 不带号 */
    static String signedUsd(double amount) {
        long n = Math.round(Math.abs(amount));
        if (n == 0) return "$0";
        return (amount < 0 ? "-$" : "+$") + n;
    }

    // ==================== 数 ====================

    /** 1 分钟典型波动（%）：已收盘 1m K 线的绝对收益中位数 × 1.4826，用中位数是不让一两根大 K 线把它拉高 */
    static double sigma1mPct(List<KlineBar> bars) {
        List<Double> rets = absReturnsPct(bars);
        rets.sort(Double::compare);
        return rets.get(rets.size() / 2) * 1.4826;
    }

    /**
     * 最近几分钟的实际波动（%，折成 1 分钟）：Chainlink 每 10 秒取一个价，10 秒涨跌的均方根 × √6。
     * tick 盖不住那么长就只用盖得住的那段；不到 6 段回 0，交给一小时典型值。
     */
    static double recentSigma1mPct(List<Point> ticks, long now) {
        long start = Math.max(now - RECENT_VOL_SPAN_MS, ticks.getFirst().timeMs());
        List<Double> rets = new ArrayList<>();
        BigDecimal prev = priceAt(ticks, start);
        for (long t = start + RECENT_VOL_STEP_MS; t <= now; t += RECENT_VOL_STEP_MS) {
            BigDecimal cur = priceAt(ticks, t);
            rets.add(pct(prev, cur));
            prev = cur;
        }
        if (rets.size() < 6) return 0;
        double ss = 0;
        for (double r : rets) ss += r * r;
        return Math.sqrt(ss / rets.size()) * Math.sqrt(60.0 * 1000 / RECENT_VOL_STEP_MS);
    }

    /** 相邻已收盘 1m K 线的绝对涨跌（%）；末根还在形成，不算 */
    private static List<Double> absReturnsPct(List<KlineBar> bars) {
        List<Double> rets = new ArrayList<>();
        for (int i = 1; i < bars.size() - 1; i++) {
            rets.add(Math.abs(pct(bars.get(i - 1).close(), bars.get(i).close())));
        }
        return rets;
    }

    /** 时刻 t 的价：最后一个不晚于 t 的点，都晚于 t 就取首个 */
    static BigDecimal priceAt(List<Point> points, long t) {
        BigDecimal price = points.getFirst().price();
        for (Point p : points) {
            if (p.timeMs() > t) break;
            price = p.price();
        }
        return price;
    }

    /** 不早于 fromMs、不晚于 t 的最后一个采样点的价；没有回 null */
    static BigDecimal sampleAt(List<Point> points, long fromMs, long t) {
        BigDecimal price = null;
        for (Point p : points) {
            if (p.timeMs() > t) break;
            if (p.timeMs() >= fromMs) price = p.price();
        }
        return price;
    }

    /** (to − from) / from × 100 */
    static double pct(BigDecimal from, BigDecimal to) {
        return to.subtract(from).doubleValue() / from.doubleValue() * 100.0;
    }
}
