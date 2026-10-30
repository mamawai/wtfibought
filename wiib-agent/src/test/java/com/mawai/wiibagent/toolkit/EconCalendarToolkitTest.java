package com.mawai.wiibagent.toolkit;

import com.mawai.wiibquant.mapper.EconCalendarMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EconCalendarToolkitTest {

    /** 2025-09-04 15:33:20 UTC = 北京 23:33:20 */
    private static final long NOW = 1_757_000_000_000L;
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    private final EconCalendarMapper mapper = mock(EconCalendarMapper.class);
    private final EconCalendarToolkit toolkit = new EconCalendarToolkit(mapper);

    private static EconCalendarMapper.Row row(long time, String country, String title,
                                              String actual, String forecast, String previous) {
        EconCalendarMapper.Row r = new EconCalendarMapper.Row();
        r.setEventTime(time);
        r.setCountry(country);
        r.setTitle(title);
        r.setActual(actual);
        r.setForecast(forecast);
        r.setPrevious(previous);
        return r;
    }

    @Test
    void 不传指标_前3天后7天只留美国_按已公布与即将公布分两段() {
        when(mapper.selectWindow(anyLong(), anyLong())).thenReturn(List.of(
                row(NOW - 5 * HOUR, "US", "Inflation Rate YoY", "2.9%", "2.8%", "2.7%"),
                row(NOW - 3 * HOUR, "GB", "GDP MoM", "0.4%", "0%", "0.3%"),
                row(NOW - HOUR, "US", "Fed Chair Powell Speech", null, null, null),
                row(NOW + 2 * DAY, "US", "Non Farm Payrolls", null, "75K", "22K")));

        JsonNode out = MAPPER.readTree(toolkit.econCalendar(null, NOW));

        verify(mapper).selectWindow(NOW - 3 * DAY, NOW + 7 * DAY);
        assertThat(out.get("country").asString()).isEqualTo("US");
        assertThat(out.get("published")).hasSize(2);
        assertThat(out.get("upcoming")).hasSize(1);
        assertThat(out.toString()).doesNotContain("GDP MoM");
        JsonNode cpi = out.get("published").get(0);
        assertThat(cpi.get("time").asString()).isEqualTo("2025-09-04 18:33");
        assertThat(cpi.get("actual").asString()).isEqualTo("2.9%");
        // 讲话类三个值都没有、未公布的没有 actual：字段不出现
        assertThat(out.get("published").get(1).has("forecast")).isFalse();
        assertThat(out.get("upcoming").get(0).has("actual")).isFalse();
    }

    @Test
    void 传指标_按关键字取美国最近12次() {
        when(mapper.selectRecentByTitle("US", "Inflation Rate", 12)).thenReturn(List.of(
                row(NOW + 10 * DAY, "US", "Inflation Rate YoY", null, "2.9%", "2.9%"),
                row(NOW - 20 * DAY, "US", "Inflation Rate YoY", "2.9%", "2.8%", "2.7%")));

        JsonNode out = MAPPER.readTree(toolkit.econCalendar("Inflation Rate", NOW));

        assertThat(out.get("indicator").asString()).isEqualTo("Inflation Rate");
        assertThat(out.get("releases")).hasSize(2);
        assertThat(out.get("releases").get(1).get("actual").asString()).isEqualTo("2.9%");
    }
}
