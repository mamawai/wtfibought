package com.mawai.wiibagent.jev.predictor;

import com.mawai.wiibagent.jev.JevClient;
import com.mawai.wiibagent.jev.JevClient.Answer;
import com.mawai.wiibagent.jev.JevPlatformConfig;
import com.mawai.wiibagent.jev.predictor.PredictionStateWriter.Snapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import static com.mawai.wiibagent.jev.predictor.PredictionQuestions.CASCADE;
import static com.mawai.wiibagent.jev.predictor.PredictionQuestions.CHOP;
import static com.mawai.wiibagent.jev.predictor.PredictionQuestions.DIP_RECOVERED;
import static com.mawai.wiibagent.jev.predictor.PredictionQuestions.FLOW_CONFIRMS;
import static com.mawai.wiibagent.jev.predictor.PredictionQuestions.LATEST_AGAINST;
import static com.mawai.wiibagent.jev.predictor.PredictionQuestions.NEITHER;
import static com.mawai.wiibagent.jev.predictor.PredictionQuestions.PATTERN;
import static com.mawai.wiibagent.jev.predictor.PredictionQuestions.PUSH_FADING;
import static com.mawai.wiibagent.jev.predictor.PredictionQuestions.WIN;

/** 拿着 state 问 Jev 六道盘面题，题按看的那一边出 */
@Component
@RequiredArgsConstructor
public class PredictionJudge {

    private static final Set<String> PATTERNS = Set.of(CASCADE, CHOP, NEITHER);

    private final JevClient client;
    private final JevPlatformConfig config;

    /**
     * Jev 的回答，规则要用的三个单独拿出来，概率都是"是"的概率
     *
     * @param side          看的那一边 UP / DOWN
     * @param win           看的那一边会赢
     * @param pushFading    看的那一边在变弱
     * @param latestAgainst 最新一步逆着看的那一边
     * @param answers       六道题的回包原样，落库用
     */
    public record Judgment(String side, double win, double pushFading, double latestAgainst,
                           Map<String, Answer> answers, String model, int inputTokens, int latencyMs) {

        /** Jev 的上涨概率：看 UP 时就是 win，看 DOWN 时是 1 − win */
        public double pJev() {
            return "UP".equals(side) ? win : 1 - win;
        }
    }

    /** 六道题缺一道、是非题没给数、pattern 没选或选了不在选项里、没给选中项概率的，都按失败抛出 */
    public Judgment judge(Snapshot snap, String side) {
        long startedAt = System.currentTimeMillis();
        JevClient.Response r = client.ask(config.getBaseUrl(), config.getApiKey(), config.getModel(), snap.state(),
                PredictionQuestions.questions(side, snap.raw().jump() != null));
        Map<String, Answer> a = r.answers();
        Answer pattern = answer(a, PATTERN);
        if (pattern.choice() == null || !PATTERNS.contains(pattern.choice()) || pattern.probabilities() == null
                || pattern.probabilities().get(pattern.choice()) == null) {
            throw new IllegalStateException("Jev 的形态选择不对: " + pattern.choice() + " " + pattern.probabilities());
        }
        // 只记录的两道也要有数
        noul(a, FLOW_CONFIRMS);
        noul(a, DIP_RECOVERED);
        return new Judgment(side, noul(a, WIN), noul(a, PUSH_FADING), noul(a, LATEST_AGAINST), a, r.model(),
                (int) r.inputTokens(), (int) (System.currentTimeMillis() - startedAt));
    }

    private static Answer answer(Map<String, Answer> answers, String key) {
        Answer x = answers.get(key);
        if (x == null) {
            throw new IllegalStateException("Jev 回包缺题 " + key + "，只有 " + answers.keySet());
        }
        return x;
    }

    /** 是非题的概率，没给数按失败 */
    private static double noul(Map<String, Answer> answers, String key) {
        Double p = answer(answers, key).noul();
        if (p == null) {
            throw new IllegalStateException("Jev 没给 " + key + " 的概率");
        }
        return p;
    }
}
