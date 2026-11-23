package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibagent.trader.TradeRecordService;
import com.mawai.wiibagent.trader.trade.PlanEntry;
import com.mawai.wiibagent.trader.trade.TradeActions;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 单笔复盘的开场素材（纯代码）：这笔单子的事实表、从开仓那轮到平仓后第一个看过这个币的轮每一轮的全部内容、经验账本的现役条目。
 * 各轮只收看整盘的轮、预案行和只处理这个币的轮；几轮可以同时在跑，按实际开始的先后排，动作带上做成的时刻。
 * 各轮的内容全部给，不截断、不设上限：当时的原文和代码数据原样带上，只去掉别的币的结论段。
 * 段标签在 {@link PromptCatalog} 的 {@code debrief.label.*}；事实表和统一复盘用同一份（{@link CaseFileRenderer}）。
 */
@Component
@RequiredArgsConstructor
public class DebriefMaterialAssembler {

    /** 各轮块收的行：五种唤醒，加预案执行 */
    private static final List<String> ROUND_KINDS = Stream.concat(
            AiTraderDecision.WAKE_KINDS.stream(), Stream.of(AiTraderDecision.KIND_PRESET)).toList();
    /** 开仓回执里每一档留下的字段，其余是 sim 订单的内部字段 */
    private static final List<String> ENTRY_FIELDS = List.of(
            "orderId", "orderType", "limitPrice", "quantity", "status", "filledPrice", "reason");
    /** 还挂着的入场档带出来的字段 */
    private static final List<String> PENDING_FIELDS = List.of("orderId", "limitPrice", "quantity", "lossAtStopPctOfEquity");
    /** 账户层面带出来的三个数 */
    private static final List<String> ACCOUNT_FIELDS = List.of(
            "equity", "lossAtAllStopsPctOfEquity", "lossIfPendingFillPctOfEquity");

    private final AiTraderDecisionMapper decisionMapper;
    private final PromptCatalog prompts;
    private final CaseFileBuilder caseFiles;
    private final CaseFileRenderer caseRenderer;
    private final ExperienceLedger ledger;
    private final DecisionText decisionText;
    private final TradeRecordService tradeRecords;

    /** 三块素材文本，加账本块里列了几条现役条目（归类段至少要认出一行，就看它是不是 0） */
    public record DebriefMaterial(String factsBlock, String roundsBlock, String ledgerBlock, int ledgerEntries) {
    }

    /** 一轮里和这笔单子有关的动作：对这笔单子做的（改仓位、挂单、止损止盈），和给这个币布的盯盘 */
    private record Acted(List<String> actions, List<String> watches) {
    }

    /** 一段里写的字；stage 从 1 起，0 是没存过程轨迹、分不出段 */
    private record StageText(int stage, String text) {
    }

    /** 一轮实际跑的时段：开始是落库时刻减去跑了多久，结束是落库时刻；没记耗时的（预案行、老行）两头都是 wake_time */
    record Span(long from, long to) {
        static Span of(AiTraderDecision d) {
            if (d.getLatencyMs() == null) {
                return new Span(d.getWakeTime(), d.getWakeTime());
            }
            long end = TradePairing.msOf(d.getCreatedAt());
            return new Span(end - d.getLatencyMs(), end);
        }

        /** 时段有交集；一轮刚好在另一轮开始那一刻结束不算 */
        boolean overlaps(Span o) {
            return from < o.to && o.from < to;
        }
    }

    /** plan 是绑着这个仓位的计划行 */
    public DebriefMaterial assemble(AiTrader trader, FuturesPositionDTO pos, AiTraderPlan plan, AgentLang lang) {
        List<AiTraderExperience> entries = ledger.active(trader.getId(), trader.getRoundNo()).stream()
                .filter(e -> !AiTraderExperience.KIND_FINDING.equals(e.getKind())).toList();
        return new DebriefMaterial(
                prompts.get(lang, "debrief.label.factsHeader", Map.of("guide", caseRenderer.guide(lang))) + "\n"
                        + caseRenderer.render(caseFiles.build(pos, plan), lang),
                roundsBlock(trader, pos, plan, lang),
                ledgerBlock(trader, pos, plan, entries, lang),
                entries.size());
    }

    // ==================== 各轮决策 ====================

