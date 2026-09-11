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
import static org.mockito.Mockito.when;

/**
 * 财经日历注入块：过去 12h 已公布 + 未来 24h 即将公布，两段式；
 * 过滤 = High/Medium 全部 + USD 讲话类（含 Low——普通联储官员讲话被外汇视角标 Low，
 * 但主席级讲话打穿止损的先例就是立项依据，不能纯按 impact 筛）。
 * 无内容返回 null 整块缺席，与 PlayStatsAssembler 同语义。
 */
class EconCalendarAssemblerTest {

    private static final long NOW = 1_757_000_000_000L;
    private static final long HOUR = 3_600_000L;

    private final EconCalendarMapper mapper = mock(EconCalendarMapper.class);
    private final EconCalendarAssembler assembler =
            new EconCalendarAssembler(mapper, new PromptCatalog());

    private static EconCalendarMapper.Row row(long time, String currency, String impact,
                                              String title, String forecast, String previous) {
        EconCalendarMapper.Row r = new EconCalendarMapper.Row();
        r.setEventTime(time);
        r.setCurrency(currency);
        r.setImpact(impact);
        r.setTitle(title);
        r.setForecast(forecast);
        r.setPrevious(previous);
        return r;
    }

    @Test
    void 已公布与即将公布分两段_行带预测前值() {
        when(mapper.selectWindow(anyLong(), anyLong())).thenReturn(List.of(
                row(NOW - 2 * HOUR, "USD", "High", "CPI m/m", "0.3%", "0.2%"),
                row(NOW + 5 * HOUR, "USD", "High", "Non-Farm Employment Change", "55K", "-23K")));

        String block = assembler.assemble(NOW, AgentLang.ZH);

        assertThat(block).contains("已公布").contains("即将公布");
        assertThat(block).contains("CPI m/m").contains("Non-Farm Employment Change");
        assertThat(block).contains("预测:55K").contains("前值:-23K");
        // 已公布段必须排在即将公布段之前（时间叙事顺序）
        assertThat(block.indexOf("CPI m/m")).isLessThan(block.indexOf("Non-Farm"));
        // 对外宣称的"过去12h/未来24h"钉在查询窗口上，改错常量测试要红
        org.mockito.Mockito.verify(mapper).selectWindow(NOW - 12 * HOUR, NOW + 24 * HOUR);
    }

    @Test
    void 过滤_低重要度剔除但USD讲话保留() {
        when(mapper.selectWindow(anyLong(), anyLong())).thenReturn(List.of(
                row(NOW + HOUR, "USD", "Low", "FOMC Member Waller Speaks", null, null),
                row(NOW + HOUR, "USD", "Low", "Fed Chair Testifies", null, null),
                row(NOW + HOUR, "JPY", "Low", "Retail Sales y/y", "3.2%", "0.5%"),
                row(NOW + HOUR, "EUR", "Medium", "German Prelim CPI m/m", "0.3%", "0.8%")));

        String block = assembler.assemble(NOW, AgentLang.ZH);

        assertThat(block).contains("Waller Speaks");     // USD 讲话：impact 低也留
        assertThat(block).contains("Testifies");          // 国会作证与讲话同类，同样补捞
        assertThat(block).doesNotContain("Retail Sales"); // 非 USD 的 Low：剔
        assertThat(block).contains("German Prelim CPI");  // Medium：留
    }

    @Test
    void 无预测前值的行不出现空标签() {
        when(mapper.selectWindow(anyLong(), anyLong())).thenReturn(List.of(
                row(NOW + HOUR, "GBP", "High", "BOE Gov Bailey Speaks", null, null)));

        String block = assembler.assemble(NOW, AgentLang.ZH);

        assertThat(block).contains("Bailey Speaks");
        assertThat(block).doesNotContain("预测").doesNotContain("前值");
    }

    @Test
    void 窗口内无相关事件_返回null整块缺席() {
        when(mapper.selectWindow(anyLong(), anyLong())).thenReturn(List.of(
                row(NOW + HOUR, "JPY", "Low", "Retail Sales y/y", null, null)));
        assertThat(assembler.assemble(NOW, AgentLang.ZH)).isNull();

        when(mapper.selectWindow(anyLong(), anyLong())).thenReturn(List.of());
        assertThat(assembler.assemble(NOW, AgentLang.ZH)).isNull();
    }

    @Test
    void 查询失败_返回null不拖垮唤醒() {
        when(mapper.selectWindow(anyLong(), anyLong())).thenThrow(new RuntimeException("db down"));
        assertThat(assembler.assemble(NOW, AgentLang.ZH)).isNull();
    }

    @Test
    void 英文版全文无中文() {
        when(mapper.selectWindow(anyLong(), anyLong())).thenReturn(List.of(
                row(NOW - HOUR, "USD", "High", "CPI m/m", "0.3%", "0.2%"),
                row(NOW + HOUR, "USD", "High", "Non-Farm Employment Change", "55K", "-23K")));

        PromptI18nAssertions.assertNoCjk("英文财经日历块", assembler.assemble(NOW, AgentLang.EN));
    }
}
