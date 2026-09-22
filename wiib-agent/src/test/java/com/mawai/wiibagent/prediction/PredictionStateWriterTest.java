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

/** 眼睛的词怎么出：路径与形状、划不划算、赔率走势、波动，以及整份 state 怎么拼 */
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
    void 最近一分钟方向() {
        List<Point> pts = List.of(at(0, "100"), at(30, "100.02"), at(90, "100.10"));
        assertThat(PredictionStateWriter.lastMinuteWord(pts, 90_000, 0.05)).isEqualTo("rising");
        List<Point> flat = List.of(at(0, "100"), at(90, "100.005"));
        assertThat(PredictionStateWriter.lastMinuteWord(flat, 90_000, 0.05)).isEqualTo("flat");
    }

    @Test
    void 划不划算按每份优势分五档_没有卖价是没人卖() {
        // 公平 0.74 买 0.62：0.74 − 0.62 − 0.0165 = 0.1035
        assertThat(PredictionStateWriter.valuePhrase("UP", 0.74, new BigDecimal("0.62")))
                .isEqualTo("UP looks clearly cheap against where BTC stands");
        // 0.0535
        assertThat(PredictionStateWriter.valuePhrase("UP", 0.69, new BigDecimal("0.62"))).contains("slightly cheap");
        // 0.0335 和 −0.0365 都还在合理档
        assertThat(PredictionStateWriter.valuePhrase("UP", 0.67, new BigDecimal("0.62"))).contains("fairly priced");
        assertThat(PredictionStateWriter.valuePhrase("DOWN", 0.60, new BigDecimal("0.62"))).contains("fairly priced");
        // −0.0865
        assertThat(PredictionStateWriter.valuePhrase("DOWN", 0.55, new BigDecimal("0.62"))).contains("slightly expensive");
        assertThat(PredictionStateWriter.valuePhrase("DOWN", 0.30, new BigDecimal("0.62"))).contains("clearly expensive");
        assertThat(PredictionStateWriter.valuePhrase("UP", 0.99, null)).isEqualTo("UP has no sellers right now");
    }

    @Test
    void 模型和盘口一致时热门冷门都算合理() {
        // 盘口 UP 0.75/0.76、DOWN 0.24/0.25，公平价取 mid 0.755：两边每份都约 −0.018
        assertThat(PredictionStateWriter.valuePhrase("UP", 0.755, new BigDecimal("0.76"))).contains("fairly priced");
        assertThat(PredictionStateWriter.valuePhrase("DOWN", 0.245, new BigDecimal("0.25"))).contains("fairly priced");
    }

    @Test
    void 赔率怎么动_不够三十秒不给() {
        long now = 100_000;
        List<Point> rose = List.of(new Point(60_000, new BigDecimal("0.50")), new Point(70_000, new BigDecimal("0.52")),
                new Point(99_000, new BigDecimal("0.61")));
        assertThat(PredictionStateWriter.oddsMovePhrase(rose, now)).isEqualTo("UP's price rose sharply over the last 30 seconds");
        List<Point> still = List.of(new Point(60_000, new BigDecimal("0.50")), new Point(99_000, new BigDecimal("0.51")));
        assertThat(PredictionStateWriter.oddsMovePhrase(still, now)).isEqualTo("prices barely moved over the last 30 seconds");
        List<Point> fell = List.of(new Point(60_000, new BigDecimal("0.50")), new Point(99_000, new BigDecimal("0.46")));
        assertThat(PredictionStateWriter.oddsMovePhrase(fell, now)).isEqualTo("UP's price fell a little over the last 30 seconds");
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
    void 五块都给_数字留在raw() {
        Deps d = deps(WS + 150);
        Snapshot snap = d.writer().write(WS);

        Map<String, Object> s = snap.state();
        assertThat(s).containsOnlyKeys("market", "clock", "btc", "binance_flow", "odds");
        assertThat(s.get("clock")).isEqualTo("middle: one to three minutes left");
        Map<String, Object> btc = (Map<String, Object>) s.get("btc");
        assertThat(btc.get("vs_open")).isEqualTo("above the opening average");
        assertThat(btc).containsKeys("lead", "since_open", "last_minute", "pace");
        Map<String, Object> flow = (Map<String, Object>) s.get("binance_flow");
        assertThat(flow.get("takers")).isEqualTo("buyers ahead in the last minute");
        assertThat(flow.get("large_trades")).isEqualTo("mostly buys in the last minute");
        // 开盘前那笔多头强平不算
        assertThat(flow.get("liquidations")).isEqualTo("shorts liquidated since the open");
        Map<String, Object> odds = (Map<String, Object>) s.get("odds");
        assertThat(odds.get("standing")).isEqualTo("UP is a slight favourite");
        assertThat((String) odds.get("up_value")).startsWith("UP looks");
        assertThat(odds.get("odds_move")).isEqualTo("UP's price rose a little over the last 30 seconds");

        assertThat(snap.raw().driftSign()).isEqualTo(1);
        assertThat(snap.raw().pModel()).isGreaterThan(0.5);
        assertThat(snap.raw().bookUpdatedAtMs()).isEqualTo((WS + 150) * 1000 - 800);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 末分钟说锁了多少_逐笔流停了不给主动买卖() {
        Deps d = deps(WS + 270);
        when(d.flow().getLastUpdateMs("BTCUSDT")).thenReturn((WS + 200) * 1000L);

        Snapshot snap = d.writer().write(WS);

        Map<String, Object> s = snap.state();
        assertThat(s.get("clock")).isEqualTo("final minute: about half of the settlement average is already set");
        Map<String, Object> flow = (Map<String, Object>) s.get("binance_flow");
        assertThat(flow).doesNotContainKeys("takers", "large_trades").containsKey("liquidations");
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
