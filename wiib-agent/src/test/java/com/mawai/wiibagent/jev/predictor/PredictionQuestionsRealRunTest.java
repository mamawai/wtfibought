package com.mawai.wiibagent.jev.predictor;

import com.mawai.wiibagent.jev.JevClient;
import com.mawai.wiibagent.jev.JevPlatformConfig;
import com.mawai.wiibagent.jev.predictor.PredictionJudge.Judgment;
import com.mawai.wiibagent.jev.predictor.PredictionStateWriter.OddsJump;
import com.mawai.wiibagent.jev.predictor.PredictionStateWriter.Raw;
import com.mawai.wiibagent.jev.predictor.PredictionStateWriter.Snapshot;
import com.mawai.wiibcommon.market.OrderFlowAggregator.Metrics;
import com.mawai.wiibcommon.market.TimeWeightedAverage.Point;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 六道题和 state 措辞的真跑验收：真调平台 Jev，局面用 {@link PredictionStateWriter} 的同一套句子拼。
 * 人工构造 9 种典型局面加 3 种带突变句的，每种再做一份 UP / DOWN 对调的镜像，题跟着换边。过线沿用试跑 A：
 * 对得上事先写好的预期 ≥ 80%、每道题单独 ≥ 70%（是非题 ≥ 0.70 算是、≤ 0.30 算否，中间算没答对）；
 * 镜像 pattern 选同一项 ≥ 80%，五道是非题各自的概率平均相差 ≤ 0.10。
 * <p>
 * 会烧真 token（24 次调用），默认跳过。跑法（项目根）：
 * <pre>
 * WIIB_REAL_RUN=1 JEV_API_KEY=... mvn test -pl wiib-agent -am -DskipTests=false \
 *   -Dtest=PredictionQuestionsRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
@EnabledIfEnvironmentVariable(named = "JEV_API_KEY", matches = ".+")
class PredictionQuestionsRealRunTest {

    private static final Logger log = LoggerFactory.getLogger(PredictionQuestionsRealRunTest.class);
    private static final BigDecimal OPEN = new BigDecimal("86000");
    /** 30 秒正常波动 $24，折成 1 分钟的美元和百分比 */
    private static final double NORMAL_30S_USD = 24;
    private static final double SIGMA1M_USD = NORMAL_30S_USD / Math.sqrt(0.5);
    private static final double SIGMA1M_PCT = SIGMA1M_USD / OPEN.doubleValue() * 100;
    /** 开盘后 180 秒：6 步各 30 秒，离结算均价截止 117 秒 */
    private static final int STEPS = 6;
    private static final long NOW = STEPS * 30_000L;
    private static final int LEFT = 117;
    private static final List<String> NOULS = List.of("win", "push_fading", "flow_confirms", "dip_recovered", "latest_against");

    private final PredictionJudge judge = new PredictionJudge(new JevClient(),
            new JevPlatformConfig(System.getenv("JEV_API_KEY"), JevClient.DEFAULT_BASE_URL, JevClient.DEFAULT_MODEL));

    /**
     * 一种局面，数都按 UP 那一边的世界给
     *
     * @param steps  6 步各自 BTC 涨跌多少美元
     * @param buys   6 步各自 Binance 主动买占比
     * @param expect 看 UP 时的预期：是非题 true / false，pattern 是选项；镜像看 DOWN，预期不变
     * @param jump   最后一步的赔率突变 {用了几秒, 几秒前}，从上一步的 UP 价跳到这一步的；没有为 null
     */
    private record Case(String name, int[] steps, int[] buys, Map<String, Object> expect, int[] jump) {
    }

