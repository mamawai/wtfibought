package com.mawai.wiibagent.prediction;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.ForceOrder;
import com.mawai.wiibcommon.market.ForceOrderService;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.OrderFlowAggregator;
import com.mawai.wiibcommon.market.PredictionFee;
import com.mawai.wiibcommon.market.TimeWeightedAverage;
import com.mawai.wiibcommon.market.TimeWeightedAverage.Point;
import com.mawai.wiibagent.prediction.PredictionRules.Book;
import com.mawai.wiibquant.market.service.KlineFetcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 预测员的眼睛：把这一刻玩家能看到的事实写成 Jev 读得懂的英文短句，要比的数（价差、秒数、¢ 价、含费成本）由代码算好写进句子，
 * 不写"便宜 / 贵"这类结论。空仓持仓同一份 state，不写持仓；数学估计不给 Jev，只记分。涉及两边的句子两边都写，两边对调时写法也对称。
 * <ul>
 *   <li>market：怎么赢、怎么买卖、多久问一次</li>
 *   <li>clock：早段 / 中段 / 最后一分钟锁了多少，加上离结算均价截止还有几秒</li>
 *   <li>btc：Chainlink 现价相对开盘均价、谁领先多少（按剩余时间的正常波动，末分钟含已锁定部分）、末分钟已锁定部分相对开盘均价、
 *       路径、形状、最近一分钟、速度、Chainlink 多久前更新和 Binance 最近 10 / 30 秒怎么动（Chainlink 按它自己的时间戳）</li>
 *   <li>binance_flow：最近 60 秒主动买卖与大单、开盘以来强平方向（直接读 Redis 逐笔流和强平库）</li>
 *   <li>odds：谁是热门和市场给两边的概率、UP / DOWN 各自的卖价买价与含费成本、最近 30 秒两边赔率怎么动、
 *       最近 15 秒里 3 秒内的赔率突变（到阈值才有）</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PredictionStateWriter {

    static final String SYMBOL = "BTCUSDT";
    static final int WINDOW_SECONDS = 300;
    /** 近一小时已收盘的 60 根 + 正在走的 1 根 */
    static final int KLINE_BARS = 61;
    /** 方向判定：相对 1 分钟典型波动的比例，低于它算 flat */
    static final double FLAT_RATIO = 0.3;
    /** 相对开盘均价"算不算在开盘价上"的死区，同样按 1 分钟典型波动的比例 */
    static final double AT_OPEN_RATIO = 0.05;
    /** 路径形状：这么长一段里走完净变动的这个比例就算"一跳" */
    static final long JUMP_SPAN_MS = 10_000L;
    static final double JUMP_RATIO = 0.6;
    /** 路径形状：区间是净变动的这么多倍就算"来回" */
    static final double SWING_RATIO = 2.5;
    /** 最近实际波动：看最近这么久，每 10 秒取一个价 */
    static final long RECENT_VOL_SPAN_MS = 180_000L;
    static final long RECENT_VOL_STEP_MS = 10_000L;
    /** Chainlink 最后一跳旧过这么久就不问 */
    static final long TICK_MAX_AGE_MS = 5_000L;
    /** Binance 逐笔流超过这么久没更新就不给主动买卖、大单和最近涨跌 */
    static final long FLOW_MAX_AGE_MS = 30_000L;
    static final int FLOW_WINDOW_SECONDS = 60;
    static final int FLOW_MIN_TRADES = 10;
    /** 看赔率怎么动：和这么久之前比，动到 3¢ 算一点、8¢ 算剧烈 */
    static final long ODDS_LOOKBACK_MS = 30_000L;
    static final BigDecimal ODDS_MOVE_LITTLE = new BigDecimal("0.03");
    static final BigDecimal ODDS_MOVE_SHARP = new BigDecimal("0.08");
    /** 赔率突变：看最近这么久（一个检查点间隔）里 */
    static final long ODDS_JUMP_LOOKBACK_MS = 15_000L;
    /** 赔率突变：两个采样点相隔不超过这么久算"3 秒内"，每秒采一次，留半秒给采样时刻的抖动 */
    static final long ODDS_JUMP_SPAN_MS = 3_500L;

    static final String GAME = "Polymarket 5-minute BTC market. UP pays 100¢ a share if BTC's average price over the final minute "
            + "is at or above its average at the open; otherwise DOWN pays 100¢. You buy at the ask; a bet can also be sold "
            + "before the close at the bid. Every buy and every sell pays a fee. You are asked again every 15 seconds.";

    private final CacheService cacheService;
    private final KlineFetcher klineFetcher;
    private final OrderFlowAggregator orderFlowAggregator;
    private final ForceOrderService forceOrderService;
    private final JevPredictionConfig cfg;

    /** 墙钟注入点 */
    LongSupplier nowMs = System::currentTimeMillis;

    /** 一次检查点：给 Jev 的 state + 代码自己留的数 */
    public record Snapshot(Map<String, Object> state, Raw raw) {
    }

    /**
     * @param zModel          纯数学的 z，正 = 偏 UP
     * @param pModel          纯数学的上涨概率
     * @param book            写 state 那一刻的盘口，Jev 看到的就是这份
     * @param bookUpdatedAtMs 盘口最近一次变化的时刻（Polymarket 那边的时间）；没有为 null
     * @param chainlinkAgeMs  Chainlink 最后一跳离现在多久；超过 {@link #TICK_MAX_AGE_MS} 不问
     * @param oddsJumpUp      最近 15 秒里 UP 中间价 3 秒内的最大涨幅（没涨是 0），不管到没到阈值都记；采样不够为 null
     * @param oddsJumpDown    同上的最大跌幅，负数（没跌是 0）
     */
    public record Raw(double zModel, double pModel, Book book, Long bookUpdatedAtMs, long chainlinkAgeMs,
                      Double oddsJumpUp, Double oddsJumpDown) {
    }

    /** 缺开盘价、缺 K 线、本回合还没有 tick 都抛 IllegalStateException：看不全就别问。Chainlink 停了不抛，年龄记在 Raw 里由回路跳过 */
    public Snapshot write(long windowStart) {
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
        BigDecimal pMkt = PredictionRules.impliedUp(book);
        boolean flowFresh = now - orderFlowAggregator.getLastUpdateMs(SYMBOL) <= FLOW_MAX_AGE_MS;

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("market", GAME);
        state.put("clock", clockPhrase(untilSettleEnd) + "; " + Math.round(untilSettleEnd)
                + " seconds until the settlement average is fixed");

        Map<String, Object> btc = new LinkedHashMap<>();
        btc.put("vs_open", gapPhrase(open, last, sigma1h));
        btc.put("lead", leadPhrase(z));
        if (twapSoFar != null) {
            btc.put("settlement_so_far", "the part of the settlement average already set is " + gapPhrase(open, twapSoFar, sigma1h));
        }
        btc.put("since_open", sinceOpenSentence(inWindow, open, sigma1h, windowStartMs, now));
        String shape = shapeWord(inWindow, open, sigma1h);
        if (shape != null) {
            btc.put("shape", shape);
        }
        btc.put("last_minute", lastMinutePhrase(inWindow, now, sigma1h));
        btc.put("pace", speedSentence(bars, sigma1h));
        btc.put("latest", latestPhrase(chainlinkAgeMs, flowFresh));
        state.put("btc", btc);

        Map<String, Object> flow = new LinkedHashMap<>();
        if (flowFresh) {
            putOrderFlow(flow);
        }
        flow.put("liquidations", liquidationPhrase(windowStart, now));
        state.put("binance_flow", flow);

        // 本回合 UP 中间价每秒一笔，赔率变动和突变共用
        List<Point> upMids = cacheService.getPredictionUpMidPoints(windowStartMs).stream()
                .filter(p -> p.timeMs() <= now).toList();
        Map<String, Object> odds = new LinkedHashMap<>();
        odds.put("standing", standingPhrase(pMkt));
        odds.put("up", quotePhrase("UP", book.upAsk(), book.upBid()));
        odds.put("down", quotePhrase("DOWN", book.downAsk(), book.downBid()));
        String move = oddsMovePhrase(upMids, now);
        if (move != null) {
            odds.put("odds_move", move);
        }
        OddsJumps jumps = biggestJumps(upMids, now);
        String jump = jumps.measured() ? oddsJumpPhrase(jumps, upMids.getLast().price(), now, cfg.getJumpThreshold()) : null;
        if (jump != null) {
            odds.put("jump", jump);
        }
        state.put("odds", odds);

        return new Snapshot(state, new Raw(z, pModel, book, cacheService.getPredictionBookUpdatedAt(), chainlinkAgeMs,
                jumps.measured() ? jumps.riseSize() : null, jumps.measured() ? jumps.fallSize() : null));
    }

    /** 最近 60 秒主动买卖与大单；不到 10 笔就不给 */
    private void putOrderFlow(Map<String, Object> flow) {
        OrderFlowAggregator.Metrics m = orderFlowAggregator.getMetrics(SYMBOL, FLOW_WINDOW_SECONDS);
        if (m == null || m.tradeCount() < FLOW_MIN_TRADES) {
            return;
        }
        flow.put("takers", takersPhrase(m.tradeDelta()));
        flow.put("large_trades", largeTradesPhrase(m.largeTradeBias()));
    }

    /** Chainlink 多久前更新；逐笔流新鲜时再加 Binance 上最近 10 秒、30 秒 BTC 涨跌多少美元 */
    private String latestPhrase(long chainlinkAgeMs, boolean flowFresh) {
        String chainlink = "Chainlink, which settles the market, last updated " + Math.round(chainlinkAgeMs / 1000.0) + " seconds ago";
        Double move10 = flowFresh ? orderFlowAggregator.priceChange(SYMBOL, 10) : null;
        Double move30 = flowFresh ? orderFlowAggregator.priceChange(SYMBOL, 30) : null;
        if (move10 == null || move30 == null) {
            return chainlink;
        }
        return chainlink + "; on Binance BTC moved " + signedUsd(move10) + " in the last 10 seconds and "
                + signedUsd(move30) + " in the last 30 seconds";
    }

    /** 开盘以来强平方向，不说量级：Binance 强平流每秒只推一笔，量是少算的 */
    private String liquidationPhrase(long windowStart, long now) {
        // 多取两分钟，分钟取整不漏开盘那一刻
        int minutes = (int) ((now / 1000 - windowStart) / 60) + 2;
        LocalDateTime since = LocalDateTime.ofInstant(Instant.ofEpochSecond(windowStart), ZoneId.systemDefault());
        double longs = 0;
        double shorts = 0;
        for (ForceOrder o : forceOrderService.getRecent(SYMBOL, minutes)) {
            if (o.getTradeTime().isBefore(since)) continue;
            // SELL = 多头被强平，BUY = 空头被强平
            if ("SELL".equals(o.getSide())) longs += o.getAmount().doubleValue();
            else shorts += o.getAmount().doubleValue();
        }
        return liquidationWord(longs, shorts);
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

    /** 相对开盘均价：死区里说在开盘价上，否则说高 / 低多少美元和百分比 */
    static String gapPhrase(BigDecimal open, BigDecimal price, double sigma1m) {
        double gap = pct(open, price);
        if (Math.abs(gap) < AT_OPEN_RATIO * sigma1m) return "at the opening average";
        return (gap > 0 ? "above" : "below") + " the opening average by " + usd(price.subtract(open).abs().doubleValue())
                + String.format(Locale.ROOT, " (%.3f%%)", Math.abs(gap));
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

    /** 开盘以来路径：前后两半各判方向，拼成一句话 */
    static String sinceOpenSentence(List<Point> inWindow, BigDecimal open, double sigma1m, long windowStartMs, long now) {
        long mid = (windowStartMs + now) / 2;
        BigDecimal midPrice = priceAt(inWindow, mid);
        BigDecimal last = inWindow.getLast().price();
        int a = direction(pct(open, midPrice), sigma1m);
        int b = direction(pct(midPrice, last), sigma1m);
        boolean aboveOpen = last.compareTo(open) >= 0;
        if (a > 0 && b > 0) return "rose steadily since the open";
        if (a > 0 && b == 0) return "rose early, flat since";
        if (a > 0) return aboveOpen ? "rose early, then gave back part of it" : "rose early, then reversed below the open";
        if (a < 0 && b < 0) return "fell steadily since the open";
        if (a < 0 && b == 0) return "fell early, flat since";
        if (a < 0) return aboveOpen ? "fell early, then reversed above the open" : "fell early, then recovered part of it";
        if (b > 0) return "flat early, rising lately";
        if (b < 0) return "flat early, falling lately";
        return "flat since the open";
    }

    /**
     * 路径形状：区间远大于净变动是"来回"；净变动大半在 10 秒内走完是"一跳"；其余"台阶"。
     * 净变动和区间都不到典型波动就没形状可说，回 null 不给。
     */
    static String shapeWord(List<Point> inWindow, BigDecimal open, double sigma1m) {
        BigDecimal hi = open;
        BigDecimal lo = open;
        for (Point p : inWindow) {
            if (p.price().compareTo(hi) > 0) hi = p.price();
            if (p.price().compareTo(lo) < 0) lo = p.price();
        }
        double rangePct = pct(lo, hi);
        double netPct = Math.abs(pct(open, inWindow.getLast().price()));
        if (rangePct >= sigma1m && rangePct >= SWING_RATIO * netPct) return "back and forth";
        if (netPct < FLAT_RATIO * sigma1m) return null;
        return biggestMovePct(inWindow, JUMP_SPAN_MS) >= JUMP_RATIO * netPct ? "one jump" : "in steps";
    }

    /** 任意 spanMs 长一段里的最大绝对涨跌（%）：每一跳往前看 span 时刻生效的价 */
    static double biggestMovePct(List<Point> pts, long spanMs) {
        double best = 0;
        int i = 0;
        for (int j = 0; j < pts.size(); j++) {
            long from = pts.get(j).timeMs() - spanMs;
            while (i < j && pts.get(i + 1).timeMs() <= from) i++;
            best = Math.max(best, Math.abs(pct(pts.get(i).price(), pts.get(j).price())));
        }
        return best;
    }

    /** 最近一分钟方向，加涨跌多少美元 */
    static String lastMinutePhrase(List<Point> inWindow, long now, double sigma1m) {
        BigDecimal from = priceAt(inWindow, now - 60_000L);
        BigDecimal last = inWindow.getLast().price();
        int d = direction(pct(from, last), sigma1m);
        return (d > 0 ? "rising" : d < 0 ? "falling" : "flat") + " (" + signedUsd(last.subtract(from).doubleValue()) + ")";
    }

    /** 最近 5 根 1 分钟的平均绝对涨跌 vs 近一小时典型值 */
    static String speedSentence(List<KlineBar> bars, double sigma1m) {
        List<Double> rets = absReturnsPct(bars);
        double recent = rets.subList(rets.size() - 5, rets.size()).stream().mapToDouble(d -> d).average().orElseThrow();
        double r = recent / sigma1m;
        if (r < 0.5) return "unusually quiet";
        if (r < 2) return "about as active as usual";
        return "unusually fast";
    }

    /** 主动买卖谁占上风，加主动买占成交额的比例 */
    static String takersPhrase(double tradeDelta) {
        String share = " (taker buys " + Math.round((1 + tradeDelta) / 2 * 100) + "% of volume)";
        if (tradeDelta >= 0.2) return "buyers ahead in the last minute" + share;
        if (tradeDelta <= -0.2) return "sellers ahead in the last minute" + share;
        return "balanced in the last minute" + share;
    }

    static String largeTradesPhrase(double largeTradeBias) {
        if (largeTradeBias >= 0.3) return "mostly buys in the last minute";
        if (largeTradeBias <= -0.3) return "mostly sells in the last minute";
        return "mixed or none in the last minute";
    }

    static String liquidationWord(double longsUsdt, double shortsUsdt) {
        double total = longsUsdt + shortsUsdt;
        if (total < 1) return "none since the open";
        if (longsUsdt / total >= 0.7) return "longs liquidated since the open";
        if (shortsUsdt / total >= 0.7) return "shorts liquidated since the open";
        return "both sides liquidated since the open";
    }

    /** 谁是热门：按盘口隐含上涨概率分桶，加市场给两边的概率 */
    static String standingPhrase(BigDecimal pMkt) {
        if (pMkt == null) return "no quotes on one side right now";
        double p = pMkt.doubleValue();
        String side = p >= 0.5 ? "UP" : "DOWN";
        double q = Math.max(p, 1 - p);
        String word;
        if (q >= 0.93) word = side + " is a near-certain favourite";
        else if (q >= 0.8) word = side + " is a strong favourite";
        else if (q >= 0.65) word = side + " is a clear favourite";
        else if (q >= 0.55) word = side + " is a slight favourite";
        else word = "neither side is favoured";
        // 半个百分点取偶，两边对调时取整也对称
        long up = pMkt.movePointRight(2).setScale(0, RoundingMode.HALF_EVEN).longValue();
        return word + "; the market prices UP at about " + up + "% and DOWN at about " + (100 - up) + "%";
    }

    /** 一边的报价：卖价、买价、按卖价买一份连手续费要多少 */
    static String quotePhrase(String side, BigDecimal ask, BigDecimal bid) {
        String bidText = bid == null ? "no bid" : "bid " + cents(bid);
        if (ask == null) return "nobody is selling " + side + " right now; " + bidText;
        double cost = ask.doubleValue() + PredictionFee.perShare(ask).doubleValue();
        return "ask " + cents(ask) + ", " + bidText + String.format(Locale.ROOT, "; buying costs %.1f¢", cost * 100)
                + " a share with the fee and pays 100¢ if " + side + " wins";
    }

    /** 最近 30 秒两边赔率怎么动，各加涨跌几美分（DOWN 就是 UP 反过来）；本回合采样还没攒够 30 秒回 null 不给 */
    static String oddsMovePhrase(List<Point> upMids, long now) {
        // 留 5 秒余量：采样从开盘后第 1 秒起，开盘后第 30 秒那次也要能比
        if (upMids.isEmpty() || upMids.getFirst().timeMs() > now - ODDS_LOOKBACK_MS + 5_000L) return null;
        // 用 BigDecimal 比分档、取整，涨跌对调时落在同一档
        BigDecimal d = upMids.getLast().price().subtract(priceAt(upMids, now - ODDS_LOOKBACK_MS));
        long c = d.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValue();
        String up = String.format(Locale.ROOT, "%+d¢", c);
        String down = String.format(Locale.ROOT, "%+d¢", -c);
        if (d.abs().compareTo(ODDS_MOVE_LITTLE) < 0) {
            return "prices barely moved over the last 30 seconds (UP " + up + ", DOWN " + down + ")";
        }
        String degree = d.abs().compareTo(ODDS_MOVE_SHARP) >= 0 ? "sharply" : "a little";
        boolean rose = d.signum() > 0;
        return "UP's price " + (rose ? "rose " : "fell ") + degree + " over the last 30 seconds (" + up + "); DOWN's price "
                + (rose ? "fell " : "rose ") + degree + " (" + down + ")";
    }

    /** 一次突变：起点、终点的时刻和 UP 中间价 */
    record OddsJump(long fromMs, long toMs, BigDecimal from, BigDecimal to) {

        double size() {
            return to.subtract(from).doubleValue();
        }

        /**
         * 比 best 更该写的：幅度大的；一样大取用时短的（同一次突变相邻几个窗口都看得到，最短那段最准），再一样取后发生的
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
     * 最近 15 秒里 3 秒内的最大一次涨、最大一次跌，没有的为 null
     *
     * @param measured 最近 15 秒里至少有一对能比的采样点；false 时两项都不算数
     */
    record OddsJumps(OddsJump rise, OddsJump fall, boolean measured) {

        double riseSize() {
            return rise == null ? 0 : rise.size();
        }

        double fallSize() {
            return fall == null ? 0 : fall.size();
        }
    }

    /**
     * 找突变的滑动窗口：右端逐个走过最近 15 秒内的采样点，左端移出离右端超过 3.5 秒的点（留下隔 1–3 次采样的），
     * 右端和窗口里每个点比，涨的、跌的各留最大的（见 {@link OddsJump#beats}）。
     * 窗口里最多 4 个点，直接扫；采样从开盘起，不会混进上一回合
     */
    static OddsJumps biggestJumps(List<Point> upMids, long now) {
        OddsJump rise = null;
        OddsJump fall = null;
        boolean measured = false;
        int left = 0;
        for (int j = 0; j < upMids.size(); j++) {
            Point r = upMids.get(j);
            while (r.timeMs() - upMids.get(left).timeMs() > ODDS_JUMP_SPAN_MS) left++;
            // 左端可以早于 15 秒，右端要在 15 秒内
            if (r.timeMs() <= now - ODDS_JUMP_LOOKBACK_MS) continue;
            for (int i = left; i < j; i++) {
                Point l = upMids.get(i);
                measured = true;
                OddsJump x = new OddsJump(l.timeMs(), r.timeMs(), l.price(), r.price());
                int dir = r.price().compareTo(l.price());
                if (dir > 0 && x.beats(rise)) rise = x;
                if (dir < 0 && x.beats(fall)) fall = x;
            }
        }
        return new OddsJumps(rise, fall, measured);
    }

    /** 到阈值的涨、跌按发生先后写成一句，末尾加现在的价；都不到回 null 不给 */
    static String oddsJumpPhrase(OddsJumps jumps, BigDecimal nowMid, long now, double threshold) {
        List<OddsJump> big = new ArrayList<>();
        if (jumps.rise() != null && jumps.riseSize() >= threshold) big.add(jumps.rise());
        if (jumps.fall() != null && -jumps.fallSize() >= threshold) big.add(jumps.fall());
        if (big.isEmpty()) return null;
        big.sort(Comparator.comparingLong(OddsJump::toMs));
        StringBuilder s = new StringBuilder("UP's price ");
        for (int k = 0; k < big.size(); k++) {
            OddsJump x = big.get(k);
            if (k > 0) s.append(", then ");
            s.append(x.size() > 0 ? "jumped" : "dropped").append(" from ").append(cents(x.from())).append(" to ").append(cents(x.to()))
                    .append(" within ").append(seconds(x.toMs() - x.fromMs())).append(" (").append(seconds(now - x.toMs())).append(" ago)");
        }
        return s.append("; it is ").append(cents(nowMid)).append(" now").toString();
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

    /** 带正负号的整数美元：+$18 / -$7，不到半美元是 +$0 */
    static String signedUsd(double amount) {
        long n = Math.round(amount);
        return (n < 0 ? "-$" : "+$") + Math.abs(n);
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

    /** 方向：涨跌幅相对 1 分钟典型波动不足 FLAT_RATIO 算 0 */
    static int direction(double changePct, double sigma1m) {
        if (Math.abs(changePct) < FLAT_RATIO * sigma1m) return 0;
        return changePct > 0 ? 1 : -1;
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

    /** (to − from) / from × 100 */
    static double pct(BigDecimal from, BigDecimal to) {
        return to.subtract(from).doubleValue() / from.doubleValue() * 100.0;
    }
}
