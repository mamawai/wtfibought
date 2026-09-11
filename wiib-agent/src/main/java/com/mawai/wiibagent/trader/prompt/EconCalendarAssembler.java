package com.mawai.wiibagent.trader.prompt;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.trader.wakeup.WakeWindow;
import com.mawai.wiibquant.mapper.EconCalendarMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 唤醒开场白的财经日历块：过去 12h 已公布 + 未来 24h 即将公布，两段式（例行/警报同享）。
 * 只给事实不给指令——"公布前该不该持仓"是模型的判断，与行情快照同一条中性纪律；
 * 唯一的措辞约束与休眠提示同款：事件临近不构成任何方向动作的理由。
 * <p>
 * 库里只有 High 级事件（采集侧服务端过滤），这里不再筛。
 * <p>
 * null=窗口内无事件或取数失败，整块缺席——与 {@link PlayStatsAssembler} 同语义，
 * 失败静默：唤醒不能死在日历表抖动上。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EconCalendarAssembler {

    static final long PAST_WINDOW_MS = 12 * 3_600_000L;
    static final long FUTURE_WINDOW_MS = 24 * 3_600_000L;
    /** 时刻按北京时间成文（与休眠提示同源 WakeWindow.ZONE），header 里已注明时区 */
    private static final DateTimeFormatter BJ_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(WakeWindow.ZONE);

    private final EconCalendarMapper mapper;
    private final PromptCatalog prompts;

    public String assemble(long nowMs, AgentLang lang) {
        List<EconCalendarMapper.Row> rows;
        try {
            rows = mapper.selectWindow(nowMs - PAST_WINDOW_MS, nowMs + FUTURE_WINDOW_MS);
        } catch (Exception e) {
            log.warn("[EconCalendar] 日历查询失败，本轮缺席: {}", e.toString());
            return null;
        }
        if (rows.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(prompts.get(lang, "trader.calendar.header")).append('\n');
        appendSection(sb, lang, "trader.calendar.published",
                rows.stream().filter(r -> r.getEventTime() <= nowMs).toList());
        appendSection(sb, lang, "trader.calendar.upcoming",
                rows.stream().filter(r -> r.getEventTime() > nowMs).toList());
        return sb.toString();
    }

    private void appendSection(StringBuilder sb, AgentLang lang, String labelKey,
                               List<EconCalendarMapper.Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        sb.append(prompts.get(lang, labelKey)).append('\n');
        for (EconCalendarMapper.Row r : rows) {
            sb.append("- ").append(BJ_FMT.format(Instant.ofEpochMilli(r.getEventTime())))
                    .append(' ').append(r.getCurrency()).append(' ').append(r.getTitle());
            if (r.getForecast() != null) {
                sb.append(' ').append(prompts.get(lang, "trader.calendar.forecast")).append(r.getForecast());
            }
            if (r.getPrevious() != null) {
                sb.append(' ').append(prompts.get(lang, "trader.calendar.previous")).append(r.getPrevious());
            }
            sb.append('\n');
        }
    }
}
