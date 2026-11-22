package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
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
import com.mawai.wiibquant.external.sim.SimTradeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.mawai.wiibagent.learning.CaseFileRenderer.nullSafe;
import static com.mawai.wiibagent.learning.CaseFileRenderer.plain;
import static com.mawai.wiibagent.learning.CaseFileRenderer.signed;
import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 统一复盘的素材组装（纯代码）：战绩表、本局论点战绩、本批单子（每笔事实表加它的单笔复盘正文，按单子类型分组）、
 * 本期行情、盯盘布防、经验账本。事实裁定归代码、模型只解读。
 * <p>
 * 本批单子不按时间切：收的是有成功的单笔复盘、还没进过统一复盘、主人没标不算数的单子。
 * 行情、盯盘布防、撤掉的限价档、战绩表的权益看窗口 (fromMs, toMs]，窗口由 {@link ReviewRunner} 定，右端在 24 小时之前。
 * 各笔单笔复盘的归类行在这里合并成每条经验一行，随素材交出去落账。
 * <p>
 * 段标签全在 {@link PromptCatalog} 的 {@code reviewer.label.*}；路径指标在 {@link CaseFileBuilder}，
 * 每笔事实表的文本在 {@link CaseFileRenderer}，账本在 {@link ExperienceLedger}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewMaterialAssembler {

    /** 初始资金，与 TraderService.INITIAL_BALANCE 同一口径 */
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000");
    /** 已平仓位拉取上限 */
    static final int CLOSED_FETCH_LIMIT = 200;
    /** 一篇统一复盘最多收几笔，取平仓最早的；收不下的留给下一篇 */
    static final int MAX_BATCH = 20;
    /** 行情块最多回看多久（本局首篇复盘 fromMs=0） */
    static final long MARKET_LOOKBACK_MS = 48 * 3_600_000L;
    /** 盯盘命中那轮的该币结论段最多带多少字 */
    static final int MAX_SECTION = 240;

    private final AiTraderDecisionMapper decisionMapper;
    private final AiTraderPlanMapper planMapper;
    private final SimTradeClient simTradeClient;
    private final PromptCatalog prompts;
    private final CaseFileBuilder caseFiles;
    private final CaseFileRenderer caseRenderer;
    private final ExperienceLedger ledger;
    private final PlayStatsAssembler playStats;
    private final TraderWatchStore watchStore;
    private final DecisionText decisionText;
    private final ExperienceOpParser parser;

    /**
     * 六块素材文本，加落账要用的三样：本批单子（仓位编号 → 净盈亏和平仓日，新增行里的仓位编号只认这些）、
     * 各笔单笔复盘合并出来的归类行、本批单子的计划行 id（落账后盖上这篇复盘的编号）
     */
    public record ReviewMaterial(String statsBlock, String playStatsBlock, String casesBlock,
                                 String marketBlock, String watchBlock, String ledgerBlock,
                                 Map<Long, ExperienceLedger.PeriodTrade> trades,
                                 List<ExperienceLedger.Classify> classifies, List<Long> planIds) {
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

    /**
     * 窗口内有过开仓动作（被拒的尝试也算），或者有做完单笔复盘、还没进过统一复盘的单子，才有素材。
     * 单子要在 sim 的已平仓位里还找得到才算，和 {@link #assemble} 收单子是同一个口径
     */
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
        Map<Long, AiTraderDecision> debriefs = debriefs(trader, unreviewed(plans(trader)).keySet());
        return !debriefs.isEmpty()
                && simTradeClient.getClosedPositions(trader.getSimUserId(), CLOSED_FETCH_LIMIT).stream()
                .anyMatch(p -> debriefs.containsKey(p.getId()));
    }

    public ReviewMaterial assemble(AiTrader trader, long fromMs, long toMs, AgentLang lang) {
        List<FuturesPositionDTO> fetched = simTradeClient.getClosedPositions(trader.getSimUserId(), CLOSED_FETCH_LIMIT);
        List<AiTraderPlan> plans = plans(trader);
        Map<Long, AiTraderPlan> planByPosition = unreviewed(plans);
        Map<Long, AiTraderDecision> debriefs = debriefs(trader, planByPosition.keySet());
        // 本批单子，按平仓先后，最多 MAX_BATCH 笔
        List<CaseFileBuilder.TradeCase> cases = fetched.stream()
                .filter(p -> debriefs.containsKey(p.getId()))
                .sorted(Comparator.comparing(FuturesPositionDTO::getUpdatedAt))
                .limit(MAX_BATCH)
                .map(p -> caseFiles.build(p, planByPosition.get(p.getId()))).toList();
        List<AiTraderExperience> active = ledger.active(trader.getId(), trader.getRoundNo());
        Map<Long, String> bodies = new HashMap<>();
        Map<Long, List<ExperienceLedger.Classify>> lines = new LinkedHashMap<>();
        for (CaseFileBuilder.TradeCase c : cases) {
            String text = debriefs.get(c.pos().getId()).getReasoning();
            bodies.put(c.pos().getId(), parser.debriefBody(text));
            // 没有净盈亏的记不了账，它的归类不并进去
            if (c.net() != null) {
                lines.put(c.pos().getId(), parser.debriefClassifies(text));
            }
        }
        List<ExperienceLedger.Classify> classifies = ExperienceLedger.mergeDebriefs(lines, active);
        String stats = playStats.assemble(trader, lang);
        return new ReviewMaterial(
                statsBlock(trader, fromMs, toMs, cases, lang),
                stats == null ? "" : stats,
                casesBlock(cases, bodies, cancelledLimits(trader, plans, fetched, fromMs, toMs), lang),
                marketBlock(trader, fromMs, toMs, lang),
                watchBlock(trader, fromMs, toMs, lang),
                ledgerBlock(trader, active, classifies, cases, lang),
                periodTrades(cases),
                classifies,
                cases.stream().map(c -> c.plan().getId()).toList());
    }

    private List<AiTraderPlan> plans(AiTrader trader) {
        return planMapper.selectList(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, trader.getId())
                .eq(AiTraderPlan::getRoundNo, trader.getRoundNo()));
    }

    /** 还能进统一复盘的计划行：绑了仓位、已归档、主人没标不算数、还没进过统一复盘。键是仓位编号 */
    private static Map<Long, AiTraderPlan> unreviewed(List<AiTraderPlan> plans) {
        Map<Long, AiTraderPlan> out = new LinkedHashMap<>();
        for (AiTraderPlan p : plans) {
            if (AiTraderPlan.STATUS_CLOSED.equals(p.getStatus()) && p.getPositionId() != null
                    && p.getReviewId() == null && !Boolean.TRUE.equals(p.getStale())) {
                out.putIfAbsent(p.getPositionId(), p);
            }
        }
        return out;
    }

    /** 这些仓位各自成功的单笔复盘（有几篇取最新的），键是仓位编号；没复盘成功过的不在里面 */
    private Map<Long, AiTraderDecision> debriefs(AiTrader trader, Collection<Long> positionIds) {
        Map<Long, AiTraderDecision> out = new HashMap<>();
        if (positionIds.isEmpty()) {
            return out;
        }
        for (AiTraderDecision d : decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .select(AiTraderDecision::getId, AiTraderDecision::getPositionId, AiTraderDecision::getReasoning)
                .eq(AiTraderDecision::getTraderId, trader.getId())
                .eq(AiTraderDecision::getRoundNo, trader.getRoundNo())
                .eq(AiTraderDecision::getKind, AiTraderDecision.KIND_DEBRIEF)
                .eq(AiTraderDecision::getStatus, AiTraderDecision.STATUS_OK)
                .in(AiTraderDecision::getPositionId, positionIds)
                .orderByAsc(AiTraderDecision::getId))) {
            out.put(d.getPositionId(), d);
        }
        return out;
    }

    /** 本批单子按仓位编号：净盈亏取事实表那个数，平仓日是北京日期；没有净盈亏的记不了账，不收 */
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

    // ==================== 战绩表 ====================

    /** 权益和回撤看窗口，笔数胜负合计数的是本批单子 */
    private String statsBlock(AiTrader t, long fromMs, long toMs, List<CaseFileBuilder.TradeCase> cases, AgentLang lang) {
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
        long wins = cases.stream().filter(CaseFileBuilder.TradeCase::win).count();
        BigDecimal pnlSum = cases.stream().map(CaseFileBuilder.TradeCase::net)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);

        StringBuilder sb = new StringBuilder(prompts.get(lang, "reviewer.label.statsHeader")).append('\n');
        sb.append(prompts.get(lang, "reviewer.label.statsEquity", Map.of(
                "start", start.setScale(2, RoundingMode.HALF_UP),
                "end", end.setScale(2, RoundingMode.HALF_UP),
                "pct", signed(returnPct)))).append('\n');
        sb.append(prompts.get(lang, "reviewer.label.statsDrawdown", Map.of("dd", maxDd))).append('\n');
        if (cases.isEmpty()) {
            sb.append(prompts.get(lang, "reviewer.label.noClosed")).append('\n');
        } else {
            sb.append(prompts.get(lang, "reviewer.label.statsClosed", Map.of(
                    "n", cases.size(), "wins", wins, "losses", cases.size() - wins,
                    "winRate", wins * 100 / cases.size(),
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

    // ==================== 本批单子 ====================

    /** 按单子类型分组，每笔是事实表加它的单笔复盘正文；末尾是本批重放汇总和窗口里没成交就撤掉的限价档 */
    private String casesBlock(List<CaseFileBuilder.TradeCase> cases, Map<Long, String> debriefBodies,
                              List<CaseFileBuilder.CancelledLimit> cancelled, AgentLang lang) {
        StringBuilder sb = new StringBuilder(prompts.get(lang, "reviewer.label.casesHeader",
                Map.of("guide", caseRenderer.guide(lang)))).append('\n');
        if (cases.isEmpty()) {
            sb.append(prompts.get(lang, "reviewer.label.casesNone")).append('\n');
        }
        for (CaseFileBuilder.SetupGroup g : CaseFileBuilder.groups(cases)) {
            sb.append(prompts.get(lang, "reviewer.label.caseGroup", Map.of(
                    "play", g.playType() == null ? prompts.get(lang, "reviewer.label.caseGeneral") : g.playType(),
                    "n", g.trades().size(), "wins", g.wins(), "net", signed(g.net().setScale(0, RoundingMode.HALF_UP))))).append('\n');
            for (CaseFileBuilder.TradeCase c : g.trades()) {
                sb.append(caseRenderer.render(c, lang));
                sb.append(prompts.get(lang, "reviewer.label.caseDebrief")).append('\n');
                debriefBodies.get(c.pos().getId()).lines().forEach(line -> sb.append("    ").append(line).append('\n'));
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
                        "target", caseRenderer.targets(c.targets(), lang),
                        "closed", BeijingTime.shortFormat(c.cancelledAt()),
                        "reason", c.entry() == null ? prompts.get(lang, "trader.cancelReason.unknown") : nullSafe(c.entry().getReason()),
                        "after", cancelledAfter(c, lang)))).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 窗口里没成交就撤掉的限价档：有逐档记录的看撤单时刻；没有的旧计划是没绑过仓位就归档的那种，看归档时刻。
     * 主人标了不算数的计划不列
     */
    private List<CaseFileBuilder.CancelledLimit> cancelledLimits(AiTrader trader, List<AiTraderPlan> plans,
                                                                 List<FuturesPositionDTO> fetched, long fromMs, long toMs) {
        // 没有仓位编号的旧计划也可能成交过：按时间就近配上了已平仓位的不算撤单
        Map<FuturesPositionDTO, AiTraderPlan> paired = TradePairing.pairAll(fetched, plans);
        List<CaseFileBuilder.CancelledLimit> cancelled = new ArrayList<>();
        for (AiTraderPlan plan : plans) {
            if (Boolean.TRUE.equals(plan.getStale())) {
                continue;
            }
            List<PlanEntry> entries = PlanEntry.read(plan);
            if (!entries.isEmpty()) {
                entries.stream().filter(e -> "CANCELLED".equals(e.getStatus()) && e.getLimitPrice() != null
                                && e.getClosedAt() > fromMs && e.getClosedAt() <= toMs)
                        .map(e -> caseFiles.cancelledLimit(plan, e)).filter(Objects::nonNull).forEach(cancelled::add);
            } else if (AiTraderPlan.STATUS_CLOSED.equals(plan.getStatus()) && plan.getPositionId() == null
                    && !paired.containsValue(plan) && plan.getClosedWakeTime() > fromMs && plan.getClosedWakeTime() <= toMs) {
                CaseFileBuilder.CancelledLimit item = caseFiles.cancelledLimit(plan, lastSeenPending(trader, plan));
                if (item != null) {
                    cancelled.add(item);
                }
            }
        }
        return cancelled;
    }

    /** 撤掉的限价档之后怎么走的那半句：没穿过限价位的只说到过几档；穿过的带上那一根的时刻和走到哪了结 */
    private String cancelledAfter(CaseFileBuilder.CancelledLimit c, AgentLang lang) {
        String targets = caseRenderer.targetsAfter(c.targets(), c.reachedTargets(), lang);
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
        String section = decisionText.symbolSection(reasoning, symbol);
        if (section == null) {
            return none;
        }
        return section.length() > MAX_SECTION ? section.substring(0, MAX_SECTION) + "…" : section;
    }

    // ==================== 经验账本 ====================

    private String ledgerBlock(AiTrader t, List<AiTraderExperience> active, List<ExperienceLedger.Classify> classifies,
                               List<CaseFileBuilder.TradeCase> cases, AgentLang lang) {
        List<AiTraderExperience> entries = active.stream()
                .filter(e -> !AiTraderExperience.KIND_FINDING.equals(e.getKind())).toList();
        StringBuilder sb = new StringBuilder(prompts.get(lang, "reviewer.label.ledgerHeader",
                Map.of("n", entries.size()))).append('\n');
        sb.append(quotaLine(active, lang)).append('\n');
        if (entries.isEmpty()) {
            sb.append(prompts.get(lang, "reviewer.label.ledgerEmpty")).append('\n');
        }
        Map<Long, List<ExperienceLedger.Mark>> sorted = classifies.stream()
                .collect(Collectors.toMap(ExperienceLedger.Classify::id, ExperienceLedger.Classify::marks));
        Map<Long, CaseFileBuilder.TradeCase> caseByPosition = cases.stream()
                .collect(Collectors.toMap(c -> c.pos().getId(), c -> c));
        Map<Long, ExperienceLedger.Tally> tallies = ledger.tallies(entries);
        for (AiTraderExperience e : entries) {
            sb.append(prompts.get(lang, "reviewer.label.ledgerRow", Map.of(
                    "id", e.getId(),
                    "kind", prompts.get(lang, "trader.label.ledgerKind." + e.getKind().toLowerCase(Locale.ROOT)),
                    "slot", prompts.get(lang, "reviewer.op.slot." + ExperienceLedger.slotKey(e.getSlot())),
                    "dir", prompts.get(lang, "reviewer.op.dir." + e.getDirection().toLowerCase(Locale.ROOT)),
                    "play", e.getPlayType() == null ? prompts.get(lang, "reviewer.op.general") : e.getPlayType(),
                    "text", e.getText(),
                    "tally", tallyText(e, tallies.get(e.getId()), lang),
                    "evidence", nullSafe(e.getEvidence())))).append('\n');
            List<ExperienceLedger.Mark> marks = sorted.get(e.getId());
            if (marks != null) {
                sb.append(sortedLine(marks, caseByPosition, lang)).append('\n');
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

    /** 条目行下面那一行：本批单笔复盘归到它这里的单子，按标记分开（名下、引用不符、守、破） */
    private String sortedLine(List<ExperienceLedger.Mark> marks, Map<Long, CaseFileBuilder.TradeCase> caseByPosition,
                              AgentLang lang) {
        String sep = prompts.get(lang, "reviewer.label.listSep");
        Map<String, List<String>> byMark = new LinkedHashMap<>();
        for (ExperienceLedger.Mark m : marks) {
            CaseFileBuilder.TradeCase c = caseByPosition.get(m.positionId());
            byMark.computeIfAbsent(m.mark(), k -> new ArrayList<>()).add(prompts.get(lang, "reviewer.label.ledgerTrade", Map.of(
                    "id", c.pos().getId(),
                    "symbol", c.pos().getSymbol(), "side", c.pos().getSide(),
                    "play", c.playType() == null ? prompts.get(lang, "reviewer.label.caseGeneral") : c.playType(),
                    "opened", BeijingTime.shortFormat(TradePairing.msOf(c.pos().getCreatedAt())),
                    "net", signed(c.net()))));
        }
        return prompts.get(lang, "reviewer.label.ledgerSorted", Map.of("marks", byMark.entrySet().stream()
                .map(e -> prompts.get(lang, "reviewer.op." + e.getKey().toLowerCase(Locale.ROOT)) + " " + String.join(sep, e.getValue()))
                .collect(Collectors.joining(prompts.get(lang, "reviewer.label.ledgerSortedSep")))));
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
}
