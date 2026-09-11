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
 * 唤醒开场白的财经日历块，三段：刚公布（上一边界以来新落地的实际值，置顶重点）→ 过去 3 天已公布 → 今天剩余即将公布，
 * 例行/警报同享。只给事实不给指令——"公布前该不该持仓"是模型的判断，与行情快照同一条中性纪律；
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

    static final long PAST_WINDOW_MS = 72 * 3_600_000L;
    /** 时刻按北京时间成文（与休眠提示同源 WakeWindow.ZONE），header 里已注明时区 */
    private static final DateTimeFormatter BJ_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(WakeWindow.ZONE);

    private final EconCalendarMapper mapper;
    private final PromptCatalog prompts;

    /**
     * @param sinceMs 上一边界（含）：这之后到此刻公布的算"刚公布"。含边界是给等待闸没等到的那条留的：
     *                5m 档下一根再醒时它已经有值，还能进重点段
     */
    public String assemble(long nowMs, long sinceMs, AgentLang lang) {
        List<EconCalendarMapper.Row> rows;
        try {
            rows = mapper.selectWindow(nowMs - PAST_WINDOW_MS, endOfDay(nowMs));
        } catch (Exception e) {
            log.warn("[EconCalendar] 日历查询失败，本轮缺席: {}", e.toString());
            return null;
        }
        if (rows.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(prompts.get(lang, "trader.calendar.header")).append('\n');
        appendSection(sb, lang, "trader.calendar.justPublished",
                rows.stream().filter(r -> r.getEventTime() >= sinceMs && r.getEventTime() <= nowMs).toList(), true);
        appendSection(sb, lang, "trader.calendar.published",
                rows.stream().filter(r -> r.getEventTime() < sinceMs).toList(), false);
        appendSection(sb, lang, "trader.calendar.upcoming",
                rows.stream().filter(r -> r.getEventTime() > nowMs).toList(), false);
        return sb.toString();
    }

    /** 北京时间当天 24:00 */
    static long endOfDay(long nowMs) {
        return Instant.ofEpochMilli(nowMs).atZone(WakeWindow.ZONE).toLocalDate().plusDays(1)
                .atStartOfDay(WakeWindow.ZONE).toInstant().toEpochMilli();
    }

    /** @param justPublished 刚公布段：数字型事件（有预测或前值）到点了实际值还没到，明写"暂缺"，别让模型当成讲话类 */
    private void appendSection(StringBuilder sb, AgentLang lang, String labelKey,
                               List<EconCalendarMapper.Row> rows, boolean justPublished) {
        if (rows.isEmpty()) {
            return;
        }
        sb.append(prompts.get(lang, labelKey)).append('\n');
        for (EconCalendarMapper.Row r : rows) {
            boolean numeric = r.getForecast() != null || r.getPrevious() != null;
            sb.append("- ").append(BJ_FMT.format(Instant.ofEpochMilli(r.getEventTime())))
                    .append(' ').append(r.getCountry()).append('/').append(r.getCurrency())
                    .append(' ').append(r.getTitle());
            if (r.getActual() != null) {
                sb.append(' ').append(prompts.get(lang, "trader.calendar.actual")).append(r.getActual());
            } else if (justPublished && numeric) {
                sb.append(' ').append(prompts.get(lang, "trader.calendar.actualPending"));
            }
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
