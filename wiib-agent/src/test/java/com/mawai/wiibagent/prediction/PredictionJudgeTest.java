package com.mawai.wiibagent.prediction;

import com.mawai.wiibagent.llm.jev.JevClient;
import com.mawai.wiibagent.llm.jev.JevClient.Answer;
import com.mawai.wiibagent.llm.jev.JevClient.Question;
import com.mawai.wiibagent.llm.jev.JevPlatformConfig;
import com.mawai.wiibagent.prediction.PredictionStateWriter.Raw;
import com.mawai.wiibagent.prediction.PredictionStateWriter.Snapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 空仓问后劲和买不买、持仓只问后劲；决定取概率最高的选项；后劲换成修正概率；缺题按失败 */
class PredictionJudgeTest {

    private final JevClient client = mock(JevClient.class);
    private final PredictionJudge judge = new PredictionJudge(client,
            new JevPlatformConfig("sk-platform", "https://api.typesafe.ai", "jev-latest"), JevPredictionRunnerTest.CFG);

    private static Answer score(double p0, double p1, double p2) {
        return new Answer("score", null, null, p1 + 2 * p2, 0.8, Map.of("0", p0, "1", p1, "2", p2));
    }

    private static Answer choice(Map<String, Double> probs) {
        String top = probs.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        return new Answer("choice", null, top, null, 0.9, probs);
    }

    private static Snapshot snapshot(int driftSign, double pModel) {
        return new Snapshot(Map.of("market", "..."), new Raw(1.0, pModel, driftSign, PredictionRulesTest.BOOK, 1L));
    }

    private void answer(Answer momentum, Answer decide) {
        Map<String, Answer> a = new LinkedHashMap<>();
        if (momentum != null) a.put(PredictionQuestions.MOMENTUM, momentum);
        if (decide != null) a.put(PredictionQuestions.DECIDE, decide);
        when(client.ask(any(), any(), any(), any(), any())).thenReturn(new JevClient.Response("jev-1.13.0", a, 820));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 空仓问后劲和下不下_取概率最高的选项() {
        answer(score(0, 0, 1), choice(Map.of("BUY_UP", 0.98, "BUY_DOWN", 0.0, "WAIT", 0.02)));

        PredictionJudge.Judgment j = judge.judge(snapshot(1, 0.7), false);

        ArgumentCaptor<Map<String, Question>> qs = ArgumentCaptor.forClass(Map.class);
        verify(client).ask(eq("https://api.typesafe.ai"), eq("sk-platform"), eq("jev-latest"), any(), qs.capture());
        assertThat(qs.getValue().keySet()).containsExactly(PredictionQuestions.MOMENTUM, PredictionQuestions.DECIDE);
        assertThat(qs.getValue().get(PredictionQuestions.DECIDE)).isSameAs(PredictionQuestions.ENTRY_Q);
        assertThat(j.decision()).isEqualTo("BUY_UP");
        assertThat(j.decisionP()).isEqualTo(0.98);
        assertThat(j.momentum()).isEqualTo(1.0);
        assertThat(j.pModel()).isEqualTo(0.7);
        // Φ(Φ⁻¹(0.7) + 0.3) ≈ 0.795
        assertThat(j.pTilted()).isCloseTo(0.795, within(0.002));
        assertThat(j.inputTokens()).isEqualTo(820);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 持仓只问后劲() {
        answer(score(0.97, 0.01, 0.02), null);

        PredictionJudge.Judgment j = judge.judge(snapshot(1, 0.6), true);

        ArgumentCaptor<Map<String, Question>> qs = ArgumentCaptor.forClass(Map.class);
        verify(client).ask(any(), any(), any(), any(), qs.capture());
        assertThat(qs.getValue().keySet()).containsExactly(PredictionQuestions.MOMENTUM);
        assertThat(j.decision()).isNull();
        assertThat(j.decisionProbs()).isEmpty();
        assertThat(j.momentum()).isCloseTo(-0.95, within(1e-9));
        assertThat(j.pTilted()).isLessThan(0.6);
    }

    @Test
    void 缺题按失败抛() {
        answer(score(0, 1, 0), null);
        assertThatThrownBy(() -> judge.judge(snapshot(1, 0.6), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺题");

        answer(null, choice(Map.of("WAIT", 1.0)));
        assertThatThrownBy(() -> judge.judge(snapshot(1, 0.6), false)).hasMessageContaining("缺题");
    }
}
