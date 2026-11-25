package com.mawai.wiibagent.learning;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibagent.trader.trade.PlanEntry;
import com.mawai.wiibagent.trader.trade.TargetRung;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.market.KlineBar;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 一笔已了结交易的事实表文本：交易行、入场安排、价格路径、重放、1h 收盘、计划（论点、失效、修订史）、入场上下文。
 * 数由 {@link CaseFileBuilder} 算好，这里只管写成行；行模板在 {@link PromptCatalog} 的 {@code reviewer.label.case*}。
 */
@Component
@RequiredArgsConstructor
public class CaseFileRenderer {

    /** 一笔交易的 1h 收盘最多列几根 */
    static final int MAX_HOURLY = 24;
    /** 时刻按新加坡时间写，与入场上下文的 hourBj 同一时区 */
    private static final DateTimeFormatter HM_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(BeijingTime.ZONE);

    private final PromptCatalog prompts;

    /** 事实表的读法：目标、入场安排、路径行、重放行各是什么意思 */
    public String guide(AgentLang lang) {
        return prompts.get(lang, "reviewer.label.caseGuide");
    }

    /** 一笔的事实表，每行末尾带换行 */
    public String render(CaseFileBuilder.TradeCase c, AgentLang lang) {
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
                Map.entry("after", afterExit(p, c.pos(), plan, lang))))).append('\n');
        if (c.replay() != null) {
            sb.append(replayLine(c.replay(), p.afterMs(), lang)).append('\n');
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

    /** 止盈档的写法；没设止盈写"无" */
    String targets(List<TargetRung> rungs, AgentLang lang) {
        return rungs.isEmpty() ? prompts.get(lang, "reviewer.label.none") : TargetRung.text(rungs);
    }

    /** "到过原目标 1/2 档"；没设止盈写另一句 */
    String targetsAfter(List<TargetRung> targets, Integer reached, AgentLang lang) {
        return targets.isEmpty() ? prompts.get(lang, "reviewer.label.caseNoTarget")
                : prompts.get(lang, "reviewer.label.caseTargetsAfter", Map.of("hit", reached, "total", targets.size()));
    }

    /**
     * 路径行出场后那一段：满 24 小时、只看得到一段、出场后没有 K 线，三种写法。
     * 最有利价后面带它比出场价往有利方向多走的价差，负数是平仓那一根之后的 K 线里再没到过出场价
     */
    private String afterExit(CaseFileBuilder.Path p, FuturesPositionDTO pos, AiTraderPlan plan, AgentLang lang) {
        if (p.afterMs() == 0) {
            return prompts.get(lang, "reviewer.label.caseAfterNone");
        }
        String none = prompts.get(lang, "reviewer.label.none");
        String key = p.afterMs() >= CaseFileBuilder.AFTER_LONG_MS ? "reviewer.label.caseAfterFull" : "reviewer.label.caseAfterPartial";
        return prompts.get(lang, key, Map.of(
                "best4", or(p.bestAfter4h(), none),
                "best", or(p.bestAfter(), none),
                "gap4", beyondExit(p.bestAfter4h(), pos, none),
                "gap", beyondExit(p.bestAfter(), pos, none),
                "targets", targetsAfter(TargetRung.of(plan), p.targetsAfter(), lang),
                "span", humanize(prompts, p.afterMs(), lang)));
    }

    /** 出场后最有利价比出场价往有利方向多走了多少，带正负号；没有最有利价给 none */
    private static String beyondExit(BigDecimal best, FuturesPositionDTO pos, String none) {
        if (best == null) {
            return none;
        }
        BigDecimal gap = "LONG".equals(pos.getSide()) ? best.subtract(pos.getClosedPrice()) : pos.getClosedPrice().subtract(best);
        return signed(gap.stripTrailingZeros());
    }

    /**
     * 一笔的重放行：实际几 R、首档成交后最多 24 小时的顺向逆向（没满 24 小时写实际多久）与拿满 24 小时的位置、每档止损宽度的结局。
     * afterMs 是平仓后的 K 线覆盖了多久（{@link CaseFileBuilder.Path#afterMs}）：没满 24 小时、又有一档还没了结的，写各档只走到了哪
     */
    String replayLine(CaseFileBuilder.Replay r, long afterMs, AgentLang lang) {
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
        String walked = afterMs >= CaseFileBuilder.AFTER_LONG_MS
                || r.widths().stream().noneMatch(w -> w.outcome() == CaseFileBuilder.Outcome.OPEN) ? ""
                : afterMs == 0 ? prompts.get(lang, "reviewer.label.replayWalkedNone")
                : prompts.get(lang, "reviewer.label.replayWalked", Map.of("span", humanize(prompts, afterMs, lang)));
        return prompts.get(lang, "reviewer.label.caseReplay", Map.of(
                "actual", r.actualR() == null ? prompts.get(lang, "reviewer.label.none") : signedR(r.actualR()),
                "span", r.spanMs() < CaseFileBuilder.DIRECTION_MS ? humanize(prompts, r.spanMs(), lang)
                        : prompts.get(lang, "reviewer.label.replaySpanFull"),
                "fav", r.favR() == null ? prompts.get(lang, "reviewer.label.none") : signedR(r.favR()),
                "adv", r.advR() == null ? prompts.get(lang, "reviewer.label.none") : signedR(r.advR()),
                "hold", r.holdR() == null ? prompts.get(lang, "reviewer.label.replayHoldShort") : signedR(r.holdR()),
                "widths", String.join(prompts.get(lang, "reviewer.label.replaySep"), widths),
                "walked", walked));
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
                Map.entry("target", targets(TargetRung.of(plan), lang)),
                Map.entry("exit", plain(pos.getClosedPrice())),
                Map.entry("manner", TradePairing.closeManner(prompts, pos, lang)),
                Map.entry("pnl", signed(pos.getClosedPnl())),
                Map.entry("funding", pos.getFundingFeeTotal() == null ? none : signed(pos.getFundingFeeTotal()))));
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

    /** 时长写成几分钟、几小时、几天：不到 2 小时写分钟，不到 2 天写小时 */
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

    // ==================== 数字写法 ====================

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

    private static String signedR(BigDecimal r) {
        return signed(r) + "R";
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
}
