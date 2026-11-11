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
import com.mawai.wiibquant.market.service.CrossAssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

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
 * + 上一轮结论（每个币最近一次写下的那段，没变化的币沿用旧段）+ 承诺块（持仓与挂单的失效条件、布的每条盯盘条件离现价多远 / 还有多久、
 * 各币最近一段里的等待行，只列事实）。
 * 第二段「布防与收尾」用 {@link #history}（最近轨迹 + 论点战绩）、{@link #symbolsSummary}（各币自上次唤醒以来的开高低收）
 * 和 {@link #snapshot}（各币标记价与涨跌、美股参考）。
 * 只读不写，取数和成文都在这里。
 */
@Component
@RequiredArgsConstructor
public class WakeObservation {

    /** 最近唤醒轨迹列几轮 */
    private static final int RECENT_DECISIONS = 5;
    /** 找每个币最近一次写的结论段，最多往回翻这么多轮；再早的不带，那个币得重写 */
    public static final int CONCLUSION_LOOKBACK = 30;
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
    /** 美股期货与 VIX，进行情快照 */
    private final CrossAssetService crossAssets;

    /** 大盘参考：不在可交易币里也给一行 */
    static final String BENCHMARK = "BTCUSDT";
    /** 进开场白的美股参考：Yahoo 代码 → 词表里的名字 */
    private static final Map<String, String> EQUITY_NAMES = Map.of("NQ=F", "nq", "ES=F", "es", "^VIX", "vix");

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

    /** 一个币最近一次写下的结论段：哪一轮写的、用哪门语言写的（等待行的标记按它认）、段身 */
    record Segment(long wakeTime, AgentLang lang, String body) {
    }

    /**
     * 最近 {@value #CONCLUSION_LOOKBACK} 条交易行（例行/警报/手动/持仓事件/盯盘命中），最新在前。
     * 轨迹只列前 {@value #RECENT_DECISIONS} 条，其余是给"每个币最近一次的结论段"往回翻用的。
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
                .last("LIMIT " + CONCLUSION_LOOKBACK));
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
     * @param armed        还在盯的盯盘条件，进承诺块
     * @param marks        各币标记价，承诺块算"离价位多远"用；没有的币不带距离
     */
    Pack assemble(AiTrader trader, Collection<String> symbols, BigDecimal equity, List<FuturesPositionDTO> positions,
                  List<FuturesOrderResponse> pendingOrders, TraderPlanStore.Reconcile reconcile,
                  List<RecentWake> recent, long boundaryTime, AgentLang lang,
                  List<AiTraderWatch> hitsNow, List<AiTraderWatch> firedQuietly, List<AiTraderWatch> armed,
                  Map<String, BigDecimal> marks) {
        StringBuilder sb = new StringBuilder();
        if (!hitsNow.isEmpty()) {
            sb.append('\n').append(prompts.get(lang, "trader.wake.watchHitsHeader")).append('\n');
            for (AiTraderWatch w : hitsNow) {
                sb.append(prompts.get(lang, "trader.wake.watchHitLine", Map.of(
                        "detail", WatchText.detail(w, w.getFiredPrice(), prompts, lang),
                        "note", WatchText.note(w, prompts, lang)))).append('\n');
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
        Map<String, Segment> segments = latestSegments(recent, symbols);
        sb.append('\n').append(lastConclusion(latestConcluded(recent), segments, symbols, lang));
        String commitments = commitments(trader, positions, pendingOrders, reconcile.live(),
                armed, segments, marks, boundaryTime, lang);
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

    /** 可交易币按币码排好；大盘参考不在里面的排在最前 */
    static List<String> board(Collection<String> tradable) {
        List<String> out = new ArrayList<>();
        if (!tradable.contains(BENCHMARK)) {
            out.add(BENCHMARK);
        }
        out.addAll(tradable.stream().sorted().toList());
        return out;
    }

    /** 不可交易的那一行后面的标注；可交易的币不带 */
    private String referenceTag(String symbol, Collection<String> tradable, AgentLang lang) {
        return tradable.contains(symbol) ? "" : prompts.get(lang, "trader.wake.referenceTag");
    }

    /**
     * 行情快照：各币标记价、1h / 4h / 24h 涨跌、资金费率，大盘参考不可交易也带一行；
     * 后面跟美股参考（纳指期货、标普期货、VIX 的现价与相对昨收的涨跌，取不到整块不带）。
     *
     * @param quotes 各币的 premiumIndex 回包，没拉到的币没有它那行
     */
    String snapshot(Map<String, JsonNode> quotes, Collection<String> tradable, long now, AgentLang lang) {
        StringBuilder sb = new StringBuilder();
        for (String symbol : board(tradable)) {
            JsonNode q = quotes.get(symbol);
            if (q == null) {
                continue;
            }
            sb.append(prompts.get(lang, "trader.wake.snapshotRow", Map.of(
                    "symbol", symbol,
                    "price", WatchText.num(q.path("markPrice").asDecimal(null)),
                    "changes", positionPath.changes(symbol, now).stream()
                            .map(c -> c == null ? prompts.get(lang, "trader.wake.noData") : signed(c) + "%")
                            .collect(Collectors.joining(" / ")),
                    "funding", q.path("lastFundingRate").asString(null),
                    "tag", referenceTag(symbol, tradable, lang)))).append('\n');
        }
        List<CrossAssetService.Quote> equities = crossAssets.snapshot().stream()
                .filter(e -> EQUITY_NAMES.containsKey(e.symbol())).toList();
        if (!equities.isEmpty()) {
            sb.append(prompts.get(lang, "trader.wake.equitiesHeader")).append('\n');
            for (CrossAssetService.Quote e : equities) {
                sb.append(prompts.get(lang, "trader.wake.equityRow", Map.of(
                        "name", prompts.get(lang, "trader.wake.equity." + EQUITY_NAMES.get(e.symbol())),
                        "price", WatchText.num(e.price()),
                        "change", signed(e.changePct1d()),
                        "time", BeijingTime.format(e.timeMs())))).append('\n');
            }
        }
        return sb.toString();
    }

    private static String signed(BigDecimal v) {
        return v.signum() >= 0 ? "+" + v.toPlainString() : v.toPlainString();
    }

    /**
     * 第二段的各币摘要：每个可交易币和大盘参考自上次完成决策以来的开高低收（代码按已收盘 5m 算）；一个币都算不出返回空串
     */
    String symbolsSummary(AiTrader trader, Collection<String> symbols, Long lastOkWakeMs, long boundaryTime, AgentLang lang) {
        long intervalMs = TraderScheduler.INTERVAL_MS.get(trader.getIntervalCode());
        StringBuilder rows = new StringBuilder();
        for (String symbol : board(symbols)) {
            CaseFileBuilder.MarketWindow w = positionPath.symbol(symbol, lastOkWakeMs, boundaryTime, intervalMs);
            if (w == null) {
                continue;
            }
            rows.append(prompts.get(lang, "trader.wake.symbolsRow", Map.ofEntries(
                    Map.entry("tag", referenceTag(symbol, symbols, lang)),
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
                    "note", WatchText.note(w, prompts, lang)))).append('\n');
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

    /**
     * 每个可交易币最近一次写下的结论段，按币码排好：从最新一轮往回翻，一个币只认最先碰到的那段。
     * 翻完都没写过的币不在结果里
     */
    private Map<String, Segment> latestSegments(List<RecentWake> recent, Collection<String> symbols) {
        Map<String, Segment> found = new HashMap<>();
        for (RecentWake w : recent) {
            if (found.size() == symbols.size()) {
                break;
            }
            DecisionText.Conclusion c = AiTraderDecision.STATUS_OK.equals(w.status()) && !w.reasoning().isBlank()
                    ? decisionText.locateConclusion(w.reasoning(), AgentLang.ZH) : null;
            if (c == null) {
                continue;
            }
            for (DecisionText.ConclusionSegment s : DecisionText.splitSegments(c.body(w.reasoning()))) {
                if (symbols.contains(s.symbol())) {
                    found.putIfAbsent(s.symbol(), new Segment(w.wakeTime(), c.lang(), s.body().strip()));
                }
            }
        }
        Map<String, Segment> out = new LinkedHashMap<>();
        symbols.stream().sorted().filter(found::containsKey).forEach(s -> out.put(s, found.get(s)));
        return out;
    }

    /**
     * 上一轮结论：最近一轮的引子（总评、主人留言的对账行）+ 每个币最近一次写下的那段，段头带写下它的时刻。
     * 本轮没写的币沿用旧段，所以各段的时刻可以不同；翻不到段的币点名；一轮结论都没有就说没有
     */
    private String lastConclusion(RecentWake concluded, Map<String, Segment> segments, Collection<String> symbols,
                                  AgentLang lang) {
        if (concluded == null) {
            return prompts.get(lang, "trader.wake.lastConclusionNone") + "\n";
        }
        StringBuilder sb = new StringBuilder(prompts.get(lang, "trader.wake.lastConclusionHeader", Map.of(
                "time", TIME_FMT.format(Instant.ofEpochMilli(concluded.wakeTime())),
                "equity", concluded.equity().setScale(0, RoundingMode.HALF_UP)))).append('\n');
        DecisionText.Conclusion c = decisionText.locateConclusion(concluded.reasoning(), lang);
        String intro = DecisionText.intro(c.body(concluded.reasoning()));
        if (!intro.isEmpty()) {
            sb.append(intro).append('\n');
        }
        segments.forEach((symbol, s) -> sb.append(prompts.get(lang, "trader.wake.lastSegment", Map.of(
                "symbol", symbol, "time", TIME_FMT.format(Instant.ofEpochMilli(s.wakeTime())))))
                .append('\n').append(s.body()).append('\n'));
        symbols.stream().sorted().filter(s -> !segments.containsKey(s)).forEach(s ->
                sb.append(prompts.get(lang, "trader.wake.lastSegmentNone", Map.of("symbol", s))).append('\n'));
        return sb.toString();
    }

    // ==================== 承诺块 ====================

    /**
     * 承诺块：持仓与挂单各一行（失效条件 + 刚收盘那根心跳周期的开高低收）、在盯的每条条件一行（离现价多远 / 还有多久，带预案的跟上命中后平台会做什么）、
     * 每个币最近一段结论里的等待行一行（那个币一条条件都没布就说一句）。只列事实，判断留给模型。一条都没有返回空串
     */
    private String commitments(AiTrader trader, List<FuturesPositionDTO> positions,
                               List<FuturesOrderResponse> pendingOrders, List<AiTraderPlan> live, List<AiTraderWatch> armed,
                               Map<String, Segment> segments, Map<String, BigDecimal> marks, long boundaryTime, AgentLang lang) {
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
                        "minutes", Math.max(0, (w.getAtTime() - boundaryTime + 59_999) / 60_000)))
                        + WatchText.action(w, prompts, lang));
            } else {
                lines.add(prompts.get(lang, "trader.wake.commitWatchPrice", Map.of(
                        "symbol", w.getSymbol(), "condition", WatchText.armed(w, prompts, lang), "note", w.getNote(),
                        "distance", distance(w.getLevel(), marks.get(w.getSymbol()), lang)))
                        + WatchText.action(w, prompts, lang));
            }
        }
        Set<String> armedSymbols = armed.stream().map(AiTraderWatch::getSymbol).collect(Collectors.toSet());
        waitLines(segments).forEach((symbol, wait) ->
                lines.add(prompts.get(lang, "trader.wake.commitWait", Map.of("symbol", symbol, "wait", wait))
                        + (armedSymbols.contains(symbol) ? "" : prompts.get(lang, "trader.wake.commitWaitUnarmed"))));
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
     * 每个币最近一段结论里的"等待"行（写了"无"的不算）：按那段自己那门语言的标记认。
     * 一行等待都没写返回空表；顺序照币码
     */
    private Map<String, String> waitLines(Map<String, Segment> segments) {
        Map<String, String> out = new LinkedHashMap<>();
        segments.forEach((symbol, s) -> {
            // 长的排前面：等待条件 / Waiting for 要先于 等待 / Waiting 认
            Matcher m = Pattern.compile("(?m)^\\s*(?:" + Pattern.quote(prompts.get(s.lang(), "trader.mark.waitLong"))
                    + "|" + Pattern.quote(prompts.get(s.lang(), "trader.mark.wait")) + ")\\s*[:：]\\s*(.+?)\\s*$").matcher(s.body());
            if (m.find() && !m.group(1).replaceAll("[。.\\s]+$", "")
                    .equalsIgnoreCase(prompts.get(s.lang(), "trader.mark.waitNone"))) {
                out.put(symbol, m.group(1));
            }
        });
        return out;
    }

    /** 一行一轮：时刻 [状态] 权益，OK 行列工具名，非 OK 行列原因；SKIPPED 行没权益、OK 行多半没 error */
    private String trajectory(List<RecentWake> recent, AgentLang lang) {
        if (recent.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(prompts.get(lang, "trader.wake.trajectoryHeader")).append('\n');
        for (RecentWake w : recent.subList(0, Math.min(RECENT_DECISIONS, recent.size()))) {
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
