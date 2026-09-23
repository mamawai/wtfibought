package com.mawai.wiibagent.prediction;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.ForceOrder;
import com.mawai.wiibcommon.market.ForceOrderService;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.OrderFlowAggregator;
import com.mawai.wiibcommon.market.TimeWeightedAverage.Point;
import com.mawai.wiibagent.prediction.PredictionStateWriter.Snapshot;
import com.mawai.wiibquant.market.service.KlineFetcher;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 眼睛的句子怎么出：路径与形状、相对开盘价与谁领先、报价与成本、赔率走势、波动，以及整份 state 怎么拼 */
class PredictionStateWriterTest {

    private static final long WS = 1_790_016_000L;

    private static Point at(long sec, String price) {
        return new Point(sec * 1000, new BigDecimal(price));
    }

    /** n 根 1m K 线，收盘价在 100 和 100.04 之间来回 */
    private static List<KlineBar> bars(int n) {
        List<KlineBar> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String close = i % 2 == 0 ? "100" : "100.04";
            bars.add(new KlineBar(i * 60_000L, i * 60_000L + 59_999, new BigDecimal("100"), new BigDecimal("101"),
                    new BigDecimal("99"), new BigDecimal(close), BigDecimal.ONE));
        }
        return bars;
    }

    // ==================== 各个词 ====================

    @Test
    void 开盘以来路径句子() {
        BigDecimal open = new BigDecimal("100");
        double sigma = 0.05;   // 0.05%：0.015% 以内算平
        List<Point> roseThenFlat = List.of(at(0, "100"), at(30, "100.10"), at(60, "100.10"));
        assertThat(PredictionStateWriter.sinceOpenSentence(roseThenFlat, open, sigma, 0, 60_000))
                .isEqualTo("rose early, flat since");
        List<Point> roseThenReversed = List.of(at(0, "100"), at(30, "100.10"), at(60, "99.95"));
        assertThat(PredictionStateWriter.sinceOpenSentence(roseThenReversed, open, sigma, 0, 60_000))
                .isEqualTo("rose early, then reversed below the open");
        List<Point> fellThenRecovered = List.of(at(0, "100"), at(30, "99.80"), at(60, "99.90"));
        assertThat(PredictionStateWriter.sinceOpenSentence(fellThenRecovered, open, sigma, 0, 60_000))
                .isEqualTo("fell early, then recovered part of it");
        List<Point> flat = List.of(at(0, "100"), at(30, "100.005"), at(60, "100.01"));
        assertThat(PredictionStateWriter.sinceOpenSentence(flat, open, sigma, 0, 60_000)).isEqualTo("flat since the open");
    }

    @Test
    void 路径形状_一跳_台阶_来回_平盘不给() {
        BigDecimal open = new BigDecimal("100");
        double sigma = 0.05;
        List<Point> jump = List.of(at(0, "100"), at(30, "100.00"), at(35, "100.10"), at(90, "100.10"));
        assertThat(PredictionStateWriter.shapeWord(jump, open, sigma)).isEqualTo("one jump");
        List<Point> steps = List.of(at(0, "100"), at(30, "100.025"), at(60, "100.05"), at(90, "100.075"), at(120, "100.10"));
        assertThat(PredictionStateWriter.shapeWord(steps, open, sigma)).isEqualTo("in steps");
        List<Point> swing = List.of(at(0, "100"), at(30, "100.15"), at(60, "99.90"), at(90, "100.02"));
        assertThat(PredictionStateWriter.shapeWord(swing, open, sigma)).isEqualTo("back and forth");
        List<Point> flat = List.of(at(0, "100"), at(30, "100.005"), at(60, "100.01"));
        assertThat(PredictionStateWriter.shapeWord(flat, open, sigma)).isNull();
    }

    @Test
    void 十秒内最大涨跌_往前看十秒时刻生效的价() {
        List<Point> pts = List.of(at(0, "100"), at(12, "100.05"), at(20, "100.12"));
        assertThat(PredictionStateWriter.biggestMovePct(pts, 10_000)).isCloseTo(0.12, within(1e-9));
        List<Point> slow = List.of(at(0, "100"), at(15, "100.03"), at(30, "100.06"));
        assertThat(PredictionStateWriter.biggestMovePct(slow, 10_000)).isCloseTo(0.03, within(1e-6));
    }

    @Test
    void 最近一分钟方向带涨跌美元() {
        List<Point> pts = List.of(at(0, "86000"), at(30, "86010"), at(90, "86040"));
        assertThat(PredictionStateWriter.lastMinutePhrase(pts, 90_000, 0.02)).isEqualTo("rising (+$30)");
        List<Point> flat = List.of(at(0, "86000"), at(90, "85998"));
        assertThat(PredictionStateWriter.lastMinutePhrase(flat, 90_000, 0.02)).isEqualTo("flat (-$2)");
        List<Point> still = List.of(at(0, "86000"), at(90, "85999.7"));
        assertThat(PredictionStateWriter.lastMinutePhrase(still, 90_000, 0.02)).isEqualTo("flat (+$0)");
    }

    @Test
    void 相对开盘均价_死区里算在开盘价上() {
        BigDecimal open = new BigDecimal("86000");
        assertThat(PredictionStateWriter.gapPhrase(open, new BigDecimal("86037"), 0.05))
                .isEqualTo("above the opening average by $37 (0.043%)");
        assertThat(PredictionStateWriter.gapPhrase(open, new BigDecimal("85990"), 0.05))
                .isEqualTo("below the opening average by $10 (0.012%)");
        // 0.0012% 不到 0.05 × 0.05% 的死区
        assertThat(PredictionStateWriter.gapPhrase(open, new BigDecimal("86001"), 0.05)).isEqualTo("at the opening average");
    }

    @Test
    void 领先带方向() {
        assertThat(PredictionStateWriter.leadPhrase(0.3)).startsWith("neither side clearly ahead");
        assertThat(PredictionStateWriter.leadPhrase(1.0)).isEqualTo("UP ahead by about one normal move for the time left");
        assertThat(PredictionStateWriter.leadPhrase(-2.0)).isEqualTo("DOWN ahead by a couple of normal moves for the time left");
    }

    @Test
    void 报价写成美分_成本连手续费() {
        // 0.62 + 0.07 × 0.62 × 0.38 = 0.6365
        assertThat(PredictionStateWriter.quotePhrase("UP", new BigDecimal("0.62"), new BigDecimal("0.60")))
                .isEqualTo("ask 62¢, bid 60¢; buying costs 63.6¢ a share with the fee and pays 100¢ if UP wins");
        assertThat(PredictionStateWriter.quotePhrase("DOWN", null, new BigDecimal("0.38")))
                .isEqualTo("nobody is selling DOWN right now; bid 38¢");
        assertThat(PredictionStateWriter.quotePhrase("DOWN", new BigDecimal("0.405"), null)).startsWith("ask 40.5¢, no bid; ");
    }

    @Test
    void 赔率怎么动带美分_不够三十秒不给() {
        long now = 100_000;
        List<Point> rose = List.of(new Point(60_000, new BigDecimal("0.50")), new Point(70_000, new BigDecimal("0.52")),
                new Point(99_000, new BigDecimal("0.61")));
        assertThat(PredictionStateWriter.oddsMovePhrase(rose, now)).isEqualTo("UP's price rose sharply over the last 30 seconds (+9¢)");
        List<Point> still = List.of(new Point(60_000, new BigDecimal("0.50")), new Point(99_000, new BigDecimal("0.51")));
        assertThat(PredictionStateWriter.oddsMovePhrase(still, now)).isEqualTo("prices barely moved over the last 30 seconds (+1¢)");
        List<Point> fell = List.of(new Point(60_000, new BigDecimal("0.50")), new Point(99_000, new BigDecimal("0.46")));
        assertThat(PredictionStateWriter.oddsMovePhrase(fell, now)).isEqualTo("UP's price fell a little over the last 30 seconds (-4¢)");
        List<Point> tooShort = List.of(new Point(85_000, new BigDecimal("0.50")), new Point(99_000, new BigDecimal("0.60")));
        assertThat(PredictionStateWriter.oddsMovePhrase(tooShort, now)).isNull();
        assertThat(PredictionStateWriter.oddsMovePhrase(List.of(), now)).isNull();
    }

    @Test
    void 一分钟典型波动与最近实际波动() {
        // 每根来回 0.04%：中位数 0.04 × 1.4826 ≈ 0.059
        assertThat(PredictionStateWriter.sigma1mPct(bars(30))).isBetween(0.055, 0.065);

        // 每 10 秒来回 0.02%：10 秒涨跌均方根 0.02%，折成 1 分钟 × √6 ≈ 0.049%
        List<Point> ticks = new ArrayList<>();
        for (int i = 0; i <= 18; i++) {
            ticks.add(at(i * 10L, i % 2 == 0 ? "100" : "100.02"));
        }
        assertThat(PredictionStateWriter.recentSigma1mPct(ticks, 180_000)).isCloseTo(0.049, within(0.001));
        // 不到 6 段不算
        assertThat(PredictionStateWriter.recentSigma1mPct(List.of(at(150, "100"), at(170, "100.02")), 180_000)).isEqualTo(0);
    }

    // ==================== 整份 state ====================

    private record Deps(CacheService cache, KlineFetcher klines, OrderFlowAggregator flow, ForceOrderService force,
                        PredictionStateWriter writer) {
    }

    /** 开盘价 100、开盘后一路涨到 100.05、盘口和逐笔流都新鲜 */
    private static Deps deps(long nowSec) {
        CacheService cache = mock(CacheService.class);
        KlineFetcher klines = mock(KlineFetcher.class);
        OrderFlowAggregator flow = mock(OrderFlowAggregator.class);
        ForceOrderService force = mock(ForceOrderService.class);
        PredictionStateWriter writer = new PredictionStateWriter(cache, klines, flow, force);
        writer.nowMs = () -> nowSec * 1000;
        when(cache.getPolymarketOpenPrice(WS)).thenReturn(new BigDecimal("100"));
        List<Point> ticks = new ArrayList<>();
        for (long s = WS - 60; s <= nowSec; s += 5) {
            double p = s < WS ? 100 : 100 + 0.05 * Math.min(1.0, (s - WS) / (double) (nowSec - WS));
            ticks.add(new Point(s * 1000, BigDecimal.valueOf(p)));
        }
        when(cache.getBtcPricePoints(anyLong())).thenReturn(ticks);
        when(cache.getPredictionAsk("UP")).thenReturn(new BigDecimal("0.62"));
        when(cache.getPredictionBid("UP")).thenReturn(new BigDecimal("0.60"));
        when(cache.getPredictionAsk("DOWN")).thenReturn(new BigDecimal("0.40"));
        when(cache.getPredictionBid("DOWN")).thenReturn(new BigDecimal("0.38"));
        when(cache.getPredictionBookUpdatedAt()).thenReturn(nowSec * 1000 - 800);
        when(cache.getPredictionUpMidPoints(anyLong())).thenReturn(List.of(
                new Point((nowSec - 40) * 1000, new BigDecimal("0.55")), new Point((nowSec - 1) * 1000, new BigDecimal("0.61"))));
        when(klines.fetch(eq("BTCUSDT"), eq("1m"), anyInt())).thenReturn(bars(61));
        when(flow.getLastUpdateMs("BTCUSDT")).thenReturn(nowSec * 1000 - 500);
        when(flow.getMetrics("BTCUSDT", 60)).thenReturn(new OrderFlowAggregator.Metrics(0.35, 12, 0.5, 2_000_000, 600));
        when(flow.priceChange("BTCUSDT", 10)).thenReturn(12.0);
        when(flow.priceChange("BTCUSDT", 30)).thenReturn(-30.0);
        ForceOrder shortLiq = new ForceOrder();
        shortLiq.setSide("BUY");
        shortLiq.setAmount(new BigDecimal("80000"));
        shortLiq.setTradeTime(LocalDateTime.ofInstant(Instant.ofEpochSecond(WS + 20), ZoneId.systemDefault()));
        ForceOrder beforeOpen = new ForceOrder();
        beforeOpen.setSide("SELL");
        beforeOpen.setAmount(new BigDecimal("900000"));
        beforeOpen.setTradeTime(LocalDateTime.ofInstant(Instant.ofEpochSecond(WS - 30), ZoneId.systemDefault()));
        when(force.getRecent(eq("BTCUSDT"), anyInt())).thenReturn(List.of(shortLiq, beforeOpen));
        return new Deps(cache, klines, flow, force, writer);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 五块都给_只有事实没有公平价的结论() {
        Deps d = deps(WS + 150);
        Snapshot snap = d.writer().write(WS);

        Map<String, Object> s = snap.state();
        assertThat(s).containsOnlyKeys("market", "clock", "btc", "binance_flow", "odds");
        assertThat(s.get("clock")).isEqualTo("middle: one to three minutes left; 147 seconds until the settlement average is fixed");
        Map<String, Object> btc = (Map<String, Object>) s.get("btc");
        assertThat((String) btc.get("vs_open")).startsWith("above the opening average by ");
        assertThat((String) btc.get("lead")).startsWith("UP ahead");
        assertThat(btc).containsKeys("since_open", "last_minute", "pace").doesNotContainKey("settlement_so_far");
        assertThat(btc.get("latest")).isEqualTo("Chainlink, which settles the market, last updated 0 seconds ago; "
                + "on Binance BTC moved +$12 in the last 10 seconds and -$30 in the last 30 seconds");
        Map<String, Object> flow = (Map<String, Object>) s.get("binance_flow");
        assertThat(flow.get("takers")).isEqualTo("buyers ahead in the last minute (taker buys 68% of volume)");
        assertThat(flow.get("large_trades")).isEqualTo("mostly buys in the last minute");
        // 开盘前那笔多头强平不算
        assertThat(flow.get("liquidations")).isEqualTo("shorts liquidated since the open");
        Map<String, Object> odds = (Map<String, Object>) s.get("odds");
        assertThat(odds.get("standing")).isEqualTo("UP is a slight favourite; the market prices UP at about 61%");
        assertThat((String) odds.get("up")).startsWith("ask 62¢, bid 60¢; ");
        assertThat((String) odds.get("down")).startsWith("ask 40¢, bid 38¢; ");
        assertThat(odds.get("odds_move")).isEqualTo("UP's price rose a little over the last 30 seconds (+6¢)");
        assertThat(s.toString()).doesNotContain("cheap", "expensive");

        assertThat(snap.raw().pModel()).isGreaterThan(0.5);
        assertThat(snap.raw().bookUpdatedAtMs()).isEqualTo((WS + 150) * 1000 - 800);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 末分钟说锁了多少_逐笔流停了不给Binance() {
        Deps d = deps(WS + 270);
        when(d.flow().getLastUpdateMs("BTCUSDT")).thenReturn((WS + 200) * 1000L);

        Snapshot snap = d.writer().write(WS);

        Map<String, Object> s = snap.state();
        assertThat(s.get("clock")).isEqualTo("final minute: about half of the settlement average is already set; "
                + "27 seconds until the settlement average is fixed");
        Map<String, Object> btc = (Map<String, Object>) s.get("btc");
        assertThat(btc).containsKey("settlement_so_far");
        assertThat(btc.get("latest")).isEqualTo("Chainlink, which settles the market, last updated 0 seconds ago");
        Map<String, Object> flow = (Map<String, Object>) s.get("binance_flow");
        assertThat(flow).doesNotContainKeys("takers", "large_trades").containsKey("liquidations");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 末分钟现价刚跌破开盘价_已锁定部分仍在上方_UP领先() {
        Deps d = deps(WS + 280);
        // 末分钟从 WS+237 起：到 WS+277 一直在 100.10，最后三秒跌到 99.99
        List<Point> ticks = new ArrayList<>();
        for (long sec = WS - 60; sec <= WS + 280; sec++) {
            String p = sec < WS + 237 ? "100" : sec < WS + 278 ? "100.10" : "99.99";
            ticks.add(at(sec, p));
        }
        when(d.cache().getBtcPricePoints(anyLong())).thenReturn(ticks);

        Map<String, Object> btc = (Map<String, Object>) d.writer().write(WS).state().get("btc");

        assertThat((String) btc.get("vs_open")).startsWith("below the opening average");
        assertThat((String) btc.get("settlement_so_far")).contains("above the opening average");
        assertThat((String) btc.get("lead")).startsWith("UP ahead");
    }

    @Test
    void 缺开盘价_缺K线_Chainlink停了都不问() {
        Deps noOpen = deps(WS + 150);
        when(noOpen.cache().getPolymarketOpenPrice(WS)).thenReturn(null);
        assertThatThrownBy(() -> noOpen.writer().write(WS)).hasMessageContaining("开盘价未到");

        Deps noBars = deps(WS + 150);
        when(noBars.klines().fetch(eq("BTCUSDT"), eq("1m"), anyInt())).thenReturn(List.of());
        assertThatThrownBy(() -> noBars.writer().write(WS)).hasMessageContaining("K 线取不到");

        Deps stale = deps(WS + 150);
        when(stale.cache().getBtcPricePoints(anyLong())).thenReturn(List.of(
                new Point((WS + 10) * 1000, new BigDecimal("100")), new Point((WS + 100) * 1000, new BigDecimal("100.02"))));
        assertThatThrownBy(() -> stale.writer().write(WS)).hasMessageContaining("Chainlink 价停了");
    }
}
