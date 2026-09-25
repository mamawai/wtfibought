package com.mawai.wiibagent.prediction;

import com.mawai.wiibcommon.dto.PredictionBetResponse;
import com.mawai.wiibcommon.entity.JevPredictionDecision;
import com.mawai.wiibagent.llm.jev.JevClient.Answer;
import com.mawai.wiibagent.prediction.PredictionJudge.Judgment;
import com.mawai.wiibagent.prediction.PredictionRules.Book;
import com.mawai.wiibagent.prediction.PredictionRules.Entry;
import com.mawai.wiibagent.prediction.PredictionRules.Holding;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Jev 拍板、代码只管执行：选什么做什么，把握不够不动，没人卖 / 没钱 / 加满拦下 */
class PredictionRulesTest {

    private static final JevPredictionConfig CFG = JevPredictionRunnerTest.CFG;
    /** 几个测试共用的盘口：UP 0.60/0.62，DOWN 0.38/0.40 */
    static final Book BOOK = new Book(new BigDecimal("0.62"), new BigDecimal("0.60"),
            new BigDecimal("0.40"), new BigDecimal("0.38"));
    private static final BigDecimal BALANCE = new BigDecimal("100");

    /** Jev 在选择题上选 choice，各项概率按 kv 给 */
    static Judgment choose(String choice, Object... kv) {
        Map<String, Double> probs = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            probs.put((String) kv[i], (Double) kv[i + 1]);
        }
        return new Judgment(0.5, new Answer("choice", null, choice, null, null, probs), Map.of(), "jev-1.13.0", 820, 150);
    }

    @Test
    void 空仓_Jev选买就买_固定注额() {
        Entry e = PredictionRules.entry(choose("BUY_UP", "BUY_UP", 0.7, "BUY_DOWN", 0.1, "PASS", 0.2), BOOK, BALANCE, CFG);
        assertThat(e.action()).isEqualTo(JevPredictionDecision.ACTION_BUY_UP);
        assertThat(e.side()).isEqualTo("UP");
        assertThat(e.stake()).isEqualByComparingTo("5");
        assertThat(e.reason()).isEqualTo("BUY UP 0.700 ask 0.62");

        Entry down = PredictionRules.entry(choose("BUY_DOWN", "BUY_UP", 0.1, "BUY_DOWN", 0.55, "PASS", 0.35), BOOK, BALANCE, CFG);
        assertThat(down.action()).isEqualTo(JevPredictionDecision.ACTION_BUY_DOWN);
        assertThat(down.reason()).isEqualTo("BUY DOWN 0.550 ask 0.40");
    }

    @Test
    void 空仓_Jev选不买或把握不够_不动() {
        Entry pass = PredictionRules.entry(choose("PASS", "BUY_UP", 0.2, "BUY_DOWN", 0.2, "PASS", 0.6), BOOK, BALANCE, CFG);
        assertThat(pass.action()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(pass.side()).isNull();
        assertThat(pass.reason()).isEqualTo("PASS 0.600");
        // 选了买 UP 但只有 0.45，不到其余两项加起来
        Entry unsure = PredictionRules.entry(choose("BUY_UP", "BUY_UP", 0.45, "BUY_DOWN", 0.2, "PASS", 0.35), BOOK, BALANCE, CFG);
        assertThat(unsure.action()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(unsure.side()).isEqualTo("UP");
        assertThat(unsure.reason()).isEqualTo("UNSURE UP 0.450");
    }

    @Test
    void 空仓_那边没人卖_没钱_代码拦下() {
        Book noUpAsk = new Book(null, new BigDecimal("0.60"), new BigDecimal("0.40"), new BigDecimal("0.38"));
        assertThat(PredictionRules.entry(choose("BUY_UP", "BUY_UP", 0.8), noUpAsk, BALANCE, CFG).reason()).isEqualTo("NO_QUOTE UP");
        Entry broke = PredictionRules.entry(choose("BUY_UP", "BUY_UP", 0.8), BOOK, new BigDecimal("0.5"), CFG);
        assertThat(broke.action()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(broke.reason()).isEqualTo(PredictionRules.NO_BALANCE);
    }

    @Test
    void 持仓_选另一边就卖掉_选不买或把握不够就拿着() {
        Holding sell = PredictionRules.holding(choose("BUY_DOWN", "BUY_UP", 0.1, "BUY_DOWN", 0.7, "PASS", 0.2),
                "UP", new BigDecimal("5"), BOOK, BALANCE, CFG);
        assertThat(sell.action()).isEqualTo(JevPredictionDecision.ACTION_SELL);
        assertThat(sell.stake()).isNull();
        assertThat(sell.reason()).isEqualTo("SELL 0.700 bid 0.60");
        Holding down = PredictionRules.holding(choose("BUY_UP", "BUY_UP", 0.6, "BUY_DOWN", 0.1, "PASS", 0.3),
                "DOWN", new BigDecimal("5"), BOOK, BALANCE, CFG);
        assertThat(down.reason()).isEqualTo("SELL 0.600 bid 0.38");

        Holding keep = PredictionRules.holding(choose("PASS", "BUY_UP", 0.2, "BUY_DOWN", 0.2, "PASS", 0.6),
                "UP", new BigDecimal("5"), BOOK, BALANCE, CFG);
        assertThat(keep.action()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(keep.reason()).isEqualTo("HOLD PASS 0.600");

        Holding unsure = PredictionRules.holding(choose("BUY_DOWN", "BUY_UP", 0.2, "BUY_DOWN", 0.45, "PASS", 0.35),
                "UP", new BigDecimal("5"), BOOK, BALANCE, CFG);
        assertThat(unsure.action()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(unsure.reason()).isEqualTo("UNSURE DOWN 0.450");
    }

    @Test
    void 持仓_选手里这边就加注_同一边合计到上限为止() {
        Judgment again = choose("BUY_UP", "BUY_UP", 0.7, "BUY_DOWN", 0.1, "PASS", 0.2);
        Holding add = PredictionRules.holding(again, "UP", new BigDecimal("5"), BOOK, BALANCE, CFG);
        assertThat(add.action()).isEqualTo(JevPredictionDecision.ACTION_BUY_UP);
        assertThat(add.stake()).isEqualByComparingTo("5");
        assertThat(add.reason()).isEqualTo("ADD UP 0.700 ask 0.62");
        // 已押 7，上限 10，只能再加 3
        assertThat(PredictionRules.holding(again, "UP", new BigDecimal("7"), BOOK, BALANCE, CFG).stake()).isEqualByComparingTo("3");
        // 剩的不到 sim 最小本金就算加满
        Holding full = PredictionRules.holding(again, "UP", new BigDecimal("9.5"), BOOK, BALANCE, CFG);
        assertThat(full.action()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(full.reason()).isEqualTo("MAX_STAKE UP 0.700");
    }

    @Test
    void 持仓_加注时没人卖_没钱_拿着不动() {
        Judgment again = choose("BUY_UP", "BUY_UP", 0.7, "BUY_DOWN", 0.1, "PASS", 0.2);
        Book noUpAsk = new Book(null, new BigDecimal("0.60"), new BigDecimal("0.40"), new BigDecimal("0.38"));
        Holding noQuote = PredictionRules.holding(again, "UP", new BigDecimal("5"), noUpAsk, BALANCE, CFG);
        assertThat(noQuote.action()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(noQuote.reason()).isEqualTo("NO_QUOTE UP");
        Holding broke = PredictionRules.holding(again, "UP", new BigDecimal("5"), BOOK, new BigDecimal("0.5"), CFG);
        assertThat(broke.action()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(broke.reason()).isEqualTo(PredictionRules.NO_BALANCE);
    }

    @Test
    void 买入优势是概率减卖价减吃单费_卖出多拿是买价减吃单费减概率() {
        // 0.70 − 0.62 − 0.07×0.62×0.38
        assertThat(PredictionRules.edge(0.70, new BigDecimal("0.62"))).isCloseTo(0.063508, within(1e-6));
        // 0.60 − 0.07×0.60×0.40 − 0.45
        assertThat(PredictionRules.sellOver(0.45, new BigDecimal("0.60"))).isCloseTo(0.1332, within(1e-6));
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
