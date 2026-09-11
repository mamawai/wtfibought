package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 休眠提示是纯函数：不起图、不起 Spring，直接测措辞与时刻 */
class TraderWakeupRunnerSleepNoticeTest {

    private static final PromptCatalog PROMPTS = new PromptCatalog();

    private static final long H1 = 3_600_000L;

    private static long bj(int h, int m) {
        return ZonedDateTime.of(2026, 7, 27, h, m, 0, 0, WakeWindow.ZONE).toInstant().toEpochMilli();
    }

    /** 21:00-08:00 的 08:00 是末次：说清到哪根为止、下次时刻（北京 21:00）与约 13 小时，反偏置两个方向都堵 */
    @Test
    void lastBoundaryGetsNotice() {
        String s = TraderWakeupRunner.sleepNotice(PROMPTS, AgentLang.ZH, WakeWindow.parse("21:00-08:00"), bj(8, 0), H1, bj(8, 0));

        assertThat(s).contains("到 07-27 08:00 这根K线为止")
                .contains("下次例行唤醒在 07-27 21:00")
                .contains("约 13.0 小时后")
                .contains("既不是平仓或收紧止损的理由，也不是赶在休眠前多开一笔的理由");
    }

    /** 手动轮晚于边界 20 分钟叫醒：小时数从 now 算（12.7），措辞仍成立 */
    @Test
    void manualWakeAfterBoundaryCountsHoursFromNow() {
        String s = TraderWakeupRunner.sleepNotice(PROMPTS, AgentLang.ZH, WakeWindow.parse("21:00-08:00"), bj(8, 0), H1, bj(8, 20));

        assertThat(s).contains("约 12.7 小时后");
    }

    /** 不是末次 / 全天：一个字都不加 */
    @Test
    void notLastOrAllDayIsEmpty() {
        assertThat(TraderWakeupRunner.sleepNotice(PROMPTS, AgentLang.ZH, WakeWindow.parse("21:00-08:00"), bj(7, 0), H1, bj(7, 0))).isEmpty();
        assertThat(TraderWakeupRunner.sleepNotice(PROMPTS, AgentLang.ZH, null, bj(8, 0), H1, bj(8, 0))).isEmpty();
    }
}
