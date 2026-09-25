package com.mawai.wiibagent.prediction;

import com.mawai.wiibagent.llm.jev.JevClient;
import com.mawai.wiibagent.llm.jev.JevClient.Answer;
import com.mawai.wiibagent.llm.jev.JevPlatformConfig;
import com.mawai.wiibagent.prediction.PredictionStateWriter.Raw;
import com.mawai.wiibagent.prediction.PredictionStateWriter.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 只发一道入场题，空仓持仓一样；缺题、选项不对按失败 */
class PredictionJudgeTest {

    private final JevClient client = mock(JevClient.class);
    private final PredictionJudge judge = new PredictionJudge(client,
            new JevPlatformConfig("sk-platform", "https://api.typesafe.ai", "jev-latest"));
    private static final Snapshot SNAP = new Snapshot(Map.of("market", "..."),
            new Raw(1.0, 0.7, PredictionRulesTest.BOOK, 1L, 800, 0.0, 0.0));

    private static Answer choice(String pick, Map<String, Double> probs) {
        return new Answer("choice", null, pick, null, 0.8, probs);
    }

    private void answer(Map<String, Answer> answers) {
        when(client.ask(any(), any(), any(), any(), any())).thenReturn(new JevClient.Response("jev-1.13.0", answers, 820));
    }

    @Test
    void 只发入场题_选择和概率原样带回() {
        answer(Map.of(PredictionQuestions.ENTRY, choice("BUY_UP", Map.of("BUY_UP", 0.6, "BUY_DOWN", 0.1, "PASS", 0.3))));

        PredictionJudge.Judgment j = judge.judge(SNAP);

        verify(client).ask(eq("https://api.typesafe.ai"), eq("sk-platform"), eq("jev-latest"), eq(SNAP.state()),
                eq(PredictionQuestions.questions()));
        assertThat(PredictionQuestions.questions()).containsOnlyKeys("entry");
        assertThat(j.pModel()).isEqualTo(0.7);
        assertThat(j.decision().choice()).isEqualTo("BUY_UP");
        assertThat(j.choiceP()).isEqualTo(0.6);
        assertThat(j.inputTokens()).isEqualTo(820);
    }

    @Test
    void 选项顺序固定UP在前() {
        @SuppressWarnings("unchecked")
        Map<String, String> options = (Map<String, String>) PredictionQuestions.ENTRY_Q.criteria();
        assertThat(options.keySet()).containsExactly("BUY_UP", "BUY_DOWN", "PASS");
    }

    @Test
    void 缺题_选项不对_没给选中项概率_都按失败抛() {
        answer(Map.of());
        assertThatThrownBy(() -> judge.judge(SNAP)).hasMessageContaining("缺题");

        answer(Map.of(PredictionQuestions.ENTRY, choice("SELL", Map.of("SELL", 0.9))));
        assertThatThrownBy(() -> judge.judge(SNAP)).hasMessageContaining("选择不对");

        answer(Map.of(PredictionQuestions.ENTRY, choice("PASS", Map.of("BUY_UP", 0.2))));
        assertThatThrownBy(() -> judge.judge(SNAP)).hasMessageContaining("选择不对");

        answer(Map.of(PredictionQuestions.ENTRY, choice(null, Map.of("PASS", 0.9))));
        assertThatThrownBy(() -> judge.judge(SNAP)).hasMessageContaining("选择不对");
    }
}
