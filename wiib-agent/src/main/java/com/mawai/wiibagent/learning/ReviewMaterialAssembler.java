package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibagent.trader.TradeRecordService;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibagent.trader.trade.TargetRung;
import com.mawai.wiibagent.trader.trade.PlanEntry;
import com.mawai.wiibagent.trader.trade.TradeActions;
import com.mawai.wiibagent.trader.trade.TradeTools;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibagent.trader.wakeup.WatchText;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 复盘素材组装（纯代码）：战绩表、本局论点战绩、案例素材（每笔事实表按单子类型分组）、本期行情、盯盘布防、经验账本。
 * 事实裁定归代码、模型只解读。素材窗口 = (上次成功 REVIEW 的 wake_time, 本日线边界]。
 * <p>
 * 段标签全在 {@link PromptCatalog} 的 {@code reviewer.label.*}；配对与了结方式在 {@link TradePairing}，
 * 路径指标在 {@link CaseFileBuilder}，账本在 {@link ExperienceLedger}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewMaterialAssembler {

    /** 初始资金，与 TraderService.INITIAL_BALANCE 同一口径 */
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000");
    /** 已平仓位拉取上限：窗口通常一天，远超一天可能的成交笔数 */
    static final int CLOSED_FETCH_LIMIT = 200;
    /** 一笔交易的 1h 收盘最多列几根 */
    static final int MAX_HOURLY = 24;
    /** 行情块最多回看多久（本局首篇复盘 fromMs=0） */
    static final long MARKET_LOOKBACK_MS = 48 * 3_600_000L;
    /** 时刻按北京时间写，与行情块头、入场上下文的 hourBj 同一时区 */
    private static final DateTimeFormatter HM_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(BeijingTime.ZONE);
    /** 盯盘命中那轮的该币结论段最多带多少字 */
    static final int MAX_SECTION = 240;

    private final AiTraderDecisionMapper decisionMapper;
    private final AiTraderPlanMapper planMapper;
    private final SimTradeClient simTradeClient;
    private final PromptCatalog prompts;
    private final CaseFileBuilder caseFiles;
    private final ExperienceLedger ledger;
    private final PlayStatsAssembler playStats;
    private final TraderWatchStore watchStore;
    private final DecisionText decisionText;
    private final TradeRecordService tradeRecords;

    /** 六块素材文本 + 本期单子（仓位编号 → 净盈亏和平仓日）：复盘归类里的仓位编号只认这些 */
    public record ReviewMaterial(String statsBlock, String playStatsBlock, String casesBlock,
                                 String marketBlock, String watchBlock, String ledgerBlock,
                                 Map<Long, ExperienceLedger.PeriodTrade> trades) {
    }

    /** 上一期成功的复盘行；无 → null（本局首篇，素材窗口从本局开始算） */
    public AiTraderDecision lastReview(long traderId, int roundNo) {
        return decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, traderId)
                .eq(AiTraderDecision::getRoundNo, roundNo)
                .eq(AiTraderDecision::getKind, AiTraderDecision.KIND_REVIEW)
                .eq(AiTraderDecision::getStatus, AiTraderDecision.STATUS_OK)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT 1"));
    }

    /** 窗口内有过开仓动作（被拒的尝试也算）或有了结仓位，才有素材；先查库，库里没有才打 sim */
    public boolean hasNewMaterial(AiTrader trader, long fromMs, long toMs) {
        Long opens = decisionMapper.selectCount(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, trader.getId())
                .eq(AiTraderDecision::getRoundNo, trader.getRoundNo())
                .in(AiTraderDecision::getKind, AiTraderDecision.WAKE_KINDS)
                .gt(AiTraderDecision::getWakeTime, fromMs)
                .le(AiTraderDecision::getWakeTime, toMs)
                .like(AiTraderDecision::getActionsJson, "open_position"));
        if (opens != null && opens > 0) {
            return true;
        }
        return !inWindow(simTradeClient.getClosedPositions(trader.getSimUserId(), CLOSED_FETCH_LIMIT), fromMs, toMs).isEmpty();
    }

    public ReviewMaterial assemble(AiTrader trader, long fromMs, long toMs, AgentLang lang) {
        List<FuturesPositionDTO> fetched = simTradeClient.getClosedPositions(trader.getSimUserId(), CLOSED_FETCH_LIMIT);
        boolean maybeTruncated = fetched.size() >= CLOSED_FETCH_LIMIT;
        List<FuturesPositionDTO> closed = inWindow(fetched, fromMs, toMs);
        List<AiTraderPlan> plans = planMapper.selectList(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, trader.getId())
                .eq(AiTraderPlan::getRoundNo, trader.getRoundNo()));
        // 配对一次；stale 过滤在配对之后——被忽略的计划仍占配对位，先滤后配会错配
        Map<FuturesPositionDTO, AiTraderPlan> planByPos = TradePairing.pairAll(closed, plans);
        List<FuturesPositionDTO> visible = closed.stream()
                .filter(p -> !isStale(planByPos.get(p)))
                .toList();
        List<CaseFileBuilder.TradeCase> cases = visible.stream()
                .map(p -> caseFiles.build(p, planByPos.get(p))).toList();
        List<AiTraderExperience> active = ledger.active(trader.getId(), trader.getRoundNo());
        Map<Long, List<CaseFileBuilder.TradeCase>> citedBy = citedBy(trader, cases, active);
        List<CaseFileBuilder.CancelledLimit> cancelled = new ArrayList<>();
        for (AiTraderPlan plan : plans) {
            if (isStale(plan)) continue;
            List<PlanEntry> entries = PlanEntry.read(plan);
            if (!entries.isEmpty()) {
                entries.stream().filter(e -> "CANCELLED".equals(e.getStatus()) && e.getLimitPrice() != null
                                && e.getClosedAt() > fromMs && e.getClosedAt() <= toMs)
                        .map(e -> caseFiles.cancelledLimit(plan, e)).filter(Objects::nonNull).forEach(cancelled::add);
            } else if (AiTraderPlan.STATUS_CLOSED.equals(plan.getStatus()) && plan.getPositionId() == null
                    && !planByPos.containsValue(plan) && plan.getClosedWakeTime() > fromMs && plan.getClosedWakeTime() <= toMs) {
                CaseFileBuilder.CancelledLimit item = caseFiles.cancelledLimit(plan, lastSeenPending(trader, plan));
                if (item != null) cancelled.add(item);
            }
        }
        String stats = playStats.assemble(trader, lang);
        return new ReviewMaterial(
                statsBlock(trader, fromMs, toMs, visible, maybeTruncated, lang),
                stats == null ? "" : stats,
                casesBlock(cases, cancelled, lang),
                marketBlock(trader, fromMs, toMs, lang),
                watchBlock(trader, fromMs, toMs, lang),
                ledgerBlock(trader, active, citedBy, lang),
                periodTrades(cases));
    }

    /** 本期单子按仓位编号：净盈亏取案例行那个数，平仓日是北京日期；没有净盈亏的记不了账，不收 */
    private static Map<Long, ExperienceLedger.PeriodTrade> periodTrades(List<CaseFileBuilder.TradeCase> cases) {
        Map<Long, ExperienceLedger.PeriodTrade> out = new HashMap<>();
        for (CaseFileBuilder.TradeCase c : cases) {
            if (c.net() != null) {
                out.put(c.pos().getId(), new ExperienceLedger.PeriodTrade(c.net(),
                        Instant.ofEpochMilli(TradePairing.msOf(c.pos().getUpdatedAt())).atZone(BeijingTime.ZONE).toLocalDate()));
            }
        }
        return out;
    }

    private static boolean isStale(AiTraderPlan p) {
        return p != null && Boolean.TRUE.equals(p.getStale());
    }

    /**
     * 现役经验各自被本期哪些单子引用过：单子开仓那轮的结论里，该币那一段写了它的 #编号
     * （那轮没按币分段就看整块结论）。发现不算。
     * 计划行对上的那一轮得真开过这个币这个方向才算：续立行对上的是对账那轮，补归档行对不上任何一轮，这两种不列
     */
    private Map<Long, List<CaseFileBuilder.TradeCase>> citedBy(AiTrader trader, List<CaseFileBuilder.TradeCase> cases,
                                                               List<AiTraderExperience> active) {
        Map<Long, AiTraderDecision> openByWake = tradeRecords.openDecisions(trader,
                cases.stream().map(CaseFileBuilder.TradeCase::plan).filter(Objects::nonNull).toList());
        Set<Long> ids = active.stream().filter(e -> !AiTraderExperience.KIND_FINDING.equals(e.getKind()))
                .map(AiTraderExperience::getId).collect(Collectors.toSet());
        Map<Long, List<CaseFileBuilder.TradeCase>> out = new HashMap<>();
        for (CaseFileBuilder.TradeCase c : cases) {
            AiTraderDecision open = c.plan() == null ? null : openByWake.get(c.plan().getOpenedWakeTime());
            if (open == null || !opened(open, c.pos())) {
                continue;
            }
            for (Long id : citedIds(open.getReasoning(), c.pos().getSymbol())) {
                if (ids.contains(id)) {
                    out.computeIfAbsent(id, k -> new ArrayList<>()).add(c);
                }
            }
        }
        return out;
    }

    /** 这一轮的动作里有没有开成这个币这个方向的仓（反手的开仓腿也算） */
    private static boolean opened(AiTraderDecision d, FuturesPositionDTO pos) {
        if (d.getActionsJson() == null) {
            return false;
        }
        for (JsonNode action : MAPPER.readTree(d.getActionsJson())) {
            for (JsonNode leg : TradeActions.legs(action)) {
                if (TradeActions.opened(leg) && pos.getSymbol().equals(leg.path("args").path("symbol").asString(null))
                        && pos.getSide().equals(leg.path("args").path("side").asString(null))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 一轮结论里该币那一段引用的账本编号；那轮没按币分段就看整块结论 */
    private Set<Long> citedIds(String reasoning, String symbol) {
        String block = decisionText.conclusionBlock(reasoning);
        if (block == null) {
            return Set.of();
        }
        List<DecisionText.ConclusionSegment> segments = DecisionText.splitSegments(block);
        return DecisionText.ledgerRefs(segments.isEmpty() ? block : segments.stream()
                .filter(s -> symbol.equals(s.symbol())).map(DecisionText.ConclusionSegment::body)
                .collect(Collectors.joining("\n")));
    }

    // ==================== 战绩表 ====================

    private String statsBlock(AiTrader t, long fromMs, long toMs, List<FuturesPositionDTO> closed,
                              boolean maybeTruncated, AgentLang lang) {
        // 起始权益 = 窗口起点前最后一条带权益的决策行；开局首次复盘无前值 → 初始资金
        AiTraderDecision prior = decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .isNotNull(AiTraderDecision::getEquity)
                .le(AiTraderDecision::getWakeTime, fromMs)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT 1"));
        BigDecimal start = prior != null ? prior.getEquity() : INITIAL_BALANCE;
        List<AiTraderDecision> series = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .select(AiTraderDecision::getWakeTime, AiTraderDecision::getEquity)
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .isNotNull(AiTraderDecision::getEquity)
                .gt(AiTraderDecision::getWakeTime, fromMs)
                .le(AiTraderDecision::getWakeTime, toMs)
                .orderByAsc(AiTraderDecision::getWakeTime));
        BigDecimal end = series.isEmpty() ? start : series.getLast().getEquity();

        BigDecimal returnPct = start.signum() > 0
                ? end.subtract(start).multiply(BigDecimal.valueOf(100)).divide(start, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal maxDd = getMaxDd(start, series);
        long wins = closed.stream().filter(p -> p.getClosedPnl() != null && p.getClosedPnl().signum() > 0).count();
        BigDecimal pnlSum = closed.stream().map(FuturesPositionDTO::getClosedPnl)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);

        StringBuilder sb = new StringBuilder(prompts.get(lang, "reviewer.label.statsHeader")).append('\n');
        if (maybeTruncated) {
            sb.append(prompts.get(lang, "reviewer.label.statsTruncated",
                    Map.of("limit", CLOSED_FETCH_LIMIT))).append('\n');
        }
        sb.append(prompts.get(lang, "reviewer.label.statsEquity", Map.of(
                "start", start.setScale(2, RoundingMode.HALF_UP),
                "end", end.setScale(2, RoundingMode.HALF_UP),
                "pct", signed(returnPct)))).append('\n');
        sb.append(prompts.get(lang, "reviewer.label.statsDrawdown", Map.of("dd", maxDd))).append('\n');
        if (closed.isEmpty()) {
            sb.append(prompts.get(lang, "reviewer.label.noClosed")).append('\n');
        } else {
            sb.append(prompts.get(lang, "reviewer.label.statsClosed", Map.of(
                    "n", closed.size(), "wins", wins, "losses", closed.size() - wins,
                    "winRate", wins * 100 / closed.size(),
                    "pnl", signed(pnlSum.setScale(2, RoundingMode.HALF_UP))))).append('\n');
        }
        return sb.toString();
    }

    private static @NonNull BigDecimal getMaxDd(BigDecimal start, List<AiTraderDecision> series) {
        BigDecimal peak = start;
        BigDecimal maxDd = BigDecimal.ZERO;
        for (AiTraderDecision d : series) {
            BigDecimal e = d.getEquity();
            if (e.compareTo(peak) > 0) {
                peak = e;
            } else if (peak.signum() > 0) {
                BigDecimal dd = peak.subtract(e).multiply(BigDecimal.valueOf(100))
                        .divide(peak, 2, RoundingMode.HALF_UP);
                if (dd.compareTo(maxDd) > 0) {
                    maxDd = dd;
                }
            }
        }
        return maxDd;
    }

    // ==================== 案例素材 ====================

    private String casesBlock(List<CaseFileBuilder.TradeCase> cases, List<CaseFileBuilder.CancelledLimit> cancelled,
                              AgentLang lang) {
        StringBuilder sb = new StringBuilder(prompts.get(lang, "reviewer.label.casesHeader")).append('\n');
        if (cases.isEmpty()) {
            sb.append(prompts.get(lang, "reviewer.label.casesNone")).append('\n');
        }
        List<CaseFileBuilder.SetupGroup> groups = CaseFileBuilder.groups(cases);
        List<CaseFileBuilder.SetupGroup> selected = CaseFileBuilder.select(groups);
        for (CaseFileBuilder.SetupGroup g : groups) {
            boolean detailed = selected.contains(g);
            sb.append(prompts.get(lang, detailed ? "reviewer.label.caseGroup" : "reviewer.label.caseGroupOther", Map.of(
                    "play", g.playType() == null ? prompts.get(lang, "reviewer.label.caseGeneral") : g.playType(),
                    "n", g.trades().size(), "wins", g.wins(), "net", signed(g.net().setScale(0, RoundingMode.HALF_UP))))).append('\n');
            for (CaseFileBuilder.TradeCase c : g.trades()) {
                sb.append(detailed ? caseLines(c, lang) : briefLine(c.pos(), lang) + "\n");
            }
        }
        List<CaseFileBuilder.Replay> replays = cases.stream()
                .map(CaseFileBuilder.TradeCase::replay).filter(Objects::nonNull).toList();
        if (!replays.isEmpty()) {
            sb.append(prompts.get(lang, "reviewer.label.caseReplaySummary")).append('\n')
                    .append(playStats.replayLines(replays, lang));
        }
        if (!cancelled.isEmpty()) {
            sb.append(prompts.get(lang, "reviewer.label.cancelledHeader")).append('\n');
            for (CaseFileBuilder.CancelledLimit c : cancelled) {
                AiTraderPlan plan = c.plan();
                sb.append(prompts.get(lang, "reviewer.label.cancelledRow", Map.of(
                        "symbol", plan.getSymbol(), "side", plan.getSide(),
                        "limit", plain(c.limit()),
                        "stop", plain(c.stop()),
                        "target", targets(c.targets(), lang),
                        "closed", BeijingTime.shortFormat(c.cancelledAt()),
                        "reason", c.entry() == null ? prompts.get(lang, "trader.cancelReason.unknown") : nullSafe(c.entry().getReason()),
                        "after", cancelledAfter(c, lang)))).append('\n');
            }
        }
        return sb.toString();
    }

    /** 撤掉的限价档之后怎么走的那半句：没穿过限价位的只说到过几档；穿过的带上那一根的时刻和走到哪了结 */
    private String cancelledAfter(CaseFileBuilder.CancelledLimit c, AgentLang lang) {
        String targets = targetsAfter(c.targets(), c.reachedTargets(), lang);
        if (c.end() == CaseFileBuilder.CancelledEnd.NOT_CROSSED) {
            return prompts.get(lang, "reviewer.label.cancelledNotCrossed", Map.of("targetsAfter", targets));
        }
        return prompts.get(lang, "reviewer.label.cancelledCrossed", Map.of(
                "at", BeijingTime.shortFormat(c.crossedAt()),
                "end", prompts.get(lang, switch (c.end()) {
                    case STOPPED -> "reviewer.label.cancelledStopped";
                    case TARGETS_DONE -> "reviewer.label.cancelledTargetsDone";
                    default -> "reviewer.label.cancelledUndecided";
                }),
                "targetsAfter", targets));
    }

    /**
     * 撤掉的限价单最后一次被看到还挂着的时刻：归档它的那轮之前最近的一次唤醒，撤单就发生在那之后；
     * 中间没有别的唤醒就是挂出它的那轮
     */
    private long lastSeenPending(AiTrader t, AiTraderPlan plan) {
        AiTraderDecision before = decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .select(AiTraderDecision::getWakeTime)
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .in(AiTraderDecision::getKind, AiTraderDecision.WAKE_KINDS)
                .lt(AiTraderDecision::getWakeTime, plan.getClosedWakeTime())
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT 1"));
        return before == null ? plan.getOpenedWakeTime() : Math.max(before.getWakeTime(), plan.getOpenedWakeTime());
    }

    /** 计划里的止盈档写法；没计划或没设止盈写"无" */
    private String targets(AiTraderPlan plan, AgentLang lang) {
        return targets(TargetRung.of(plan), lang);
    }

    private String targets(List<TargetRung> rungs, AgentLang lang) {
        return rungs.isEmpty() ? prompts.get(lang, "reviewer.label.none") : TargetRung.text(rungs);
    }

    /** "之后到过原目标 1/2 档"；没设止盈、数据不够各有一句。reached 为 null＝出场后数据不够 24 小时 */
    private String targetsAfter(AiTraderPlan plan, Integer reached, AgentLang lang) {
        return targetsAfter(TargetRung.of(plan), reached, lang);
    }

    private String targetsAfter(List<TargetRung> targets, Integer reached, AgentLang lang) {
        int total = targets.size();
        if (total == 0) {
            return prompts.get(lang, "reviewer.label.caseNoTarget");
        }
        return reached == null ? prompts.get(lang, "reviewer.label.caseAfterShort")
                : prompts.get(lang, "reviewer.label.caseTargetsAfter", Map.of("hit", reached, "total", total));
    }

    private String caseLines(CaseFileBuilder.TradeCase c, AgentLang lang) {
        AiTraderPlan plan = c.plan();
        CaseFileBuilder.Path p = c.path();
        String none = prompts.get(lang, "reviewer.label.none");
        StringBuilder sb = new StringBuilder(tradeLine(c.pos(), plan, lang));
        if (CaseFileBuilder.hasFilledAdds(plan)) sb.append(prompts.get(lang, "reviewer.label.caseWithAdds"));
        sb.append('\n').append(entriesLine(plan, lang));
        sb.append(prompts.get(lang, "reviewer.label.casePath", Map.ofEntries(
                Map.entry("mfe", or(p.mfePrice(), none)),
                Map.entry("mfeAt", p.mfeAt() == null ? none : HM_FMT.format(Instant.ofEpochMilli(p.mfeAt()))),
                Map.entry("mfeR", p.mfeR() == null ? none : signedR(p.mfeR())),
                Map.entry("mae", or(p.maePrice(), none)),
                Map.entry("maeR", p.maeR() == null ? none : signedR(p.maeR().negate())),
                Map.entry("tHalf", p.minToHalfR() == null ? none : String.valueOf(p.minToHalfR())),
                Map.entry("t1", p.minTo1R() == null ? none : String.valueOf(p.minTo1R())),
                Map.entry("firstBack", p.firstBackAt() == null ? none : HM_FMT.format(Instant.ofEpochMilli(p.firstBackAt()))),
                Map.entry("best4", or(p.bestAfter4h(), none)),
                Map.entry("best24", or(p.bestAfter24h(), none)),
                Map.entry("targetsAfter", targetsAfter(plan, p.targetsAfter24h(), lang))))).append('\n');
        if (c.replay() != null) {
            sb.append(replayLine(c.replay(), lang)).append('\n');
        }
        if (!p.hourly().isEmpty()) {
            List<KlineBar> hourly = p.hourly().size() > MAX_HOURLY
                    ? p.hourly().subList(p.hourly().size() - MAX_HOURLY, p.hourly().size()) : p.hourly();
            StringBuilder closes = new StringBuilder(hourly.size() < p.hourly().size() ? "… " : "");
            for (KlineBar b : hourly) {
                if (!closes.isEmpty() && closes.charAt(closes.length() - 1) != ' ') {
                    closes.append(' ');
                }
                closes.append(plain(b.close()));
            }
            sb.append(prompts.get(lang, "reviewer.label.caseHourly", Map.of("closes", closes.toString()))).append('\n');
        }
        if (plan == null) {
            sb.append(prompts.get(lang, "reviewer.label.caseNoPlan")).append('\n');
        } else {
            sb.append(prompts.get(lang, "reviewer.label.casePlan", Map.of(
                    "signals", nullSafe(plan.getSignalsUsed()),
                    "invalidation", nullSafe(plan.getInvalidationCondition()),
                    "revisions", revisions(plan.getRevisionsJson(), lang, none)))).append('\n');
        }
        if (c.entryCtx() != null) {
            sb.append(ctxLine(c.entryCtx(), c.pos(), lang)).append('\n');
        }
        return sb.toString();
    }

    private String entriesLine(AiTraderPlan plan, AgentLang lang) {
        List<PlanEntry> entries = PlanEntry.read(plan);
        if (entries.isEmpty() || entries.size() == 1 && "FILLED".equals(entries.getFirst().getStatus())) return "";
        List<String> rows = new ArrayList<>();
        BigDecimal planned = BigDecimal.ZERO, filled = BigDecimal.ZERO;
        for (PlanEntry entry : entries) {
            planned = planned.add(entry.getQuantity());
            String state = switch (entry.getStatus()) {
                case "FILLED" -> {
                    filled = filled.add(entry.getQuantity());
                    yield prompts.get(lang, "reviewer.label.entryFilled", Map.of("price", plain(entry.getFilledPrice()),
                            "time", BeijingTime.shortFormat(entry.getClosedAt())));
                }
                case "CANCELLED" -> prompts.get(lang, "reviewer.label.entryCancelled",
                        Map.of("time", BeijingTime.shortFormat(entry.getClosedAt()), "reason",
                                entry.getReason() == null ? prompts.get(lang, "trader.cancelReason.unknown") : entry.getReason()));
                case "UNKNOWN" -> prompts.get(lang, "reviewer.label.entryUnknown");
                default -> prompts.get(lang, "reviewer.label.entryPending");
            };
            rows.add(prompts.get(lang, "reviewer.label.entryRow", Map.of(
                    "price", entry.getLimitPrice() == null ? prompts.get(lang, "reviewer.label.entryMarket") : plain(entry.getLimitPrice()),
                    "qty", plain(entry.getQuantity()), "add", entry.isAdd() ? prompts.get(lang, "reviewer.label.entryAdd") : "", "state", state)));
        }
        return prompts.get(lang, "reviewer.label.caseEntries", Map.of("entries", String.join(" / ", rows),
                "planned", plain(planned), "filled", plain(filled))) + "\n";
    }

    /** 一笔的重放行：实际几 R、开仓后 24 小时的顺向逆向与拿满的位置、每档止损宽度的结局 */
    String replayLine(CaseFileBuilder.Replay r, AgentLang lang) {
        List<String> widths = new ArrayList<>();
        for (CaseFileBuilder.WidthOutcome w : r.widths()) {
            String outcome = switch (w.outcome()) {
                case TARGET -> prompts.get(lang, "reviewer.label.replayTarget", Map.of("r", signedR(w.r())));
                case STOP -> prompts.get(lang, "reviewer.label.replayStop", Map.of("r", signedR(w.r())));
                case OPEN -> prompts.get(lang, "reviewer.label.replayOpen");
            };
            widths.add(w.width().compareTo(BigDecimal.ONE) == 0
                    ? prompts.get(lang, "reviewer.label.replayOriginal", Map.of("outcome", outcome))
                    : prompts.get(lang, "reviewer.label.replayWidth", Map.of("width", w.width().toPlainString(), "outcome", outcome)));
        }
        return prompts.get(lang, "reviewer.label.caseReplay", Map.of(
                "actual", r.actualR() == null ? prompts.get(lang, "reviewer.label.none") : signedR(r.actualR()),
                "fav", r.fav24R() == null ? prompts.get(lang, "reviewer.label.none") : signedR(r.fav24R()),
                "adv", r.adv24R() == null ? prompts.get(lang, "reviewer.label.none") : signedR(r.adv24R()),
                "hold", r.hold24R() == null ? prompts.get(lang, "reviewer.label.replayHoldShort") : signedR(r.hold24R()),
                "widths", String.join(prompts.get(lang, "reviewer.label.replaySep"), widths)));
    }

    private static String signedR(BigDecimal r) {
        return signed(r) + "R";
    }

    /** 没展开的组逐笔一行：仓位编号、币、方向、开平时刻、净盈亏 */
    private String briefLine(FuturesPositionDTO pos, AgentLang lang) {
        return prompts.get(lang, "reviewer.label.caseTradeBrief", Map.of(
                "id", pos.getId(),
                "symbol", pos.getSymbol(),
                "side", pos.getSide(),
                "opened", BeijingTime.shortFormat(TradePairing.msOf(pos.getCreatedAt())),
                "closed", BeijingTime.shortFormat(TradePairing.msOf(pos.getUpdatedAt())),
                "pnl", signed(pos.getClosedPnl())));
    }

    /** 一笔的交易行：仓位编号 / 币 / 方向 / 开平时刻 / 持有 / 入场 止损 目标 出场 / 了结方式 / 净额 / 资金费 */
    private String tradeLine(FuturesPositionDTO pos, AiTraderPlan plan, AgentLang lang) {
        String none = prompts.get(lang, "reviewer.label.none");
        return prompts.get(lang, "reviewer.label.caseTrade", Map.ofEntries(
                Map.entry("id", pos.getId()),
                Map.entry("symbol", pos.getSymbol()),
                Map.entry("side", pos.getSide()),
                Map.entry("opened", BeijingTime.shortFormat(TradePairing.msOf(pos.getCreatedAt()))),
                Map.entry("closed", BeijingTime.shortFormat(TradePairing.msOf(pos.getUpdatedAt()))),
                Map.entry("held", humanize(prompts, TradePairing.msOf(pos.getUpdatedAt()) - TradePairing.msOf(pos.getCreatedAt()), lang)),
                Map.entry("entry", plain(pos.getEntryPrice())),
                Map.entry("stop", plan == null ? none : plain(plan.getStopLossPrice())),
                Map.entry("target", targets(plan, lang)),
                Map.entry("exit", plain(pos.getClosedPrice())),
                Map.entry("manner", TradePairing.closeManner(prompts, pos, lang)),
                Map.entry("pnl", signed(pos.getClosedPnl())),
                Map.entry("funding", pos.getFundingFeeTotal() == null ? none : signed(pos.getFundingFeeTotal()))));
    }

    /**
     * 入场上下文一行；限价单记了下单时刻的，带上挂了多久才成交。
     * 加仓会把上下文换成加仓那一单的，它的下单时刻晚于仓位开仓，这时不带
     */
    private String ctxLine(JsonNode ctx, FuturesPositionDTO pos, AgentLang lang) {
        String none = prompts.get(lang, "reviewer.label.none");
        long pendingMs = TradePairing.msOf(pos.getCreatedAt()) - ctx.path("placedAt").asLong();
        String pending = "LIMIT".equals(ctx.path("orderType").asString(null)) && ctx.has("placedAt") && pendingMs >= 0
                ? prompts.get(lang, "reviewer.label.casePending", Map.of("held", humanize(prompts, pendingMs, lang)))
                : "";
        return prompts.get(lang, "reviewer.label.caseCtx", Map.ofEntries(
                Map.entry("pending", pending),
                Map.entry("adx", text(ctx, "adx1h", none)),
                Map.entry("bollBw", text(ctx, "bollBw1h", none)),
                Map.entry("atr", text(ctx, "atr1h", none)),
                Map.entry("stopAtr", text(ctx, "stopAtr", none)),
                Map.entry("stopPct", text(ctx, "stopPct", none)),
                Map.entry("eventMin", text(ctx, "eventMin", none)),
                Map.entry("eventTitle", text(ctx, "eventTitle", "")),
                Map.entry("hour", text(ctx, "hourBj", none)),
                Map.entry("funding", text(ctx, "funding", none)),
                Map.entry("orderType", text(ctx, "orderType", none)),
                Map.entry("leverage", text(ctx, "leverage", none))));
    }

    /** 修订史一行：类型 变化（理由），理由截 60 字 */
    private String revisions(String json, AgentLang lang, String none) {
        if (json == null || json.isBlank()) {
            return none;
        }
        ArrayNode arr = MAPPER.readValue(json, ArrayNode.class);
        List<String> parts = new ArrayList<>();
        for (JsonNode r : arr) {
            String reason = r.path("reason").asString("");
            parts.add(prompts.get(lang, "reviewer.label.caseRevision", Map.of(
                    "type", r.path("type").asString(""),
                    "change", r.path("change").asString(""),
                    "reason", reason.length() > 60 ? reason.substring(0, 60) + "…" : reason)));
        }
        return parts.isEmpty() ? none : String.join("；", parts);
    }

    // ==================== 本期行情 ====================

    private String marketBlock(AiTrader t, long fromMs, long toMs, AgentLang lang) {
        long from = Math.max(fromMs, toMs - MARKET_LOOKBACK_MS);
        StringBuilder sb = new StringBuilder(prompts.get(lang, "reviewer.label.marketHeader")).append('\n');
        for (String symbol : t.getSymbols().split(",")) {
            symbol = symbol.trim();
            if (symbol.isEmpty()) {
                continue;
            }
            CaseFileBuilder.MarketWindow w = caseFiles.market(symbol, from, toMs);
            if (w == null) {
                sb.append(prompts.get(lang, "reviewer.label.marketNoBars", Map.of("symbol", symbol))).append('\n');
                continue;
            }
            sb.append(prompts.get(lang, "reviewer.label.marketRow", Map.of(
                    "symbol", symbol,
                    "open", plain(w.open()), "close", plain(w.close()),
                    "low", plain(w.low()), "lowAt", BeijingTime.shortFormat(w.lowAt()),
                    "high", plain(w.high()), "highAt", BeijingTime.shortFormat(w.highAt()),
                    "range", w.lowToHighPct()))).append('\n');
        }
        return sb.toString();
    }

    // ==================== 盯盘布防 ====================

    /** 期间布的盯盘条件、命中，以及得知命中那轮的交易动作与该币结论；整期没布过条件的币点名 */
    private String watchBlock(AiTrader t, long fromMs, long toMs, AgentLang lang) {
        List<AiTraderWatch> rows = watchStore.inWindow(t.getId(), t.getRoundNo(), fromMs, toMs);
        List<AiTraderWatch> created = rows.stream()
                .filter(w -> w.getCreatedWakeTime() > fromMs && w.getCreatedWakeTime() <= toMs).toList();
        List<AiTraderWatch> fired = rows.stream()
                .filter(w -> w.getFiredAt() != null && w.getFiredAt() > fromMs && w.getFiredAt() <= toMs)
                .sorted(Comparator.comparingLong(AiTraderWatch::getFiredAt)).toList();
        List<AiTraderWatch> armed = watchStore.armed(t.getId(), t.getRoundNo());
        // 期间布过的币；窗口前布下、现在还在盯的也算有条件
        Set<String> createdSymbols = new LinkedHashSet<>();
        created.forEach(w -> createdSymbols.add(w.getSymbol()));
        Set<String> covered = new LinkedHashSet<>(createdSymbols);
        armed.forEach(w -> covered.add(w.getSymbol()));
        List<String> idle = Arrays.stream(t.getSymbols().split(",")).map(String::trim)
                .filter(s -> !s.isEmpty() && !covered.contains(s)).toList();
        StringBuilder sb = new StringBuilder(prompts.get(lang, "reviewer.label.watchHeader")).append('\n');
        sb.append(prompts.get(lang, "reviewer.label.watchSummary", Map.of(
                "n", created.size(),
                "symbols", createdSymbols.isEmpty() ? prompts.get(lang, "reviewer.label.none") : String.join(", ", createdSymbols),
                "hits", fired.size(),
                "armed", armed.size(),
                "idle", idle.isEmpty() ? ""
                        : prompts.get(lang, "reviewer.label.watchIdle", Map.of("symbols", String.join(", ", idle)))))).append('\n');
        for (AiTraderWatch w : fired) {
            sb.append(prompts.get(lang, "reviewer.label.watchHit", Map.of(
                    "firedAt", BeijingTime.shortFormat(w.getFiredAt()),
                    "detail", WatchText.detail(w, w.getFiredPrice(), prompts, lang),
                    "note", WatchText.note(w, prompts, lang),
                    "followUp", followUp(t, w, lang)))).append('\n');
        }
        return sb.toString();
    }

    /** 得知命中的那轮（woke_at 对应的决策行）：时刻、唤醒类型、交易动作、该币的结论段 */
    private String followUp(AiTrader t, AiTraderWatch w, AgentLang lang) {
        if (w.getWokeAt() == null) {
            return prompts.get(lang, "reviewer.label.watchUnreported");
        }
        // 日线边界上 REVIEW 行与例行轮同一个 wake_time，只认唤醒轮
        AiTraderDecision d = decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .eq(AiTraderDecision::getWakeTime, w.getWokeAt())
                .in(AiTraderDecision::getKind, AiTraderDecision.WAKE_KINDS)
                .last("LIMIT 1"));
        String none = prompts.get(lang, "reviewer.label.none");
        return prompts.get(lang, "reviewer.label.watchFollowUp", Map.of(
                "wokeAt", BeijingTime.shortFormat(w.getWokeAt()),
                "kind", d == null ? none : d.getKind(),
                "actions", d == null ? none : tradeActions(d.getActionsJson(), lang),
                "section", d == null ? none : symbolSection(d.getReasoning(), w.getSymbol(), none)));
    }

    /** 那轮的交易动作：工具名列表；被护栏拒的、sim 报错的、结果未知的各带标，没标的才是做成了 */
    private String tradeActions(String actionsJson, AgentLang lang) {
        List<String> out = new ArrayList<>();
        if (actionsJson != null && !actionsJson.isBlank()) {
            for (JsonNode a : MAPPER.readTree(actionsJson).valueStream()
                    .flatMap(a -> TradeActions.legs(a).stream()).toList()) {
                String tool = a.path("tool").asString(null);
                if (tool == null || !TradeTools.SERIAL_TOOLS.contains(tool)) {
                    continue;
                }
                String status = a.path("status").asString("");
                String key = a.has("rejected") ? "watchRejected"
                        : TradeActions.ERROR.equals(status) ? "watchFailed"
                        : TradeActions.NOT_ATTEMPTED.equals(status) ? "watchNotAttempted"
                        : TradeActions.LIQUIDATED.equals(status) ? "watchLiquidated"
                        : TradeActions.UNKNOWN.equals(status) ? "watchUnknown" : null;
                out.add(key == null ? tool : prompts.get(lang, "reviewer.label." + key, Map.of("tool", tool)));
            }
        }
        return out.isEmpty() ? prompts.get(lang, "reviewer.label.watchNoAction") : String.join(", ", out);
    }

    /** 那轮结论里该币的段，压成一行 */
    private String symbolSection(String reasoning, String symbol, String none) {
        String block = decisionText.conclusionBlock(reasoning);
        if (block == null) {
            return none;
        }
        return DecisionText.splitSegments(block).stream()
                .filter(s -> symbol.equals(s.symbol()))
                .map(s -> s.body().strip().replaceAll("\\s+", " "))
                .map(s -> s.length() > MAX_SECTION ? s.substring(0, MAX_SECTION) + "…" : s)
                .findFirst().orElse(none);
    }

    // ==================== 经验账本 ====================

    private String ledgerBlock(AiTrader t, List<AiTraderExperience> active,
                               Map<Long, List<CaseFileBuilder.TradeCase>> citedBy, AgentLang lang) {
        List<AiTraderExperience> entries = active.stream()
                .filter(e -> !AiTraderExperience.KIND_FINDING.equals(e.getKind())).toList();
        StringBuilder sb = new StringBuilder(prompts.get(lang, "reviewer.label.ledgerHeader",
                Map.of("n", entries.size()))).append('\n');
        sb.append(quotaLine(active, lang)).append('\n');
        if (entries.isEmpty()) {
            sb.append(prompts.get(lang, "reviewer.label.ledgerEmpty")).append('\n');
        }
        String sep = prompts.get(lang, "reviewer.label.listSep");
        Map<Long, ExperienceLedger.Tally> tallies = ledger.tallies(entries);
        for (AiTraderExperience e : entries) {
            sb.append(prompts.get(lang, "reviewer.label.ledgerRow", Map.of(
                    "id", e.getId(),
                    "kind", prompts.get(lang, "trader.label.ledgerKind." + e.getKind().toLowerCase(Locale.ROOT)),
                    "slot", slotWord(e.getSlot(), lang),
                    "dir", prompts.get(lang, "reviewer.op.dir." + e.getDirection().toLowerCase(Locale.ROOT)),
                    "play", e.getPlayType() == null ? prompts.get(lang, "reviewer.op.general") : e.getPlayType(),
                    "text", e.getText(),
                    "tally", tallyText(e, tallies.get(e.getId()), lang),
                    "evidence", nullSafe(e.getEvidence())))).append('\n');
            // 本期开仓那轮引用了它的单子，不管单子是什么类型
            List<CaseFileBuilder.TradeCase> cited = citedBy.get(e.getId());
            if (cited != null) {
                sb.append(prompts.get(lang, "reviewer.label.ledgerCited", Map.of("trades", cited.stream()
                        .map(c -> prompts.get(lang, "reviewer.label.ledgerCitedTrade", Map.of(
                                "id", c.pos().getId(),
                                "symbol", c.pos().getSymbol(), "side", c.pos().getSide(),
                                "play", c.playType() == null ? prompts.get(lang, "reviewer.label.caseGeneral") : c.playType(),
                                "opened", BeijingTime.shortFormat(TradePairing.msOf(c.pos().getCreatedAt())),
                                "net", signed(c.net()))))
                        .collect(Collectors.joining(sep))))).append('\n');
            }
        }
        List<AiTraderExperience> findings = active.stream()
                .filter(e -> AiTraderExperience.KIND_FINDING.equals(e.getKind())).toList();
        if (!findings.isEmpty()) {
            sb.append(prompts.get(lang, "reviewer.label.findingsHeader")).append('\n');
            for (AiTraderExperience f : findings) {
                sb.append(prompts.get(lang, "reviewer.label.findingRow", Map.of(
                        "id", f.getId(), "text", f.getText(), "evidence", nullSafe(f.getEvidence())))).append('\n');
            }
        }
        List<AiTraderExperience> retired = ledger.retired(t.getId(), t.getRoundNo());
        if (!retired.isEmpty()) {
            sb.append(prompts.get(lang, "reviewer.label.retiredHeader")).append('\n');
            for (AiTraderExperience r : retired) {
                sb.append(prompts.get(lang, "reviewer.label.retiredRow", Map.of(
                        "id", r.getId(), "text", r.getText(), "reason", nullSafe(r.getRetireReason())))).append('\n');
            }
        }
        return sb.toString();
    }

    /** 账本名额占了多少：现役总数、哪些单子类型满了、限制型几条（带编号）；数由账本出 */
    private String quotaLine(List<AiTraderExperience> active, AgentLang lang) {
        String sep = prompts.get(lang, "reviewer.label.listSep");
        ExperienceLedger.Quota q = ExperienceLedger.quota(active);
        String fullPlays = q.perPlay().entrySet().stream().filter(e -> e.getValue() >= ExperienceLedger.MAX_PER_PLAY)
                .map(e -> e.getKey().isEmpty() ? prompts.get(lang, "reviewer.op.general") : e.getKey())
                .collect(Collectors.joining(sep));
        String restrictions = q.restrictions().stream().map(id -> "#" + id).collect(Collectors.joining(sep));
        return prompts.get(lang, "reviewer.label.ledgerQuota", Map.of(
                "n", q.active(), "max", ExperienceLedger.MAX_ACTIVE,
                "perPlay", ExperienceLedger.MAX_PER_PLAY,
                "fullPlays", fullPlays.isEmpty() ? "" : prompts.get(lang, "reviewer.label.ledgerQuotaFull", Map.of("plays", fullPlays)),
                "r", q.restrictions().size(), "rMax", ExperienceLedger.MAX_RESTRICTION,
                "rIds", restrictions.isEmpty() ? "" : prompts.get(lang, "reviewer.label.ledgerQuotaIds", Map.of("ids", restrictions))));
    }

    /** 账本行里代码记的账：执行缺口是守几次破几次；其余是名下累计和当前段，引用不符大于 0 才带 */
    private String tallyText(AiTraderExperience e, ExperienceLedger.Tally t, AgentLang lang) {
        if (AiTraderExperience.KIND_GAP.equals(e.getKind())) {
            return prompts.get(lang, "reviewer.label.ledgerTallyGap", Map.of("kept", t.kept(), "broken", t.broken()));
        }
        return prompts.get(lang, "reviewer.label.ledgerTally", Map.of(
                "n", t.under(),
                "wins", t.wins(),
                "losses", t.losses(),
                "net", ExperienceLedger.money(t.net()),
                "days", t.days(),
                "segN", t.segmentUnder(),
                "segNet", ExperienceLedger.money(t.segmentNet()),
                "segDays", t.segmentDays(),
                "miscited", t.miscited() > 0
                        ? prompts.get(lang, "reviewer.label.ledgerTallyMiscited", Map.of("m", t.miscited())) : ""));
    }

    private String slotWord(String slot, AgentLang lang) {
        String key = switch (slot) {
            case AiTraderExperience.SLOT_ENTRY -> "entry";
            case AiTraderExperience.SLOT_FAIL_DEF -> "failDef";
            case AiTraderExperience.SLOT_FAIL_HANDLE -> "failHandle";
            case AiTraderExperience.SLOT_PROFIT_HANDLE -> "profitHandle";
            case AiTraderExperience.SLOT_RISK -> "risk";
            default -> "exec";
        };
        return prompts.get(lang, "reviewer.op.slot." + key);
    }

    // ==================== 小工具 ====================

    static List<FuturesPositionDTO> inWindow(List<FuturesPositionDTO> fetched, long fromMs, long toMs) {
        return fetched.stream()
                .filter(p -> p.getUpdatedAt() != null)
                .filter(p -> {
                    long closedAt = TradePairing.msOf(p.getUpdatedAt());
                    return closedAt > fromMs && closedAt <= toMs;
                })
                .sorted(Comparator.comparing(FuturesPositionDTO::getUpdatedAt))
                .toList();
    }

    static String plain(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }

    static String signed(BigDecimal v) {
        if (v == null) {
            return "?";
        }
        return v.signum() >= 0 ? "+" + v.toPlainString() : v.toPlainString();
    }

    static String nullSafe(String s) {
        return s == null ? "—" : s;
    }

    private static String or(BigDecimal v, String none) {
        return v == null ? none : plain(v);
    }

    /** 数字按原样十进制给（0.0001 不写成 1.0E-4） */
    private static String text(JsonNode ctx, String field, String none) {
        JsonNode v = ctx.get(field);
        if (v == null || v.isNull()) {
            return none;
        }
        return v.isNumber() ? v.decimalValue().stripTrailingZeros().toPlainString() : v.asString();
    }

    static String humanize(PromptCatalog prompts, long ms, AgentLang lang) {
        long min = Math.max(0, ms / 60_000);
        if (min < 120) {
            return prompts.get(lang, "reviewer.label.duration.minutes", Map.of("n", min));
        }
        long hours = min / 60;
        return hours < 48
                ? prompts.get(lang, "reviewer.label.duration.hours", Map.of("n", hours))
                : prompts.get(lang, "reviewer.label.duration.days", Map.of("n", hours / 24));
    }
}
