package com.mawai.wiibagent.jev.predictor;

import com.mawai.wiibcommon.dto.PredictionBetResponse;
import com.mawai.wiibcommon.entity.JevPredictionDecision;
import com.mawai.wiibagent.jev.predictor.PredictionJudge.Judgment;
import com.mawai.wiibagent.jev.predictor.PredictionRules.Book;
import com.mawai.wiibagent.jev.predictor.PredictionRules.Decision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三组的规则：按顺序记第一条没过的，边界值含在内；v5-1、v5-2 盯着看又走多少、吐回多少，盯到了买哪一边，
 * v5-2 的 Jev 答值得下单到线才盯；v5-3 空仓看卖价和最新一步逆着，持仓看会赢
 */
class PredictionRulesTest {

    private static final JevPredictionConfig CFG = JevPredictionRunnerTest.CFG;
    /** 几个测试共用的盘口：UP 0.60/0.62，DOWN 0.38/0.40 */
    static final Book BOOK = new Book(new BigDecimal("0.62"), new BigDecimal("0.60"),
            new BigDecimal("0.40"), new BigDecimal("0.38"));
    /** UP 大幅领先的盘口：UP 0.88/0.90，DOWN 0.10/0.12 */
    static final Book STRONG_UP = new Book(new BigDecimal("0.90"), new BigDecimal("0.88"),
            new BigDecimal("0.12"), new BigDecimal("0.10"));
    private static final BigDecimal BALANCE = new BigDecimal("100");
    private static final BigDecimal BROKE = new BigDecimal("0.5");

    /** 整点：Jev 看 side 这一边的回答，会赢、最新一步逆着 */
    static Judgment judged(String side, double win, double against) {
        return new Judgment(side, win, against, null, Map.of(), "jev-1.14.0", 820, 150);
    }

    /** 突变：Jev 看突变那一边的回答，这次突变值得下单 */
    static Judgment jumped(String side, double buy) {
        return new Judgment(side, 0.6, null, buy, Map.of(), "jev-1.14.0", 820, 150);
    }

    private static Book upAsk(String ask) {
        return new Book(new BigDecimal(ask), new BigDecimal(ask).subtract(new BigDecimal("0.02")),
                new BigDecimal("0.12"), new BigDecimal("0.10"));
    }

    private static String watch(String p0, String p, String jumpSize) {
        return PredictionRules.watch(new BigDecimal(p0), new BigDecimal(p), new BigDecimal(jumpSize), CFG);
    }

    // ==================== v5-1、v5-2 盯 ====================

    @Test
    void 盯_又走10美分算延续_吐回整个跳幅算回落_都没到不算() {
        // 起点 58¢，跳幅 18¢
        assertThat(watch("0.58", "0.68", "0.18")).isEqualTo("EXTEND");
        assertThat(watch("0.58", "0.679", "0.18")).isNull();
        assertThat(watch("0.58", "0.40", "0.18")).isEqualTo("REJECT");
        assertThat(watch("0.58", "0.401", "0.18")).isNull();
        assertThat(watch("0.58", "0.58", "0.18")).isNull();
    }

    @Test
    void 盯到了_延续买突变那一边_回落买另一边_数是价差() {
        Decision extend = PredictionRules.jumpEntry("EXTEND", "UP", 0.1, BOOK, BALANCE, CFG);
        assertThat(extend.action()).isEqualTo(JevPredictionDecision.ACTION_BUY_UP);
        assertThat(extend.side()).isEqualTo("UP");
        assertThat(extend.stake()).isEqualByComparingTo("5");
        assertThat(extend.reason()).isEqualTo("EXTEND UP 0.100 ask 0.62");
        assertThat(extend.trades()).isTrue();

        // 第二个词是买的那一边
        Decision reject = PredictionRules.jumpEntry("REJECT", "UP", 0.18, BOOK, BALANCE, CFG);
        assertThat(reject.action()).isEqualTo(JevPredictionDecision.ACTION_BUY_DOWN);
        assertThat(reject.side()).isEqualTo("DOWN");
        assertThat(reject.reason()).isEqualTo("REJECT DOWN 0.180 ask 0.40");
        assertThat(PredictionRules.jumpEntry("REJECT", "DOWN", 0.2, BOOK, BALANCE, CFG).reason()).isEqualTo("REJECT UP 0.200 ask 0.62");
        assertThat(PredictionRules.target("EXTEND", "DOWN")).isEqualTo("DOWN");
        assertThat(PredictionRules.target("REJECT", "DOWN")).isEqualTo("UP");
    }

