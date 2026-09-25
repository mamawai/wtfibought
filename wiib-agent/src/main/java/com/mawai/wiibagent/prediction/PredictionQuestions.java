package com.mawai.wiibagent.prediction;

import com.mawai.wiibagent.llm.jev.JevClient.Question;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 问 Jev 的题：每个检查点只问一道，买 UP / 买 DOWN / 不买，Jev 拍板。空仓持仓同一道题、同一份 state，
 * 持仓时代码按它的选择加注、卖掉或拿着，见 {@link PredictionRules#holding}。题目一律英文。
 * <p>
 * 不提持仓、不给估计当参照，只说清怎么赢、state 各段是什么。选项顺序固定 UP 在前：Jev 对选项位置敏感，换顺序会改变它偏哪边。
 */
final class PredictionQuestions {

    static final String ENTRY = "entry";

    static final String BUY_UP = "BUY_UP";
    static final String BUY_DOWN = "BUY_DOWN";
    static final String PASS = "PASS";

    static final Question ENTRY_Q = Question.choice(
            "You are playing `market`. Choose the side you would buy right now, or pass. UP wins if BTC's average price over the "
                    + "final minute ends at or above the opening average, and DOWN wins otherwise. `clock` says how long is left and how much "
                    + "of that final-minute average is already set; `btc` says where BTC stands against the opening average and how it got "
                    + "there; `binance_flow` says what traders on Binance are doing; `odds` says what each side costs with the fee and how "
                    + "its price has been moving. A share pays 100¢ if its side wins and nothing if it loses. Buy a side only if you judge "
                    + "its chance of winning to be higher than its cost; otherwise pass.",
            ordered(
                    BUY_UP, "Buy UP now: UP's true chance of winning is higher than what a share of UP costs.",
                    BUY_DOWN, "Buy DOWN now: DOWN's true chance of winning is higher than what a share of DOWN costs.",
                    PASS, "Buy nothing now: neither side's true chance of winning is higher than what its share costs."));

    private PredictionQuestions() {
    }

    static Map<String, Question> questions() {
        return Map.of(ENTRY, ENTRY_Q);
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
