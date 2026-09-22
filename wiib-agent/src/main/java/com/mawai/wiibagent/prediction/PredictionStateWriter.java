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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 预测员的眼睛：把这一刻玩家能看到的东西压成 Jev 读得懂的英文短语，全部出词不出数；数字留在 {@link Raw} 给规则和记分用。
 * <ul>
 *   <li>market：一句话讲清怎么赢</li>
 *   <li>clock：早段 / 中段 / 最后一分钟，最后一分钟说结算均价锁了多少</li>
 *   <li>btc：相对开盘均价、领先有多大、路径、形状、最近一分钟、速度（Polymarket 推的 Chainlink 现货，按 Chainlink 自己的时间戳）</li>
 *   <li>binance_flow：最近 60 秒主动买卖与大单、开盘以来强平方向（直接读 Redis 逐笔流和强平库）</li>
 *   <li>odds：谁是热门、UP/DOWN 各自划不划算（代码拿公平价比卖价加手续费）、最近 30 秒赔率怎么动</li>
 *   <li>position：只在持仓时给，持哪边、买时赔率、买后涨跌、现在卖划不划算</li>
 * </ul>
 * 余额、概率、秒数这些数字都不给：Jev 读数不准，数字交给代码，它只做玩家的判断。
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
    /** Chainlink 最后一跳超过这么久就当价停了，不问 */
    static final long TICK_MAX_AGE_MS = 10_000L;
    /** Binance 逐笔流超过这么久没更新就不给主动买卖和大单 */
    static final long FLOW_MAX_AGE_MS = 30_000L;
    static final int FLOW_WINDOW_SECONDS = 60;
    static final int FLOW_MIN_TRADES = 10;
    /** 看赔率怎么动：和这么久之前比 */
    static final long ODDS_LOOKBACK_MS = 30_000L;

    static final String GAME = "Polymarket 5-minute BTC market. UP pays 1 if BTC's average price over the final minute is at or above "
            + "its average at the open, otherwise DOWN pays 1. Buying costs the quoted price plus a small fee.";

    private final CacheService cacheService;
    private final KlineFetcher klineFetcher;
    private final OrderFlowAggregator orderFlowAggregator;
    private final ForceOrderService forceOrderService;

    /** 墙钟注入点 */
    LongSupplier nowMs = System::currentTimeMillis;

    /** 本回合手里的注：哪边、成交均价 */
    public record Holding(String side, BigDecimal avgPrice) {
    }

    /** 一次检查点：给 Jev 的 state + 代码自己留的数 */
    public record Snapshot(Map<String, Object> state, Raw raw) {
    }

    /**
     * @param zModel          纯数学的 z，正 = 偏 UP
     * @param pModel          纯数学的上涨概率
     * @param driftSign       开盘以来方向：1 涨 / -1 跌 / 0 平
     * @param book            写 state 那一刻的盘口
     * @param bookUpdatedAtMs 盘口最后收到推送的时刻；没有为 null
     */
    public record Raw(double zModel, double pModel, int driftSign, Book book, Long bookUpdatedAtMs) {
    }

    /** 缺开盘价、缺 K 线、本回合还没有 tick、Chainlink 停了都抛 IllegalStateException：看不全就别问 */
    public Snapshot write(long windowStart, Holding holding) {
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
        if (now - inWindow.getLast().timeMs() > TICK_MAX_AGE_MS) {
            throw new IllegalStateException("Chainlink 价停了 " + (now - inWindow.getLast().timeMs()) + "ms");
        }
        BigDecimal last = inWindow.getLast().price();
        List<KlineBar> bars = klineFetcher.fetch(SYMBOL, "1m", KLINE_BARS);
        if (bars.isEmpty()) {
            throw new IllegalStateException("1m K 线取不到");
        }
        double sigma1h = sigma1mPct(bars);
        // 刚起波的时候一小时典型值偏小，取大的那个公平价才不会过于自信
        double sigmaEff = Math.max(sigma1h, recentSigma1mPct(ticks, now));
        double lead = pct(open, last);
        int driftSign = Math.abs(lead) < AT_OPEN_RATIO * sigma1h ? 0 : lead > 0 ? 1 : -1;
        // 进了末分钟（收盘前 63 秒起）：已走过部分的均价已经算进结算价了
        Double twapSoFar = null;
        long twapStartMs = settleEndMs - PredictionModel.TWAP_SECONDS * 1000L;
        if (now > twapStartMs) {
            twapSoFar = pct(open, TimeWeightedAverage.of(inWindow, twapStartMs, now));
        }
        double z = PredictionModel.z(lead, twapSoFar, sigmaEff, untilSettleEnd);
        double pModel = PredictionModel.p(z);

        Book book = new Book(cacheService.getPredictionAsk("UP"), cacheService.getPredictionBid("UP"),
                cacheService.getPredictionAsk("DOWN"), cacheService.getPredictionBid("DOWN"));
        BigDecimal pMkt = PredictionRules.impliedUp(book);

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("market", GAME);
        state.put("clock", clockPhrase(untilSettleEnd));

        Map<String, Object> btc = new LinkedHashMap<>();
        btc.put("vs_open", driftSign == 0 ? "at the opening average"
                : driftSign > 0 ? "above the opening average" : "below the opening average");
        btc.put("lead", leadPhrase(z));
        btc.put("since_open", sinceOpenSentence(inWindow, open, sigma1h, windowStartMs, now));
        String shape = shapeWord(inWindow, open, sigma1h);
        if (shape != null) {
            btc.put("shape", shape);
        }
        btc.put("last_minute", lastMinuteWord(inWindow, now, sigma1h));
        btc.put("pace", speedSentence(bars, sigma1h));
        state.put("btc", btc);

        Map<String, Object> flow = new LinkedHashMap<>();
        putOrderFlow(flow, now);
        flow.put("liquidations", liquidationPhrase(windowStart, now));
        state.put("binance_flow", flow);

        Map<String, Object> odds = new LinkedHashMap<>();
        odds.put("standing", standingPhrase(pMkt));
        odds.put("up_value", valuePhrase("UP", pModel, book.upAsk()));
        odds.put("down_value", valuePhrase("DOWN", 1 - pModel, book.downAsk()));
        String move = oddsMovePhrase(cacheService.getPredictionUpMidPoints(windowStartMs), now);
        if (move != null) {
            odds.put("odds_move", move);
        }
        state.put("odds", odds);

        if (holding != null) {
            state.put("position", positionBlock(holding, book, pModel));
        }

        return new Snapshot(state, new Raw(z, pModel, driftSign, book, cacheService.getPredictionBookUpdatedAt()));
    }

    /** 最近 60 秒主动买卖与大单；逐笔流停了或不到 10 笔就不给，几笔成交看不出方向 */
    private void putOrderFlow(Map<String, Object> flow, long now) {
        if (now - orderFlowAggregator.getLastUpdateMs(SYMBOL) > FLOW_MAX_AGE_MS) {
            return;
        }
        OrderFlowAggregator.Metrics m = orderFlowAggregator.getMetrics(SYMBOL, FLOW_WINDOW_SECONDS);
        if (m == null || m.tradeCount() < FLOW_MIN_TRADES) {
            return;
        }
        flow.put("takers", takersPhrase(m.tradeDelta()));
        flow.put("large_trades", largeTradesPhrase(m.largeTradeBias()));
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

    /** 领先相对剩余时间里的正常波动：用含时间、含锁定的 z 分桶，说的是守不守得住 */
    static String leadPhrase(double z) {
        double r = Math.abs(z);
        if (r < 0.5) return "well inside normal noise";
        if (r < 1.5) return "about one normal move";
        if (r < 3) return "a couple of normal moves";
        return "several normal moves";
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

    static String lastMinuteWord(List<Point> inWindow, long now, double sigma1m) {
        BigDecimal from = priceAt(inWindow, now - 60_000L);
        int d = direction(pct(from, inWindow.getLast().price()), sigma1m);
        return d > 0 ? "rising" : d < 0 ? "falling" : "flat";
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

    static String takersPhrase(double tradeDelta) {
        if (tradeDelta >= 0.2) return "buyers ahead in the last minute";
        if (tradeDelta <= -0.2) return "sellers ahead in the last minute";
        return "balanced in the last minute";
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

    /** 谁是热门：按盘口隐含上涨概率分桶 */
    static String standingPhrase(BigDecimal pMkt) {
        if (pMkt == null) return "no quotes on one side right now";
        double p = pMkt.doubleValue();
        String side = p >= 0.5 ? "UP" : "DOWN";
        double q = Math.max(p, 1 - p);
        if (q >= 0.93) return side + " is a near-certain favourite";
        if (q >= 0.8) return side + " is a strong favourite";
        if (q >= 0.65) return side + " is a clear favourite";
        if (q >= 0.55) return side + " is a slight favourite";
        return "neither side is favoured";
    }

    /** 划不划算：公平价比卖价加手续费，按优势占最大利润的比例分五档；没有卖价就是没人卖 */
    static String valuePhrase(String side, double pSide, BigDecimal ask) {
        if (ask == null) return side + " has no sellers right now";
        double r = PredictionRules.edgeRatio(pSide, ask);
        String word;
        if (r >= 0.2) word = "looks clearly cheap";
        else if (r >= 0.05) word = "looks slightly cheap";
        else if (r > -0.05) word = "looks fairly priced";
        else if (r > -0.2) word = "looks slightly expensive";
        else word = "looks clearly expensive";
        return side + " " + word + " against where BTC stands";
    }

    /** 最近 30 秒 UP 中间价怎么动；本回合采样还没攒够 30 秒回 null 不给 */
    static String oddsMovePhrase(List<Point> upMids, long now) {
        // 留 5 秒余量：采样从开盘后第 1 秒起，开盘后第 30 秒那次也要能比
        if (upMids.isEmpty() || upMids.getFirst().timeMs() > now - ODDS_LOOKBACK_MS + 5_000L) return null;
        double then = priceAt(upMids, now - ODDS_LOOKBACK_MS).doubleValue();
        double d = upMids.getLast().price().doubleValue() - then;
        if (d >= 0.08) return "UP's price rose sharply over the last 30 seconds";
        if (d >= 0.03) return "UP's price rose a little over the last 30 seconds";
        if (d <= -0.08) return "UP's price fell sharply over the last 30 seconds";
        if (d <= -0.03) return "UP's price fell a little over the last 30 seconds";
        return "prices barely moved over the last 30 seconds";
    }

    /** 持仓块：卖出按买一价成交，划不划算拿扣完手续费的买一价比公平价 */
    static Map<String, Object> positionBlock(Holding h, Book book, double pModel) {
        boolean up = "UP".equals(h.side());
        BigDecimal bid = up ? book.upBid() : book.downBid();
        double pSide = up ? pModel : 1 - pModel;
        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("holding", h.side());
        pos.put("bought", "when " + h.side() + " was " + oddsWord(h.avgPrice().doubleValue()));
        if (bid == null) {
            pos.put("since_bought", h.side() + " has no buyers right now");
            return pos;
        }
        double d = bid.doubleValue() - h.avgPrice().doubleValue();
        String moved;
        if (d >= 0.10) moved = "risen a lot";
        else if (d >= 0.03) moved = "risen a little";
        else if (d <= -0.10) moved = "fallen a lot";
        else if (d <= -0.03) moved = "fallen a little";
        else moved = "barely moved";
        pos.put("since_bought", h.side() + "'s price has " + moved);
        double net = bid.doubleValue() - PredictionFee.perShare(bid).doubleValue();
        double gap = net - pSide;
        pos.put("sell_value", gap >= 0.05 ? "selling now pays more than BTC's position says the bet is worth"
                : gap <= -0.05 ? "selling now pays less than BTC's position says the bet is worth"
                : "selling now pays about what the bet is worth");
        return pos;
    }

    /** 份额价换成赔率说法 */
    static String oddsWord(double price) {
        if (price < 0.35) return "an underdog";
        if (price < 0.45) return "a slight underdog";
        if (price < 0.55) return "about even";
        if (price < 0.7) return "a slight favourite";
        if (price < 0.85) return "a clear favourite";
        return "a strong favourite";
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
