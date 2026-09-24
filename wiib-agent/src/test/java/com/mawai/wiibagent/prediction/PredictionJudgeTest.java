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
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 空仓发入场题、持仓发离场题，谁赢两问每次都带、平均成 Jev 的上涨概率；缺题、选项不对按失败 */
class PredictionJudgeTest {

    private final JevClient client = mock(JevClient.class);
    private final PredictionJudge judge = new PredictionJudge(client,
            new JevPlatformConfig("sk-platform", "https://api.typesafe.ai", "jev-latest"));
    private static final Snapshot SNAP = new Snapshot(Map.of("market", "..."), new Raw(1.0, 0.7, PredictionRulesTest.BOOK, 1L, 800));

    private static Answer noul(double p) {
        return new Answer("noul", p, null, null, null, null);
    }

    private static Answer choice(String pick, Map<String, Double> probs) {
        return new Answer("choice", null, pick, null, 0.8, probs);
    }

    private void answer(Map<String, Answer> answers) {
        when(client.ask(any(), any(), any(), any(), any())).thenReturn(new JevClient.Response("jev-1.13.0", answers, 820));
    }

    @Test
    void 空仓_发入场题_谁赢两问取平均() {
        answer(Map.of(PredictionQuestions.UP_WINS, noul(0.62), PredictionQuestions.DOWN_WINS, noul(0.30),
                PredictionQuestions.ENTRY, choice("BUY_UP", Map.of("BUY_UP", 0.6, "BUY_DOWN", 0.1, "PASS", 0.3))));

        PredictionJudge.Judgment j = judge.judge(SNAP, false);

        verify(client).ask(eq("https://api.typesafe.ai"), eq("sk-platform"), eq("jev-latest"), eq(SNAP.state()),
                eq(PredictionQuestions.questions(false)));
        // (0.62 + 1 − 0.30) / 2
        assertThat(j.pJev()).isCloseTo(0.66, within(1e-9));
        assertThat(j.pModel()).isEqualTo(0.7);
        assertThat(j.decision().choice()).isEqualTo("BUY_UP");
        assertThat(j.choiceP()).isEqualTo(0.6);
        assertThat(j.inputTokens()).isEqualTo(820);
    }

    @Test
    void 持仓_发离场题() {
        answer(Map.of(PredictionQuestions.UP_WINS, noul(0.4), PredictionQuestions.DOWN_WINS, noul(0.5),
                PredictionQuestions.EXIT, choice("SELL", Map.of("HOLD", 0.35, "SELL", 0.65))));

        PredictionJudge.Judgment j = judge.judge(SNAP, true);

        verify(client).ask(any(), any(), any(), any(), eq(PredictionQuestions.questions(true)));
        assertThat(PredictionQuestions.questions(true)).containsOnlyKeys("up_wins", "down_wins", "exit");
        assertThat(j.decision().choice()).isEqualTo("SELL");
        assertThat(j.choiceP()).isEqualTo(0.65);
    }

    @Test
    void 缺题_选项不对_没给选中项概率_都按失败抛() {
        answer(Map.of(PredictionQuestions.UP_WINS, noul(0.62), PredictionQuestions.DOWN_WINS, noul(0.3)));
        assertThatThrownBy(() -> judge.judge(SNAP, false)).hasMessageContaining("缺题");

        answer(Map.of(PredictionQuestions.UP_WINS, noul(0.62), PredictionQuestions.DOWN_WINS, noul(0.3),
                PredictionQuestions.ENTRY, choice("SELL", Map.of("SELL", 0.9))));
        assertThatThrownBy(() -> judge.judge(SNAP, false)).hasMessageContaining("选择不对");

        answer(Map.of(PredictionQuestions.UP_WINS, noul(0.62), PredictionQuestions.DOWN_WINS, noul(0.3),
                PredictionQuestions.ENTRY, choice("PASS", Map.of("BUY_UP", 0.2))));
        assertThatThrownBy(() -> judge.judge(SNAP, false)).hasMessageContaining("选择不对");

        answer(Map.of(PredictionQuestions.UP_WINS, noul(0.62), PredictionQuestions.DOWN_WINS, noul(0.3),
                PredictionQuestions.ENTRY, choice(null, Map.of("PASS", 0.9))));
        assertThatThrownBy(() -> judge.judge(SNAP, false)).hasMessageContaining("选择不对");
    }
}
