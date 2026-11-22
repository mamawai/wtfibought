package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 单笔复盘查历史用的两个工具：某一轮用过的工具和开场白（round_detail）、本局已了结的单子（trade_history）。
 * 只读，查的都是这个交易员当前这一局的。
 */
@Component
@RequiredArgsConstructor
public class DebriefToolkit {

    /** 轮次详情的总览里，每条工具回执带多少字；要全文按第几次模型调用再查 */
    static final int OVERVIEW_RESULT_CHARS = 300;
    /** 已了结单子的清单最多列几笔 */
    static final int LIST_LIMIT = 50;
    /** 能查过程的行：五种唤醒，加预案执行 */
    private static final List<String> ROUND_KINDS = Stream.concat(
            AiTraderDecision.WAKE_KINDS.stream(), Stream.of(AiTraderDecision.KIND_PRESET)).toList();

    private final AiTraderDecisionMapper decisionMapper;
    private final AiTraderPlanMapper planMapper;
    private final SimTradeClient simTradeClient;
    private final CaseFileBuilder caseFiles;
    private final CaseFileRenderer caseRenderer;

    /** 建循环时取一份绑着交易员和语言的工具挂上去 */
    public Tools boundTo(AiTrader trader, AgentLang lang) {
        return new Tools(this, trader, lang);
    }

    /** 交易员和语言在建循环时定下，不做成工具参数 */
    @RequiredArgsConstructor
    public static class Tools {

        private final DebriefToolkit toolkit;
        private final AiTrader trader;
        private final AgentLang lang;

        // 描述在词表 tool.round_detail（见 LocalizedToolCallbacks）
        @Tool(name = "round_detail")
        public String roundDetail(
                @ToolParam(description = "Decision id of the round: the number after # in that round's title line")
                long decisionId,
                @ToolParam(required = false, description = "Model-call number inside that round (the `call` field of the overview); give it to get that call's text and its tool results in full instead of the overview")
                Integer call,
                @ToolParam(required = false, description = "true to get the round's whole opening message, and the opening of each later stage, instead of the overview")
                Boolean opening) {
            return toolkit.roundDetail(trader, decisionId, call, opening);
        }

        // 描述在词表 tool.trade_history
        @Tool(name = "trade_history")
        public String tradeHistory(
                @ToolParam(required = false, description = "Position id of a closed trade; give it to get that trade's fact sheet and its debrief instead of the list")
                Long positionId) {
            return toolkit.tradeHistory(trader, lang, positionId);
        }
    }

