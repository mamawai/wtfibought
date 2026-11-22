package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibagent.trader.TradeRecordService;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 单笔复盘的开场素材（纯代码）：这笔单子的事实表、从开仓那轮到平仓后第一轮的各轮决策、经验账本的现役条目。
 * 段标签在 {@link PromptCatalog} 的 {@code debrief.label.*}；事实表和统一复盘用同一份（{@link CaseFileRenderer}）。
 */
@Component
@RequiredArgsConstructor
public class DebriefMaterialAssembler {

    /** 带结论段的轮最多列几轮，超出的只列决策编号和时刻 */
    static final int MAX_DETAILED = 40;
    /** 一轮里该币的结论段最多带多少字 */
    static final int MAX_SECTION = 400;
    /** 各轮清单收的行：五种唤醒，加预案执行 */
    private static final List<String> ROUND_KINDS = Stream.concat(
            AiTraderDecision.WAKE_KINDS.stream(), Stream.of(AiTraderDecision.KIND_PRESET)).toList();
    /** 动作后面带出来的参数 */
    private static final List<String> SHOWN_ARGS = List.of("quantity", "closePercent", "stopLossPrice", "takeProfits", "reason");

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
        StringBuilder sb = new StringBuilder(prompts.get(lang, "debrief.label.roundsHeader")).append('\n');
        // 每轮对这笔单子做的动作；预案行只留对这笔单子做的
        Map<Long, String> actions = new HashMap<>();
        List<AiTraderDecision> rows = new ArrayList<>();
        for (AiTraderDecision d : rounds(t, plan.getOpenedWakeTime(), closeMs)) {
            String acted = actionsOn(d.getActionsJson(), pos, lang);
            if (!acted.isEmpty()) {
                actions.put(d.getId(), acted);
            }
            if (!acted.isEmpty() || !AiTraderDecision.KIND_PRESET.equals(d.getKind())) {
                rows.add(d);
            }
        }
        if (rows.isEmpty()) {
            return sb.append(prompts.get(lang, "debrief.label.roundsNone")).append('\n').toString();
        }
        Set<Long> detailed = detailed(rows, actions.keySet(), closeMs);
        Map<Long, String> reasonings = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                        .select(AiTraderDecision::getId, AiTraderDecision::getReasoning)
                        .in(AiTraderDecision::getId, detailed))
                .stream().filter(d -> d.getReasoning() != null)
                .collect(Collectors.toMap(AiTraderDecision::getId, AiTraderDecision::getReasoning));
        String closedMark = prompts.get(lang, "debrief.label.closedMark", Map.of(
                "time", BeijingTime.shortFormat(closeMs), "manner", TradePairing.closeManner(prompts, pos, lang)));
        boolean closed = false;
        List<String> brief = new ArrayList<>();
        for (AiTraderDecision d : rows) {
            if (!closed && d.getWakeTime() > closeMs) {
                sb.append(closedMark).append('\n');
                closed = true;
            }
            String time = BeijingTime.shortFormat(d.getWakeTime());
            if (!detailed.contains(d.getId())) {
                brief.add(prompts.get(lang, "debrief.label.roundBrief", Map.of("id", d.getId(), "time", time)));
                continue;
            }
            sb.append(prompts.get(lang, "debrief.label.roundRow", Map.of(
                    "id", d.getId(), "time", time,
                    "kind", AiTraderDecision.STATUS_OK.equals(d.getStatus()) ? d.getKind() : d.getKind() + "/" + d.getStatus(),
                    "actions", actions.getOrDefault(d.getId(), prompts.get(lang, "debrief.label.noAction")),
                    "section", section(d, reasonings.get(d.getId()), pos.getSymbol(), lang)))).append('\n');
        }
        if (!closed) {
            sb.append(closedMark).append('\n');
        }
        if (!brief.isEmpty()) {
            sb.append(prompts.get(lang, "debrief.label.roundsBrief", Map.of("n", brief.size(),
                    "rows", String.join(prompts.get(lang, "debrief.label.listSep"), brief)))).append('\n');
        }
        return sb.toString();
    }

    /** 开仓那轮到平仓后第一轮之间的唤醒行和预案行，不带正文；被跳过的轮不要 */
    private List<AiTraderDecision> rounds(AiTrader t, long fromMs, long closeMs) {
        AiTraderDecision after = decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .select(AiTraderDecision::getWakeTime)
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .in(AiTraderDecision::getKind, AiTraderDecision.WAKE_KINDS)
                .ne(AiTraderDecision::getStatus, AiTraderDecision.STATUS_SKIPPED)
                .gt(AiTraderDecision::getWakeTime, closeMs)
                .orderByAsc(AiTraderDecision::getWakeTime)
                .last("LIMIT 1"));
        return decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .select(AiTraderDecision::getId, AiTraderDecision::getWakeTime, AiTraderDecision::getKind,
                        AiTraderDecision::getStatus, AiTraderDecision::getActionsJson)
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .in(AiTraderDecision::getKind, ROUND_KINDS)
                .ne(AiTraderDecision::getStatus, AiTraderDecision.STATUS_SKIPPED)
                .between(AiTraderDecision::getWakeTime, fromMs, after == null ? closeMs : after.getWakeTime())
                .orderByAsc(AiTraderDecision::getWakeTime)
                .orderByAsc(AiTraderDecision::getId));
    }

    /**
     * 哪几轮带结论段：不超过上限全带；超过时留开仓那轮、对这笔单子有动作的、平仓之后的，
     * 再从平仓前最近的往回补到上限
     */
    private static Set<Long> detailed(List<AiTraderDecision> rows, Set<Long> acted, long closeMs) {
        Set<Long> keep = new HashSet<>();
        if (rows.size() <= MAX_DETAILED) {
            rows.forEach(d -> keep.add(d.getId()));
            return keep;
        }
        keep.add(rows.getFirst().getId());
        for (AiTraderDecision d : rows) {
            if (acted.contains(d.getId()) || d.getWakeTime() > closeMs) {
                keep.add(d.getId());
            }
        }
        for (int i = rows.size() - 1; i >= 0 && keep.size() < MAX_DETAILED; i--) {
            keep.add(rows.get(i).getId());
        }
        return keep;
    }

    /**
     * 这一轮对这笔单子做的交易动作：按仓位编号改止损止盈、平仓的，和开这个币这个方向的仓（加仓）。
     * 每条是工具名（没做成的带状态）加几个参数；一条都没有给空串
     */
    private String actionsOn(String actionsJson, FuturesPositionDTO pos, AgentLang lang) {
        if (actionsJson == null || actionsJson.isBlank()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (JsonNode action : MAPPER.readTree(actionsJson)) {
            for (JsonNode leg : TradeActions.legs(action)) {
                JsonNode args = leg.path("args");
                boolean mine = args.path("positionId").asLong() == pos.getId()
                        || pos.getSymbol().equals(args.path("symbol").asString(null))
                        && pos.getSide().equals(args.path("side").asString(null));
                if (!mine || !leg.hasNonNull("tool")) {
                    continue;
                }
                StringBuilder s = new StringBuilder(TradeActions.label(leg));
                for (String key : SHOWN_ARGS) {
                    JsonNode v = args.get(key);
                    if (v != null && !v.isNull()) {
                        s.append(' ').append(key).append('=').append(v.isString() ? v.asString() : v.toString());
                    }
                }
                out.add(s.toString());
            }
        }
        return String.join(prompts.get(lang, "debrief.label.actionSep"), out);
    }

    /** 这一轮结论里该币的那一段；预案行没有结论块，给它那句条件和执行结果 */
    private String section(AiTraderDecision d, String reasoning, String symbol, AgentLang lang) {
        String section = reasoning == null ? null
                : AiTraderDecision.KIND_PRESET.equals(d.getKind()) ? reasoning.strip().replaceAll("\\s+", " ")
                : decisionText.symbolSection(reasoning, symbol);
        if (section == null) {
            return prompts.get(lang, "debrief.label.none");
        }
        return section.length() > MAX_SECTION ? section.substring(0, MAX_SECTION) + "…" : section;
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
     * 计划行对上的那一轮得真开过这个币这个方向才算：续立行对上的是对账那轮，补归档行对不上任何一轮，这两种都算没引用
     */
    private Set<Long> citedAtOpen(AiTrader t, FuturesPositionDTO pos, AiTraderPlan plan) {
        AiTraderDecision open = tradeRecords.openDecisions(t, List.of(plan)).get(plan.getOpenedWakeTime());
        if (open == null || !opened(open, pos)) {
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
}