    private static final List<Case> CASES = List.of(
            new Case("cascade", new int[]{30, 26, 35, 28, 32, 40}, new int[]{64, 66, 70, 68, 70, 72},
                    Map.of("pattern", "cascade", "dip_recovered", false, "push_fading", false, "flow_confirms", true,
                            "latest_against", false), null),
            new Case("chop", new int[]{40, -30, 28, -32, 30, -26}, new int[]{55, 46, 54, 45, 55, 47},
                    Map.of("pattern", "chop", "flow_confirms", false, "dip_recovered", true, "latest_against", true), null),
            new Case("dip_then_recover", new int[]{35, 30, -28, -10, 30, 38}, new int[]{62, 64, 40, 45, 63, 66},
                    Map.of("push_fading", false, "flow_confirms", true, "dip_recovered", true, "latest_against", false), null),
            new Case("pullback_in_progress", new int[]{38, 34, 30, 5, -26, -30}, new int[]{65, 66, 62, 52, 38, 35},
                    Map.of("push_fading", true, "flow_confirms", false, "dip_recovered", false, "latest_against", true), null),
            new Case("fading_push", new int[]{40, 36, 30, 8, 4, 2}, new int[]{68, 66, 60, 54, 51, 50},
                    Map.of("push_fading", true, "flow_confirms", false, "dip_recovered", false, "latest_against", false), null),
            new Case("accelerating", new int[]{3, 5, 8, 26, 38, 50}, new int[]{50, 52, 55, 62, 68, 74},
                    Map.of("pattern", "cascade", "push_fading", false, "flow_confirms", true, "dip_recovered", false,
                            "latest_against", false), null),
            new Case("quiet", new int[]{3, -2, 4, -3, 2, 1}, new int[]{50, 49, 51, 50, 50, 51},
                    Map.of("pattern", "neither", "flow_confirms", false, "latest_against", false), null),
            new Case("flow_against", new int[]{28, 30, 26, 32, 27, 34}, new int[]{38, 36, 35, 37, 34, 36},
                    Map.of("push_fading", false, "flow_confirms", false, "dip_recovered", false, "latest_against", false), null),
            new Case("lead_change", new int[]{-30, -28, 26, 34, 30, 36}, new int[]{38, 36, 60, 64, 62, 63},
                    Map.of("push_fading", false, "flow_confirms", true, "dip_recovered", true, "latest_against", false), null),
            new Case("jump_with_trend", new int[]{-20, -8, 6, 5, 6, 22}, new int[]{40, 44, 55, 58, 62, 72},
                    Map.of("push_fading", false, "flow_confirms", true, "dip_recovered", true, "latest_against", false),
                    new int[]{2, 1}),
            new Case("jump_flow_against", new int[]{-20, -8, 6, 5, 6, 22}, new int[]{42, 45, 44, 40, 38, 34},
                    Map.of("push_fading", false, "flow_confirms", false, "dip_recovered", true, "latest_against", false),
                    new int[]{3, 2}),
            new Case("jump_after_chop", new int[]{25, -30, 24, -28, -4, 24}, new int[]{58, 42, 57, 43, 47, 68},
                    Map.of("flow_confirms", true, "dip_recovered", true, "latest_against", false), new int[]{2, 1}));

    /** 按离开盘均价多远、剩多少秒定的 UP 价，取两位 */
    private static BigDecimal upMid(double gapUsd, int left) {
        double p = PredictionModel.p(gapUsd / (SIGMA1M_USD / Math.sqrt(60) * Math.sqrt(left - 60 + 20)));
        return BigDecimal.valueOf(Math.min(0.99, Math.max(0.01, p))).setScale(2, RoundingMode.HALF_UP);
    }

    /** 拼一份 state：dir = 1 是 UP 的世界，−1 是镜像（BTC 绕开盘均价对调、买占比 p → 100 − p、UP 价 → 1 − 价） */
    private static Snapshot snapshot(Case c, int dir) {
        long[] bounds = new long[STEPS + 1];
        List<Point> ticks = new ArrayList<>(List.of(new Point(0, OPEN)));
        List<Point> mids = new ArrayList<>();
        List<Metrics> flows = new ArrayList<>();
        double gapUp = 0;
        for (int k = 0; k < STEPS; k++) {
            long t = (k + 1) * 30_000L;
            bounds[k + 1] = t;
            gapUp += c.steps()[k];
            ticks.add(new Point(t, OPEN.add(BigDecimal.valueOf(dir * gapUp))));
            BigDecimal up = upMid(gapUp, 297 - (int) (t / 1000));
            mids.add(new Point(t, dir > 0 ? up : BigDecimal.ONE.subtract(up)));
            int buys = dir > 0 ? c.buys()[k] : 100 - c.buys()[k];
            flows.add(new Metrics((buys - 50) / 50.0, 3, 0, 1_000_000, 100));
        }
        double z = dir * gapUp / (SIGMA1M_USD / Math.sqrt(60) * Math.sqrt(LEFT - 60 + 20));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("market", PredictionStateWriter.GAME);
        state.put("clock", PredictionStateWriter.clockPhrase(LEFT) + "; " + LEFT + " seconds until the settlement average is fixed");
        state.put("btc_now", "BTC is " + PredictionStateWriter.posPhrase(OPEN, ticks.getLast().price(), SIGMA1M_PCT));
        state.put("lead", PredictionStateWriter.leadPhrase(z));
        state.put("story", PredictionStateWriter.story(bounds, ticks, OPEN, SIGMA1M_PCT, NORMAL_30S_USD, flows));
        state.put("odds_history", PredictionStateWriter.oddsHistory(bounds, mids));
        OddsJump jump = null;
        if (c.jump() != null) {
            long to = NOW - c.jump()[1] * 1000L;
            jump = new OddsJump(to - c.jump()[0] * 1000L, to, mids.get(STEPS - 2).price(), mids.getLast().price());
            state.put("jump", PredictionStateWriter.jumpPhrase(jump, mids.getLast().price(), NOW));
        }
        return new Snapshot(state, new Raw(z, PredictionModel.p(z), null, null, 0, jump));
    }

