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

/** 买卖由 Jev 拍板：概率不够不动、明显不该买的拦下、注额怎么定、持仓卖不卖 */
class PredictionRulesTest {

    private static final JevPredictionConfig CFG = JevPredictionRunnerTest.CFG;
    /** 几个测试共用的盘口：UP 0.60/0.62，DOWN 0.38/0.40 */
    static final Book BOOK = new Book(new BigDecimal("0.62"), new BigDecimal("0.60"),
            new BigDecimal("0.40"), new BigDecimal("0.38"));
    private static final BigDecimal BALANCE = new BigDecimal("100");

    private static Judgment decide(double pModel, String choice, double p) {
        return new Judgment(pModel, pModel, 0, choice, p, Map.of(choice, p), Map.of(), "jev-1.13.0", 820, 150);
    }

    private static Judgment exit(double pHold, double pSell) {
        String top = pSell > pHold ? "SELL" : "HOLD";
        return new Judgment(0.6, 0.6, 0, top, Math.max(pHold, pSell), Map.of("HOLD", pHold, "SELL", pSell),
                Map.of(), "jev-1.13.0", 820, 150);
    }

    @Test
    void Jev说等或拿不准就不动_想买那边的优势照记() {
        Entry wait = PredictionRules.entry(decide(0.74, "WAIT", 0.57), BOOK, BALANCE, CFG);
        assertThat(wait.action()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(wait.reason()).isEqualTo("WAIT 0.570");
        assertThat(wait.edge()).isNull();

        Entry unsure = PredictionRules.entry(decide(0.74, "BUY_UP", 0.55), BOOK, BALANCE, CFG);
        assertThat(unsure.action()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(unsure.reason()).isEqualTo("UNSURE BUY_UP 0.550");
        // 0.74 − 0.62 − 0.07×0.62×0.38 = 0.1035
        assertThat(unsure.edge()).isCloseTo(0.103508, within(1e-6));
    }

    @Test
    void 买价公道也能买_这是赌后劲() {
        // 0.64 − 0.62 − 0.0165 = 0.0035，比例 ≈ 0.01，不算偏贵
        Entry e = PredictionRules.entry(decide(0.64, "BUY_UP", 0.96), BOOK, BALANCE, CFG);
        assertThat(e.action()).isEqualTo(JevPredictionDecision.ACTION_BUY_UP);
        assertThat(e.stake()).isEqualByComparingTo("5");
        assertThat(e.reason()).isEqualTo("BUY BUY_UP 0.960 ratio 0.010 ask 0.62");
    }

    @Test
    void 很有把握且明显便宜才下两倍() {
        // 比例 0.28 ≥ 0.2 且概率 0.98 ≥ 0.85
        Entry big = PredictionRules.entry(decide(0.74, "BUY_UP", 0.98), BOOK, BALANCE, CFG);
        assertThat(big.stake()).isEqualByComparingTo("10");
        // 概率够但只是略便宜
        Entry small = PredictionRules.entry(decide(0.66, "BUY_UP", 0.98), BOOK, BALANCE, CFG);
        assertThat(small.stake()).isEqualByComparingTo("5");
        // 明显便宜但概率只有 0.7
        Entry unsure = PredictionRules.entry(decide(0.74, "BUY_UP", 0.70), BOOK, BALANCE, CFG);
        assertThat(unsure.stake()).isEqualByComparingTo("5");
    }

    @Test
    void 买DOWN按一减公平价算() {
        // DOWN 公平 0.7：0.7 − 0.40 − 0.0168 = 0.2832，比例 0.486
        Entry e = PredictionRules.entry(decide(0.30, "BUY_DOWN", 0.96), BOOK, BALANCE, CFG);
        assertThat(e.action()).isEqualTo(JevPredictionDecision.ACTION_BUY_DOWN);
        assertThat(e.side()).isEqualTo("DOWN");
        assertThat(e.edge()).isCloseTo(0.2832, within(1e-6));
        assertThat(e.stake()).isEqualByComparingTo("10");
    }

    @Test
    void 代码拦下_偏贵_卖价出区间_没报价_没钱() {
        Entry pricey = PredictionRules.entry(decide(0.50, "BUY_UP", 0.9), BOOK, BALANCE, CFG);
        assertThat(pricey.action()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(pricey.reason()).isEqualTo("EXPENSIVE ratio -0.375");

        Book tail = new Book(new BigDecimal("0.98"), new BigDecimal("0.97"), new BigDecimal("0.03"), new BigDecimal("0.02"));
        Entry capped = PredictionRules.entry(decide(0.999, "BUY_UP", 0.9), tail, BALANCE, CFG);
        assertThat(capped.reason()).isEqualTo("ASK_RANGE 0.98");

        Book noQuote = new Book(null, null, new BigDecimal("0.40"), null);
        assertThat(PredictionRules.entry(decide(0.74, "BUY_UP", 0.98), noQuote, BALANCE, CFG).reason()).isEqualTo("NO_QUOTE");

        Entry broke = PredictionRules.entry(decide(0.74, "BUY_UP", 0.98), BOOK, new BigDecimal("0.5"), CFG);
        assertThat(broke.reason()).isEqualTo("NO_BALANCE");
    }

    @Test
    void 持仓_Jev说卖且概率够才卖_没人接盘只能拿着() {
        Review sell = PredictionRules.review(exit(0.01, 0.99), "UP", BOOK, CFG);
        assertThat(sell.action()).isEqualTo(JevPredictionDecision.ACTION_SELL);
        assertThat(sell.reason()).isEqualTo("SELL 0.990 bid 0.60");

        Book noBid = new Book(new BigDecimal("0.62"), null, new BigDecimal("0.40"), null);
        Review stuck = PredictionRules.review(exit(0.01, 0.99), "UP", noBid, CFG);
        assertThat(stuck.action()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(stuck.reason()).isEqualTo("NO_BID 0.990");

        Review keep = PredictionRules.review(exit(0.72, 0.28), "UP", BOOK, CFG);
        assertThat(keep.action()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(keep.reason()).isEqualTo("HOLD 0.720");
        // 最看好卖但不到 0.6：拿着，标成概率不够
        Review unsure = PredictionRules.review(exit(0.45, 0.55), "DOWN", BOOK, CFG);
        assertThat(unsure.action()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(unsure.reason()).isEqualTo("UNSURE SELL 0.550");
    }

    @Test
    void 优势比例就是Kelly分数() {
        // (p − c) / (1 − c)，c = 卖价 + 每份费
        assertThat(PredictionRules.edgeRatio(0.99, new BigDecimal("0.96"))).isCloseTo(0.732, within(1e-3));
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
