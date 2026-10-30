package com.mawai.wiibagent.toolkit;

import com.mawai.wiibquant.mapper.EconCalendarMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 财经日历工具（chat 主 agent 挂）：库里的 TradingView High 级事件，只看美国的。
 * 不传指标给前 3 天已公布 + 后 7 天即将公布；传指标给标题含它的最近 12 条。
 * 时刻按北京时间成文，与 trader 开场白的日历块同口径（trader 那边是全部国家、注入不走工具）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EconCalendarToolkit {

    static final String COUNTRY = "US";
    static final long PAST_MS = 3 * 86_400_000L;
    static final long AHEAD_MS = 7 * 86_400_000L;
    static final int SERIES_LIMIT = 12;

    private final EconCalendarMapper mapper;

    // 描述按语言取自词表 tool.econ_calendar，注解这份只当词表缺失时的兜底
    @Tool(name = "econ_calendar", description = """
            US macro economic calendar: TradingView high-impact US events only (no other countries).
            Without indicator: events published in the last 3 days (with actual values) and scheduled in the
            next 7 days. With indicator (keyword matched against event titles, case-insensitive): the latest
            12 events whose title contains it, newest first, including the next scheduled one. A broad keyword
            mixes several series ("Inflation Rate" hits YoY / MoM / Core); for one series pass the full
            title, e.g. "Inflation Rate YoY".
            Titles follow TradingView naming: CPI is "Inflation Rate YoY/MoM" (core: "Core Inflation Rate"),
            NFP "Non Farm Payrolls", PCE "Core PCE Price Index MoM", Fed decision "Fed Interest Rate Decision",
            minutes "FOMC Minutes", unemployment "Unemployment Rate".
            Fields: time (UTC+8), title, actual, forecast, previous. An event with forecast/previous is
            numeric: past time and no actual means not published yet; neither forecast nor previous means
            a speech or meeting.""")
    public String econCalendar(
            @ToolParam(required = false, description = """
                    Keyword matched against event titles, e.g. "Inflation Rate YoY", "Non Farm Payrolls",
                    "Fed Interest Rate Decision"; omit for the 3-days-back / 7-days-ahead window""")
            String indicator) {
        return econCalendar(indicator, System.currentTimeMillis());
    }

    String econCalendar(String indicator, long nowMs) {
        String keyword = indicator == null || indicator.isBlank() ? null : indicator.strip();
        ObjectNode out = MAPPER.createObjectNode();
        out.put("available", true);
        out.put("country", COUNTRY);
        out.put("timezone", "UTC+8");
        out.put("now", BeijingTime.format(nowMs));
        if (keyword == null) {
            List<EconCalendarMapper.Row> rows = mapper.selectWindow(nowMs - PAST_MS, nowMs + AHEAD_MS).stream()
                    .filter(r -> COUNTRY.equals(r.getCountry())).toList();
            out.set("published", rows(rows.stream().filter(r -> r.getEventTime() <= nowMs).toList()));
            out.set("upcoming", rows(rows.stream().filter(r -> r.getEventTime() > nowMs).toList()));
            log.info("[EconCalendarTool] econ_calendar 窗口 {} 条", rows.size());
        } else {
            List<EconCalendarMapper.Row> rows = mapper.selectRecentByTitle(COUNTRY, keyword, SERIES_LIMIT);
            out.put("indicator", keyword);
            out.set("releases", rows(rows));
            log.info("[EconCalendarTool] econ_calendar 指标 {} {} 条", keyword, rows.size());
        }
        return MAPPER.writeValueAsString(out);
    }

    /** 没有的字段不出现 */
    private static ArrayNode rows(List<EconCalendarMapper.Row> rows) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (EconCalendarMapper.Row r : rows) {
            ObjectNode o = arr.addObject();
            o.put("time", BeijingTime.format(r.getEventTime()));
            o.put("title", r.getTitle());
            putIfPresent(o, "actual", r.getActual());
            putIfPresent(o, "forecast", r.getForecast());
            putIfPresent(o, "previous", r.getPrevious());
        }
        return arr;
    }

    private static void putIfPresent(ObjectNode o, String key, String value) {
        if (value != null) {
            o.put(key, value);
        }
    }
}
