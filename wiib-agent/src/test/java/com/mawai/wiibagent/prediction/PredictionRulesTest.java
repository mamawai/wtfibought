package com.mawai.wiibagent.prediction;

import com.mawai.wiibcommon.dto.PredictionBetResponse;
import com.mawai.wiibcommon.entity.JevPredictionDecision;
import com.mawai.wiibagent.prediction.PredictionJudge.Judgment;
import com.mawai.wiibagent.prediction.PredictionRules.Book;
import com.mawai.wiibagent.prediction.PredictionRules.Entry;
import com.mawai.wiibagent.prediction.PredictionRules.Review;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 买看 Jev 的胜率：两边挑优势大的、到门槛才买、到大门槛下两倍、护栏拦什么；卖由代码按公平价定 */
class PredictionRulesTest {

    private static final JevPredictionConfig CFG = JevPredictionRunnerTest.CFG;
    /** 几个测试共用的盘口：UP 0.60/0.62，DOWN 0.38/0.40 */
    static final Book BOOK = new Book(new BigDecimal("0.62"), new BigDecimal("0.60"),
            new BigDecimal("0.40"), new BigDecimal("0.38"));
    private static final BigDecimal BALANCE = new BigDecimal("100");

    /** Jev 说 UP 有 pJev 的胜率；数学概率不参与买 */
    private static Judgment jev(double pJev) {
        return new Judgment(0.5, pJev, Map.of(), "jev-1.13.0", 820, 150);
    }

    @Test
    void 优势到门槛才买_不到就等() {
        // UP：0.70 − 0.62 − 0.07×0.62×0.38 = 0.0635；DOWN：0.30 − 0.40 − 0.0168 = −0.1168
        Entry e = PredictionRules.entry(jev(0.70), BOOK, BALANCE, CFG);
        assertThat(e.action()).isEqualTo(JevPredictionDecision.ACTION_BUY_UP);
        assertThat(e.stake()).isEqualByComparingTo("5");
        assertThat(e.edge()).isCloseTo(0.063508, within(1e-6));
        assertThat(e.reason()).isEqualTo("BUY UP 0.064 ask 0.62");
        // 0.66 − 0.6365 = 0.0235，不到 0.05
        Entry wait = PredictionRules.entry(jev(0.66), BOOK, BALANCE, CFG);
        assertThat(wait.action()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(wait.reason()).isEqualTo("WAIT UP 0.024");
    }

    @Test
    void 两边挑优势大的_到大门槛下两倍() {
        // DOWN：0.60 − 0.40 − 0.0168 = 0.1832
        Entry down = PredictionRules.entry(jev(0.40), BOOK, BALANCE, CFG);
        assertThat(down.action()).isEqualTo(JevPredictionDecision.ACTION_BUY_DOWN);
        assertThat(down.side()).isEqualTo("DOWN");
        assertThat(down.edge()).isCloseTo(0.1832, within(1e-6));
        assertThat(down.stake()).isEqualByComparingTo("10");
        // UP 0.1035 到 0.10 下两倍
        assertThat(PredictionRules.entry(jev(0.74), BOOK, BALANCE, CFG).stake()).isEqualByComparingTo("10");
    }

    @Test
    void 代码拦下_卖价太低_两边没人卖_没钱() {
        // DOWN：0.10 − 0.02 − 0.0014 = 0.0786，够了但卖价低于 0.03
        Book tail = new Book(new BigDecimal("0.98"), new BigDecimal("0.97"), new BigDecimal("0.02"), new BigDecimal("0.01"));
        assertThat(PredictionRules.entry(jev(0.90), tail, BALANCE, CFG).reason()).isEqualTo("ASK_LOW DOWN 0.02");

        assertThat(PredictionRules.entry(jev(0.74), new Book(null, new BigDecimal("0.60"), null, null), BALANCE, CFG).reason())
                .isEqualTo("NO_QUOTE");
        // 只有一边有人卖就只算那边
        Book downOnly = new Book(null, new BigDecimal("0.60"), new BigDecimal("0.40"), new BigDecimal("0.38"));
        assertThat(PredictionRules.entry(jev(0.40), downOnly, BALANCE, CFG).action()).isEqualTo(JevPredictionDecision.ACTION_BUY_DOWN);

        Entry broke = PredictionRules.entry(jev(0.74), BOOK, new BigDecimal("0.5"), CFG);
        assertThat(broke.reason()).isEqualTo(PredictionRules.NO_BALANCE);
    }

    @Test
    void 持仓_市场给多了才卖_没人接盘只能拿着() {
        // 持 UP，公平 0.45，买一 0.60 扣费 0.0168 = 0.583，高出 0.133
        Review sell = PredictionRules.review(0.45, "UP", BOOK, CFG);
        assertThat(sell.action()).isEqualTo(JevPredictionDecision.ACTION_SELL);
        assertThat(sell.reason()).isEqualTo("SELL over 0.133 bid 0.60");
        // 公平 0.74，市场出的还不到值：拿着
        Review keep = PredictionRules.review(0.74, "UP", BOOK, CFG);
        assertThat(keep.action()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(keep.reason()).isEqualTo("HOLD over -0.157");
        // 持 DOWN 按一减公平价：公平 0.30，买一 0.38 扣费 0.0165 = 0.3635，高出 0.064
        Review down = PredictionRules.review(0.70, "DOWN", BOOK, CFG);
        assertThat(down.action()).isEqualTo(JevPredictionDecision.ACTION_SELL);
        assertThat(down.reason()).isEqualTo("SELL over 0.064 bid 0.38");

        Book noBid = new Book(new BigDecimal("0.62"), null, new BigDecimal("0.40"), null);
        Review stuck = PredictionRules.review(0.45, "UP", noBid, CFG);
        assertThat(stuck.action()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(stuck.reason()).isEqualTo("NO_BID");
    }

    @Test
    void 注额留足手续费_不够最小本金就不下() {
        // 余额 3：3 / (1 + 0.07×0.38) = 2.92
        assertThat(PredictionRules.stake(new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("0.62"))).isEqualByComparingTo("2.92");
        assertThat(PredictionRules.stake(new BigDecimal("5"), new BigDecimal("0.8"), new BigDecimal("0.62"))).isNull();
    }

    @Test
    void 隐含概率取双边mid归一() {
        // up mid 0.61，down mid 0.39 → 0.61
        assertThat(PredictionRules.impliedUp(BOOK)).isEqualByComparingTo("0.6100");
        assertThat(PredictionRules.impliedUp(new Book(null, null, new BigDecimal("0.4"), null))).isNull();
    }

    @Test
    void 盈亏扣掉买入手续费_未终态为null() {
        PredictionBetResponse won = new PredictionBetResponse();
        won.setStatus("WON");
        won.setContracts(new BigDecimal("20"));
        won.setAvgPrice(new BigDecimal("0.50"));
        won.setCost(new BigDecimal("10"));
        won.setPayout(new BigDecimal("20"));
        // 20 − 10 − 0.35
        assertThat(PredictionRules.pnl(won)).isEqualByComparingTo("9.65");

        PredictionBetResponse active = new PredictionBetResponse();
        active.setStatus("ACTIVE");
        assertThat(PredictionRules.pnl(active)).isNull();
        assertThat(PredictionRules.pnl(null)).isNull();
    }
}
