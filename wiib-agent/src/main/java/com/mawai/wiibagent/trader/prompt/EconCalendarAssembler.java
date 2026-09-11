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
import java.util.Locale;

/**
 * 唤醒开场白的财经日历块：过去 12h 已公布 + 未来 24h 即将公布，两段式（例行/警报同享）。
 * 只给事实不给指令——"公布前该不该持仓"是模型的判断，与行情快照同一条中性纪律；
 * 唯一的措辞约束与休眠提示同款：事件临近不构成任何方向动作的理由。
 * <p>
 * 过滤：High/Medium 全部 + USD 讲话类含 Low——普通联储官员讲话被外汇视角标 Low，
 * 但主席级讲话打穿止损的先例正是本功能的立项依据，不能纯按 impact 筛。
 * <p>
 * 已公布事件的实际值不在 feed 里（免费源只有预测/前值），文案里明说结果自查快讯与价格——
 * 日历管"何时有雷"，快讯管"雷响了什么"。
 * <p>
 * null=窗口内无相关事件或取数失败，整块缺席——与 {@link PlayStatsAssembler} 同语义，
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
        List<EconCalendarMapper.Row> relevant = rows.stream()
                .filter(EconCalendarAssembler::relevant).toList();
        if (relevant.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(prompts.get(lang, "trader.calendar.header")).append('\n');
        appendSection(sb, lang, "trader.calendar.published",
                relevant.stream().filter(r -> r.getEventTime() <= nowMs).toList());
        appendSection(sb, lang, "trader.calendar.upcoming",
                relevant.stream().filter(r -> r.getEventTime() > nowMs).toList());
        return sb.toString();
    }

    /** 讲话类补捞认 Speaks 与 Testifies 两种标题（国会作证与讲话同类，都是无数值的时刻型事件）。首页日历卡同用这条 */
    public static boolean relevant(EconCalendarMapper.Row r) {
        String title = r.getTitle().toLowerCase(Locale.ROOT);
        return "High".equals(r.getImpact()) || "Medium".equals(r.getImpact())
                || ("USD".equals(r.getCurrency()) && (title.contains("speak") || title.contains("testif")));
    }

    private void appendSection(StringBuilder sb, AgentLang lang, String labelKey,
                               List<EconCalendarMapper.Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        sb.append(prompts.get(lang, labelKey)).append('\n');
        for (EconCalendarMapper.Row r : rows) {
            sb.append("- ").append(BJ_FMT.format(Instant.ofEpochMilli(r.getEventTime())))
                    .append(" [").append(r.getImpact()).append("] ")
                    .append(r.getCurrency()).append(' ').append(r.getTitle());
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
