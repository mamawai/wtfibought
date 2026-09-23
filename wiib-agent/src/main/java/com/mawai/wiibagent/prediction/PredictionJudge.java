package com.mawai.wiibagent.prediction;

import com.mawai.wiibagent.llm.jev.JevClient;
import com.mawai.wiibagent.llm.jev.JevClient.Answer;
import com.mawai.wiibagent.llm.jev.JevPlatformConfig;
import com.mawai.wiibagent.prediction.PredictionStateWriter.Snapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.mawai.wiibagent.prediction.PredictionQuestions.DOWN_WINS;
import static com.mawai.wiibagent.prediction.PredictionQuestions.UP_WINS;

/** 拿着 state 问 Jev 这一回合谁会赢，正反两种问法平均成 Jev 的上涨概率 */
@Component
@RequiredArgsConstructor
public class PredictionJudge {

    private final JevClient client;
    private final JevPlatformConfig config;

    /**
     * @param pModel 纯数学的上涨概率，原样带着给规则
     * @param pJev   Jev 的上涨概率：UP 会赢的概率和 1 − DOWN 会赢的概率取平均
     */
    public record Judgment(double pModel, double pJev, Map<String, Answer> answers, String model, int inputTokens, int latencyMs) {
    }

    /** 回包缺题按失败抛出 */
    public Judgment judge(Snapshot snap) {
        long startedAt = System.currentTimeMillis();
        JevClient.Response r = client.ask(config.getBaseUrl(), config.getApiKey(), config.getModel(), snap.state(),
                PredictionQuestions.QUESTIONS);
        Answer up = r.answers().get(UP_WINS);
        Answer down = r.answers().get(DOWN_WINS);
        if (up == null || down == null) {
            throw new IllegalStateException("Jev 回包缺题，只有 " + r.answers().keySet());
        }
        return new Judgment(snap.raw().pModel(), (up.noul() + 1 - down.noul()) / 2, r.answers(), r.model(),
                (int) r.inputTokens(), (int) (System.currentTimeMillis() - startedAt));
    }
}
