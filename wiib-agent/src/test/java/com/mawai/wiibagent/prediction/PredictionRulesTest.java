package com.mawai.wiibagent.prediction;

import com.mawai.wiibcommon.dto.PredictionBetResponse;
import com.mawai.wiibcommon.entity.JevPredictionDecision;
import com.mawai.wiibagent.llm.jev.JevClient.Answer;
import com.mawai.wiibagent.prediction.PredictionJudge.Judgment;
import com.mawai.wiibagent.prediction.PredictionRules.Book;
import com.mawai.wiibagent.prediction.PredictionRules.Entry;
import com.mawai.wiibagent.prediction.PredictionRules.Review;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Jev 拍板、代码只管执行：选什么做什么，把握不够不动，没人卖 / 没钱拦下 */
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
        return new Judgment(0.5, 0.5, new Answer("choice", null, choice, null, null, probs), Map.of(), "jev-1.13.0", 820, 150);
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
    void 持仓_Jev选卖就卖_选拿着就拿着_卖和拿着打平不卖() {
        Review sell = PredictionRules.exit(choose("SELL", "HOLD", 0.3, "SELL", 0.7), "UP", BOOK, CFG);
        assertThat(sell.action()).isEqualTo(JevPredictionDecision.ACTION_SELL);
        assertThat(sell.reason()).isEqualTo("SELL 0.700 bid 0.60");
        Review down = PredictionRules.exit(choose("SELL", "HOLD", 0.4, "SELL", 0.6), "DOWN", BOOK, CFG);
        assertThat(down.reason()).isEqualTo("SELL 0.600 bid 0.38");

        Review keep = PredictionRules.exit(choose("HOLD", "HOLD", 0.8, "SELL", 0.2), "UP", BOOK, CFG);
        assertThat(keep.action()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(keep.reason()).isEqualTo("HOLD 0.800");

        Review tie = PredictionRules.exit(choose("SELL", "HOLD", 0.5, "SELL", 0.5), "UP", BOOK, CFG);
        assertThat(tie.action()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(tie.reason()).isEqualTo("UNSURE SELL 0.500");
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
