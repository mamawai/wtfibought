package com.mawai.wiibagent.prediction;

import com.mawai.wiibagent.llm.jev.JevClient.Question;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 问 Jev 的题，一次请求发：空仓问买 UP / 买 DOWN / 不买，持仓问拿着 / 卖掉，Jev 拍板；
 * UP 会不会赢、DOWN 会不会赢正反两问每次都带，只记分不管买卖。题目一律英文。
 * <p>
 * 买卖两题明说 state 里的 estimate 是随机游走的基线，要 Jev 自己看盘判断真实胜率比它高还是低，再拿真实胜率比成本。
 */
final class PredictionQuestions {

    static final String UP_WINS = "up_wins";
    static final String DOWN_WINS = "down_wins";
    static final String ENTRY = "entry";
    static final String EXIT = "exit";

    static final String BUY_UP = "BUY_UP";
    static final String BUY_DOWN = "BUY_DOWN";
    static final String PASS = "PASS";
    static final String HOLD = "HOLD";
    static final String SELL = "SELL";

    static final Question UP_WINS_Q = Question.noul(
            "Will this window settle UP: BTC's average price over the final minute at or above its average at the open?",
            "UP wins: the final-minute average ends at or above the opening average.",
            "DOWN wins: the final-minute average ends below the opening average.");

    static final Question DOWN_WINS_Q = Question.noul(
            "Will this window settle DOWN: BTC's average price over the final minute below its average at the open?",
            "DOWN wins: the final-minute average ends below the opening average.",
            "UP wins: the final-minute average ends at or above the opening average.");

    static final Question ENTRY_Q = Question.choice(
            "You are playing `market` and hold nothing. Decide what to do right now. A share of either side pays 100¢ if that side wins "
                    + "and nothing otherwise, so it is worth buying only if its true chance of winning is higher than its cost with the fee in `odds`. "
                    + "`estimate` gives a random-walk chance for each side and how far its cost sits above or below that; judge from `clock`, `btc`, "
                    + "`binance_flow` and `odds` whether the true chance is higher or lower than the estimate, then choose.",
            ordered(
                    BUY_UP, "Buy UP now: UP's true chance of winning is higher than what a share of UP costs.",
                    BUY_DOWN, "Buy DOWN now: DOWN's true chance of winning is higher than what a share of DOWN costs.",
                    PASS, "Buy nothing now: neither side's true chance of winning is higher than what its share costs."));

    static final Question EXIT_Q = Question.choice(
            "You are playing `market` and hold the bet described in `position`. Decide what to do right now. Held to the close, each share "
                    + "pays 100¢ if your side wins and nothing otherwise, so holding is worth your side's true chance of winning; selling now returns "
                    + "the after-fee amount in `position`. `estimate` gives a random-walk chance for your side; judge from `clock`, `btc`, "
                    + "`binance_flow` and `odds` whether the true chance is higher or lower than that, then choose.",
            ordered(
                    HOLD, "Keep the bet: your side's true chance of winning is worth more than what selling now returns.",
                    SELL, "Sell now: what selling now returns is worth more than your side's true chance of winning."));

    private PredictionQuestions() {
    }

    /** 记分两问每次都带；空仓加入场题，持仓加离场题 */
    static Map<String, Question> questions(boolean holding) {
        Map<String, Question> qs = new LinkedHashMap<>();
        qs.put(UP_WINS, UP_WINS_Q);
        qs.put(DOWN_WINS, DOWN_WINS_Q);
        qs.put(holding ? EXIT : ENTRY, holding ? EXIT_Q : ENTRY_Q);
        return qs;
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
