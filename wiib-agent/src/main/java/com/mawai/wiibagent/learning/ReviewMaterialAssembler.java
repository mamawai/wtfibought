package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibagent.trader.trade.TradeTools;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibagent.trader.wakeup.WakeWindow;
import com.mawai.wiibagent.trader.wakeup.WatchText;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.entity.AiTraderExperienceVerdict;
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
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(WakeWindow.ZONE);
    private static final DateTimeFormatter HM_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(WakeWindow.ZONE);
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

    /** 六块素材文本 + 已了结笔数（调用方日志用）+ 本期仓位 id 按单子类型分组（未标类型键为空串），结论流水记 position_ids 用 */
    public record ReviewMaterial(String statsBlock, String playStatsBlock, String casesBlock,
                                 String marketBlock, String watchBlock, String ledgerBlock, int closedTrades,
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
        Map<String, List<Long>> idsByPlay = new LinkedHashMap<>();
        for (CaseFileBuilder.TradeCase c : cases) {
            if (c.pos().getId() != null) {
                idsByPlay.computeIfAbsent(Objects.toString(c.playType(), ""), k -> new ArrayList<>()).add(c.pos().getId());
            }
        }
        // 没绑过仓位就归档的计划 = 没成交就撤掉的限价单（没存仓位 id 的旧计划靠时间配上了仓位，不算）
        List<CaseFileBuilder.CancelledLimit> cancelled = plans.stream()
                .filter(p -> AiTraderPlan.STATUS_CLOSED.equals(p.getStatus()) && p.getPositionId() == null && !isStale(p))
                .filter(p -> !planByPos.containsValue(p))
                .filter(p -> p.getClosedWakeTime() > fromMs && p.getClosedWakeTime() <= toMs)
                .map(caseFiles::cancelledLimit).filter(Objects::nonNull).toList();
        String stats = playStats.assemble(trader, lang);
        return new ReviewMaterial(
                statsBlock(trader, fromMs, toMs, visible, maybeTruncated, lang),
                stats == null ? "" : stats,
                casesBlock(cases, cancelled, lang),
                marketBlock(trader, fromMs, toMs, lang),
                watchBlock(trader, fromMs, toMs, lang),
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
            if (!detailed) {
                continue;
            }
            for (CaseFileBuilder.TradeCase c : g.trades()) {
                sb.append(caseLines(c, lang));
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
                        "limit", plain(plan.getEntryPrice()),
                        "stop", plain(plan.getStopLossPrice()),
                        "target", plain(plan.getTakeProfitPrice()),
                        "closed", TIME_FMT.format(Instant.ofEpochMilli(plan.getClosedWakeTime())),
                        "touched", prompts.get(lang, c.touchedLimit() ? "reviewer.label.reached" : "reviewer.label.notReached"),
                        "reached", prompts.get(lang, c.reachedTarget() ? "reviewer.label.reached" : "reviewer.label.notReached")))).append('\n');
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

    /** 一笔的重放行：实际几 R、开仓后 24 小时的顺向逆向与拿满的位置、每档止损宽度的结局 */
    private String replayLine(CaseFileBuilder.Replay r, AgentLang lang) {
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
                "fav", signedR(r.fav24R()),
                "adv", signedR(r.adv24R()),
                "hold", r.hold24R() == null ? prompts.get(lang, "reviewer.label.replayHoldShort") : signedR(r.hold24R()),
                "widths", String.join(prompts.get(lang, "reviewer.label.replaySep"), widths)));
    }

    private static String signedR(BigDecimal r) {
        return signed(r) + "R";
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

    /** 入场上下文一行；限价单记了下单时刻的，带上挂了多久才成交 */
    private String ctxLine(JsonNode ctx, FuturesPositionDTO pos, AgentLang lang) {
        String none = prompts.get(lang, "reviewer.label.none");
        String pending = "LIMIT".equals(ctx.path("orderType").asString(null)) && ctx.has("placedAt")
                ? prompts.get(lang, "reviewer.label.casePending", Map.of("held", humanize(prompts,
                        TradePairing.msOf(pos.getCreatedAt()) - ctx.path("placedAt").asLong(), lang)))
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
                    "low", plain(w.low()), "lowAt", TIME_FMT.format(Instant.ofEpochMilli(w.lowAt())),
                    "high", plain(w.high()), "highAt", TIME_FMT.format(Instant.ofEpochMilli(w.highAt())),
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
                    "firedAt", TIME_FMT.format(Instant.ofEpochMilli(w.getFiredAt())),
                    "detail", WatchText.detail(w, w.getFiredPrice(), prompts, lang),
                    "note", w.getNote(),
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
                "wokeAt", TIME_FMT.format(Instant.ofEpochMilli(w.getWokeAt())),
                "kind", d == null ? none : d.getKind(),
                "actions", d == null ? none : tradeActions(d.getActionsJson(), lang),
                "section", d == null ? none : symbolSection(d.getReasoning(), w.getSymbol(), none)));
    }

    /** 那轮的交易动作：工具名列表；被护栏拒的、sim 报错的、结果未知的各带标，没标的才是做成了 */
    private String tradeActions(String actionsJson, AgentLang lang) {
        List<String> out = new ArrayList<>();
        if (actionsJson != null && !actionsJson.isBlank()) {
            for (JsonNode a : MAPPER.readTree(actionsJson)) {
                String tool = a.path("tool").asString(null);
                if (tool == null || !TradeTools.SERIAL_TOOLS.contains(tool)) {
                    continue;
                }
                String status = a.path("status").asString("");
                String key = a.has("rejected") ? "watchRejected"
                        : "error".equals(status) ? "watchFailed"
                        : "unknown".equals(status) ? "watchUnknown" : null;
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
                sb.append(ctxLine(ctx, pos, lang)).append('\n');
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
