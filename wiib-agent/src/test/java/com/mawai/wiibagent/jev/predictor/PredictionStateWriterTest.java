package com.mawai.wiibagent.jev.predictor;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.OrderFlowAggregator;
import com.mawai.wiibcommon.market.OrderFlowAggregator.Metrics;
import com.mawai.wiibcommon.market.TimeWeightedAverage.Point;
import com.mawai.wiibagent.jev.predictor.PredictionStateWriter.OddsJump;
import com.mawai.wiibagent.jev.predictor.PredictionStateWriter.Snapshot;
import com.mawai.wiibquant.market.service.KlineFetcher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** state 的句子怎么出：步怎么切、每步的涨跌档与主动成交、赔率历史、突变句，UP / DOWN 对调时句子也对调，以及整份 state 怎么拼 */
class PredictionStateWriterTest {

    private static final long WS = 1_790_016_000L;
    private static final long WS_MS = WS * 1000;
    private static final BigDecimal OPEN = new BigDecimal("86000");

    private static Point at(long sec, String price) {
        return new Point(sec * 1000, new BigDecimal(price));
    }

    /** n 根 1m K 线，收盘价在 100 和 100.04 之间来回：1 分钟典型波动 0.04% × 1.4826 */
    private static List<KlineBar> bars(int n) {
        List<KlineBar> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String close = i % 2 == 0 ? "100" : "100.04";
            bars.add(new KlineBar(i * 60_000L, i * 60_000L + 59_999, new BigDecimal("100"), new BigDecimal("101"),
                    new BigDecimal("99"), new BigDecimal(close), BigDecimal.ONE));
        }
        return bars;
    }

    // ==================== 步和各个词 ====================

    @Test
    void story的步_从现在往回每三十秒一步_开头不到十五秒并进下一步() {
        assertThat(PredictionStateWriter.storyBounds(WS_MS, WS_MS + 30_000)).containsExactly(WS_MS, WS_MS + 30_000);
        assertThat(PredictionStateWriter.storyBounds(WS_MS, WS_MS + 44_000)).containsExactly(WS_MS, WS_MS + 44_000);
        assertThat(PredictionStateWriter.storyBounds(WS_MS, WS_MS + 45_000)).containsExactly(WS_MS, WS_MS + 15_000, WS_MS + 45_000);
        assertThat(PredictionStateWriter.storyBounds(WS_MS, WS_MS + 57_000)).containsExactly(WS_MS, WS_MS + 27_000, WS_MS + 57_000);
        // 97 秒：往回切到 7 秒，不到 15 秒并进去，第一步 37 秒
        assertThat(PredictionStateWriter.storyBounds(WS_MS, WS_MS + 97_000))
                .containsExactly(WS_MS, WS_MS + 37_000, WS_MS + 67_000, WS_MS + 97_000);
        long[] full = PredictionStateWriter.storyBounds(WS_MS, WS_MS + 270_000);
        assertThat(full).hasSize(10);
        for (int k = 0; k < full.length; k++) {
            assertThat(full[k]).isEqualTo(WS_MS + k * 30_000L);
        }
    }

    @Test
    void 一步的涨跌_按三十秒正常波动分四档_带美元() {
        double normal = 30;
        assertThat(PredictionStateWriter.movePhrase(5, normal)).isEqualTo("barely moved (+$5)");
        assertThat(PredictionStateWriter.movePhrase(0.4, normal)).isEqualTo("barely moved ($0)");
        assertThat(PredictionStateWriter.movePhrase(9, normal)).isEqualTo("rose a little (+$9)");
        assertThat(PredictionStateWriter.movePhrase(-12, normal)).isEqualTo("fell a little (-$12)");
        assertThat(PredictionStateWriter.movePhrase(30, normal)).isEqualTo("rose (+$30)");
        assertThat(PredictionStateWriter.movePhrase(-45, normal)).isEqualTo("fell (-$45)");
        assertThat(PredictionStateWriter.movePhrase(60, normal)).isEqualTo("rose sharply (+$60)");
        assertThat(PredictionStateWriter.movePhrase(-60, normal)).isEqualTo("fell sharply (-$60)");
    }

    @Test
    void 主动成交_按买占比取整_两边对调时p对100减p() {
        assertThat(PredictionStateWriter.takersPhrase(0.24)).isEqualTo("buyers ahead among Binance takers (62% buys)");
        assertThat(PredictionStateWriter.takersPhrase(-0.24)).isEqualTo("sellers ahead among Binance takers (38% buys)");
        assertThat(PredictionStateWriter.takersPhrase(0.2)).isEqualTo("buyers ahead among Binance takers (60% buys)");
        assertThat(PredictionStateWriter.takersPhrase(-0.2)).isEqualTo("sellers ahead among Binance takers (40% buys)");
        assertThat(PredictionStateWriter.takersPhrase(0.18)).isEqualTo("Binance takers balanced (59% buys)");
        assertThat(PredictionStateWriter.takersPhrase(-0.18)).isEqualTo("Binance takers balanced (41% buys)");
        // 买占比落在半个百分点上，两边往离 50 远的方向进，还是对称
        assertThat(PredictionStateWriter.takersPhrase(0.25)).endsWith("(63% buys)");
        assertThat(PredictionStateWriter.takersPhrase(-0.25)).endsWith("(37% buys)");
        assertThat(PredictionStateWriter.takersPhrase(0)).isEqualTo("Binance takers balanced (50% buys)");
    }

    @Test
    void 美元_按绝对值取整两边对称_零不带正负号() {
        assertThat(PredictionStateWriter.signedUsd(18.4)).isEqualTo("+$18");
        assertThat(PredictionStateWriter.signedUsd(-7.6)).isEqualTo("-$8");
        assertThat(PredictionStateWriter.signedUsd(2.5)).isEqualTo("+$3");
        assertThat(PredictionStateWriter.signedUsd(-2.5)).isEqualTo("-$3");
        assertThat(PredictionStateWriter.signedUsd(0.4)).isEqualTo("$0");
        assertThat(PredictionStateWriter.signedUsd(-0.4)).isEqualTo("$0");
    }

    @Test
    void 离开盘均价多远_死区里算在开盘价上() {
        assertThat(PredictionStateWriter.posPhrase(OPEN, new BigDecimal("86037"), 0.05)).isEqualTo("$37 above the opening average");
        assertThat(PredictionStateWriter.posPhrase(OPEN, new BigDecimal("85963"), 0.05)).isEqualTo("$37 below the opening average");
        // 0.0012% 不到 0.05 × 0.05% 的死区
        assertThat(PredictionStateWriter.posPhrase(OPEN, new BigDecimal("86001"), 0.05)).isEqualTo("at the opening average");
    }

    @Test
    void 领先带方向() {
        assertThat(PredictionStateWriter.leadPhrase(0.3)).startsWith("neither side clearly ahead");
        assertThat(PredictionStateWriter.leadPhrase(1.0)).isEqualTo("UP ahead by about one normal move for the time left");
        assertThat(PredictionStateWriter.leadPhrase(-2.0)).isEqualTo("DOWN ahead by a couple of normal moves for the time left");
    }

    @Test
    void 赔率历史_每步结束时的价_那一刻之前没有采样就跳过这一步() {
        long[] bounds = {WS_MS, WS_MS + 37_000, WS_MS + 67_000, WS_MS + 97_000};
        List<Point> mids = List.of(at(WS + 40, "0.55"), at(WS + 60, "0.605"), at(WS + 96, "0.62"));
        assertThat(PredictionStateWriter.oddsHistory(bounds, mids))
                .containsExactly("Step 2: UP 60.5¢, DOWN 39.5¢", "Step 3, the latest: UP 62¢, DOWN 38¢");
        // 开盘之前的采样不算
        assertThat(PredictionStateWriter.sampleAt(List.of(at(WS - 1, "0.5")), WS_MS, WS_MS + 37_000)).isNull();
    }

    @Test
    void 突变_按涨的那一边写() {
        OddsJump up = new OddsJump(WS_MS + 94_000, WS_MS + 96_000, new BigDecimal("0.40"), new BigDecimal("0.58"));
        assertThat(up.side()).isEqualTo("UP");
        assertThat(up.sideFrom()).isEqualByComparingTo("0.40");
        assertThat(up.sideTo()).isEqualByComparingTo("0.58");
        assertThat(PredictionStateWriter.jumpPhrase(up, new BigDecimal("0.58"), WS_MS + 97_000))
                .isEqualTo("UP's price jumped from 40¢ to 58¢ within 2 seconds (1 second ago); DOWN's price dropped from 60¢ to 42¢. "
                        + "UP is 58¢ now and DOWN is 42¢");
        // UP 中间价跌 = DOWN 涨，按 DOWN 自己的价写
        OddsJump down = new OddsJump(WS_MS + 93_000, WS_MS + 96_000, new BigDecimal("0.60"), new BigDecimal("0.42"));
        assertThat(down.side()).isEqualTo("DOWN");
        assertThat(down.sideFrom()).isEqualByComparingTo("0.40");
        assertThat(down.sideTo()).isEqualByComparingTo("0.58");
        assertThat(PredictionStateWriter.jumpPhrase(down, new BigDecimal("0.43"), WS_MS + 98_400))
                .isEqualTo("DOWN's price jumped from 40¢ to 58¢ within 3 seconds (2 seconds ago); UP's price dropped from 60¢ to 42¢. "
                        + "DOWN is 57¢ now and UP is 43¢");
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

    private record Deps(CacheService cache, KlineFetcher klines, OrderFlowAggregator flow, PredictionStateWriter writer) {
    }

    /**
     * 开盘后 97 秒，开盘均价 86000，BTC 每秒一跳（dir = −1 时绕开盘均价对调）：
     * 前 37 秒涨 $50、再 30 秒跌 $5、最后 30 秒涨 $90；1 分钟典型波动 0.0593%（30 秒约 $36）。
     * 三步的主动成交：62% 买、不到 10 笔、25% 买（对调时 38%、不到 10 笔、75%）。
     * UP 中间价：开盘后 1 秒 0.50，30 秒 0.58，60 秒 0.555，96 秒 0.62（对调时 1 − 价）
     */
    private static Deps deps(int dir) {
        long now = WS + 97;
        CacheService cache = mock(CacheService.class);
        KlineFetcher klines = mock(KlineFetcher.class);
        OrderFlowAggregator flow = mock(OrderFlowAggregator.class);
        PredictionStateWriter writer = new PredictionStateWriter(cache, klines, flow);
        writer.nowMs = () -> now * 1000;
        when(cache.getPolymarketOpenPrice(WS)).thenReturn(OPEN);
        List<Point> ticks = new ArrayList<>();
        for (long s = WS - 180; s <= now; s++) {
            long e = s - WS;
            double gap = e <= 0 ? 0 : e <= 37 ? 50.0 * e / 37 : e <= 67 ? 50 - 5.0 * (e - 37) / 30 : 45 + 90.0 * (e - 67) / 30;
            ticks.add(new Point(s * 1000, BigDecimal.valueOf(86000 + dir * gap).setScale(2, RoundingMode.HALF_UP)));
        }
        when(cache.getBtcPricePoints(anyLong())).thenReturn(ticks);
        when(cache.getPredictionAsk("UP")).thenReturn(new BigDecimal("0.62"));
        when(cache.getPredictionBid("UP")).thenReturn(new BigDecimal("0.60"));
        when(cache.getPredictionAsk("DOWN")).thenReturn(new BigDecimal("0.40"));
        when(cache.getPredictionBid("DOWN")).thenReturn(new BigDecimal("0.38"));
        when(cache.getPredictionBookUpdatedAt()).thenReturn(now * 1000 - 800);
        when(cache.getPredictionUpMidPoints(anyLong())).thenReturn(List.of(
                mid(WS + 1, "0.50", dir), mid(WS + 30, "0.58", dir), mid(WS + 60, "0.555", dir), mid(WS + 96, "0.62", dir)));
        when(klines.fetch(eq("BTCUSDT"), eq("1m"), anyInt())).thenReturn(bars(61));
        when(flow.getLastUpdateMs("BTCUSDT")).thenReturn(now * 1000 - 500);
        when(flow.getMetricsBetween(eq("BTCUSDT"), any())).thenReturn(List.of(
                new Metrics(dir * 0.24, 2, 0, 500_000, 50), new Metrics(dir * 0.9, 0.2, 0, 50_000, 6),
                new Metrics(dir * -0.5, 3, 0, 900_000, 80)));
        return new Deps(cache, klines, flow, writer);
    }

    /** UP 中间价采样，dir = −1 时是 1 − 价 */
    private static Point mid(long sec, String up, int dir) {
        BigDecimal p = new BigDecimal(up);
        return new Point(sec * 1000, dir > 0 ? p : BigDecimal.ONE.subtract(p));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 整点_只有这几个键_story按步写_赔率历史和步对应() {
        Deps d = deps(1);
        Snapshot snap = d.writer().write(WS, null);

        Map<String, Object> s = snap.state();
        assertThat(s.keySet()).containsExactly("market", "clock", "btc_now", "lead", "story", "odds_history");
        assertThat(s.get("market")).isEqualTo("Polymarket 5-minute BTC market. UP wins if BTC's average price over the final "
                + "minute is at or above its average at the open; otherwise DOWN wins.");
        assertThat(s.get("clock")).isEqualTo("early: more than three minutes left; 200 seconds until the settlement average is fixed");
        assertThat(s.get("btc_now")).isEqualTo("BTC is $135 above the opening average");
        assertThat(s.get("lead")).isEqualTo(PredictionStateWriter.leadPhrase(snap.raw().zModel()));
        assertThat((String) s.get("lead")).startsWith("UP ahead");
        assertThat((List<String>) s.get("story")).containsExactly(
                "Step 1 (first 37 seconds): BTC rose (+$50), ending $50 above the opening average; "
                        + "buyers ahead among Binance takers (62% buys)",
                "Step 2 (next 30 seconds): BTC barely moved (-$5), ending $45 above the opening average",
                "Step 3, the latest (next 30 seconds): BTC rose sharply (+$90), ending $135 above the opening average; "
                        + "sellers ahead among Binance takers (25% buys)");
        assertThat((List<String>) s.get("odds_history")).containsExactly(
                "Step 1: UP 58¢, DOWN 42¢", "Step 2: UP 55.5¢, DOWN 44.5¢", "Step 3, the latest: UP 62¢, DOWN 38¢");
        // 主动成交整份 state 只读一次，按步的边界
        ArgumentCaptor<long[]> bounds = ArgumentCaptor.forClass(long[].class);
        verify(d.flow()).getMetricsBetween(eq("BTCUSDT"), bounds.capture());
        assertThat(bounds.getValue()).containsExactly(WS_MS, WS_MS + 37_000, WS_MS + 67_000, WS_MS + 97_000);
        verify(d.cache()).getPredictionUpMidPoints(WS_MS);

        assertThat(snap.raw().zModel()).isGreaterThan(0);
        assertThat(snap.raw().pModel()).isGreaterThan(0.5);
        assertThat(snap.raw().bookUpdatedAtMs()).isEqualTo((WS + 97) * 1000 - 800);
        assertThat(snap.raw().chainlinkAgeMs()).isZero();
        assertThat(snap.raw().book().upAsk()).isEqualByComparingTo("0.62");
        assertThat(snap.raw().jump()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 镜像_UP和DOWN对调后句子跟着对调() {
        Snapshot snap = deps(-1).writer().write(WS, null);

        Map<String, Object> s = snap.state();
        assertThat(s.get("btc_now")).isEqualTo("BTC is $135 below the opening average");
        assertThat((String) s.get("lead")).startsWith("DOWN ahead");
        assertThat(s.get("lead")).isEqualTo(deps(1).writer().write(WS, null).state().get("lead").toString().replace("UP", "DOWN"));
        assertThat((List<String>) s.get("story")).containsExactly(
                "Step 1 (first 37 seconds): BTC fell (-$50), ending $50 below the opening average; "
                        + "sellers ahead among Binance takers (38% buys)",
                "Step 2 (next 30 seconds): BTC barely moved (+$5), ending $45 below the opening average",
                "Step 3, the latest (next 30 seconds): BTC fell sharply (-$90), ending $135 below the opening average; "
                        + "buyers ahead among Binance takers (75% buys)");
        assertThat((List<String>) s.get("odds_history")).containsExactly(
                "Step 1: UP 42¢, DOWN 58¢", "Step 2: UP 44.5¢, DOWN 55.5¢", "Step 3, the latest: UP 38¢, DOWN 62¢");
    }

    @Test
    void 突变唤醒_多一个jump键_两边都写() {
        Deps up = deps(1);
        OddsJump jump = new OddsJump(WS_MS + 94_000, WS_MS + 96_000, new BigDecimal("0.42"), new BigDecimal("0.62"));
        Snapshot snap = up.writer().write(WS, jump);

        assertThat(snap.state().keySet()).containsExactly("market", "clock", "btc_now", "lead", "story", "odds_history", "jump");
        assertThat(snap.state().get("jump")).isEqualTo("UP's price jumped from 42¢ to 62¢ within 2 seconds (1 second ago); "
                + "DOWN's price dropped from 58¢ to 38¢. UP is 62¢ now and DOWN is 38¢");
        assertThat(snap.raw().jump()).isSameAs(jump);

        OddsJump mirrored = new OddsJump(WS_MS + 94_000, WS_MS + 96_000, new BigDecimal("0.58"), new BigDecimal("0.38"));
        assertThat(deps(-1).writer().write(WS, mirrored).state().get("jump"))
                .isEqualTo("DOWN's price jumped from 42¢ to 62¢ within 2 seconds (1 second ago); "
                        + "UP's price dropped from 58¢ to 38¢. DOWN is 62¢ now and UP is 38¢");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 逐笔流停了_各步都不写主动成交_也不去读() {
        Deps d = deps(1);
        when(d.flow().getLastUpdateMs("BTCUSDT")).thenReturn((WS + 60) * 1000L);

        List<String> story = (List<String>) d.writer().write(WS, null).state().get("story");

        assertThat(story).hasSize(3).noneMatch(line -> line.contains("takers"));
        assertThat(story.getLast()).isEqualTo("Step 3, the latest (next 30 seconds): BTC rose sharply (+$90), "
                + "ending $135 above the opening average");
        verify(d.flow(), never()).getMetricsBetween(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 末分钟_说锁了多少_给已锁定部分_九步() {
        Deps d = deps(1);
        long now = WS + 270;
        d.writer().nowMs = () -> now * 1000;
        List<Point> ticks = new ArrayList<>();
        for (long sec = WS - 60; sec <= now; sec++) {
            ticks.add(at(sec, sec < WS ? "86000" : "86040"));
        }
        when(d.cache().getBtcPricePoints(anyLong())).thenReturn(ticks);
        when(d.flow().getLastUpdateMs("BTCUSDT")).thenReturn(now * 1000 - 500);
        // 前八步没成交，最后一步 65% 买
        List<Metrics> flows = new ArrayList<>();
        for (int k = 0; k < 8; k++) {
            flows.add(new Metrics(0, 0, 0, 0, 0));
        }
        flows.add(new Metrics(0.3, 1.5, 0, 1_000_000, 40));
        when(d.flow().getMetricsBetween(eq("BTCUSDT"), any())).thenReturn(flows);

        Map<String, Object> s = d.writer().write(WS, null).state();

        assertThat(s.keySet()).containsExactly("market", "clock", "btc_now", "lead", "settlement_so_far", "story", "odds_history");
        assertThat(s.get("clock")).isEqualTo("final minute: about half of the settlement average is already set; "
                + "27 seconds until the settlement average is fixed");
        assertThat(s.get("settlement_so_far")).isEqualTo("the part of the settlement average already set is $40 above the opening average");
        List<String> story = (List<String>) s.get("story");
        assertThat(story).hasSize(9);
        assertThat(story.getFirst()).isEqualTo("Step 1 (first 30 seconds): BTC rose (+$40), ending $40 above the opening average");
        assertThat(story.getLast()).isEqualTo("Step 9, the latest (next 30 seconds): BTC barely moved ($0), "
                + "ending $40 above the opening average; buyers ahead among Binance takers (65% buys)");
    }

    @Test
    void 缺开盘价_缺K线_本回合没有tick都不问_Chainlink停了不抛_年龄交给回路() {
        Deps noOpen = deps(1);
        when(noOpen.cache().getPolymarketOpenPrice(WS)).thenReturn(null);
        assertThatThrownBy(() -> noOpen.writer().write(WS, null)).hasMessageContaining("开盘价未到");

        Deps noBars = deps(1);
        when(noBars.klines().fetch(eq("BTCUSDT"), eq("1m"), anyInt())).thenReturn(List.of());
        assertThatThrownBy(() -> noBars.writer().write(WS, null)).hasMessageContaining("K 线取不到");

        Deps noTick = deps(1);
        when(noTick.cache().getBtcPricePoints(anyLong())).thenReturn(List.of(at(WS - 5, "86000")));
        assertThatThrownBy(() -> noTick.writer().write(WS, null)).hasMessageContaining("还没有 Chainlink tick");

        Deps stale = deps(1);
        when(stale.cache().getBtcPricePoints(anyLong())).thenReturn(List.of(at(WS + 10, "86000"), at(WS + 47, "86020")));
        assertThat(stale.writer().write(WS, null).raw().chainlinkAgeMs()).isEqualTo(50_000);
    }
}