    /**
     * 一轮当时用过的工具和看到的开场白。各段写的字在开场素材里给全了，总览里不重复。
     * 什么都不带：每次模型调用写了多少字、调了哪些工具（回执只带开头一段）；
     * 给 call：那一次调用写的字和工具回执全文（库里存的预览，最多 2000 字）；
     * opening 为真：整篇开场白，和后面每一段的段开场白。
     * 没存过程轨迹的行（旧行、预案行）只有正文和动作
     */
    String roundDetail(AiTrader t, long decisionId, Integer call, Boolean opening) {
        AiTraderDecision d = decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .select(AiTraderDecision::getId, AiTraderDecision::getTraderId, AiTraderDecision::getRoundNo,
                        AiTraderDecision::getWakeTime, AiTraderDecision::getKind, AiTraderDecision::getStatus,
                        AiTraderDecision::getError, AiTraderDecision::getReasoning, AiTraderDecision::getActionsJson,
                        AiTraderDecision::getTraceJson)
                .eq(AiTraderDecision::getId, decisionId));
        if (d == null || !Objects.equals(d.getTraderId(), t.getId()) || !Objects.equals(d.getRoundNo(), t.getRoundNo())
                || !ROUND_KINDS.contains(d.getKind())) {
            return unavailable("no round with decision id " + decisionId);
        }
        ObjectNode out = MAPPER.createObjectNode()
                .put("decisionId", d.getId())
                .put("time", BeijingTime.format(d.getWakeTime()))
                .put("kind", d.getKind())
                .put("status", d.getStatus());
        if (d.getError() != null) {
            out.put("error", d.getError());
        }
        if (d.getTraceJson() == null) {
            out.put("reasoning", d.getReasoning());
            if (d.getActionsJson() != null) {
                ArrayNode actions = out.putArray("actions");
                for (JsonNode a : MAPPER.readTree(d.getActionsJson())) {
                    ObjectNode row = actions.addObject().put("tool", a.path("tool").asString(null));
                    row.set("args", a.get("args"));
                    row.put("status", a.has("rejected") ? "rejected" : a.path("status").asString(null));
                }
            }
            return MAPPER.writeValueAsString(out);
        }
        JsonNode trace = MAPPER.readTree(d.getTraceJson());
        if (call != null) {
            for (JsonNode c : trace.path("calls")) {
                if (c.path("n").asInt() == call) {
                    out.put("call", call).put("text", c.path("text").asString(""));
                    out.set("tools", tools(c, Integer.MAX_VALUE));
                    return MAPPER.writeValueAsString(out);
                }
            }
            return unavailable("round " + decisionId + " has no model call " + call);
        }
        if (Boolean.TRUE.equals(opening)) {
            out.put("opening", trace.path("prompt").path("instruction").asString(null));
            // call 是这一段从第几次模型调用开始
            ArrayNode stages = out.putArray("stageOpenings");
            for (JsonNode stage : trace.path("stages")) {
                stages.addObject().put("call", stage.path("call").asInt())
                        .put("text", stage.path("instruction").asString(null));
            }
            return MAPPER.writeValueAsString(out);
        }
        // 每次调用都列，带上这次写了多少字：要看字用 call 点名
        ArrayNode calls = out.putArray("calls");
        for (JsonNode c : trace.path("calls")) {
            calls.addObject().put("call", c.path("n").asInt())
                    .put("textChars", c.path("text").asString("").length())
                    .set("tools", tools(c, OVERVIEW_RESULT_CHARS));
        }
        return MAPPER.writeValueAsString(out);
    }

    /** 一次模型调用里调的工具：名字、入参，和按调用 id 对上的回执（状态、正文最多 max 字） */
    private static ArrayNode tools(JsonNode call, int max) {
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode tc : call.path("toolCalls")) {
            ObjectNode row = out.addObject().put("name", tc.path("name").asString(null));
            row.set("args", tc.get("args"));
            for (JsonNode r : call.path("results")) {
                if (r.path("id").asString("").equals(tc.path("id").asString(null))) {
                    String text = r.path("preview").asString("");
                    row.put("status", r.path("status").asString(null));
                    row.put("result", text.length() > max ? text.substring(0, max) + "…" : text);
                }
            }
        }
        return out;
    }

    /**
     * 不给仓位编号：本局已了结的单子，新的在前，最多 {@value #LIST_LIMIT} 笔，主人标了不算数的不列。
     * 给仓位编号：那一笔的事实表，和它的单笔复盘正文（还没有就没有这一项）
     */
    String tradeHistory(AiTrader t, AgentLang lang, Long positionId) {
        List<FuturesPositionDTO> closed = simTradeClient.getClosedPositions(t.getSimUserId(), PlayStatsAssembler.FETCH_LIMIT);
        Map<FuturesPositionDTO, AiTraderPlan> planByPos = TradePairing.pairAll(closed,
                planMapper.selectList(new LambdaQueryWrapper<AiTraderPlan>()
                        .eq(AiTraderPlan::getTraderId, t.getId())
                        .eq(AiTraderPlan::getRoundNo, t.getRoundNo())));
        List<FuturesPositionDTO> visible = closed.stream()
                .filter(p -> planByPos.get(p) == null || !Boolean.TRUE.equals(planByPos.get(p).getStale())).toList();
        if (positionId == null) {
            ArrayNode arr = MAPPER.createArrayNode();
            for (FuturesPositionDTO p : visible.stream().limit(LIST_LIMIT).toList()) {
                AiTraderPlan plan = planByPos.get(p);
                arr.addObject()
                        .put("positionId", p.getId())
                        .put("symbol", p.getSymbol())
                        .put("side", p.getSide())
                        .put("playType", plan == null ? null : plan.getPlayType())
                        .put("opened", BeijingTime.format(TradePairing.msOf(p.getCreatedAt())))
                        .put("closed", BeijingTime.format(TradePairing.msOf(p.getUpdatedAt())))
                        .put("closeManner", TradePairing.closeMannerKey(p))
                        .put("net", p.getClosedPnl());
            }
            return MAPPER.writeValueAsString(arr);
        }
        FuturesPositionDTO pos = visible.stream().filter(p -> positionId.equals(p.getId())).findFirst().orElse(null);
        if (pos == null) {
            return unavailable("no closed trade with position id " + positionId);
        }
        ObjectNode out = MAPPER.createObjectNode().put("positionId", positionId)
                .put("factSheet", caseRenderer.render(caseFiles.build(pos, planByPos.get(pos)), lang));
        AiTraderDecision debrief = decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .select(AiTraderDecision::getReasoning)
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .eq(AiTraderDecision::getKind, AiTraderDecision.KIND_DEBRIEF)
                .eq(AiTraderDecision::getStatus, AiTraderDecision.STATUS_OK)
                .eq(AiTraderDecision::getPositionId, positionId)
                .orderByDesc(AiTraderDecision::getId)
                .last("LIMIT 1"));
        if (debrief != null) {
            out.put("debrief", debrief.getReasoning());
        }
        return MAPPER.writeValueAsString(out);
    }

    /** 和数据工具"没取到"同一个形状 */
    private static String unavailable(String reason) {
        return MAPPER.writeValueAsString(MAPPER.createObjectNode().put("available", false).put("reason", reason));
    }
}
