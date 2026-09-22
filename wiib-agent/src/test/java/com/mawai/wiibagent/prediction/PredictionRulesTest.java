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

/** 买由 Jev 拍板：概率不够不动、明显不该买的拦下、注额怎么定；卖由代码按公平价定 */
class PredictionRulesTest {

    private static final JevPredictionConfig CFG = JevPredictionRunnerTest.CFG;
    /** 几个测试共用的盘口：UP 0.60/0.62，DOWN 0.38/0.40 */
    static final Book BOOK = new Book(new BigDecimal("0.62"), new BigDecimal("0.60"),
            new BigDecimal("0.40"), new BigDecimal("0.38"));
    private static final BigDecimal BALANCE = new BigDecimal("100");

    private static Judgment decide(double pModel, String choice, double p) {
        return new Judgment(pModel, pModel, 0, choice, p, Map.of(choice, p), Map.of(), "jev-1.13.0", 820, 150);
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
    void 合理价不买_便宜才买() {
        // 0.64 − 0.62 − 0.0165 = 0.0035，不到 0.04 不算便宜
        Entry fair = PredictionRules.entry(decide(0.64, "BUY_UP", 0.96), BOOK, BALANCE, CFG);
        assertThat(fair.action()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(fair.reason()).isEqualTo("NOT_CHEAP edge 0.004");
        // 0.68 − 0.62 − 0.0165 = 0.0435；reason 里的比例是定注额用的
        Entry e = PredictionRules.entry(decide(0.68, "BUY_UP", 0.96), BOOK, BALANCE, CFG);
        assertThat(e.action()).isEqualTo(JevPredictionDecision.ACTION_BUY_UP);
        assertThat(e.stake()).isEqualByComparingTo("5");
        assertThat(e.reason()).isEqualTo("BUY BUY_UP 0.960 ratio 0.120 ask 0.62");
    }

    @Test
    void 很有把握且明显便宜才下两倍() {
        // 比例 0.28 ≥ 0.2 且概率 0.98 ≥ 0.85
        Entry big = PredictionRules.entry(decide(0.74, "BUY_UP", 0.98), BOOK, BALANCE, CFG);
        assertThat(big.stake()).isEqualByComparingTo("10");
        // 概率够但比例只有 0.17
        Entry small = PredictionRules.entry(decide(0.70, "BUY_UP", 0.98), BOOK, BALANCE, CFG);
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
    void 代码拦下_不便宜_卖价出区间_没报价_没钱() {
        // 0.50 − 0.62 − 0.0165 = −0.1365
        Entry pricey = PredictionRules.entry(decide(0.50, "BUY_UP", 0.9), BOOK, BALANCE, CFG);
        assertThat(pricey.action()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(pricey.reason()).isEqualTo("NOT_CHEAP edge -0.136");

        Book tail = new Book(new BigDecimal("0.98"), new BigDecimal("0.97"), new BigDecimal("0.03"), new BigDecimal("0.02"));
        Entry capped = PredictionRules.entry(decide(0.999, "BUY_UP", 0.9), tail, BALANCE, CFG);
        assertThat(capped.reason()).isEqualTo("ASK_RANGE 0.98");

        Book noQuote = new Book(null, null, new BigDecimal("0.40"), null);
        assertThat(PredictionRules.entry(decide(0.74, "BUY_UP", 0.98), noQuote, BALANCE, CFG).reason()).isEqualTo("NO_QUOTE");

        Entry broke = PredictionRules.entry(decide(0.74, "BUY_UP", 0.98), BOOK, new BigDecimal("0.5"), CFG);
        assertThat(broke.reason()).isEqualTo("NO_BALANCE");
    }

    @Test
    void 热门略便宜也能买() {
        // 盘口 UP 0.75/0.76：0.815 − 0.76 − 0.0128 = 0.0422，过了 0.04
        Book fav = new Book(new BigDecimal("0.76"), new BigDecimal("0.75"), new BigDecimal("0.25"), new BigDecimal("0.24"));
        Entry e = PredictionRules.entry(decide(0.815, "BUY_UP", 0.9), fav, BALANCE, CFG);
        assertThat(e.action()).isEqualTo(JevPredictionDecision.ACTION_BUY_UP);
        assertThat(e.edge()).isCloseTo(0.042232, within(1e-6));
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
