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
 * 时刻按北京时间成文，与 trader 开场白的日历块同口径（trader 那边也只认美国，注入不走工具）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EconCalendarToolkit {

    /** 对话工具和 trader 的日历都只认这个国家 */
    public static final String COUNTRY = "US";
    static final long PAST_MS = 3 * 86_400_000L;
    static final long AHEAD_MS = 7 * 86_400_000L;
    static final int SERIES_LIMIT = 12;

    private final EconCalendarMapper mapper;

    // 描述在词表 tool.econ_calendar（见 LocalizedToolCallbacks）
    @Tool(name = "econ_calendar")
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
