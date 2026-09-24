package com.mawai.wiibagent.prediction;

import com.mawai.wiibcommon.dto.PredictionBetResponse;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 题目和 state 措辞的真跑验收：真调平台 Jev，情景用 {@link PredictionStateWriter} 的同一套句子拼（含 estimate / position）。
 * 镜像：UP 领先的情景和把所有方向对调的镜像，选择要跟着对调；单调：估计比成本高得越多，买那边的概率越高；
 * 极端冷门：估计不到 1% 的 4¢ 那边不买；末分钟已锁一半在高位、现价快速回落时不追跌；持仓：卖出比估计多拿很多就卖，少拿很多就拿着。
 * <p>
 * 会烧真 token（9 次调用），默认跳过。跑法（项目根）：
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
     * 一份情景：dir = 1 时 UP 领先，-1 时一切对调成 DOWN 领先；盘面中性。
     *
     * @param gapUsd  现价离开盘均价多少美元
     * @param z       领先相对剩余时间的正常波动
     * @param favAsk  领先那边的卖价，买价低 1¢；落后那边按镜像报价
     * @param pFav    随机游走给领先那边的胜率
     * @param left    离结算均价截止还有几秒
     * @param holding 是否拿着领先那边 10 份、均价 50¢
     */
    private static Map<String, Object> state(int dir, double gapUsd, double z, String favAsk, double pFav, int left, boolean holding) {
        Map<String, Object> btc = new LinkedHashMap<>();
        btc.put("vs_open", PredictionStateWriter.gapPhrase(OPEN, OPEN.add(BigDecimal.valueOf(dir * gapUsd)), SIGMA));
        btc.put("lead", PredictionStateWriter.leadPhrase(dir * z));
        btc.put("since_open", dir > 0 ? "rose early, flat since" : "fell early, flat since");
        btc.put("last_minute", "flat (+$0)");
        btc.put("pace", "about as active as usual");
        btc.put("latest", "Chainlink, which settles the market, last updated 2 seconds ago; on Binance BTC moved +$0 in the last 10 seconds "
                + "and +$0 in the last 30 seconds");

        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("takers", PredictionStateWriter.takersPhrase(0));
        flow.put("large_trades", PredictionStateWriter.largeTradesPhrase(0));
        flow.put("liquidations", "none since the open");

        BigDecimal ask = new BigDecimal(favAsk);
        BigDecimal bid = ask.subtract(new BigDecimal("0.01"));
        BigDecimal otherAsk = BigDecimal.ONE.subtract(bid);
        BigDecimal otherBid = BigDecimal.ONE.subtract(ask);
        Book book = dir > 0 ? new Book(ask, bid, otherAsk, otherBid) : new Book(otherAsk, otherBid, ask, bid);
        BigDecimal upMid = book.upAsk().add(book.upBid()).divide(BigDecimal.TWO);
        Map<String, Object> odds = new LinkedHashMap<>();
        odds.put("standing", PredictionStateWriter.standingPhrase(upMid));
        odds.put("up", PredictionStateWriter.quotePhrase("UP", book.upAsk(), book.upBid()));
        odds.put("down", PredictionStateWriter.quotePhrase("DOWN", book.downAsk(), book.downBid()));
        odds.put("odds_move", PredictionStateWriter.oddsMovePhrase(List.of(new Point(0, upMid), new Point(29_000, upMid)), 30_000));

        double pUp = dir > 0 ? pFav : 1 - pFav;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("market", PredictionStateWriter.GAME);
        state.put("clock", PredictionStateWriter.clockPhrase(left) + "; " + left + " seconds until the settlement average is fixed");
        state.put("btc", btc);
        state.put("binance_flow", flow);
        state.put("odds", odds);
        state.put("estimate", PredictionStateWriter.estimateSection(pUp, book));
        if (holding) {
            PredictionBetResponse bet = new PredictionBetResponse();
            bet.setSide(dir > 0 ? "UP" : "DOWN");
            bet.setContracts(BigDecimal.TEN);
            bet.setAvgPrice(new BigDecimal("0.50"));
            state.put("position", PredictionStateWriter.positionSection(bet, pUp, book));
        }
        return state;
    }

    /** 问一次，选择和各项概率打日志 */
    private Judgment ask(Map<String, Object> state, boolean holding) {
        Judgment j = judge.judge(new Snapshot(state, new Raw(0, 0.5, null, null, 0)), holding);
        log.info("choice={} probs={} | {} | {}", j.decision().choice(), j.decision().probabilities(),
                ((Map<?, ?>) state.get("estimate")).get("up"), ((Map<?, ?>) state.get("estimate")).get("down"));
        return j;
    }

    private static double pBuyUp(Judgment j) {
        return j.decision().probabilities().getOrDefault(PredictionQuestions.BUY_UP, 0.0);
    }

    @Test
    void 镜像情景_选择跟着对调() {
        // 估计 80%，买一份 63.6¢，比估计少 16.4¢
        Judgment up = ask(state(1, 40, 1.0, "0.62", 0.80, 147, false), false);
        Judgment down = ask(state(-1, 40, 1.0, "0.62", 0.80, 147, false), false);

        String mirrored = switch (up.decision().choice()) {
            case PredictionQuestions.BUY_UP -> PredictionQuestions.BUY_DOWN;
            case PredictionQuestions.BUY_DOWN -> PredictionQuestions.BUY_UP;
            default -> PredictionQuestions.PASS;
        };
        assertThat(down.decision().choice()).isEqualTo(mirrored);
    }

    @Test
    void 估计比成本高得越多_买那边的概率越高() {
        // 估计 75%：卖价 0.60 / 0.72 / 0.84 的含费成本 61.7 / 73.4 / 84.9¢
        double cheap = pBuyUp(ask(state(1, 40, 1.0, "0.60", 0.75, 147, false), false));
        double near = pBuyUp(ask(state(1, 40, 1.0, "0.72", 0.75, 147, false), false));
        double dear = pBuyUp(ask(state(1, 40, 1.0, "0.84", 0.75, 147, false), false));

        assertThat(cheap).isGreaterThanOrEqualTo(near - 0.05);
        assertThat(near).isGreaterThanOrEqualTo(dear - 0.05);
        assertThat(cheap).isGreaterThan(dear);
    }

    @Test
    void 极端冷门_估计不到百分之一的四美分那边不买() {
        // UP 大幅领先只剩 40 秒：DOWN 卖 4¢，估计 less than 1%
        Judgment j = ask(state(1, 120, 4.0, "0.97", 0.998, 40, false), false);

        assertThat(j.decision().choice()).isNotEqualTo(PredictionQuestions.BUY_DOWN);
    }

    @Test
    void 末分钟已锁一半在高位_现价快速回落_不追跌() {
        // 已锁定的那半分钟均价高出 $95，现价只高 $15、最近 30 秒跌了 $80：结算均价还是远在开盘均价上方
        int left = 27;
        double z = PredictionModel.z(15 / 860.0, 95 / 860.0, SIGMA, left);
        Map<String, Object> btc = new LinkedHashMap<>();
        btc.put("vs_open", PredictionStateWriter.gapPhrase(OPEN, OPEN.add(new BigDecimal("15")), SIGMA));
        btc.put("lead", PredictionStateWriter.leadPhrase(z));
        btc.put("settlement_so_far", "the part of the settlement average already set is "
                + PredictionStateWriter.gapPhrase(OPEN, OPEN.add(new BigDecimal("95")), SIGMA));
        btc.put("since_open", "rose early, then gave back part of it");
        btc.put("last_minute", "falling (-$85)");
        btc.put("pace", "unusually fast");
        btc.put("latest", "Chainlink, which settles the market, last updated 1 seconds ago; on Binance BTC moved -$40 in the last 10 seconds "
                + "and -$80 in the last 30 seconds");
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("takers", PredictionStateWriter.takersPhrase(-0.5));
        flow.put("large_trades", PredictionStateWriter.largeTradesPhrase(-0.6));
        flow.put("liquidations", "longs liquidated since the open");
        Book book = new Book(new BigDecimal("0.92"), new BigDecimal("0.91"), new BigDecimal("0.09"), new BigDecimal("0.08"));
        Map<String, Object> odds = new LinkedHashMap<>();
        odds.put("standing", PredictionStateWriter.standingPhrase(new BigDecimal("0.915")));
        odds.put("up", PredictionStateWriter.quotePhrase("UP", book.upAsk(), book.upBid()));
        odds.put("down", PredictionStateWriter.quotePhrase("DOWN", book.downAsk(), book.downBid()));
        odds.put("odds_move", PredictionStateWriter.oddsMovePhrase(List.of(new Point(0, new BigDecimal("0.995")),
                new Point(29_000, new BigDecimal("0.915"))), 30_000));
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("market", PredictionStateWriter.GAME);
        state.put("clock", PredictionStateWriter.clockPhrase(left) + "; " + left + " seconds until the settlement average is fixed");
        state.put("btc", btc);
        state.put("binance_flow", flow);
        state.put("odds", odds);
        state.put("estimate", PredictionStateWriter.estimateSection(PredictionModel.p(z), book));

        Judgment j = ask(state, false);

        assertThat(j.decision().choice()).isNotEqualTo(PredictionQuestions.BUY_DOWN);
    }

    @Test
    void 持仓_卖出比估计多拿很多就卖_少拿很多就拿着() {
        // 估计 55%，买价 0.71 卖出扣费 69.6¢，多拿 14.6¢
        Judgment sell = ask(state(1, 8, 0.3, "0.72", 0.55, 147, true), true);
        // 估计 95%，买价 0.79 卖出扣费 77.8¢，少拿 17.2¢
        Judgment hold = ask(state(1, 100, 2.5, "0.80", 0.95, 147, true), true);

        assertThat(sell.decision().choice()).isEqualTo(PredictionQuestions.SELL);
        assertThat(hold.decision().choice()).isEqualTo(PredictionQuestions.HOLD);
    }
}
