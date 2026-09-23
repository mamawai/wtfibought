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

/** 正反两问一次发，平均成 Jev 的上涨概率；缺题按失败 */
class PredictionJudgeTest {

    private final JevClient client = mock(JevClient.class);
    private final PredictionJudge judge = new PredictionJudge(client,
            new JevPlatformConfig("sk-platform", "https://api.typesafe.ai", "jev-latest"));
    private static final Snapshot SNAP = new Snapshot(Map.of("market", "..."), new Raw(1.0, 0.7, PredictionRulesTest.BOOK, 1L));

    private static Answer noul(double p) {
        return new Answer("noul", p, null, null, null, null);
    }

    private void answer(Map<String, Answer> answers) {
        when(client.ask(any(), any(), any(), any(), any())).thenReturn(new JevClient.Response("jev-1.13.0", answers, 820));
    }

    @Test
    void 正反两问取平均() {
        answer(Map.of(PredictionQuestions.UP_WINS, noul(0.62), PredictionQuestions.DOWN_WINS, noul(0.30)));

        PredictionJudge.Judgment j = judge.judge(SNAP);

        verify(client).ask(eq("https://api.typesafe.ai"), eq("sk-platform"), eq("jev-latest"), eq(SNAP.state()),
                eq(PredictionQuestions.QUESTIONS));
        // (0.62 + 1 − 0.30) / 2
        assertThat(j.pJev()).isCloseTo(0.66, within(1e-9));
        assertThat(j.pModel()).isEqualTo(0.7);
        assertThat(j.inputTokens()).isEqualTo(820);
    }

    @Test
    void 缺一道就按失败抛() {
        answer(Map.of(PredictionQuestions.UP_WINS, noul(0.62)));
        assertThatThrownBy(() -> judge.judge(SNAP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺题");
    }
}
