package com.mawai.wiibagent.trader.prompt;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.PromptI18nAssertions;
import com.mawai.wiibquant.mapper.EconCalendarMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 财经日历注入块：刚公布（上一边界以来，置顶）→ 过去 3 天已公布 → 今天剩余即将公布；
 * 无内容返回 null 整块缺席，与 PlayStatsAssembler 同语义。
 */
class EconCalendarAssemblerTest {

    /** 2025-09-04 15:33:20 UTC = 北京 23:33:20，当天 24:00 是 26 分 40 秒之后 */
    private static final long NOW = 1_757_000_000_000L;
    private static final long END_OF_DAY = NOW + 1_600_000L;
    private static final long HOUR = 3_600_000L;
    /** 1h 档：上一边界一小时前 */
    private static final long SINCE = NOW - HOUR;

    private final EconCalendarMapper mapper = mock(EconCalendarMapper.class);
    private final EconCalendarAssembler assembler =
            new EconCalendarAssembler(mapper, new PromptCatalog());

    private static EconCalendarMapper.Row row(long time, String country, String currency, String title,
                                              String actual, String forecast, String previous) {
        EconCalendarMapper.Row r = new EconCalendarMapper.Row();
        r.setEventTime(time);
        r.setCountry(country);
        r.setCurrency(currency);
        r.setTitle(title);
        r.setActual(actual);
        r.setForecast(forecast);
        r.setPrevious(previous);
        return r;
    }

    @Test
    void 三段_刚公布置顶_行带国家货币与实际值() {
        when(mapper.selectWindow(anyLong(), anyLong())).thenReturn(List.of(
                row(NOW - 5 * HOUR, "GB", "GBP", "GDP m/m", "0.4%", "0%", "0.3%"),
                row(NOW - 30 * 60_000L, "US", "USD", "CPI m/m", "0.4%", "0.3%", "0.2%"),
                row(NOW + 10 * 60_000L, "US", "USD", "Michigan Consumer Sentiment Prel", null, "51", "55.2")));

        String block = assembler.assemble(NOW, SINCE, AgentLang.ZH);

        assertThat(block).contains("US/USD CPI m/m 实际:0.4% 预测:0.3% 前值:0.2%");
        // 三段顺序：刚公布 → 已公布 → 即将公布
        assertThat(block.indexOf("刚公布")).isLessThan(block.indexOf("已公布"));
        assertThat(block.indexOf("已公布")).isLessThan(block.indexOf("即将公布"));
        assertThat(block.indexOf("CPI m/m")).isLessThan(block.indexOf("GDP m/m"));
        // 窗口钉死：过去 72h 到北京时间当天 24:00
        verify(mapper).selectWindow(NOW - 72 * HOUR, END_OF_DAY);
    }

    @Test
    void 刚公布段_数字型事件缺实际值写暂缺_讲话类不写() {
        when(mapper.selectWindow(anyLong(), anyLong())).thenReturn(List.of(
                row(NOW - 10 * 60_000L, "US", "USD", "PPI m/m", null, "0.3%", "0.2%"),
                row(NOW - 10 * 60_000L, "US", "USD", "Fed Press Conference", null, null, null)));

        String block = assembler.assemble(NOW, SINCE, AgentLang.ZH);

        assertThat(block).contains("PPI m/m 实际:暂缺 预测:0.3%");
        assertThat(block).contains("Fed Press Conference\n");
    }

    @Test
    void 恰在上一边界的那条归刚公布() {
        when(mapper.selectWindow(anyLong(), anyLong())).thenReturn(List.of(
                row(SINCE, "US", "USD", "Non Farm Payrolls", "206K", "205K", "207K")));

        String block = assembler.assemble(NOW, SINCE, AgentLang.ZH);

        assertThat(block).contains("刚公布").doesNotContain("已公布（");
    }

    @Test
    void 窗口内无事件_返回null整块缺席() {
        when(mapper.selectWindow(anyLong(), anyLong())).thenReturn(List.of());
        assertThat(assembler.assemble(NOW, SINCE, AgentLang.ZH)).isNull();
    }

    @Test
    void 查询失败_返回null不拖垮唤醒() {
        when(mapper.selectWindow(anyLong(), anyLong())).thenThrow(new RuntimeException("db down"));
        assertThat(assembler.assemble(NOW, SINCE, AgentLang.ZH)).isNull();
    }

    @Test
    void 英文版全文无中文() {
        when(mapper.selectWindow(anyLong(), anyLong())).thenReturn(List.of(
                row(NOW - 30 * 60_000L, "US", "USD", "CPI m/m", "0.4%", "0.3%", "0.2%"),
                row(NOW - 5 * HOUR, "GB", "GBP", "GDP m/m", null, "0%", "0.3%"),
                row(NOW + 10 * 60_000L, "US", "USD", "Fed Press Conference", null, null, null)));

        PromptI18nAssertions.assertNoCjk("英文财经日历块", assembler.assemble(NOW, SINCE, AgentLang.EN));
    }
}
