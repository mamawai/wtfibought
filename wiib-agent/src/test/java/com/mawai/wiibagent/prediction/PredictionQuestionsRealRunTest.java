package com.mawai.wiibagent.prediction;

import com.mawai.wiibagent.llm.jev.JevClient;
import com.mawai.wiibagent.llm.jev.JevPlatformConfig;
import com.mawai.wiibagent.prediction.PredictionStateWriter.Raw;
import com.mawai.wiibagent.prediction.PredictionStateWriter.Snapshot;
import com.mawai.wiibcommon.market.TimeWeightedAverage.Point;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 题目和 state 措辞的真跑验收：真调平台 Jev，情景用 {@link PredictionStateWriter} 的同一套句子拼。
 * 镜像：UP 领先的情景和把所有方向对调的镜像，UP 会赢的概率要对称；
 * 单调（盘面中性）：领先调大，UP 会赢的概率跟着升；UP 卖价调高，按 Jev 胜率算的买 UP 优势跟着降。
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

    private final PredictionJudge judge = new PredictionJudge(new JevClient(),
            new JevPlatformConfig(System.getenv("JEV_API_KEY"), JevClient.DEFAULT_BASE_URL, JevClient.DEFAULT_MODEL));

    /**
     * 一份空仓情景：dir = 1 时 UP 领先，-1 时一切对调成 DOWN 领先。
     *
     * @param gapUsd  现价离开盘均价多少美元
     * @param z       领先相对剩余时间的正常波动
     * @param favAsk  领先那边的卖价，买价低 1¢；落后那边按镜像报价
     * @param tape    盘面（最近一分钟、主动买卖、大单、Binance、赔率走势）是否顺着领先方向；否则全是中性
     */
    private static Map<String, Object> state(int dir, double gapUsd, double z, String favAsk, boolean tape) {
        int t = tape ? dir : 0;
        Map<String, Object> btc = new LinkedHashMap<>();
        btc.put("vs_open", PredictionStateWriter.gapPhrase(OPEN, OPEN.add(BigDecimal.valueOf(dir * gapUsd)), SIGMA));
        btc.put("lead", PredictionStateWriter.leadPhrase(dir * z));
        btc.put("since_open", tape ? (dir > 0 ? "rose steadily since the open" : "fell steadily since the open")
                : (dir > 0 ? "rose early, flat since" : "fell early, flat since"));
        btc.put("last_minute", (t > 0 ? "rising (" : t < 0 ? "falling (" : "flat (") + PredictionStateWriter.signedUsd(t * 15.0) + ")");
        btc.put("pace", "about as active as usual");
        btc.put("latest", "Chainlink, which settles the market, last updated 2 seconds ago; on Binance BTC moved "
                + PredictionStateWriter.signedUsd(t * 4.0) + " in the last 10 seconds and "
                + PredictionStateWriter.signedUsd(t * 12.0) + " in the last 30 seconds");

        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("takers", PredictionStateWriter.takersPhrase(t * 0.3));
        flow.put("large_trades", PredictionStateWriter.largeTradesPhrase(t * 0.4));
        flow.put("liquidations", "none since the open");

        BigDecimal ask = new BigDecimal(favAsk);
        BigDecimal bid = ask.subtract(new BigDecimal("0.01"));
        BigDecimal otherAsk = BigDecimal.ONE.subtract(bid);
        BigDecimal otherBid = BigDecimal.ONE.subtract(ask);
        BigDecimal upAsk = dir > 0 ? ask : otherAsk;
        BigDecimal upBid = dir > 0 ? bid : otherBid;
        BigDecimal upMid = upAsk.add(upBid).divide(BigDecimal.TWO);
        Map<String, Object> odds = new LinkedHashMap<>();
        odds.put("standing", PredictionStateWriter.standingPhrase(upMid));
        odds.put("up", PredictionStateWriter.quotePhrase("UP", upAsk, upBid));
        odds.put("down", PredictionStateWriter.quotePhrase("DOWN", dir > 0 ? otherAsk : ask, dir > 0 ? otherBid : bid));
        odds.put("odds_move", PredictionStateWriter.oddsMovePhrase(List.of(
                new Point(0, upMid.subtract(BigDecimal.valueOf(t * 0.04))), new Point(29_000, upMid)), 30_000));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("market", PredictionStateWriter.GAME);
        state.put("clock", PredictionStateWriter.clockPhrase(147) + "; 147 seconds until the settlement average is fixed");
        state.put("btc", btc);
        state.put("binance_flow", flow);
        state.put("odds", odds);
        return state;
    }

    /** Jev 的上涨概率（正反两问的平均），两个原始答案打日志 */
    private double upWins(Map<String, Object> state) {
        PredictionJudge.Judgment j = judge.judge(new Snapshot(state, new Raw(0, 0.5, null, null)));
        log.info("pJev={} up_wins={} down_wins={} | {}", j.pJev(), j.answers().get(PredictionQuestions.UP_WINS).noul(),
                j.answers().get(PredictionQuestions.DOWN_WINS).noul(), ((Map<?, ?>) state.get("odds")).get("up"));
        return j.pJev();
    }

    @Test
    void 镜像情景_UP会赢的概率对称() {
        double up = upWins(state(1, 40, 1.0, "0.72", true));
        double down = upWins(state(-1, 40, 1.0, "0.72", true));

        assertThat(up).isCloseTo(1 - down, within(0.1));
    }

    @Test
    void 盘面中性_领先越大涨的概率越高() {
        double small = upWins(state(1, 8, 0.3, "0.60", false));
        double mid = upWins(state(1, 40, 1.0, "0.60", false));
        double large = upWins(state(1, 100, 2.5, "0.60", false));

        assertThat(mid).isGreaterThanOrEqualTo(small - 0.02);
        assertThat(large).isGreaterThanOrEqualTo(mid - 0.02);
        assertThat(large).isGreaterThan(small);
    }

    @Test
    void 盘面中性_UP越贵_按Jev胜率算的买UP优势越小() {
        double cheap = PredictionRules.edge(upWins(state(1, 40, 1.0, "0.45", false)), new BigDecimal("0.45"));
        double fair = PredictionRules.edge(upWins(state(1, 40, 1.0, "0.65", false)), new BigDecimal("0.65"));
        double dear = PredictionRules.edge(upWins(state(1, 40, 1.0, "0.85", false)), new BigDecimal("0.85"));

        assertThat(fair).isLessThan(cheap);
        assertThat(dear).isLessThan(fair);
    }
}
