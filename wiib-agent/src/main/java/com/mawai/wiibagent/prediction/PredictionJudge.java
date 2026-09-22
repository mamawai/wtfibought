package com.mawai.wiibagent.prediction;

import com.mawai.wiibagent.llm.jev.JevClient;
import com.mawai.wiibagent.llm.jev.JevClient.Answer;
import com.mawai.wiibagent.llm.jev.JevPlatformConfig;
import com.mawai.wiibagent.prediction.PredictionStateWriter.Raw;
import com.mawai.wiibagent.prediction.PredictionStateWriter.Snapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.mawai.wiibagent.prediction.PredictionQuestions.DECIDE;
import static com.mawai.wiibagent.prediction.PredictionQuestions.MOMENTUM;

/**
 * 拿着 state 问 Jev：后劲 + 决定。决定取概率最高的那个选项交给规则；后劲换算成修正后的上涨概率，只记分。
 */
@Component
@RequiredArgsConstructor
public class PredictionJudge {

    private final JevClient client;
    private final JevPlatformConfig config;
    private final JevPredictionConfig cfg;

    /**
     * @param pTilted       后劲修正后的上涨概率，只记分
     * @param momentum      还在推减在回吐，−1 … +1
     * @param decision      决定题概率最高的选项
     * @param decisionP     它的概率
     * @param decisionProbs 决定题各选项概率
     */
    public record Judgment(double pModel, double pTilted, double momentum,
                           String decision, double decisionP, Map<String, Double> decisionProbs,
                           Map<String, Answer> answers, String model, int inputTokens, int latencyMs) {
    }

    /** 回包缺题按失败抛出 */
    public Judgment judge(Snapshot snap, boolean holding) {
        long startedAt = System.currentTimeMillis();
        Raw raw = snap.raw();
        JevClient.Response r = client.ask(config.getBaseUrl(), config.getApiKey(), config.getModel(), snap.state(),
                PredictionQuestions.questions(holding));
        Answer mom = r.answers().get(MOMENTUM);
        Answer dec = r.answers().get(DECIDE);
        if (mom == null || dec == null) {
            throw new IllegalStateException("Jev 回包缺题，只有 " + r.answers().keySet());
        }
        Map<String, Double> probs = dec.probabilities();
        Map.Entry<String, Double> top = probs.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
        double momentum = PredictionQuestions.momentum(mom);
        double pTilted = PredictionModel.tilted(raw.pModel(), momentum, raw.driftSign(), cfg.getMomentumTilt());
        return new Judgment(raw.pModel(), pTilted, momentum, top.getKey(), top.getValue(), probs,
                r.answers(), r.model(), (int) r.inputTokens(), (int) (System.currentTimeMillis() - startedAt));
    }
}
