package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibagent.trader.wakeup.WakeWindow;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.entity.AiTraderExperienceVerdict;
import com.mawai.wiibcommon.entity.AiTraderPlan;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 复盘素材组装（纯代码）：战绩表、本局论点战绩、案例素材（每笔事实表按单子类型分组）、本期行情、经验账本。
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
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(WakeWindow.ZONE);
    private static final DateTimeFormatter HM_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(WakeWindow.ZONE);

    private final AiTraderDecisionMapper decisionMapper;
    private final AiTraderPlanMapper planMapper;
    private final SimTradeClient simTradeClient;
    private final PromptCatalog prompts;
    private final CaseFileBuilder caseFiles;
    private final ExperienceLedger ledger;
    private final PlayStatsAssembler playStats;

    /** 五块素材文本 + 已了结笔数（调用方日志用）+ 本期仓位 id 按单子类型分组（未标类型键为空串），结论流水记 position_ids 用 */
    public record ReviewMaterial(String statsBlock, String playStatsBlock, String casesBlock,
                                 String marketBlock, String ledgerBlock, int closedTrades,
                                 Map<String, List<Long>> positionIdsByPlay) {
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
                .in(AiTraderDecision::getKind, AiTraderDecision.KIND_TRADE,
                        AiTraderDecision.KIND_ALERT, AiTraderDecision.KIND_MANUAL)
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
        Map<String, List<Long>> idsByPlay = new LinkedHashMap<>();
        for (CaseFileBuilder.TradeCase c : cases) {
            if (c.pos().getId() != null) {
                idsByPlay.computeIfAbsent(Objects.toString(c.playType(), ""), k -> new ArrayList<>()).add(c.pos().getId());
            }
        }
        String stats = playStats.assemble(trader, lang);
        return new ReviewMaterial(
                statsBlock(trader, fromMs, toMs, visible, maybeTruncated, lang),
                stats == null ? "" : stats,
                casesBlock(CaseFileBuilder.groups(cases), lang),
                marketBlock(trader, fromMs, toMs, lang),
                ledgerBlock(trader, lang),
                visible.size(),
                idsByPlay);
    }

    private static boolean isStale(AiTraderPlan p) {
        return p != null && Boolean.TRUE.equals(p.getStale());
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

    private String casesBlock(List<CaseFileBuilder.SetupGroup> groups, AgentLang lang) {
        StringBuilder sb = new StringBuilder(prompts.get(lang, "reviewer.label.casesHeader")).append('\n');
        if (groups.isEmpty()) {
            return sb.append(prompts.get(lang, "reviewer.label.casesNone")).append('\n').toString();
        }
        List<CaseFileBuilder.SetupGroup> selected = CaseFileBuilder.select(groups);
        for (CaseFileBuilder.SetupGroup g : groups) {
            boolean detailed = selected.contains(g);
            sb.append(prompts.get(lang, detailed ? "reviewer.label.caseGroup" : "reviewer.label.caseGroupOther", Map.of(
                    "play", g.playType() == null ? prompts.get(lang, "reviewer.label.caseGeneral") : g.playType(),
                    "n", g.trades().size(), "wins", g.wins(), "net", signed(g.net().setScale(0, RoundingMode.HALF_UP))))).append('\n');
            if (!detailed) {
                continue;
            }
            for (CaseFileBuilder.TradeCase c : g.trades()) {
                sb.append(caseLines(c, lang));
            }
        }
        return sb.toString();
    }

    private String caseLines(CaseFileBuilder.TradeCase c, AgentLang lang) {
        AiTraderPlan plan = c.plan();
        CaseFileBuilder.Path p = c.path();
        String none = prompts.get(lang, "reviewer.label.none");
        StringBuilder sb = new StringBuilder(tradeLine(c.pos(), plan, lang)).append('\n');
        sb.append(prompts.get(lang, "reviewer.label.casePath", Map.ofEntries(
                Map.entry("mfe", or(p.mfePrice(), none)),
                Map.entry("mfeAt", p.mfeAt() == null ? none : HM_FMT.format(Instant.ofEpochMilli(p.mfeAt()))),
                Map.entry("mfeFrac", pct(p.mfeFrac(), none)),
                Map.entry("mae", or(p.maePrice(), none)),
                Map.entry("maeFrac", pct(p.maeFrac(), none)),
                Map.entry("t25", p.minTo25() == null ? none : String.valueOf(p.minTo25())),
                Map.entry("t50", p.minTo50() == null ? none : String.valueOf(p.minTo50())),
                Map.entry("firstBack", p.firstBackAt() == null ? none : HM_FMT.format(Instant.ofEpochMilli(p.firstBackAt()))),
                Map.entry("best4", or(p.bestAfter4h(), none)),
                Map.entry("best24", or(p.bestAfter24h(), none)),
                Map.entry("targetAfter", prompts.get(lang, p.targetAfter24h() == null ? "reviewer.label.caseAfterShort"
                        : p.targetAfter24h() ? "reviewer.label.caseTargetHit" : "reviewer.label.caseTargetMiss"))))).append('\n');
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
            sb.append(ctxLine(c.entryCtx(), lang)).append('\n');
        }
        return sb.toString();
    }

    /** 一笔的交易行：币 / 方向 / 开平时刻 / 持有 / 入场 止损 目标 出场 / 了结方式 / 净额 / 资金费 */
    private String tradeLine(FuturesPositionDTO pos, AiTraderPlan plan, AgentLang lang) {
        String none = prompts.get(lang, "reviewer.label.none");
        return prompts.get(lang, "reviewer.label.caseTrade", Map.ofEntries(
                Map.entry("symbol", pos.getSymbol()),
                Map.entry("side", pos.getSide()),
                Map.entry("opened", TIME_FMT.format(Instant.ofEpochMilli(TradePairing.msOf(pos.getCreatedAt())))),
                Map.entry("closed", TIME_FMT.format(Instant.ofEpochMilli(TradePairing.msOf(pos.getUpdatedAt())))),
                Map.entry("held", humanize(prompts, TradePairing.msOf(pos.getUpdatedAt()) - TradePairing.msOf(pos.getCreatedAt()), lang)),
                Map.entry("entry", plain(pos.getEntryPrice())),
                Map.entry("stop", plan == null ? none : plain(plan.getStopLossPrice())),
                Map.entry("target", plan == null ? none : plain(plan.getTakeProfitPrice())),
                Map.entry("exit", plain(pos.getClosedPrice())),
                Map.entry("manner", TradePairing.closeManner(prompts, pos, lang)),
                Map.entry("pnl", signed(pos.getClosedPnl())),
                Map.entry("funding", pos.getFundingFeeTotal() == null ? none : signed(pos.getFundingFeeTotal()))));
    }

    /** 入场上下文一行 */
    private String ctxLine(JsonNode ctx, AgentLang lang) {
        String none = prompts.get(lang, "reviewer.label.none");
        return prompts.get(lang, "reviewer.label.caseCtx", Map.ofEntries(
                Map.entry("regime", text(ctx, "regime", none)),
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
                    "low", plain(w.low()), "lowAt", TIME_FMT.format(Instant.ofEpochMilli(w.lowAt())),
                    "high", plain(w.high()), "highAt", TIME_FMT.format(Instant.ofEpochMilli(w.highAt())),
                    "range", w.lowToHighPct()))).append('\n');
        }
        return sb.toString();
    }

    // ==================== 经验账本 ====================

    private String ledgerBlock(AiTrader t, AgentLang lang) {
        List<AiTraderExperience> active = ledger.active(t.getId(), t.getRoundNo());
        List<AiTraderExperience> entries = active.stream()
                .filter(e -> !AiTraderExperience.KIND_FINDING.equals(e.getKind())).toList();
        StringBuilder sb = new StringBuilder(prompts.get(lang, "reviewer.label.ledgerHeader",
                Map.of("n", entries.size()))).append('\n');
        if (entries.isEmpty()) {
            sb.append(prompts.get(lang, "reviewer.label.ledgerEmpty")).append('\n');
        }
        for (AiTraderExperience e : entries) {
            sb.append(prompts.get(lang, "reviewer.label.ledgerRow", Map.ofEntries(
                    Map.entry("id", e.getId()),
                    Map.entry("kind", prompts.get(lang, "trader.label.ledgerKind." + e.getKind().toLowerCase(Locale.ROOT))),
                    Map.entry("slot", slotWord(e.getSlot(), lang)),
                    Map.entry("dir", prompts.get(lang, "reviewer.op.dir." + e.getDirection().toLowerCase(Locale.ROOT))),
                    Map.entry("play", e.getPlayType() == null ? prompts.get(lang, "reviewer.op.general") : e.getPlayType()),
                    Map.entry("text", e.getText()),
                    Map.entry("supports", e.getSupports()),
                    Map.entry("opposes", e.getOpposes()),
                    Map.entry("untested", e.getUntested()),
                    Map.entry("evidence", nullSafe(e.getEvidence())),
                    Map.entry("flagged", Boolean.TRUE.equals(e.getFlagged())
                            ? prompts.get(lang, "reviewer.label.ledgerFlagged") : "")))).append('\n');
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

    /** 复审一条经验的素材：条目全文 + 结论流水 + 支持率 + 历史最长连败 + 支持 / 反对两组单子的对照表；positions 是调用方拉好的已平仓位按 id */
    public String auditBlock(AiTrader t, AiTraderExperience e, AgentLang lang, Map<Long, FuturesPositionDTO> positions) {
        List<AiTraderExperienceVerdict> log = ledger.verdicts(e.getId());
        int checks = e.getSupports() + e.getOpposes();
        StringBuilder sb = new StringBuilder(prompts.get(lang, "reviewer.audit.entry", Map.ofEntries(
                Map.entry("id", e.getId()),
                Map.entry("play", e.getPlayType() == null ? prompts.get(lang, "reviewer.op.general") : e.getPlayType()),
                Map.entry("slot", slotWord(e.getSlot(), lang)),
                Map.entry("dir", prompts.get(lang, "reviewer.op.dir." + e.getDirection().toLowerCase(Locale.ROOT))),
                Map.entry("text", e.getText()),
                Map.entry("supports", e.getSupports()),
                Map.entry("opposes", e.getOpposes()),
                Map.entry("untested", e.getUntested()),
                Map.entry("rate", checks == 0 ? 0 : e.getSupports() * 100 / checks),
                Map.entry("streak", ExperienceLedger.maxOpposeStreak(log))))).append('\n');
        if (log.isEmpty()) {
            return sb.append(prompts.get(lang, "reviewer.audit.noVerdicts")).append('\n').toString();
        }
        Set<Long> support = new LinkedHashSet<>();
        Set<Long> oppose = new LinkedHashSet<>();
        for (AiTraderExperienceVerdict v : log) {
            sb.append(prompts.get(lang, "reviewer.audit.verdictRow", Map.of(
                    "verdict", verdictWord(v.getVerdict(), lang),
                    "note", nullSafe(v.getNote())))).append('\n');
            if (AiTraderExperienceVerdict.SUPPORT.equals(v.getVerdict())) {
                support.addAll(positionIds(v));
            } else if (AiTraderExperienceVerdict.OPPOSE.equals(v.getVerdict())) {
                oppose.addAll(positionIds(v));
            }
        }
        Set<Long> all = new LinkedHashSet<>(support);
        all.addAll(oppose);
        Map<Long, AiTraderPlan> plans = new HashMap<>();
        if (!all.isEmpty()) {
            for (AiTraderPlan p : planMapper.selectList(new LambdaQueryWrapper<AiTraderPlan>()
                    .eq(AiTraderPlan::getTraderId, t.getId())
                    .in(AiTraderPlan::getPositionId, all))) {
                plans.put(p.getPositionId(), p);
            }
        }
        appendGroup(sb, "reviewer.audit.supportGroup", support, positions, plans, lang);
        appendGroup(sb, "reviewer.audit.opposeGroup", oppose, positions, plans, lang);
        return sb.toString();
    }

    /** 一组单子：段头带笔数，每笔交易行 + 入场上下文行；sim 最近记录里没有的只列编号 */
    private void appendGroup(StringBuilder sb, String headerKey, Set<Long> ids, Map<Long, FuturesPositionDTO> positions,
                             Map<Long, AiTraderPlan> plans, AgentLang lang) {
        sb.append(prompts.get(lang, headerKey, Map.of("n", ids.size()))).append('\n');
        for (Long id : ids) {
            FuturesPositionDTO pos = positions.get(id);
            if (pos == null) {
                sb.append(prompts.get(lang, "reviewer.audit.missingPosition", Map.of("id", id))).append('\n');
                continue;
            }
            AiTraderPlan plan = plans.get(id);
            sb.append(tradeLine(pos, plan, lang)).append('\n');
            JsonNode ctx = CaseFileBuilder.entryCtx(plan);
            if (ctx != null) {
                sb.append(ctxLine(ctx, lang)).append('\n');
            }
        }
    }

    /** 流水里的 position_ids："42,43" → [42, 43] */
    private static List<Long> positionIds(AiTraderExperienceVerdict v) {
        if (v.getPositionIds() == null) {
            return List.of();
        }
        return Arrays.stream(v.getPositionIds().split(",")).map(String::strip).map(Long::valueOf).toList();
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

    private String verdictWord(String verdict, AgentLang lang) {
        return prompts.get(lang, "reviewer.op." + switch (verdict) {
            case AiTraderExperienceVerdict.SUPPORT -> "support";
            case AiTraderExperienceVerdict.OPPOSE -> "oppose";
            default -> "untested";
        });
    }

    // ==================== 同侪详情共用 ====================

    /** 一笔已了结交易的行尾（入场→出场及开平时刻/盈亏/持有/了结方式）；同侪详情共用 */
    static String tradeRow(PromptCatalog prompts, FuturesPositionDTO pos, AgentLang lang) {
        return prompts.get(lang, "reviewer.label.tradeRow", Map.of(
                "entry", plain(pos.getEntryPrice()),
                "openedAt", TIME_FMT.format(Instant.ofEpochMilli(TradePairing.msOf(pos.getCreatedAt()))),
                "exit", plain(pos.getClosedPrice()),
                "closedAt", TIME_FMT.format(Instant.ofEpochMilli(TradePairing.msOf(pos.getUpdatedAt()))),
                "pnl", signed(pos.getClosedPnl()),
                "held", humanize(prompts, TradePairing.msOf(pos.getUpdatedAt())
                        - TradePairing.msOf(pos.getCreatedAt()), lang),
                "manner", TradePairing.closeManner(prompts, pos, lang)));
    }

    /** 论点/失效条件那一行，同样对同侪详情开放 */
    static String planLine(PromptCatalog prompts, String signalsUsed, String invalidationCondition,
                           AgentLang lang) {
        return prompts.get(lang, "reviewer.label.planLine", Map.of(
                "signals", nullSafe(signalsUsed),
                "invalidation", nullSafe(invalidationCondition)));
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

    /** 0.56 → 56% */
    private static String pct(BigDecimal frac, String none) {
        return frac == null ? none : frac.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).toPlainString() + "%";
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
