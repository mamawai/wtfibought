package com.mawai.wiibagent.prediction;

import com.mawai.wiibagent.llm.jev.JevClient;
import com.mawai.wiibagent.llm.jev.JevClient.Answer;
import com.mawai.wiibagent.llm.jev.JevPlatformConfig;
import com.mawai.wiibagent.prediction.PredictionStateWriter.Snapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import static com.mawai.wiibagent.prediction.PredictionQuestions.BUY_DOWN;
import static com.mawai.wiibagent.prediction.PredictionQuestions.BUY_UP;
import static com.mawai.wiibagent.prediction.PredictionQuestions.DOWN_WINS;
import static com.mawai.wiibagent.prediction.PredictionQuestions.ENTRY;
import static com.mawai.wiibagent.prediction.PredictionQuestions.EXIT;
import static com.mawai.wiibagent.prediction.PredictionQuestions.HOLD;
import static com.mawai.wiibagent.prediction.PredictionQuestions.PASS;
import static com.mawai.wiibagent.prediction.PredictionQuestions.SELL;
import static com.mawai.wiibagent.prediction.PredictionQuestions.UP_WINS;

/** 拿着 state 问 Jev：空仓买不买、买哪边，持仓拿着还是卖；顺带正反两问平均成 Jev 的上涨概率记分 */
@Component
@RequiredArgsConstructor
public class PredictionJudge {

    private static final Set<String> ENTRY_OPTIONS = Set.of(BUY_UP, BUY_DOWN, PASS);
    private static final Set<String> EXIT_OPTIONS = Set.of(HOLD, SELL);

    private final JevClient client;
    private final JevPlatformConfig config;

    /**
     * @param pModel   纯数学的上涨概率，原样带着
     * @param pJev     Jev 的上涨概率：UP 会赢的概率和 1 − DOWN 会赢的概率取平均，只记分
     * @param decision Jev 的拍板：空仓是入场题的回答，持仓是离场题的回答
     */
    public record Judgment(double pModel, double pJev, Answer decision, Map<String, Answer> answers, String model,
                           int inputTokens, int latencyMs) {

        /** Jev 选的那一项的概率 */
        public double choiceP() {
            return decision.probabilities().get(decision.choice());
        }
    }

    /** 回包缺题、没选或选了不在选项里的、没给选中项概率的都按失败抛出 */
    public Judgment judge(Snapshot snap, boolean holding) {
        long startedAt = System.currentTimeMillis();
        JevClient.Response r = client.ask(config.getBaseUrl(), config.getApiKey(), config.getModel(), snap.state(),
                PredictionQuestions.questions(holding));
        Answer up = r.answers().get(UP_WINS);
        Answer down = r.answers().get(DOWN_WINS);
        Answer decision = r.answers().get(holding ? EXIT : ENTRY);
        if (up == null || down == null || decision == null) {
            throw new IllegalStateException("Jev 回包缺题，只有 " + r.answers().keySet());
        }
        Set<String> options = holding ? EXIT_OPTIONS : ENTRY_OPTIONS;
        if (decision.choice() == null || !options.contains(decision.choice()) || decision.probabilities() == null
                || decision.probabilities().get(decision.choice()) == null) {
            throw new IllegalStateException("Jev 的选择不对: " + decision.choice() + " " + decision.probabilities());
        }
        return new Judgment(snap.raw().pModel(), (up.noul() + 1 - down.noul()) / 2, decision, r.answers(), r.model(),
                (int) r.inputTokens(), (int) (System.currentTimeMillis() - startedAt));
    }
}
