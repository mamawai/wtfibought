package com.mawai.wiibagent.prediction;

import com.mawai.wiibagent.llm.jev.JevClient.Question;

import java.util.Map;

/**
 * 问 Jev 的题：这一回合 UP 会不会赢、DOWN 会不会赢，同一件事正反各问一次，一次请求发。
 * 买不买、买哪边由代码拿两种问法平均出的胜率比两边的成本定。题目一律英文。
 */
final class PredictionQuestions {

    static final String UP_WINS = "up_wins";
    static final String DOWN_WINS = "down_wins";

    static final Map<String, Question> QUESTIONS = Map.of(
            UP_WINS, Question.noul(
                    "Will this window settle UP: BTC's average price over the final minute at or above its average at the open?",
                    "UP wins: the final-minute average ends at or above the opening average.",
                    "DOWN wins: the final-minute average ends below the opening average."),
            DOWN_WINS, Question.noul(
                    "Will this window settle DOWN: BTC's average price over the final minute below its average at the open?",
                    "DOWN wins: the final-minute average ends below the opening average.",
                    "UP wins: the final-minute average ends at or above the opening average."));

    private PredictionQuestions() {
    }
}
