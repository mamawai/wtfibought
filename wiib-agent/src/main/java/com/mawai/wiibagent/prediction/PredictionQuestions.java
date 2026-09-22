package com.mawai.wiibagent.prediction;

import com.mawai.wiibagent.llm.jev.JevClient.Answer;
import com.mawai.wiibagent.llm.jev.JevClient.Question;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 问 Jev 的题，一次请求两道：后劲 + 玩家的决定。空仓问"下不下、下哪边"，持仓问"拿着还是卖"。
 * 决定题就是 Jev 的买卖输出，代码只在明显不该买时拦；后劲题单独记分，看这个判断本身有没有信息量。
 * 措辞都用真 key 测过（改一块数据决定跟着变，不是照某个词翻译）。题目一律英文。
 */
final class PredictionQuestions {

    static final String MOMENTUM = "momentum";
    static final String DECIDE = "decide";

    static final String BUY_UP = "BUY_UP";
    static final String BUY_DOWN = "BUY_DOWN";
    static final String WAIT = "WAIT";
    static final String HOLD = "HOLD";
    static final String SELL = "SELL";

    /** 档 0 在回吐 / 1 没方向 / 2 还在推 */
    static final Question MOMENTUM_Q = Question.score(
            "What are `btc` and `binance_flow` doing to BTC's move since the open?",
            List.of(
                    "Giving back: price and trading now run against the move. The last minute goes the other way and takers or large trades lean against it.",
                    "No lean: price and trading show no common direction, or price has stalled.",
                    "Still pushing: price keeps going the move's way and takers and large trades lean the same way."));

    static final Question ENTRY_Q = Question.choice(
            "You play `market` and hold nothing. Judging by `clock`, `btc`, `binance_flow` and `odds`, what do you do right now?",
            ordered(
                    BUY_UP, "Buy UP: UP looks cheap, or fairly priced with the tape clearly behind it, against where BTC stands.",
                    BUY_DOWN, "Buy DOWN: DOWN looks cheap, or fairly priced with the tape clearly behind it, against where BTC stands.",
                    WAIT, "Wait: neither side is a good bet now. Prices already reflect the move, the tape is mixed, "
                            + "or buying would mean chasing a move that is running out."));

    static final Question EXIT_Q = Question.choice(
            "You play `market` and hold the bet in `position`. Judging by `clock`, `btc`, `binance_flow`, `odds` and `position`, "
                    + "what do you do with it right now?",
            ordered(
                    HOLD, "Keep it: the side you hold still looks worth at least its sell price, or it is safely ahead late in the window.",
                    SELL, "Sell now: the sell price already pays more than BTC's position justifies while the move fades, "
                            + "or the move has turned against you and the bet is slipping away."));

    private PredictionQuestions() {
    }

    /** 空仓问买不买，持仓问卖不卖；两道题一次发 */
    static Map<String, Question> questions(boolean holding) {
        Map<String, Question> qs = new LinkedHashMap<>();
        qs.put(MOMENTUM, MOMENTUM_Q);
        qs.put(DECIDE, holding ? EXIT_Q : ENTRY_Q);
        return qs;
    }

    /** 还在推减在回吐：−1 全回吐 … +1 全还在推 */
    static double momentum(Answer a) {
        return a.probabilities().get("2") - a.probabilities().get("0");
    }

    /** 选项按写的顺序发给 Jev，Map.of 不保序 */
    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