    /** 问一次，六个回答和 state 打日志 */
    private Judgment ask(Case c, int dir) {
        String side = dir > 0 ? "UP" : "DOWN";
        Snapshot snap = snapshot(c, dir);
        Judgment j = judge.judge(snap, side);
        log.info("{} {} pattern={} {} | {}", c.name(), side, pattern(j),
                NOULS.stream().map(q -> q + "=" + noul(j, q)).toList(), snap.state());
        return j;
    }

    /** ≥ 0.70 算是，≤ 0.30 算否，中间算没答 */
    private static Boolean band(double p) {
        return p >= 0.70 ? Boolean.TRUE : p <= 0.30 ? Boolean.FALSE : null;
    }

    private static double noul(Judgment j, String q) {
        return j.answers().get(q).noul();
    }

    private static String pattern(Judgment j) {
        return j.answers().get("pattern").choice();
    }

    @Test
    void 构造局面对得上预期_镜像答得一样() {
        Map<String, int[]> perQuestion = new LinkedHashMap<>();
        int samePattern = 0;
        Map<String, Double> mirrorDiff = new LinkedHashMap<>();
        for (Case c : CASES) {
            Judgment up = ask(c, 1);
            Judgment down = ask(c, -1);
            for (Judgment j : List.of(up, down)) {
                for (Map.Entry<String, Object> e : c.expect().entrySet()) {
                    boolean hit = "pattern".equals(e.getKey())
                            ? e.getValue().equals(pattern(j))
                            : Objects.equals(band(noul(j, e.getKey())), e.getValue());
                    int[] n = perQuestion.computeIfAbsent(e.getKey(), k -> new int[2]);
                    n[0] += hit ? 1 : 0;
                    n[1]++;
                }
            }
            samePattern += pattern(up).equals(pattern(down)) ? 1 : 0;
            for (String q : NOULS) {
                mirrorDiff.merge(q, Math.abs(noul(up, q) - noul(down, q)), Double::sum);
            }
        }

        int hit = perQuestion.values().stream().mapToInt(n -> n[0]).sum();
        int total = perQuestion.values().stream().mapToInt(n -> n[1]).sum();
        perQuestion.forEach((q, n) -> log.info("对得上 {}：{}/{}", q, n[0], n[1]));
        mirrorDiff.forEach((q, d) -> log.info("镜像 {} 概率平均相差 {}", q, d / CASES.size()));
        log.info("总体对得上 {}/{}；镜像 pattern 同一项 {}/{}", hit, total, samePattern, CASES.size());

        assertThat((double) hit / total).as("总体对得上").isGreaterThanOrEqualTo(0.8);
        perQuestion.forEach((q, n) -> assertThat((double) n[0] / n[1]).as("对得上 " + q).isGreaterThanOrEqualTo(0.7));
        assertThat((double) samePattern / CASES.size()).as("镜像 pattern 同一项").isGreaterThanOrEqualTo(0.8);
        mirrorDiff.forEach((q, d) -> assertThat(d / CASES.size()).as("镜像 " + q + " 概率平均相差").isLessThanOrEqualTo(0.10));
    }
}
