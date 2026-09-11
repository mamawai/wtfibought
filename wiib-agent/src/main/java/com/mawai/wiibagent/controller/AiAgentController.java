package com.mawai.wiibagent.controller;

import com.mawai.wiibcommon.dto.NewsEventItem;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibquant.mapper.EconCalendarMapper;
import com.mawai.wiibquant.mapper.NewsEventMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI Agent 查询接口：快讯一条 + 财经日历两条（首页卡、BTC K 线标记）。
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
    private static final long CALENDAR_PAST_MS = 3 * 86_400_000L;
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

    /** 首页财经日历：过去 3 天已公布取末 6 条，未来 7 天即将公布取前 6 条。库里只有 High 级事件 */
    @GetMapping("/quant/econ-calendar")
    @Operation(summary = "财经日历：已公布 / 即将公布各 6 条")
    public Result<EconCalendarView> econCalendar() {
        long now = System.currentTimeMillis();
        List<EconCalendarMapper.Row> rows = econCalendarMapper.selectWindow(now - CALENDAR_PAST_MS, now + WEEK_MS);
        List<EconCalendarMapper.Row> past = rows.stream().filter(r -> r.getEventTime() <= now).toList();
        List<EconCalendarMapper.Row> upcoming = rows.stream().filter(r -> r.getEventTime() > now).toList();
        return Result.ok(new EconCalendarView(
                past.subList(Math.max(0, past.size() - CALENDAR_SIDE), past.size()),
                upcoming.subList(0, Math.min(CALENDAR_SIDE, upcoming.size()))));
    }

    /** BTC K 线的日历标记数据源：时间窗内全部事件，High 每天约 3 条，不设上限 */
    @GetMapping("/quant/econ-calendar/events")
    @Operation(summary = "财经日历事件（K线标记数据源）：时间窗内全部")
    public Result<List<EconCalendarMapper.Row>> econCalendarEvents(@RequestParam long from,
                                                                  @RequestParam long to) {
        return Result.ok(econCalendarMapper.selectWindow(from, to));
    }
}
