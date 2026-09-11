package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WakeWindowTest {

    private static final long M5 = 300_000L;
    private static final long H1 = 3_600_000L;
    private static final long H4 = 14_400_000L;

    /** 北京时间 2026-07-27 hh:mm 的 epoch ms（时区跟 WakeWindow.ZONE 走，测试与机器 TZ 无关） */
    private static long bj(int h, int m) {
        return ZonedDateTime.of(2026, 7, 27, h, m, 0, 0, WakeWindow.ZONE).toInstant().toEpochMilli();
    }

    @Test
    void parseNullOrBlankMeansAllDay() {
        assertThat(WakeWindow.parse(null)).isNull();
        assertThat(WakeWindow.parse("  ")).isNull();
    }

    @Test
    void parseValidAndRoundTripsText() {
        WakeWindow w = WakeWindow.parse("21:00-08:30");
        assertThat(w.fromMin()).isEqualTo(21 * 60);
        assertThat(w.toMin()).isEqualTo(8 * 60 + 30);
        assertThat(w.text()).isEqualTo("21:00-08:30");
    }

    /**
     * 四种不合法各抛各的 key（parse 抛的是词表 key 不是成文的话，见它的注释）。
     * 顺带钉住 key 在两门语言的词表里都真有条目——抛出去却查不到，用户看到的就是一行 key。
     */
    @Test
    void parseRejectsBadFormatGranularityAndSameEnds() {
        MessageCatalog messages = new MessageCatalog();
        Map<String, String> cases = Map.of(
                "21:00~08:30", "trader.config.window.format",
                "21:03-08:30", "trader.config.window.minuteStep",
                "24:00-08:30", "trader.config.window.badTime",
                "08:00-08:00", "trader.config.window.sameEnds");
        cases.forEach((text, key) -> {
            assertThatThrownBy(() -> WakeWindow.parse(text))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(key);
            for (AgentLang lang : AgentLang.values()) {
                assertThat(messages.get(lang, key)).as("%s 的 %s", lang.code(), key).isNotBlank();
            }
        });
    }

    /** 不跨午夜：两端含 */
    @Test
    void containsSameDayInclusive() {
        WakeWindow w = WakeWindow.parse("09:00-17:00");
        assertThat(w.contains(bj(9, 0))).isTrue();
        assertThat(w.contains(bj(17, 0))).isTrue();
        assertThat(w.contains(bj(8, 55))).isFalse();
        assertThat(w.contains(bj(17, 5))).isFalse();
    }

    /** 跨午夜：21:00 → 次日 08:30，两端含，正午在外 */
    @Test
    void containsWrapAroundInclusive() {
        WakeWindow w = WakeWindow.parse("21:00-08:30");
        assertThat(w.contains(bj(21, 0))).isTrue();
        assertThat(w.contains(bj(23, 59))).isTrue();
        assertThat(w.contains(bj(0, 0))).isTrue();
        assertThat(w.contains(bj(8, 30))).isTrue();
        assertThat(w.contains(bj(8, 35))).isFalse();
        assertThat(w.contains(bj(12, 0))).isFalse();
        assertThat(w.contains(bj(20, 55))).isFalse();
    }

    @Test
    void nextBoundaryFromReturnsSelfWhenInside() {
        WakeWindow w = WakeWindow.parse("21:00-08:30");
        assertThat(w.nextBoundaryFrom(bj(22, 0), H1)).isEqualTo(bj(22, 0));
    }

    @Test
    void nextBoundaryFromJumpsIntoWindow() {
        WakeWindow w = WakeWindow.parse("21:00-08:30");
        assertThat(w.nextBoundaryFrom(bj(12, 0), H1)).isEqualTo(bj(21, 0));
        // 15m 档从 08:45 起下一次是当晚 21:00
        assertThat(w.nextBoundaryFrom(bj(8, 45), 900_000L)).isEqualTo(bj(21, 0));
    }

    /** 4h 档边界只有北京 00/04/08/12/16/20 点：21:00-23:00 里一根都没有 → -1 */
    @Test
    void nextBoundaryFromMinusOneWhenNoBoundaryInWindow() {
        WakeWindow w = WakeWindow.parse("21:00-23:00");
        assertThat(w.nextBoundaryFrom(bj(20, 0), H4)).isEqualTo(-1);
    }

    /** 21:00-08:00 的 08:00 是末次（下一根 09:00 已出时段）；07:00 不是 */
    @Test
    void isLastBoundary() {
        WakeWindow w = WakeWindow.parse("21:00-08:00");
        assertThat(w.isLastBoundary(bj(8, 0), H1)).isTrue();
        assertThat(w.isLastBoundary(bj(7, 0), H1)).isFalse();
        assertThat(w.isLastBoundary(bj(12, 0), H1)).isFalse();
        assertThat(w.isLastBoundary(bj(8, 0), M5)).isTrue();
        assertThat(w.isLastBoundary(bj(7, 55), M5)).isFalse();
    }

    @Test
    void ofTreatsBadStoredValueAsAllDay() {
        AiTrader t = new AiTrader();
        t.setId(1L);
        t.setWakeWindow("garbage");
        assertThat(WakeWindow.of(t)).isNull();
        t.setWakeWindow(null);
        assertThat(WakeWindow.of(t)).isNull();
        t.setWakeWindow("21:00-08:30");
        assertThat(WakeWindow.of(t)).isNotNull();
    }
}
