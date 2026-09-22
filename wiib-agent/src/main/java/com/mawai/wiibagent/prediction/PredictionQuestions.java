package com.mawai.wiibagent.prediction;

import com.mawai.wiibagent.llm.jev.JevClient.Answer;
import com.mawai.wiibagent.llm.jev.JevClient.Question;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 问 Jev 的题：后劲题每次都问，只记分；空仓再加一道决定题"下不下、下哪边"。持仓不问决定，卖不卖代码按公平价定。
 * 决定题只在便宜时买，盘面只能否决不能发起：盘面短语（最近 60 秒主动买卖、大单、最近一分钟涨跌）和结算没关系。
 * 措辞都用真 key 测过：便宜就买、盘面明显反对就等、合理价不买，UP 和 DOWN 的镜像情景答得一样。题目一律英文。
 */
final class PredictionQuestions {

    static final String MOMENTUM = "momentum";
    static final String DECIDE = "decide";

    static final String BUY_UP = "BUY_UP";
    static final String BUY_DOWN = "BUY_DOWN";
    static final String WAIT = "WAIT";

    /** 档 0 在回吐 / 1 没方向 / 2 还在推 */
    static final Question MOMENTUM_Q = Question.score(
            "What are `btc` and `binance_flow` doing to BTC's move since the open?",
            List.of(
                    "Giving back: price and trading now run against the move. The last minute goes the other way and takers or large trades lean against it.",
                    "No lean: price and trading show no common direction, or price has stalled.",
                    "Still pushing: price keeps going the move's way and takers and large trades lean the same way."));

    /** 便宜与否已经含了价格位置，题目里明说拿它当已知，不然 Jev 会把"价在开盘价下方"再算一遍反对 UP，两边不对称 */
    static final Question ENTRY_Q = Question.choice(
            "You play `market` and hold nothing. Which side, if any, do you buy right now? Take `odds.up_value` and `odds.down_value` "
                    + "as given: they already include where BTC stands and how far it has moved. Use `btc.last_minute`, "
                    + "`binance_flow.takers` and `binance_flow.large_trades` only to veto a cheap side.",
            ordered(
                    BUY_UP, "Buy UP: `odds.up_value` says UP looks cheap, and the last minute, takers and large trades are not clearly running against UP.",
                    BUY_DOWN, "Buy DOWN: `odds.down_value` says DOWN looks cheap, and the last minute, takers and large trades are not clearly running against DOWN.",
                    WAIT, "Wait: neither side looks cheap, or the cheap side has the last minute, takers and large trades clearly running against it."));

    private PredictionQuestions() {
    }

    /** 后劲题都问；空仓再问买不买。一次发 */
    static Map<String, Question> questions(boolean holding) {
        Map<String, Question> qs = new LinkedHashMap<>();
        qs.put(MOMENTUM, MOMENTUM_Q);
        if (!holding) {
            qs.put(DECIDE, ENTRY_Q);
        }
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