    private String roundsBlock(AiTrader t, FuturesPositionDTO pos, AiTraderPlan plan, AgentLang lang) {
        long closeMs = TradePairing.msOf(pos.getUpdatedAt());
        // 这笔的起点：计划立案那一轮、仓位开仓时刻里早的那个。续立计划的立案时刻是对账那一轮，仓位可能在那之前就成交了
        long from = Math.min(plan.getOpenedWakeTime(), TradePairing.msOf(pos.getCreatedAt()));
        Set<Long> planOrders = PlanEntry.read(plan).stream().map(PlanEntry::getOrderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        // 预案行只留对这笔单子做了动作的
        List<AiTraderDecision> rows = new ArrayList<>();
        Map<Long, Acted> acted = new HashMap<>();
        for (AiTraderDecision d : rounds(t, pos, from, closeMs)) {
            Acted a = acted(d, pos, planOrders, from, closeMs, lang);
            if (!a.actions().isEmpty() || !AiTraderDecision.KIND_PRESET.equals(d.getKind())) {
                rows.add(d);
                acted.put(d.getId(), a);
            }
        }
        StringBuilder sb = new StringBuilder(prompts.get(lang, "debrief.label.roundsHeader")).append('\n');
        if (rows.isEmpty()) {
            return sb.append(prompts.get(lang, "debrief.label.roundsNone")).append('\n').toString();
        }
        // 几轮可以同时在跑：按实际开始的先后排
        rows.sort(Comparator.comparingLong((AiTraderDecision d) -> Span.of(d).from()).thenComparing(AiTraderDecision::getId));
        List<Span> spans = rows.stream().map(Span::of).toList();
        String closedMark = prompts.get(lang, "debrief.label.closedMark", Map.of(
                "time", BeijingTime.shortFormat(closeMs), "manner", TradePairing.closeManner(prompts, pos, lang)));
        boolean closed = false;
        for (int i = 0; i < rows.size(); i++) {
            AiTraderDecision d = rows.get(i);
            Span span = spans.get(i);
            if (!closed && span.from() > closeMs) {
                sb.append('\n').append(closedMark).append('\n');
                closed = true;
            }
            // 和它的时段有交集的那几轮，编号从 1 起
            List<Integer> overlaps = new ArrayList<>();
            for (int j = 0; j < spans.size(); j++) {
                if (j != i && spans.get(j).overlaps(span)) {
                    overlaps.add(j + 1);
                }
            }
            // 正文和过程轨迹很大，一轮一轮取
            sb.append('\n').append(round(full(d.getId()), span, i + 1, rows.size(), overlaps,
                    acted.get(d.getId()), pos, planOrders, closeMs, lang));
        }
        if (!closed) {
            sb.append('\n').append(closedMark).append('\n');
        }
        return sb.toString();
    }

    /**
     * 这笔单子的各轮，不带正文和过程轨迹：看整盘的轮、预案行和只处理这个币的轮，只处理别的币的不要，跳过的轮也不要。
     * 实际跑的时段和这笔有交集的都收，从这笔的起点到平仓后第一个开跑的、看过这个币的轮；开仓前就在跑、后来动过它的那一轮也在里面。
     * "平仓后第一个"按实际开始认：例行轮的 wake_time 是边界，撞上数据公布要等一会儿才开跑
     */
    private List<AiTraderDecision> rounds(AiTrader t, FuturesPositionDTO pos, long from, long closeMs) {
        // wake_time 晚于平仓的第一轮：平仓后第一个开跑的那一轮，开跑不会晚于它
        AiTraderDecision next = decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .select(AiTraderDecision::getWakeTime, AiTraderDecision::getCreatedAt, AiTraderDecision::getLatencyMs)
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .in(AiTraderDecision::getKind, AiTraderDecision.WAKE_KINDS)
                .ne(AiTraderDecision::getStatus, AiTraderDecision.STATUS_SKIPPED)
                .and(w -> w.isNull(AiTraderDecision::getSymbol).or().eq(AiTraderDecision::getSymbol, pos.getSymbol()))
                .gt(AiTraderDecision::getWakeTime, closeMs)
                .orderByAsc(AiTraderDecision::getWakeTime)
                .last("LIMIT 1"));
        List<AiTraderDecision> rows = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .select(AiTraderDecision::getId, AiTraderDecision::getWakeTime, AiTraderDecision::getCreatedAt,
                        AiTraderDecision::getLatencyMs, AiTraderDecision::getKind, AiTraderDecision::getStatus,
                        AiTraderDecision::getSymbol, AiTraderDecision::getActionsJson)
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .in(AiTraderDecision::getKind, ROUND_KINDS)
                .ne(AiTraderDecision::getStatus, AiTraderDecision.STATUS_SKIPPED)
                .and(w -> w.isNull(AiTraderDecision::getSymbol).or().eq(AiTraderDecision::getSymbol, pos.getSymbol()))
                .le(AiTraderDecision::getWakeTime, next == null ? closeMs : Span.of(next).from())
                .ge(AiTraderDecision::getCreatedAt, TradePairing.localOf(from))
                .orderByAsc(AiTraderDecision::getWakeTime)
                .orderByAsc(AiTraderDecision::getId));
        // 平仓后第一个开跑的唤醒轮，比它开始得晚的不要；平仓后还没有轮开跑过，就到平仓为止
        long until = rows.stream().filter(d -> AiTraderDecision.WAKE_KINDS.contains(d.getKind()))
                .mapToLong(d -> Span.of(d).from()).filter(s -> s > closeMs).min().orElse(closeMs);
        return rows.stream().filter(d -> Span.of(d).from() <= until).toList();
    }

    /** 一轮的全文：正文和过程轨迹。过程轨迹那一列默认不查（select = false），得点名要 */
    private AiTraderDecision full(long id) {
        return decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .select(AiTraderDecision::getId, AiTraderDecision::getLatencyMs, AiTraderDecision::getKind,
                        AiTraderDecision::getStatus, AiTraderDecision::getSymbol, AiTraderDecision::getReasoning,
                        AiTraderDecision::getTraceJson)
                .eq(AiTraderDecision::getId, id));
    }

    /**
     * 一轮的全部内容，按顺序：标题行（实际起止、只处理哪个币、和哪几轮同时在跑）、开场白开头、这一轮开始时这笔仓位、
     * 对这笔单子做的动作、给这个币布的盯盘、各段写的原文、本轮总评、这个币的结论段。没存过程轨迹的行（预案行、旧行）没有开场白那两块
     *
     * @param overlaps 和这一轮的时段有交集的那几轮的编号
     */
    private String round(AiTraderDecision d, Span span, int no, int total, List<Integer> overlaps, Acted acted,
                         FuturesPositionDTO pos, Set<Long> planOrders, long closeMs, AgentLang lang) {
        JsonNode trace = d.getTraceJson() == null ? null : MAPPER.readTree(d.getTraceJson());
        String kind = prompts.get(lang, "debrief.label.kind." + d.getKind().toLowerCase(Locale.ROOT));
        String time = span.from() == span.to() ? BeijingTime.shortFormat(span.from())
                : BeijingTime.shortFormat(span.from()) + "–" + BeijingTime.shortFormat(span.to());
        StringBuilder sb = new StringBuilder(prompts.get(lang, "debrief.label.roundTitle", Map.of(
                "no", no, "total", total, "id", d.getId(), "time", time,
                "kind", AiTraderDecision.STATUS_OK.equals(d.getStatus()) ? kind : kind + "/" + d.getStatus(),
                "scope", d.getSymbol() == null ? ""
                        : prompts.get(lang, "debrief.label.roundScope", Map.of("symbol", d.getSymbol())),
                "overlap", overlaps.isEmpty() ? "" : prompts.get(lang, "debrief.label.roundOverlap", Map.of("nos",
                        overlaps.stream().map(String::valueOf).collect(Collectors.joining(prompts.get(lang, "debrief.label.listSep"))))))))
                .append('\n');
        if (trace != null) {
            String opening = trace.path("prompt").path("instruction").asString("");
            int account = accountAt(opening);
            if (account >= 0) {
                sb.append(prompts.get(lang, "debrief.label.roundOpening")).append('\n')
                        .append(opening.substring(0, account).strip()).append('\n');
                sb.append(prompts.get(lang, "debrief.label.roundPosition")).append('\n')
                        .append(positionAtStart(opening.substring(account), pos, planOrders, span.from() > closeMs, lang));
            }
        }
        sb.append(prompts.get(lang, "debrief.label.roundActions")).append('\n')
                .append(listOrNone(acted.actions(), lang));
        // 这一轮跑到一半这笔仓位平掉了
        if (d.getLatencyMs() != null && span.from() <= closeMs && closeMs <= span.to()) {
            sb.append(prompts.get(lang, "debrief.label.closedMidRound", Map.of(
                    "time", BeijingTime.shortFormat(closeMs), "manner", TradePairing.closeManner(prompts, pos, lang),
                    "from", BeijingTime.shortFormat(span.from()), "to", BeijingTime.shortFormat(span.to())))).append('\n');
        }
        sb.append(prompts.get(lang, "debrief.label.roundWatches", Map.of("symbol", pos.getSymbol()))).append('\n')
                .append(listOrNone(acted.watches(), lang));
        List<StageText> texts = texts(d, trace);
        for (int i = 0; i < texts.size(); i++) {
            StageText st = texts.get(i);
            // 结论块只在最后一段写的字里
            DecisionText.Conclusion c = i == texts.size() - 1 ? decisionText.locateConclusion(st.text(), lang) : null;
            String body = (c == null ? st.text() : st.text().substring(0, c.index())).strip();
            if (!body.isEmpty()) {
                String part = c == null ? "" : prompts.get(lang, "debrief.label.beforeConclusion");
                sb.append(st.stage() == 0 ? prompts.get(lang, "debrief.label.wholeText", Map.of("part", part))
                                : prompts.get(lang, "debrief.label.stageText", Map.of("n", st.stage(), "part", part)))
                        .append('\n').append(body).append('\n');
            }
            if (c != null) {
                String conclusion = c.body(st.text());
                sb.append(prompts.get(lang, "debrief.label.roundOverall")).append('\n')
                        .append(DecisionText.intro(conclusion)).append('\n');
                List<DecisionText.ConclusionSegment> segments = DecisionText.splitSegments(conclusion);
                sb.append(prompts.get(lang, "debrief.label.roundSymbol", Map.of("symbol", pos.getSymbol()))).append('\n')
                        .append(segments.isEmpty() ? prompts.get(lang, "debrief.label.notSegmented") : segments.stream()
                                .filter(s -> pos.getSymbol().equals(s.symbol())).map(s -> s.body().strip())
                                .findFirst().orElse(prompts.get(lang, "debrief.label.symbolAbsent"))).append('\n');
            }
        }
        return sb.toString();
    }

    private String listOrNone(List<String> rows, AgentLang lang) {
        return (rows.isEmpty() ? prompts.get(lang, "debrief.label.noAction") : String.join("\n", rows)) + "\n";
    }

    /**
     * 开场白里账户段从哪开始：账户 JSON 那一行（{"equity" 开头）的上一行就是账户段的标题。
     * 标题的措辞不固定，也分语言，不按标题认。没有给 -1
     */
    private static int accountAt(String opening) {
        int json = opening.indexOf("\n{\"equity\"");
        return json < 0 ? -1 : opening.lastIndexOf('\n', json - 1) + 1;
    }

    /**
     * 这一轮开始时这笔仓位。accountPart 从账户段的标题起：第二行是账户 JSON，第三行是"今天净亏损了结的仓位"（没有就是空行）。
     * 给出：JSON 里这笔仓位的那一段（计划不带，事实表里有）、这笔计划还挂着的入场档、账户层面的三个数、今天净亏损了结那一行
     */
    private String positionAtStart(String accountPart, FuturesPositionDTO pos, Set<Long> planOrders,
                                   boolean afterClose, AgentLang lang) {
        String[] lines = accountPart.split("\n", 4);
        ObjectNode account = (ObjectNode) MAPPER.readTree(lines[1]);
        StringBuilder sb = new StringBuilder();
        ObjectNode mine = null;
        for (JsonNode p : account.path("positions")) {
            if (p.path("positionId").asLong() == pos.getId()) {
                mine = (ObjectNode) p;
            }
        }
        if (mine == null) {
            sb.append(prompts.get(lang, afterClose ? "debrief.label.positionGone" : "debrief.label.positionNotYet"));
        } else {
            mine.remove("plan");
            sb.append(mine);
        }
        sb.append('\n');
        ArrayNode pending = MAPPER.createArrayNode();
        for (JsonNode o : account.path("pendingOrders")) {
            if (planOrders.contains(o.path("orderId").asLong())) {
                pending.add(((ObjectNode) o).retain(PENDING_FIELDS));
            }
        }
        if (!pending.isEmpty()) {
            sb.append(prompts.get(lang, "debrief.label.pendingEntries")).append(pending).append('\n');
        }
        sb.append(prompts.get(lang, "debrief.label.account")).append(account.retain(ACCOUNT_FIELDS)).append('\n');
        if (lines.length > 2 && !lines[2].isBlank()) {
            sb.append(lines[2].strip()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 这一轮和这笔单子有关的动作，每条是记下的时刻（老行没记就不写）、工具名（没做成的带状态）、参数原样，对这笔单子做的后面跟回执。
     * 盯盘按它为哪个币布的认
     *
     * @param from 这笔的起点
     */
    private Acted acted(AiTraderDecision d, FuturesPositionDTO pos, Set<Long> planOrders, long from, long closeMs,
                        AgentLang lang) {
        List<String> actions = new ArrayList<>();
        List<String> watches = new ArrayList<>();
        if (d.getActionsJson() == null || d.getActionsJson().isBlank()) {
            return new Acted(actions, watches);
        }
        for (JsonNode action : MAPPER.readTree(d.getActionsJson())) {
            // 反手拆成两条腿，腿上没有时刻，用外层这条的。老行没记，按这一轮的 wake_time 算、不写出来
            long atMs = action.path("at").asLong(d.getWakeTime());
            String at = action.has("at") ? BeijingTime.shortSeconds(atMs) + " " : "";
            for (JsonNode leg : TradeActions.legs(action)) {
                if (!leg.hasNonNull("tool")) {
                    continue;
                }
                String row = "- " + at + TradeActions.label(leg) + " " + prompts.get(lang, "debrief.label.actionArgs") + leg.path("args");
                if ("set_watch".equals(leg.path("tool").asString())) {
                    if (pos.getSymbol().equals(watchOwner(leg, d))) {
                        watches.add(row);
                    }
                } else if (onThisTrade(leg, pos, planOrders, from <= atMs && atMs <= closeMs)) {
                    actions.add(row + receipt(leg, lang));
                }
            }
        }
        return new Acted(actions, watches);
    }

    /** 这条盯盘为哪个币布的：写了 forSymbol 用它；没写的，单币轮是这一轮的币，整盘轮是被盯的币。和 TradeTools.arm 认的一样 */
    private static String watchOwner(JsonNode leg, AiTraderDecision d) {
        String forSymbol = leg.path("args").path("forSymbol").asString(null);
        if (forSymbol != null) {
            return forSymbol;
        }
        return d.getSymbol() != null ? d.getSymbol() : leg.path("args").path("symbol").asString(null);
    }

    /**
     * 这个动作是不是对这笔单子做的：按仓位编号改止损止盈、平仓的；开仓、撤单涉及的订单是这笔计划的入场档的（撤单预案的参数是单数 orderId）；
     * 还有一张单都没下出去的开仓（整笔被拒、出错），看币和方向，而且是在这笔起点到平仓之间做的。
     * 光看币和方向不行：止损之后又开同币同向的是另一笔单子，开仓前就在跑的轮里更早的那次也是。
     * 参数是模型传的，类型可能不对，取数字都带默认值
     *
     * @param inTrade 这个动作是在这笔起点到平仓之间做的
     */
    private static boolean onThisTrade(JsonNode leg, FuturesPositionDTO pos, Set<Long> planOrders, boolean inTrade) {
        JsonNode args = leg.path("args");
        if (args.path("positionId").asLong(0) == pos.getId() || planOrders.contains(args.path("orderId").asLong(0))) {
            return true;
        }
        boolean placed = false;
        for (JsonNode e : leg.path("result").path("entries")) {
            if (planOrders.contains(e.path("orderId").asLong())) {
                return true;
            }
            placed |= e.hasNonNull("orderId");
        }
        for (JsonNode id : args.path("orderIds")) {
            if (planOrders.contains(id.asLong(0))) {
                return true;
            }
        }
        return inTrade && !placed && "open_position".equals(leg.path("tool").asString(null))
                && pos.getSymbol().equals(args.path("symbol").asString(null))
                && pos.getSide().equals(args.path("side").asString(null));
    }

    /** 动作后面跟的回执和没做成的原因，各占一行。开仓回执里每一档只留订单号、类型、价、量、状态、成交价、没成的原因 */
    private String receipt(JsonNode leg, AgentLang lang) {
        StringBuilder sb = new StringBuilder();
        if (leg.hasNonNull("result")) {
            JsonNode result = leg.get("result").deepCopy();
            if ("open_position".equals(leg.path("tool").asString())) {
                for (JsonNode e : result.path("entries")) {
                    ((ObjectNode) e).retain(ENTRY_FIELDS);
                }
            }
            sb.append("\n  ").append(prompts.get(lang, "debrief.label.actionReceipt")).append(result);
        }
        // 护栏拒掉的原因在 rejected，sim 拒掉或出错的在 error
        for (String field : List.of("rejected", "error")) {
            if (leg.hasNonNull(field)) {
                sb.append("\n  ").append(prompts.get(lang, "debrief.label.actionFailed")).append(leg.get(field).asString());
            }
        }
        return sb.toString();
    }

    /**
     * 这一轮各段写的字。有过程轨迹：每次模型调用写的字按段归，同一段里几次写的接在一起，段的边界是每段开头那次调用的序号。
     * 没有过程轨迹（预案行、旧行）：就是存下来的正文
     */
    private static List<StageText> texts(AiTraderDecision d, JsonNode trace) {
        List<StageText> out = new ArrayList<>();
        if (trace != null) {
            for (JsonNode call : trace.path("calls")) {
                String text = call.path("text").asString("").strip();
                if (text.isEmpty()) {
                    continue;
                }
                int stage = 1;
                for (JsonNode s : trace.path("stages")) {
                    if (call.path("n").asInt() >= s.path("call").asInt()) {
                        stage++;
                    }
                }
                if (!out.isEmpty() && out.getLast().stage() == stage) {
                    out.set(out.size() - 1, new StageText(stage, out.getLast().text() + "\n\n" + text));
                } else {
                    out.add(new StageText(stage, text));
                }
            }
        }
        if (out.isEmpty() && d.getReasoning() != null && !d.getReasoning().isBlank()) {
            out.add(new StageText(0, d.getReasoning()));
        }
        return out;
    }

    // ==================== 经验账本 ====================

    /** entries 是现役条目（发现不算）；列出它们，和开仓那轮引用了其中哪几条 */
    private String ledgerBlock(AiTrader t, FuturesPositionDTO pos, AiTraderPlan plan, List<AiTraderExperience> entries,
                               AgentLang lang) {
        StringBuilder sb = new StringBuilder(prompts.get(lang, "debrief.label.ledgerHeader",
                Map.of("n", entries.size()))).append('\n');
        if (entries.isEmpty()) {
            return sb.append(prompts.get(lang, "debrief.label.ledgerEmpty")).append('\n').toString();
        }
        for (AiTraderExperience e : entries) {
            sb.append(prompts.get(lang, "debrief.label.ledgerRow", Map.of(
                    "id", e.getId(),
                    "kind", prompts.get(lang, "trader.label.ledgerKind." + e.getKind().toLowerCase(Locale.ROOT)),
                    "slot", prompts.get(lang, "reviewer.op.slot." + ExperienceLedger.slotKey(e.getSlot())),
                    "dir", prompts.get(lang, "reviewer.op.dir." + e.getDirection().toLowerCase(Locale.ROOT)),
                    "play", e.getPlayType() == null ? prompts.get(lang, "reviewer.op.general") : e.getPlayType(),
                    "text", e.getText()))).append('\n');
        }
        Set<Long> ids = entries.stream().map(AiTraderExperience::getId).collect(Collectors.toSet());
        String cited = citedAtOpen(t, pos, plan).stream().filter(ids::contains).map(id -> "#" + id)
                .collect(Collectors.joining(prompts.get(lang, "debrief.label.listSep")));
        return sb.append(cited.isEmpty() ? prompts.get(lang, "debrief.label.citedNone")
                : prompts.get(lang, "debrief.label.cited", Map.of("ids", cited))).append('\n').toString();
    }

    /**
     * 开仓那轮的结论里，该币那一段引用的账本编号（那轮没按币分段就看整块结论）。
     * 开仓那轮由 {@link TradeRecordService#openDecisions} 认，得真开过这个币这个方向：续立行、补归档行对不上，都算没引用
     */
    private Set<Long> citedAtOpen(AiTrader t, FuturesPositionDTO pos, AiTraderPlan plan) {
        AiTraderDecision open = tradeRecords.openDecisions(t, List.of(plan)).get(plan.getId());
        if (open == null) {
            return Set.of();
        }
        String block = decisionText.conclusionBlock(open.getReasoning());
        if (block == null) {
            return Set.of();
        }
        List<DecisionText.ConclusionSegment> segments = DecisionText.splitSegments(block);
        return DecisionText.ledgerRefs(segments.isEmpty() ? block : segments.stream()
                .filter(s -> pos.getSymbol().equals(s.symbol())).map(DecisionText.ConclusionSegment::body)
                .collect(Collectors.joining("\n")));
    }
}
