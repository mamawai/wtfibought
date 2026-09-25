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
import static com.mawai.wiibagent.prediction.PredictionQuestions.ENTRY;
import static com.mawai.wiibagent.prediction.PredictionQuestions.PASS;

/** 拿着 state 问 Jev：买 UP / 买 DOWN / 不买，空仓持仓同一题 */
@Component
@RequiredArgsConstructor
public class PredictionJudge {

    private static final Set<String> OPTIONS = Set.of(BUY_UP, BUY_DOWN, PASS);

    private final JevClient client;
    private final JevPlatformConfig config;

    /**
     * @param pModel   纯数学的上涨概率，原样带着，记分和算页面上的数学参考用
     * @param decision Jev 的拍板
     */
    public record Judgment(double pModel, Answer decision, Map<String, Answer> answers, String model, int inputTokens, int latencyMs) {

        /** Jev 选的那一项的概率 */
        public double choiceP() {
            return decision.probabilities().get(decision.choice());
        }
    }

    /** 回包缺题、没选或选了不在选项里的、没给选中项概率的都按失败抛出 */
    public Judgment judge(Snapshot snap) {
        long startedAt = System.currentTimeMillis();
        JevClient.Response r = client.ask(config.getBaseUrl(), config.getApiKey(), config.getModel(), snap.state(),
                PredictionQuestions.questions());
        Answer decision = r.answers().get(ENTRY);
        if (decision == null) {
            throw new IllegalStateException("Jev 回包缺题，只有 " + r.answers().keySet());
        }
        if (decision.choice() == null || !OPTIONS.contains(decision.choice()) || decision.probabilities() == null
                || decision.probabilities().get(decision.choice()) == null) {
            throw new IllegalStateException("Jev 的选择不对: " + decision.choice() + " " + decision.probabilities());
        }
        return new Judgment(snap.raw().pModel(), decision, r.answers(), r.model(), (int) r.inputTokens(),
                (int) (System.currentTimeMillis() - startedAt));
    }
}
