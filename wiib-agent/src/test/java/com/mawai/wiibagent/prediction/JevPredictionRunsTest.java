package com.mawai.wiibagent.prediction;

import com.mawai.wiibagent.mapper.JevPredictionRunMapper;
import com.mawai.wiibcommon.entity.JevPredictionRun;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 开新局、按局号找、一局一个账户名 */
class JevPredictionRunsTest {

    private final JevPredictionRunMapper mapper = mock(JevPredictionRunMapper.class);
    private final JevPredictionAccount account = mock(JevPredictionAccount.class);
    private final JevPredictionRuns runs = new JevPredictionRuns(mapper, account);
    private final JevPredictionRun r1 = new JevPredictionRun(1, null, 100L);
    private final JevPredictionRun r2 = new JevPredictionRun(2, "neutral state v1", 200L);

    @Test
    void 开新局_局号加一_先建号再落库() {
        when(mapper.selectAllDesc()).thenReturn(List.of(r2, r1));

        JevPredictionRun run = runs.startNew("  ");

        InOrder order = inOrder(account, mapper);
        order.verify(account).userId(3);
        ArgumentCaptor<JevPredictionRun> saved = ArgumentCaptor.forClass(JevPredictionRun.class);
        order.verify(mapper).insert(saved.capture());
        assertThat(saved.getValue()).isSameAs(run);
        assertThat(run.getRunNo()).isEqualTo(3);
        assertThat(run.getLabel()).isNull();
        assertThat(runs.startNew(" neutral state v2 ").getLabel()).isEqualTo("neutral state v2");
    }

    @Test
    void 按局号找_没有这一局回当前局() {
        when(mapper.selectAllDesc()).thenReturn(List.of(r2, r1));

        assertThat(JevPredictionRuns.find(runs.all(), 1)).isSameAs(r1);
        assertThat(JevPredictionRuns.find(runs.all(), 9)).isSameAs(r2);
        assertThat(JevPredictionRuns.find(runs.all(), null)).isSameAs(r2);
        assertThat(runs.current()).isSameAs(r2);
    }

    @Test
    void 一局一个账户名_R1沿用老账户() {
        assertThat(JevPredictionAccount.username(1)).isEqualTo("jev-prediction");
        assertThat(JevPredictionAccount.username(3)).isEqualTo("jev-prediction-r3");
    }
}