    @Test
    void 盯到了_按顺序_要买的那边没人卖_再是没钱() {
        Book noUpAsk = new Book(null, new BigDecimal("0.60"), new BigDecimal("0.40"), new BigDecimal("0.38"));
        assertThat(PredictionRules.jumpEntry("EXTEND", "UP", 0.1, noUpAsk, BROKE, CFG).reason()).isEqualTo("NO_QUOTE UP");
        // 回落买的是 DOWN，UP 没人卖不挡
        assertThat(PredictionRules.jumpEntry("REJECT", "UP", 0.18, noUpAsk, BALANCE, CFG).reason()).isEqualTo("REJECT DOWN 0.180 ask 0.40");
        assertThat(PredictionRules.jumpEntry("REJECT", "DOWN", 0.18, noUpAsk, BALANCE, CFG).reason()).isEqualTo("NO_QUOTE UP");
        Decision broke = PredictionRules.jumpEntry("EXTEND", "UP", 0.1, BOOK, BROKE, CFG);
        assertThat(broke.action()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(broke.reason()).isEqualTo(PredictionRules.NO_BALANCE);
        assertThat(broke.stake()).isNull();
    }

    // ==================== v5-2 ====================

    @Test
    void v5_2_Jev答值得下单到0点50才盯_正好0点50算到() {
        assertThat(PredictionRules.jumpGo(jumped("UP", 0.82), CFG)).isTrue();
        assertThat(PredictionRules.jumpGo(jumped("UP", 0.50), CFG)).isTrue();
        assertThat(PredictionRules.jumpGo(jumped("DOWN", 0.49), CFG)).isFalse();
    }

    // ==================== v5-3 空仓 ====================

    @Test
    void v5_3空仓_卖价够高且最新一步逆着就买领先方_数是最新一步逆着() {
        Decision up = PredictionRules.timerBuy(judged("UP", 0.9, 0.8), STRONG_UP, BALANCE, CFG);
        assertThat(up.action()).isEqualTo(JevPredictionDecision.ACTION_BUY_UP);
        assertThat(up.stake()).isEqualByComparingTo("5");
        assertThat(up.reason()).isEqualTo("BUY UP 0.800 ask 0.90");

        Book strongDown = new Book(new BigDecimal("0.12"), new BigDecimal("0.10"), new BigDecimal("0.90"), new BigDecimal("0.88"));
        Decision down = PredictionRules.timerBuy(judged("DOWN", 0.9, 0.75), strongDown, BALANCE, CFG);
        assertThat(down.action()).isEqualTo(JevPredictionDecision.ACTION_BUY_DOWN);
        assertThat(down.reason()).isEqualTo("BUY DOWN 0.750 ask 0.90");
    }

    @Test
    void v5_3空仓_按顺序记第一条没过的() {
        Book noUpAsk = new Book(null, new BigDecimal("0.88"), new BigDecimal("0.12"), new BigDecimal("0.10"));
        Judgment noPullback = judged("UP", 0.9, 0.2);
        assertThat(PredictionRules.timerBuy(noPullback, noUpAsk, BROKE, CFG).reason()).isEqualTo("NO_QUOTE UP");
        assertThat(PredictionRules.timerBuy(noPullback, BOOK, BROKE, CFG).reason()).isEqualTo("PRICE_BAND UP ask 0.62");
        assertThat(PredictionRules.timerBuy(noPullback, STRONG_UP, BROKE, CFG).reason()).isEqualTo("NO_PULLBACK UP 0.200");
        assertThat(PredictionRules.timerBuy(judged("UP", 0.9, 0.8), STRONG_UP, BROKE, CFG).reason())
                .isEqualTo(PredictionRules.NO_BALANCE);
    }

    @Test
    void v5_3空仓_边界值都算过() {
        Judgment pullback = judged("UP", 0.9, 0.70);
        assertThat(PredictionRules.timerBuy(pullback, upAsk("0.85"), BALANCE, CFG).reason()).isEqualTo("BUY UP 0.700 ask 0.85");
        assertThat(PredictionRules.timerBuy(pullback, upAsk("0.96"), BALANCE, CFG).action()).isEqualTo(JevPredictionDecision.ACTION_BUY_UP);
        assertThat(PredictionRules.timerBuy(pullback, upAsk("0.84"), BALANCE, CFG).reason()).isEqualTo("PRICE_BAND UP ask 0.84");
        assertThat(PredictionRules.timerBuy(pullback, upAsk("0.97"), BALANCE, CFG).reason()).isEqualTo("PRICE_BAND UP ask 0.97");
        assertThat(PredictionRules.timerBuy(judged("UP", 0.9, 0.69), STRONG_UP, BALANCE, CFG).reason())
                .isEqualTo("NO_PULLBACK UP 0.690");
    }

    // ==================== v5-3 持仓 ====================

    @Test
    void v5_3持仓_会赢不低就拿着_低到线按买一价卖() {
        Decision hold = PredictionRules.timerSell(judged("UP", 0.11, 0.9), "UP", BOOK, CFG);
        assertThat(hold.action()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(hold.reason()).isEqualTo("HOLD UP");
        assertThat(hold.trades()).isFalse();

        // 正好 0.10 算到线
        Decision sell = PredictionRules.timerSell(judged("UP", 0.10, 0.1), "UP", BOOK, CFG);
        assertThat(sell.action()).isEqualTo(JevPredictionDecision.ACTION_SELL);
        assertThat(sell.side()).isEqualTo("UP");
        assertThat(sell.reason()).isEqualTo("SELL UP 0.100 bid 0.60");
        assertThat(sell.trades()).isTrue();
        assertThat(PredictionRules.timerSell(judged("DOWN", 0.08, 0.1), "DOWN", BOOK, CFG).reason())
                .isEqualTo("SELL DOWN 0.080 bid 0.38");
    }

    @Test
    void v5_3持仓_没到卖出条件先记HOLD_该卖没人接盘记NO_BID() {
        Book noDownBid = new Book(new BigDecimal("0.62"), new BigDecimal("0.60"), new BigDecimal("0.40"), null);
        assertThat(PredictionRules.timerSell(judged("DOWN", 0.5, 0.1), "DOWN", noDownBid, CFG).reason())
                .isEqualTo("HOLD DOWN");
        Decision noBid = PredictionRules.timerSell(judged("DOWN", 0.05, 0.1), "DOWN", noDownBid, CFG);
        assertThat(noBid.action()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(noBid.reason()).isEqualTo("NO_BID DOWN");
    }

    // ==================== 共用的数 ====================

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
