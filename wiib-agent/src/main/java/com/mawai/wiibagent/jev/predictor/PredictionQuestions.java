package com.mawai.wiibagent.jev.predictor;

import com.mawai.wiibagent.jev.JevClient.Question;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 问 Jev 的题：每次唤醒问六道盘面题，只问盘面是什么样，不问买不买。题目一律英文。
 * 题里直接写看的那一边（UP 或 DOWN），另一边的题是这一边的镜像：UP ↔ DOWN、at or above ↔ below、buyers ↔ sellers、rise ↔ fall。
 * <ul>
 *   <li>win：看的那一边会赢，换算成 p_jev 记分；v5-3 持仓时太低就卖</li>
 *   <li>pattern：BTC 现在是单边、来回还是说不上，只记录</li>
 *   <li>push_fading：看的那一边在变弱，v5-2 太像就不买</li>
 *   <li>flow_confirms：Binance 主动成交和看的那一边同向，只记录</li>
 *   <li>dip_recovered：这一局逆过又顺回来，只记录</li>
 *   <li>latest_against：最新一步逆着看的那一边，v5-3 空仓时是才买</li>
 * </ul>
 * 选项、判据按写的顺序发。
 */
final class PredictionQuestions {

    static final String WIN = "win";
    static final String PATTERN = "pattern";
    static final String PUSH_FADING = "push_fading";
    static final String FLOW_CONFIRMS = "flow_confirms";
    static final String DIP_RECOVERED = "dip_recovered";
    static final String LATEST_AGAINST = "latest_against";

    static final String CASCADE = "cascade";
    static final String CHOP = "chop";
    static final String NEITHER = "neither";

    /** story 的引用说明，四道过程题共用 */
    private static final String STORY_REF =
            "`story` lists what BTC did in each step since the open, oldest first; a rise favours UP and a fall favours DOWN.";

    static final Question PATTERN_Q = Question.choice(
            "What kind of move is BTC in right now, judging by `story`? `story` lists what BTC did in each step since the open, oldest first.",
            ordered(
                    CASCADE, "A one-way cascade: price keeps pushing in one direction with little bounce, and trading pushes the same way.",
                    CHOP, "Chopping in a range: price swings back and forth around a level, and moves against it get taken back.",
                    NEITHER, "No clear pattern, or price is barely moving."));

    private PredictionQuestions() {
    }

    /**
     * 六道题，按 win、pattern、push_fading、flow_confirms、dip_recovered、latest_against 的顺序
     *
     * @param side    看的那一边 UP / DOWN
     * @param hasJump 突变唤醒：state 里有 jump，win 题末尾多一句
     */
    static Map<String, Question> questions(String side, boolean hasJump) {
        boolean up = "UP".equals(side);
        String other = up ? "DOWN" : "UP";
        String win = side + " will win this round. " + side + " wins if BTC's average price over the final minute is "
                + (up ? "at or above" : "below") + " the opening average. `btc_now` and `lead` say where BTC stands, `story` lists what "
                + "BTC did in each step since the open, oldest first, `odds_history` lists what the market priced each side at after "
                + "each step, and `clock` says how much time is left."
                + (hasJump ? " `jump` says how the market's prices just moved." : "");

        Map<String, Question> q = new LinkedHashMap<>();
        q.put(WIN, noul(win, side + " wins this round.", other + " wins this round."));
        q.put(PATTERN, PATTERN_Q);
        q.put(PUSH_FADING, noul(
                side + " is losing strength: BTC's latest step is weaker in " + side + "'s favour than its earlier steps. " + STORY_REF,
                "In the latest step BTC barely moved or moved against " + side + ", after earlier steps moved clearly in " + side + "'s favour.",
                "In the latest step BTC moved in " + side + "'s favour at least as strongly as in the earlier steps."));
        q.put(FLOW_CONFIRMS, noul(
                "Right now trading on Binance is pushing in " + side + "'s favour. The latest step in `story` says who is ahead among "
                        + "Binance takers; buyers favour UP and sellers favour DOWN.",
                (up ? "Buyers" : "Sellers") + " are ahead among Binance takers in the latest step.",
                "Takers are balanced, or " + (up ? "sellers" : "buyers") + " are ahead."));
        q.put(DIP_RECOVERED, noul(
                "During this round BTC moved against " + side + " in an earlier step and moved in " + side + "'s favour again in a later step. "
                        + STORY_REF,
                "An earlier step moved against " + side + ", and a later step moved in " + side + "'s favour.",
                "No step moved against " + side + ", or no later step moved back in " + side + "'s favour."));
        q.put(LATEST_AGAINST, noul(
                "In the latest step BTC moved against " + side + ". " + STORY_REF,
                "The latest step in `story` is a " + (up ? "fall" : "rise") + ", which goes against " + side + ".",
                "The latest step in `story` is a " + (up ? "rise" : "fall") + " or barely moved."));
        return q;
    }

    /** 是非题，判据 true 在前 */
    private static Question noul(String instructions, String yes, String no) {
        return new Question("noul", instructions, ordered("true", yes, "false", no));
    }

    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
