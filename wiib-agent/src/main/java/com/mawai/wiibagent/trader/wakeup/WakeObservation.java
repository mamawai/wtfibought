package com.mawai.wiibagent.trader.wakeup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.learning.CaseFileBuilder;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 开场白里的观察包。第一段「处置」用 {@link #assemble}：收盘命中 + 上次醒来后的事件 + 账户状态（持仓带自上次完成决策以来的波动）
 * + 上一轮结论 + 承诺块（持仓与挂单的失效条件、布的每条盯盘条件离现价多远 / 还有多久、上一轮写的等待行，只列事实）。
 * 第二段「布防与收尾」用 {@link #history}（最近轨迹 + 论点战绩）和 {@link #symbolsSummary}（各币自上次唤醒以来的开高低收）。
 * 只读不写，取数和成文都在这里。
 */
@Component
@RequiredArgsConstructor
public class WakeObservation {

    private static final int RECENT_DECISIONS = 5;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final AiTraderDecisionMapper decisionMapper;
    /** stale 教材过滤的共用入口（与复盘时间线/chat 同一套识别逻辑） */
    private final DecisionText decisionText;
    /** 论点战绩统计块，进第二段；null=本局无可统计或取数失败，整块缺席 */
    private final PlayStatsAssembler playStats;
    private final PromptCatalog prompts;
    /** 持仓与各币自上次完成决策以来的波动 */
    private final PositionPath positionPath;
    /** 模型布的盯盘条件，进承诺块 */
    private final TraderWatchStore watchStore;

    /**
     * 回注给下一轮看的一轮唤醒：决策行里只留提示词用得到的几列。
     * 被主人标记忽略的交易，内容在这里已经剔掉（正文剔那个币的段、动作名剔那笔的），
     * 行头的时刻/状态/权益原样——那是唤醒事实，不是教材。
     */
    record RecentWake(long wakeTime, String status, BigDecimal equity, String error,
                      String reasoning, List<String> tools) {
    }

    /**
     * 第一段的观察包成文 + 两个事实：有没有要处置的承诺（事件、收盘命中、持仓、挂单、盯盘条件、等待行，
     * 有就分两段）、本局最近一条 OK 轮的时刻（各币摘要按它开窗）
     */
    record Pack(String text, boolean busy, Long lastOkWakeMs) {
    }

    /**
     * 最近 {@value #RECENT_DECISIONS} 条交易行（例行/警报/手动/持仓事件），最新在前。
     * 复盘行不进来：它的产出已经走 memory 注入，再进就是重复占字数。
     * 忽略过滤要按计划生命期判段落归属（同币另一方向没被忽略的段得留），plans 是本局全部计划。
     */
    List<RecentWake> recentWakes(AiTrader trader, long boundaryTime, List<AiTraderPlan> plans) {
        List<AiTraderDecision> rows = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, trader.getId())
                .eq(AiTraderDecision::getRoundNo, trader.getRoundNo())
                .in(AiTraderDecision::getKind, AiTraderDecision.WAKE_KINDS)
                .lt(AiTraderDecision::getWakeTime, boundaryTime)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT " + RECENT_DECISIONS));
        List<RecentWake> out = new ArrayList<>(rows.size());
        for (AiTraderDecision d : rows) {
            String reasoning = decisionText.staleFiltered(d, plans);
            out.add(new RecentWake(d.getWakeTime(), d.getStatus(), d.getEquity(), d.getError(),
                    reasoning == null ? "" : reasoning, decisionText.staleFilteredToolNames(d, plans)));
        }
        return out;
    }

    /**
     * 第一段的观察包：收盘命中 + 事件 + 账户 + 上一轮结论 + 承诺。排在头部事实之后、第一段的问题之前。
     *
     * @param symbols      可交易币（运行器算好的白名单），承诺块只念这些币的等待行
     * @param hitsNow      这根收盘刚命中的收盘类盯盘条件（只有例行轮有），单起一块念
     * @param firedQuietly 命中了还没哪轮告诉过它的盯盘条件，进"自上次唤醒以来"补报
     * @param marks        各币标记价，承诺块算"离价位多远"用；没有的币不带距离
     */
    Pack assemble(AiTrader trader, Collection<String> symbols, BigDecimal equity, List<FuturesPositionDTO> positions,
                  List<FuturesOrderResponse> pendingOrders, TraderPlanStore.Reconcile reconcile,
                  List<RecentWake> recent, long boundaryTime, AgentLang lang,
                  List<AiTraderWatch> hitsNow, List<AiTraderWatch> firedQuietly, Map<String, BigDecimal> marks) {
        StringBuilder sb = new StringBuilder();
        if (!hitsNow.isEmpty()) {
            sb.append('\n').append(prompts.get(lang, "trader.wake.watchHitsHeader")).append('\n');
            for (AiTraderWatch w : hitsNow) {
                sb.append(prompts.get(lang, "trader.wake.watchHitLine", Map.of(
                        "detail", WatchText.detail(w, w.getFiredPrice(), prompts, lang),
                        "note", w.getNote()))).append('\n');
            }
        }
        sb.append(events(reconcile.events(), firedQuietly, lang));
        Long lastOk = lastOkWakeTime(trader, boundaryTime);
        Map<Long, CaseFileBuilder.MarketWindow> paths = new HashMap<>();
        for (FuturesPositionDTO p : positions) {
            CaseFileBuilder.MarketWindow w = positionPath.build(p, lastOk, boundaryTime);
            if (w != null) {
                paths.put(p.getId(), w);
            }
        }
        sb.append('\n').append(prompts.get(lang, "trader.wake.accountHeader")).append('\n')
                .append(WakeAccountState.accountStateJson(prompts, lang, equity, positions, pendingOrders, reconcile.live(),
                        boundaryTime, paths)).append('\n');
        RecentWake concluded = latestConcluded(recent);
        sb.append('\n').append(lastConclusion(concluded, lang));
        String commitments = commitments(trader, symbols, positions, pendingOrders, reconcile.live(),
                watchStore.armed(trader.getId(), trader.getRoundNo()), concluded, marks, boundaryTime, lang);
        sb.append(commitments);
        boolean busy = !hitsNow.isEmpty() || !firedQuietly.isEmpty() || !reconcile.events().isEmpty() || !commitments.isEmpty();
        return new Pack(sb.toString(), busy, lastOk);
    }

    /** 第二段的历史块：最近轨迹 + 论点战绩；都没有返回空串 */
    String history(AiTrader trader, List<RecentWake> recent, AgentLang lang) {
        StringBuilder sb = new StringBuilder();
        String trajectory = trajectory(recent, lang);
        if (!trajectory.isEmpty()) {
            sb.append('\n').append(trajectory);
        }
        String stats = playStats.assemble(trader, lang);
        if (stats != null) {
            sb.append('\n').append(stats);
        }
        return sb.toString();
    }

    /** 第二段的各币摘要：每个可交易币自上次完成决策以来的开高低收（代码按已收盘 5m 算）；一个币都算不出返回空串 */
    String symbolsSummary(AiTrader trader, Collection<String> symbols, Long lastOkWakeMs, long boundaryTime, AgentLang lang) {
        long intervalMs = TraderScheduler.INTERVAL_MS.get(trader.getIntervalCode());
        StringBuilder rows = new StringBuilder();
        for (String symbol : symbols.stream().sorted().toList()) {
            CaseFileBuilder.MarketWindow w = positionPath.symbol(symbol, lastOkWakeMs, boundaryTime, intervalMs);
            if (w == null) {
                continue;
            }
            rows.append(prompts.get(lang, "trader.wake.symbolsRow", Map.ofEntries(
                    Map.entry("symbol", symbol),
                    Map.entry("from", TIME_FMT.format(Instant.ofEpochMilli(w.from()))),
                    Map.entry("to", TIME_FMT.format(Instant.ofEpochMilli(w.to()))),
                    Map.entry("open", WatchText.num(w.open())),
                    Map.entry("high", WatchText.num(w.high())),
                    Map.entry("highAt", TIME_FMT.format(Instant.ofEpochMilli(w.highAt()))),
                    Map.entry("low", WatchText.num(w.low())),
                    Map.entry("lowAt", TIME_FMT.format(Instant.ofEpochMilli(w.lowAt()))),
                    Map.entry("close", WatchText.num(w.close())),
                    Map.entry("changePct", w.changePct().toPlainString())))).append('\n');
        }
        return rows.isEmpty() ? "" : "\n" + prompts.get(lang, "trader.wake.symbolsHeader") + "\n" + rows;
    }

    /** 本局最近一条 OK 唤醒行（复盘行不算）的唤醒时刻，不限于 recent 那几条；一条都没有给 null */
    private Long lastOkWakeTime(AiTrader trader, long boundaryTime) {
        List<AiTraderDecision> rows = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, trader.getId())
                .eq(AiTraderDecision::getRoundNo, trader.getRoundNo())
                .in(AiTraderDecision::getKind, AiTraderDecision.WAKE_KINDS)
                .eq(AiTraderDecision::getStatus, AiTraderDecision.STATUS_OK)
                .lt(AiTraderDecision::getWakeTime, boundaryTime)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.getFirst().getWakeTime();
    }

    /**
     * 自上次唤醒以来：对账事件逐条成文（了结说结局、成交说成交、挂单没了说没了），同一计划内先结局后成交；
     * 再是命中了但没叫醒它的盯盘条件。都没有返回空串
     */
    private String events(List<TraderPlanStore.Event> events, List<AiTraderWatch> firedQuietly, AgentLang lang) {
        if (events.isEmpty() && firedQuietly.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n").append(prompts.get(lang, "trader.wake.eventsHeader")).append('\n');
        for (AiTraderWatch w : firedQuietly) {
            sb.append(prompts.get(lang, "trader.wake.eventWatchFired", Map.of(
                    "time", BeijingTime.format(w.getFiredAt()),
                    "detail", WatchText.detail(w, w.getFiredPrice(), prompts, lang),
                    "note", w.getNote()))).append('\n');
        }
        for (TraderPlanStore.Event e : events) {
            AiTraderPlan p = e.plan();
            sb.append(switch (e) {
                case TraderPlanStore.Closed c -> closedEvent(p, c.position(), lang);
                case TraderPlanStore.Filled f -> prompts.get(lang, "trader.wake.eventFilled",
                        Map.of("symbol", p.getSymbol(), "side", p.getSide()));
                case TraderPlanStore.Cancelled c -> prompts.get(lang, "trader.wake.eventCancelled",
                        Map.of("symbol", p.getSymbol(), "side", p.getSide()));
            }).append('\n');
        }
        return sb.toString();
    }

    /** 一条平仓事件：了结方式/成交价/盈亏 + 当时的论点与失效条件 */
    private String closedEvent(AiTraderPlan p, FuturesPositionDTO pos, AgentLang lang) {
        return prompts.get(lang, "trader.wake.eventClosed", Map.of(
                "symbol", p.getSymbol(), "side", p.getSide(),
                "manner", TradePairing.closeManner(prompts, pos, lang),
                "price", String.valueOf(pos.getClosedPrice()),
                "pnl", money(pos.getClosedPnl()),
                "playType", String.valueOf(p.getPlayType()),
                "invalidation", p.getInvalidationCondition()));
    }

    /** sim 的 closedPnl 可以为空（PlayStatsAssembler 同样按空处理），Map.of 不收 null */
    private static String money(BigDecimal v) {
        return v == null ? "?" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 最近一条写出了结论块的 OK 轮；没有给 null */
    private RecentWake latestConcluded(List<RecentWake> recent) {
        for (RecentWake w : recent) {
            if (AiTraderDecision.STATUS_OK.equals(w.status()) && decisionText.conclusionBlock(w.reasoning()) != null) {
                return w;
            }
        }
        return null;
    }

    /** 上一轮结论块原样全文；没有就说没有 */
    private String lastConclusion(RecentWake concluded, AgentLang lang) {
        if (concluded == null) {
            return prompts.get(lang, "trader.wake.lastConclusionNone") + "\n";
        }
        return prompts.get(lang, "trader.wake.lastConclusionHeader", Map.of(
                "time", TIME_FMT.format(Instant.ofEpochMilli(concluded.wakeTime())),
                "equity", concluded.equity().setScale(0, RoundingMode.HALF_UP)))
                + "\n" + decisionText.conclusionBlock(concluded.reasoning()) + "\n";
    }

    // ==================== 承诺块 ====================

    /**
     * 承诺块：持仓与挂单各一行（失效条件 + 刚收盘那根心跳周期的开高低收）、在盯的每条条件一行（离现价多远 / 还有多久）、
     * 上一轮每个币的等待行一行（那个币一条条件都没布就说一句）。只列事实，判断留给模型。一条都没有返回空串
     */
    private String commitments(AiTrader trader, Collection<String> symbols, List<FuturesPositionDTO> positions,
                               List<FuturesOrderResponse> pendingOrders, List<AiTraderPlan> live, List<AiTraderWatch> armed,
                               RecentWake concluded, Map<String, BigDecimal> marks, long boundaryTime, AgentLang lang) {
        long intervalMs = TraderScheduler.INTERVAL_MS.get(trader.getIntervalCode());
        Map<String, AiTraderPlan> planByKey = new HashMap<>();
        live.forEach(p -> planByKey.put(TraderPlanStore.key(p.getSymbol(), p.getSide()), p));
        Map<String, String> lastBars = new HashMap<>();
        List<String> lines = new ArrayList<>();
        for (FuturesPositionDTO p : positions) {
            AiTraderPlan plan = planByKey.get(TraderPlanStore.key(p.getSymbol(), p.getSide()));
            lines.add(prompts.get(lang, "trader.wake.commitPosition", Map.of(
                    "symbol", p.getSymbol(), "side", p.getSide(),
                    "invalidation", invalidation(plan),
                    "bar", lastBars.computeIfAbsent(p.getSymbol(), s -> lastBar(s, trader.getIntervalCode(), intervalMs, boundaryTime, lang)))));
        }
        for (FuturesOrderResponse o : pendingOrders) {
            String side = o.getOrderSide();
            // 开仓挂单才是承诺（带计划的入场条件）；平仓挂单只在账户块里
            if (side == null || !side.startsWith("OPEN_")) {
                continue;
            }
            AiTraderPlan plan = planByKey.get(TraderPlanStore.key(o.getSymbol(), side.substring("OPEN_".length())));
            lines.add(prompts.get(lang, "trader.wake.commitOrder", Map.of(
                    "symbol", o.getSymbol(), "side", side,
                    "limit", WatchText.num(o.getLimitPrice()),
                    "pendingFor", WakeAccountState.humanizeHeld(prompts, lang, boundaryTime - TradePairing.msOf(o.getCreatedAt())),
                    "invalidation", invalidation(plan),
                    "distance", distance(o.getLimitPrice(), marks.get(o.getSymbol()), lang))));
        }
        for (AiTraderWatch w : armed) {
            if (AiTraderWatch.AT_TIME.equals(w.getKind())) {
                lines.add(prompts.get(lang, "trader.wake.commitWatchTime", Map.of(
                        "symbol", w.getSymbol(), "at", BeijingTime.format(w.getAtTime()), "note", w.getNote(),
                        "minutes", Math.max(0, (w.getAtTime() - boundaryTime + 59_999) / 60_000))));
            } else {
                lines.add(prompts.get(lang, "trader.wake.commitWatchPrice", Map.of(
                        "symbol", w.getSymbol(), "condition", WatchText.armed(w, prompts, lang), "note", w.getNote(),
                        "distance", distance(w.getLevel(), marks.get(w.getSymbol()), lang))));
            }
        }
        Set<String> armedSymbols = armed.stream().map(AiTraderWatch::getSymbol).collect(Collectors.toSet());
        waitLines(concluded, lang).forEach((symbol, wait) -> {
            if (symbols.contains(symbol)) {
                lines.add(prompts.get(lang, "trader.wake.commitWait", Map.of("symbol", symbol, "wait", wait))
                        + (armedSymbols.contains(symbol) ? "" : prompts.get(lang, "trader.wake.commitWaitUnarmed")));
            }
        });
        if (lines.isEmpty()) {
            return "";
        }
        return "\n" + prompts.get(lang, "trader.wake.commitHeader") + "\n" + String.join("\n", lines) + "\n";
    }

    private static String invalidation(AiTraderPlan plan) {
        return plan == null || plan.getInvalidationCondition() == null ? "?" : plan.getInvalidationCondition();
    }

    /** 刚收盘那根心跳周期的开高低收，接在持仓行后面；本地没有那段 5m 就不带 */
    private String lastBar(String symbol, String interval, long intervalMs, long boundaryTime, AgentLang lang) {
        CaseFileBuilder.MarketWindow w = positionPath.lastClosedBar(symbol, boundaryTime, intervalMs);
        return w == null ? "" : prompts.get(lang, "trader.wake.commitBar", Map.of(
                "interval", interval,
                "open", WatchText.num(w.open()), "high", WatchText.num(w.high()),
                "low", WatchText.num(w.low()), "close", WatchText.num(w.close())));
    }

    /** "现价 X，在价位上方 / 下方 Y%"，接在挂单行、价格类条件行后面；没有现价就不带 */
    private String distance(BigDecimal level, BigDecimal mark, AgentLang lang) {
        if (level == null || mark == null || level.signum() <= 0) {
            return "";
        }
        BigDecimal pct = mark.subtract(level).multiply(BigDecimal.valueOf(100)).divide(level, 2, RoundingMode.HALF_UP);
        return prompts.get(lang, pct.signum() < 0 ? "trader.wake.commitBelow" : "trader.wake.commitAbove", Map.of(
                "mark", WatchText.num(mark), "level", WatchText.num(level), "pct", pct.abs().toPlainString()));
    }

    /**
     * 上一轮结论里每个币的"等待"行（写了"无"的不算）：按结论块自己那门语言的标记认。
     * 没结论、没分段或一行等待都没写返回空表；顺序照结论里的分段
     */
    private Map<String, String> waitLines(RecentWake concluded, AgentLang lang) {
        Map<String, String> out = new LinkedHashMap<>();
        if (concluded == null) {
            return out;
        }
        DecisionText.Conclusion c = decisionText.locateConclusion(concluded.reasoning(), lang);
        if (c == null) {
            return out;
        }
        // 长的排前面：等待条件 / Waiting for 要先于 等待 / Waiting 认
        Pattern line = Pattern.compile("(?m)^\\s*(?:" + Pattern.quote(prompts.get(c.lang(), "trader.mark.waitLong"))
                + "|" + Pattern.quote(prompts.get(c.lang(), "trader.mark.wait")) + ")\\s*[:：]\\s*(.+?)\\s*$");
        String none = prompts.get(c.lang(), "trader.mark.waitNone");
        for (DecisionText.ConclusionSegment segment : DecisionText.splitSegments(c.body(concluded.reasoning()))) {
            Matcher m = line.matcher(segment.body());
            if (!m.find()) {
                continue;
            }
            String wait = m.group(1);
            if (!wait.replaceAll("[。.\\s]+$", "").equalsIgnoreCase(none)) {
                out.putIfAbsent(segment.symbol(), wait);
            }
        }
        return out;
    }

    /** 一行一轮：时刻 [状态] 权益，OK 行列工具名，非 OK 行列原因；SKIPPED 行没权益、OK 行多半没 error */
    private String trajectory(List<RecentWake> recent, AgentLang lang) {
        if (recent.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(prompts.get(lang, "trader.wake.trajectoryHeader")).append('\n');
        for (RecentWake w : recent) {
            sb.append("- ").append(TIME_FMT.format(Instant.ofEpochMilli(w.wakeTime())))
                    .append(" [").append(w.status()).append(']');
            if (w.equity() != null) {
                sb.append(' ').append(prompts.get(lang, "trader.label.equity",
                        Map.of("value", w.equity().setScale(0, RoundingMode.HALF_UP))));
            }
            if (w.error() != null && !w.error().isBlank()) {
                sb.append(' ').append(w.error());
            }
            if (!w.tools().isEmpty()) {
                sb.append(' ').append(prompts.get(lang, "trader.wake.trajectoryTools", Map.of("tools", countNames(w.tools()))));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** klines, kline_structure×2 这种写法 */
    private static String countNames(List<String> names) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        names.forEach(n -> counts.merge(n, 1, Integer::sum));
        return counts.entrySet().stream()
                .map(e -> e.getValue() == 1 ? e.getKey() : e.getKey() + "×" + e.getValue())
                .collect(Collectors.joining(", "));
    }
}
