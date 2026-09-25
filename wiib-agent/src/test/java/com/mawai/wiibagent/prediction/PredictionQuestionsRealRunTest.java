package com.mawai.wiibagent.prediction;

import com.mawai.wiibagent.llm.jev.JevClient;
import com.mawai.wiibagent.llm.jev.JevPlatformConfig;
import com.mawai.wiibagent.prediction.PredictionJudge.Judgment;
import com.mawai.wiibagent.prediction.PredictionRules.Book;
import com.mawai.wiibagent.prediction.PredictionStateWriter.Raw;
import com.mawai.wiibagent.prediction.PredictionStateWriter.Snapshot;
import com.mawai.wiibcommon.market.TimeWeightedAverage.Point;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 题目和 state 措辞的真跑验收：真调平台 Jev，情景用 {@link PredictionStateWriter} 的同一套句子拼。
 * 镜像：UP 领先的情景和把所有方向对调的镜像，选择要跟着对调（赔率突变的两种走势也各做镜像）；
 * 极端冷门：4¢ 那边不买；末分钟已锁一半在高位、现价快速回落时不追跌。持仓和空仓同一份 state、同一道题，不单独测。
 * <p>
 * 会烧真 token（8 次调用），默认跳过。跑法（项目根）：
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
    /** 1 分钟典型波动 0.06% */
    private static final double SIGMA = 0.06;
    private static final long NOW = 1_000_000;

    private final PredictionJudge judge = new PredictionJudge(new JevClient(),
            new JevPlatformConfig(System.getenv("JEV_API_KEY"), JevClient.DEFAULT_BASE_URL, JevClient.DEFAULT_MODEL));

    /**
     * BTC 一段，数都按 UP 那一边的世界给，dir = −1 时全部对调
     *
     * @param gapUsd  现价离开盘均价多少美元
     * @param z       领先相对剩余时间的正常波动
     * @param lastMin 最近一分钟涨跌美元
     */
    private static Map<String, Object> btc(int dir, double gapUsd, double z, String sinceUp, String sinceDown,
                                           int lastMin, int move10, int move30) {
        Map<String, Object> btc = new LinkedHashMap<>();
        btc.put("vs_open", PredictionStateWriter.gapPhrase(OPEN, OPEN.add(BigDecimal.valueOf(dir * gapUsd)), SIGMA));
        btc.put("lead", PredictionStateWriter.leadPhrase(dir * z));
        btc.put("since_open", dir > 0 ? sinceUp : sinceDown);
        int m = dir * lastMin;
        btc.put("last_minute", (m > 0 ? "rising" : m < 0 ? "falling" : "flat") + " (" + PredictionStateWriter.signedUsd(m) + ")");
        btc.put("pace", "about as active as usual");
        btc.put("latest", "Chainlink, which settles the market, last updated 2 seconds ago; on Binance BTC moved "
                + PredictionStateWriter.signedUsd(dir * move10) + " in the last 10 seconds and "
                + PredictionStateWriter.signedUsd(dir * move30) + " in the last 30 seconds");
        return btc;
    }

    private static Map<String, Object> flow(int dir, double takerDelta, double largeBias) {
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("takers", PredictionStateWriter.takersPhrase(dir * takerDelta));
        flow.put("large_trades", PredictionStateWriter.largeTradesPhrase(dir * largeBias));
        flow.put("liquidations", "none since the open");
        return flow;
    }

    /** 按最近 30 多秒每秒一个的 UP 中间价（美分，最后一个是现在）写赔率一段：报价、变动、突变都走正式代码 */
    private static Map<String, Object> odds(int... upCents) {
        List<Point> mids = new ArrayList<>();
        for (int i = 0; i < upCents.length; i++) {
            mids.add(new Point(NOW - (upCents.length - 1 - i) * 1000L, BigDecimal.valueOf(upCents[i], 2)));
        }
        BigDecimal mid = mids.getLast().price();
        BigDecimal half = new BigDecimal("0.005");
        Book book = new Book(mid.add(half), mid.subtract(half), BigDecimal.ONE.subtract(mid).add(half), BigDecimal.ONE.subtract(mid).subtract(half));
        Map<String, Object> odds = new LinkedHashMap<>();
        odds.put("standing", PredictionStateWriter.standingPhrase(mid));
        odds.put("up", PredictionStateWriter.quotePhrase("UP", book.upAsk(), book.upBid()));
        odds.put("down", PredictionStateWriter.quotePhrase("DOWN", book.downAsk(), book.downBid()));
        odds.put("odds_move", PredictionStateWriter.oddsMovePhrase(mids, NOW));
        String jump = PredictionStateWriter.oddsJumpPhrase(PredictionStateWriter.biggestJumps(mids, NOW), mid, NOW, 0.10);
        if (jump != null) {
            odds.put("jump", jump);
        }
        return odds;
    }

    /** 同一串 UP 价镜像成 DOWN 的世界：每个价变成 100 − 价 */
    private static int[] mirror(int... upCents) {
        int[] out = new int[upCents.length];
        for (int i = 0; i < upCents.length; i++) {
            out[i] = 100 - upCents[i];
        }
        return out;
    }

    /** 一段平着的价：n 秒都是 c */
    private static int[] flat(int c, int n) {
        int[] out = new int[n];
        Arrays.fill(out, c);
        return out;
    }

    private static Map<String, Object> state(int left, Map<String, Object> btc, Map<String, Object> flow, Map<String, Object> odds) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("market", PredictionStateWriter.GAME);
        state.put("clock", PredictionStateWriter.clockPhrase(left) + "; " + left + " seconds until the settlement average is fixed");
        state.put("btc", btc);
        state.put("binance_flow", flow);
        state.put("odds", odds);
        return state;
    }

    /** 问一次，选择、各项概率和赔率一段打日志 */
    private Judgment ask(Map<String, Object> state) {
        Judgment j = judge.judge(new Snapshot(state, new Raw(0, 0.5, null, null, 0, null, null)));
        log.info("choice={} probs={} | {}", j.decision().choice(), j.decision().probabilities(), state.get("odds"));
        return j;
    }

    private static String mirrored(String choice) {
        return switch (choice) {
            case PredictionQuestions.BUY_UP -> PredictionQuestions.BUY_DOWN;
            case PredictionQuestions.BUY_DOWN -> PredictionQuestions.BUY_UP;
            default -> PredictionQuestions.PASS;
        };
    }

    @Test
    void 镜像情景_选择跟着对调() {
        // 中段 UP 领先 $40、一个正常波动，UP 卖 62¢ 左右，赔率 30 秒没怎么动
        int[] cents = flat(61, 35);
        Judgment up = ask(state(147, btc(1, 40, 1.0, "rose steadily since the open", "fell steadily since the open", 20, 3, 10),
                flow(1, 0.3, 0.4), odds(cents)));
        Judgment down = ask(state(147, btc(-1, 40, 1.0, "rose steadily since the open", "fell steadily since the open", 20, 3, 10),
                flow(-1, 0.3, 0.4), odds(mirror(cents))));

        assertThat(down.decision().choice()).isEqualTo(mirrored(up.decision().choice()));
    }

    @Test
    void 极端冷门_四美分那边不买() {
        // UP 大幅领先只剩 40 秒：DOWN 只卖 4¢ 左右
        Judgment j = ask(state(40, btc(1, 120, 4.0, "rose steadily since the open", "fell steadily since the open", 30, 2, 8),
                flow(1, 0.2, 0.3), odds(flat(96, 35))));

        assertThat(j.decision().choice()).isNotEqualTo(PredictionQuestions.BUY_DOWN);
    }

    @Test
    void 末分钟已锁一半在高位_现价快速回落_不追跌() {
        // 已锁定的那半分钟均价高出 $95，现价只高 $15、最近 30 秒跌了 $80：结算均价还是远在开盘均价上方
        int left = 27;
        double z = PredictionModel.z(15 / 860.0, 95 / 860.0, SIGMA, left);
        Map<String, Object> btc = btc(1, 15, z, "rose early, then gave back part of it", "", -85, -40, -80);
        btc.put("settlement_so_far", "the part of the settlement average already set is "
                + PredictionStateWriter.gapPhrase(OPEN, OPEN.add(new BigDecimal("95")), SIGMA));
        Map<String, Object> flow = flow(1, -0.5, -0.6);
        flow.put("liquidations", "longs liquidated since the open");
        // UP 中间价 30 秒里从 99.5¢ 一路掉到 91.5¢
        int[] cents = new int[35];
        for (int i = 0; i < cents.length; i++) {
            cents[i] = Math.max(91, 99 - Math.max(0, i - 5) * 8 / 29);
        }

        Judgment j = ask(state(left, btc, flow, odds(cents)));

        assertThat(j.decision().choice()).isNotEqualTo(PredictionQuestions.BUY_DOWN);
    }

    /** 突变情景里 BTC 开盘后先跌、后来收回一部分；镜像那边反过来 */
    private static final String RECOVERED = "fell early, then recovered part of it";
    private static final String GAVE_BACK = "rose early, then gave back part of it";

    /** 中段 UP 价 20 秒前还在 10¢，14 秒前起 3 秒里跳到 30¢，then 是之后到现在的走法 */
    private static int[] jumpThen(int... then) {
        List<Integer> c = new ArrayList<>();
        for (int i = 0; i < 20; i++) c.add(10);
        c.addAll(List.of(18, 26, 30));
        for (int x : then) c.add(x);
        return c.stream().mapToInt(Integer::intValue).toArray();
    }

    @Test
    void 突变_十到三十之后还在涨到三十八_镜像对调() {
        int[] cents = jumpThen(32, 33, 35, 36, 37, 37, 38, 38, 38, 38, 38);
        Judgment up = ask(state(150, btc(1, -10, -0.3, RECOVERED, GAVE_BACK, 40, 25, 45), flow(1, 0.4, 0.5), odds(cents)));
        Judgment down = ask(state(150, btc(-1, -10, -0.3, RECOVERED, GAVE_BACK, 40, 25, 45), flow(-1, 0.4, 0.5), odds(mirror(cents))));

        assertThat(down.decision().choice()).isEqualTo(mirrored(up.decision().choice()));
    }

    @Test
    void 突变_十到三十之后被压回二十_镜像对调() {
        int[] cents = jumpThen(28, 26, 24, 22, 21, 20, 20, 20, 20, 20, 20);
        Judgment up = ask(state(150, btc(1, -25, -0.8, RECOVERED, GAVE_BACK, 15, -12, 20), flow(1, -0.1, 0.0), odds(cents)));
        Judgment down = ask(state(150, btc(-1, -25, -0.8, RECOVERED, GAVE_BACK, 15, -12, 20), flow(-1, -0.1, 0.0), odds(mirror(cents))));

        assertThat(down.decision().choice()).isEqualTo(mirrored(up.decision().choice()));
    }
}
