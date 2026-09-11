package com.mawai.wiibagent.controller;

import com.mawai.wiibagent.trader.prompt.EconCalendarAssembler;
import com.mawai.wiibagent.trader.wakeup.WakeWindow;
import com.mawai.wiibcommon.dto.NewsEventItem;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibquant.mapper.EconCalendarMapper;
import com.mawai.wiibquant.mapper.NewsEventMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * AI Agent 查询接口：快讯两条 + 首页财经日历。
 * 深研判查询端点已随 AI 页市场研判 tab 下线（2026-08）：研判只在对话里触发时看，
 * 生成与落库仍在 DeepAnalysisToolkit/DeepAnalysisService。
 * 行为分析端点同理下线（2026-08）：它已是对话轨的 analyze_my_behavior 工具，
 * 报告以卡片形式出现在对话里，生成与准入在 BehaviorToolkit/BehaviorAnalysisService。
 * 预测端点（snapshots/scorecard/series）已随预测管线下线（2026-08：生产验证无前瞻信息），
 * 对话入口在 {@link ChatWorkbenchController}。
 */
@Slf4j
@Tag(name = "AI Agent接口")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiAgentController {

    private final NewsEventMapper newsEventMapper;
    private final EconCalendarMapper econCalendarMapper;

    /** 首页快讯卡最多给 100 条，约两三天的量 */
    private static final int NEWS_LIMIT = 100;

    /** 按天翻的上限 300 条，一天的量足够 */
    private static final int NEWS_RANGE_LIMIT = 300;

    /** 首页日历卡两栏各几条 */
    private static final int CALENDAR_SIDE = 6;
    private static final long WEEK_MS = 7 * 86_400_000L;

    /** 首页日历卡：已公布 / 即将公布两栏，都按时间正序 */
    public record EconCalendarView(List<EconCalendarMapper.Row> past, List<EconCalendarMapper.Row> upcoming) {
    }

    /**
     * 首页快讯卡读 news_event 存档，不走模型侧那份 20 条的内存缓存。
     * 中英两套一起给，前端按界面语言现选；译文空=没译成，英文界面不展示那条。
     * <p>
     * from/to 都给了就查这个左闭右开的时间窗（首页快讯卡按天翻），缺任一个仍是最新 100 条。
     */
    @GetMapping("/quant/news")
    @Operation(summary = "快讯（news_event 存档）：带 from/to 查时间窗，否则最新 100 条")
    public Result<List<NewsEventItem>> news(@RequestParam(required = false) Long from,
                                            @RequestParam(required = false) Long to) {
        if (from != null && to != null) {
            return Result.ok(newsEventMapper.selectInRange(from, to, NEWS_RANGE_LIMIT));
        }
        return Result.ok(newsEventMapper.selectLatest(NEWS_LIMIT));
    }

    @GetMapping("/quant/news-events")
    @Operation(summary = "打标快讯（K线新闻图标数据源：按标签+时间窗查 news_event 存档）")
    public Result<List<NewsEventItem>> newsEvents(@RequestParam String tag,
                                                  @RequestParam long from,
                                                  @RequestParam long to) {
        // 上限 500：图标按 K 线桶聚合，一屏至多几百桶，多给纯属流量浪费
        return Result.ok(newsEventMapper.selectByTagInRange(tag.trim().toUpperCase(), from, to, 500));
    }

    /**
     * 首页财经日历：本周起点到此刻的已公布事件取最近 6 条，此刻之后的即将公布取最近 6 条。
     * 表里留着旧周的行，下界取本周起点而不是往回数 7 天。
     * 筛选口径与 trader 唤醒注入同一条规则（{@link EconCalendarAssembler#relevant}）。
     */
    @GetMapping("/quant/econ-calendar")
    @Operation(summary = "财经日历（econ_calendar_event 本周）：已公布 / 即将公布各 6 条")
    public Result<EconCalendarView> econCalendar() {
        long now = System.currentTimeMillis();
        // ForexFactory 周历从周日起；按北京时间取本周日零点，与唤醒注入同一时区
        long weekStart = Instant.ofEpochMilli(now).atZone(WakeWindow.ZONE)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
                .truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli();
        List<EconCalendarMapper.Row> rows = econCalendarMapper.selectWindow(weekStart, now + WEEK_MS).stream()
                .filter(EconCalendarAssembler::relevant)
                .toList();
        List<EconCalendarMapper.Row> past = rows.stream().filter(r -> r.getEventTime() <= now).toList();
        List<EconCalendarMapper.Row> upcoming = rows.stream().filter(r -> r.getEventTime() > now).toList();
        return Result.ok(new EconCalendarView(
                past.subList(Math.max(0, past.size() - CALENDAR_SIDE), past.size()),
                upcoming.subList(0, Math.min(CALENDAR_SIDE, upcoming.size()))));
    }
}
