package com.mawai.wiibservice.agent.trading.watching.playbook;

import com.mawai.wiibservice.agent.trading.ExitPath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExitPlaybookSkeletonTest {

    @Test
    void emptyPlaybooksExposePathsAndReturnHold() {
        List<ExitPlaybook> playbooks = List.of(
                new BreakoutExitPlaybook(),
                new TrendExitPlaybook(),
                new MeanReversionExitPlaybook());

        assertThat(playbooks).extracting(ExitPlaybook::path)
                .containsExactly(ExitPath.BREAKOUT, ExitPath.TREND, ExitPath.MR);
        assertThat(playbooks)
                .allSatisfy(playbook -> {
                    ExitPlaybookDecision decision = playbook.evaluate(null, null, null, null);
                    assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
                    assertThat(decision.actionable()).isFalse();
                    assertThat(decision.blockedHold()).isTrue();
                });
    }

    @Test
    void decisionFactoriesPopulateOnlyRelevantFields() {
        ExitPlaybookDecision hold = ExitPlaybookDecision.hold("hold");
        ExitPlaybookDecision blocked = ExitPlaybookDecision.holdBlocked("blocked");
        ExitPlaybookDecision partial = ExitPlaybookDecision.closePartial(new BigDecimal("0.30"), "partial");
        ExitPlaybookDecision stop = ExitPlaybookDecision.moveStop(new BigDecimal("101"), "move");
        ExitPlaybookDecision mrHalf = ExitPlaybookDecision.closePartialAndMoveStop(
                new BigDecimal("0.50"), new BigDecimal("100"), true, true, "mr");

        assertThat(hold.blockedHold()).isFalse();
        assertThat(blocked.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
        assertThat(blocked.blockedHold()).isTrue();

        assertThat(partial.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_PARTIAL);
        assertThat(partial.closeQuantity()).isEqualByComparingTo("0.30");
        assertThat(partial.stopLossPrice()).isNull();
        assertThat(partial.partialMilestoneR()).isNull();
        assertThat(partial.actionable()).isTrue();
        assertThat(partial.blockedHold()).isFalse();

        assertThat(stop.action()).isEqualTo(ExitPlaybookDecision.Action.MOVE_STOP);
        assertThat(stop.stopLossPrice()).isEqualByComparingTo("101");
        assertThat(stop.closeQuantity()).isNull();
        assertThat(stop.markBreakevenDone()).isFalse();
        assertThat(stop.actionable()).isTrue();

        assertThat(mrHalf.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_PARTIAL);
        assertThat(mrHalf.closeQuantity()).isEqualByComparingTo("0.50");
        assertThat(mrHalf.stopLossPrice()).isEqualByComparingTo("100");
        assertThat(mrHalf.markBreakevenDone()).isTrue();
        assertThat(mrHalf.markMrMidlineHalfDone()).isTrue();
    }
}
