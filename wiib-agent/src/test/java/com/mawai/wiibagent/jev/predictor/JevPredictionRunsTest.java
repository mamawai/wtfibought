package com.mawai.wiibagent.jev.predictor;

import com.mawai.wiibagent.mapper.JevPredictionRunMapper;
import com.mawai.wiibcommon.entity.JevPredictionRun;
import com.mawai.wiibquant.external.sim.SimPredictionClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.List;

import static com.mawai.wiibcommon.entity.JevPredictionRun.ARM_JUMP_CODE;
import static com.mawai.wiibcommon.entity.JevPredictionRun.ARM_JUMP_JEV;
import static com.mawai.wiibcommon.entity.JevPredictionRun.ARM_TIMER_JEV;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 开局一次建三局、在跑的局是每组局号最大的、默认看 v5-1 那一局、一局一个账户名、新号按配置注资 */
class JevPredictionRunsTest {

    private final JevPredictionRunMapper mapper = mock(JevPredictionRunMapper.class);
    private final JevPredictionAccount account = mock(JevPredictionAccount.class);
    private final JevPredictionRuns runs = new JevPredictionRuns(mapper, account, JevPredictionRunnerTest.CFG);
    private static final BigDecimal OLD = new BigDecimal("100");
    private static final BigDecimal NEW = new BigDecimal("500");
    private final JevPredictionRun r1 = new JevPredictionRun(1, null, 100L, null, OLD);
    private final JevPredictionRun r2 = new JevPredictionRun(2, "neutral state v1", 200L, null, OLD);
    /** 开过两次三组：R3–R5、R6–R8 */
    private final List<JevPredictionRun> twice = List.of(
            new JevPredictionRun(8, "r5b", 800L, ARM_TIMER_JEV, NEW), new JevPredictionRun(7, "r5b", 800L, ARM_JUMP_JEV, NEW),
            new JevPredictionRun(6, "r5b", 800L, ARM_JUMP_CODE, NEW), new JevPredictionRun(5, "r5", 500L, ARM_TIMER_JEV, NEW),
            new JevPredictionRun(4, "r5", 500L, ARM_JUMP_JEV, NEW), new JevPredictionRun(3, "r5", 500L, ARM_JUMP_CODE, NEW), r2, r1);

    @Test
    void 开新局一次建三局_局号连着_先把三个号建好再落库() {
        when(mapper.selectAllDesc()).thenReturn(List.of(r2, r1));

        List<JevPredictionRun> created = runs.startNew("  ");

        assertThat(created).extracting(JevPredictionRun::getRunNo).containsExactly(3, 4, 5);
        assertThat(created).extracting(JevPredictionRun::getArm).containsExactly(ARM_JUMP_CODE, ARM_JUMP_JEV, ARM_TIMER_JEV);
        assertThat(created).allSatisfy(r -> {
            assertThat(r.getLabel()).isNull();
            assertThat(r.getInitialBalance()).isEqualByComparingTo("500");
            assertThat(r.getStartedAt()).isEqualTo(created.getFirst().getStartedAt());
        });
        InOrder order = inOrder(account, mapper);
        order.verify(account).userId(3);
        order.verify(account).userId(4);
        order.verify(account).userId(5);
        ArgumentCaptor<JevPredictionRun> saved = ArgumentCaptor.forClass(JevPredictionRun.class);
        order.verify(mapper, times(3)).insert(saved.capture());
        assertThat(saved.getAllValues()).containsExactlyElementsOf(created);
        assertThat(runs.startNew(" r5 ").getFirst().getLabel()).isEqualTo("r5");
    }

    @Test
    void 在跑的局是每组局号最大的那一局_没开过带组的局是空的() {
        when(mapper.selectAllDesc()).thenReturn(twice);
        assertThat(runs.active()).extracting(JevPredictionRun::getRunNo).containsExactly(6, 7, 8);

        when(mapper.selectAllDesc()).thenReturn(List.of(r2, r1));
        assertThat(runs.active()).isEmpty();
    }

    @Test
    void 按局号找_没传或没有这一局看v5_1在跑的那一局_没开过带组的局看最新一局() {
        assertThat(JevPredictionRuns.find(twice, 4).getRunNo()).isEqualTo(4);
        assertThat(JevPredictionRuns.find(twice, 1)).isSameAs(r1);
        assertThat(JevPredictionRuns.find(twice, null).getRunNo()).isEqualTo(6);
        assertThat(JevPredictionRuns.find(twice, 99).getRunNo()).isEqualTo(6);
        assertThat(JevPredictionRuns.find(List.of(r2, r1), null)).isSameAs(r2);
    }

    @Test
    void 一局一个账户名_R1沿用老账户_新号按配置注资() {
        assertThat(JevPredictionAccount.username(1)).isEqualTo("jev-prediction");
        assertThat(JevPredictionAccount.username(3)).isEqualTo("jev-prediction-r3");

        SimPredictionClient sim = mock(SimPredictionClient.class);
        when(sim.ensureAccount(any(), any())).thenReturn(77L);
        JevPredictionAccount real = new JevPredictionAccount(sim, JevPredictionRunnerTest.CFG);
        assertThat(real.userId(3)).isEqualTo(77L);
        assertThat(real.userId(3)).isEqualTo(77L);
        // 记住了就不再去 sim
        verify(sim, times(1)).ensureAccount("jev-prediction-r3", NEW);
    }
}
