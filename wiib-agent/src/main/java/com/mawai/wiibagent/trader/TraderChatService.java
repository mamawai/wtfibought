package com.mawai.wiibagent.trader;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 对话轨读 trader 的唯一入口：只查询、不动手，动作归 {@link TraderActionService}。
 * <p>
 * <b>两个 agent 的解耦纪律在这里落地</b>：chat 与 trader 从不互相对话，查询只读 trader
 * 自己写下的表。trader 依旧是那个"醒来读库→决策→写库→睡去"的无状态回路，
 * 它根本不知道有人在跟它聊天。
 * <p>
 * 所有方法按 userId 取自己的 trader，取不到就如实说"还没有"——归属判断只此一处。
 */
@Service
@RequiredArgsConstructor
public class TraderChatService {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** 决策一次最多给几条：叶子是轻模型，给多了读不完还挤掉问题本身 */
    static final int MAX_DECISIONS = 20;
    static final int DEFAULT_DECISIONS = 5;
    private static final int RECENT_CLOSED_PLANS = 5;
    private static final List<String> TRADE_KINDS =
            List.of(AiTraderDecision.KIND_TRADE, AiTraderDecision.KIND_ALERT, AiTraderDecision.KIND_MANUAL);

    private final TraderService traderService;
    private final TraderModelFactory modelFactory;
    private final TraderPlanStore planStore;
    private final SimTradeClient simTradeClient;
    /** stale 教材过滤的共用入口（与复盘时间线/唤醒回注同一套识别逻辑） */
    private final DecisionText decisionText;
    /** 返回 JSON 里的说明字段（chat.traderQuery.*）按 lang 取：这些字段是喂给 chat 模型看的 */
    private final PromptCatalog prompts;

    // ===== 查询（纯读库） =====

    /** 概况：状态/权益/轮次/配置 + 复盘与学习两份笔记全文（笔记是"它学到了什么"的唯一载体，必须给全）。 */
    public String overview(long userId, AgentLang lang) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return noTrader(lang);
        }
        UserLlmEndpoint endpoint = modelFactory.endpointFor(t);   // 模型名从端点库现解析，ai_trader 已没有 model 列
        return MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("hasTrader", true)
                .put("name", t.getName())
                .put("status", t.getStatus())
                .put("pausedReason", t.getPausedReason())
                .put("roundNo", t.getRoundNo())
                .put("equity", traderService.latestEquity(t))
                .put("initialBalance", TraderService.INITIAL_BALANCE)
                .put("symbols", t.getSymbols())
                .put("intervalCode", t.getIntervalCode())
                .put("model", endpoint == null ? null : endpoint.getModel())
                .put("consecutiveFailures", t.getConsecutiveFailures())
                .put("reviewEnabled", t.getReviewEnabled())
                .put("learningEnabled", t.getLearningEnabled())
                .put("alertEnabled", t.getAlertEnabled())
                .put("wakeWindow", t.getWakeWindow() == null
                        ? prompts.get(lang, "chat.traderQuery.wakeWindowAllDay")
                        : prompts.get(lang, "chat.traderQuery.wakeWindowNote", Map.of("window", t.getWakeWindow())))
                .put("leverageRange", prompts.get(lang, "chat.traderQuery.leverage",
                        Map.of("min", t.getLeverageMin(), "max", t.getLeverageMax())))
                .put("marginPctRange", plain(t.getMarginPctMin()) + "~" + plain(t.getMarginPctMax()) + "%")
                .put("memory", t.getMemory())
                .put("memoryNote", prompts.get(lang, "chat.traderQuery.memoryNote"))
                .put("learningNotes", t.getLearningNotes())
                .put("learningNotesNote", prompts.get(lang, "chat.traderQuery.learningNotesNote"))
                .put("customPrompt", t.getCustomPrompt())
                .put("pendingOwnerNote", t.getOwnerNote())
                // 剩余轮次一并给：模型答"我刚留的话还剩几次"只能靠库里这个数，卡片本身不进对话历史
                .put("pendingOwnerNoteRounds", t.getOwnerNoteRounds()));
    }

    /** 当前持仓：口径与唤醒时注入给 trader 的账户状态一致，免得两处说法对不上。 */
    public String positions(long userId, AgentLang lang) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return noTrader(lang);
        }
        ArrayNode arr = MAPPER.createArrayNode();
        for (FuturesPositionDTO p : simTradeClient.getAllPositions(t.getSimUserId())) {
            ObjectNode row = MAPPER.createObjectNode()
                    .put("positionId", p.getId())
                    .put("symbol", p.getSymbol())
                    .put("side", p.getSide())
                    .put("quantity", p.getQuantity())
                    .put("entryPrice", p.getEntryPrice())
                    .put("leverage", p.getLeverage())
                    .put("unrealizedPnl", p.getUnrealizedPnl())
                    .put("marginMode", p.getMarginMode());
            if (p.getStopLosses() != null && !p.getStopLosses().isEmpty()) {
                row.set("currentStopLoss", MAPPER.valueToTree(p.getStopLosses().stream().map(FuturesStopLoss::getPrice).toList()));
            }
            if (p.getTakeProfits() != null && !p.getTakeProfits().isEmpty()) {
                row.set("currentTakeProfit", MAPPER.valueToTree(p.getTakeProfits().stream().map(FuturesTakeProfit::getPrice).toList()));
            }
            arr.add(row);
        }
        return MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("hasTrader", true)
                .put("equity", traderService.latestEquity(t))
                .set("positions", arr));
    }

    /**
     * 决策时间线。<b>reasoning 给全文</b>：用户质询"你那笔为什么开多"靠的就是它，
     * 截断了正好把收尾的[本轮结论]切掉，剩一堆行情铺垫等于没给。
     */
    public String decisions(long userId, Integer limit, AgentLang lang) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return noTrader(lang);
        }
        int n = limit == null ? DEFAULT_DECISIONS : Math.clamp(limit, 1, MAX_DECISIONS);
        // stale 教材过滤（口径3：chat 跟随忽略）：新格式剔段、错误格式落在 stale 生命期内整轮剔，与复盘时间线同一套识别
        List<AiTraderPlan> allPlans = planStore.listAll(t.getId(), t.getRoundNo());
        ArrayNode arr = MAPPER.createArrayNode();
        for (AiTraderDecision d : traderService.decisions(t.getId(), n, null, null, null, null)) {
            String reasoning = d.getReasoning();
            boolean tradeRow = TRADE_KINDS.contains(d.getKind());
            if (tradeRow) {
                reasoning = decisionText.staleFiltered(d, allPlans);
                if (reasoning == null) {
                    continue;
                }
            }
            arr.add(MAPPER.createObjectNode()
                    .put("time", TIME_FMT.format(Instant.ofEpochMilli(d.getWakeTime())))
                    .put("wakeTime", d.getWakeTime())
                    .put("kind", d.getKind())
                    .put("status", d.getStatus())
                    .put("equity", d.getEquity())
                    .put("toolCalls", d.getToolCalls())
                    .put("error", d.getError())
                    // 交易行的工具名同样过 stale：被忽略交易的 open/close 动作名不出现
                    .set("tools", MAPPER.valueToTree(tradeRow ? decisionText.staleFilteredToolNames(d, allPlans)
                            : toolNames(d.getActionsJson())))
                    .put("reasoning", reasoning));
        }
        return MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("hasTrader", true)
                .put("roundNo", t.getRoundNo())
                .set("decisions", arr)
                .put("kindNote", prompts.get(lang, "chat.traderQuery.kindNote")));
    }

    /** 交易计划：存活的全给，另附最近归档的几条——"上一笔为什么平了"只看 LIVE 是答不了的。 */
    public String plans(long userId, AgentLang lang) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return noTrader(lang);
        }
        ArrayNode live = MAPPER.createArrayNode();
        planStore.list(t.getId(), t.getRoundNo()).forEach(p -> live.add(planJson(p)));
        ArrayNode closed = MAPPER.createArrayNode();
        // 主人标记忽略的不进对话教材（口径3），滤掉后可能不足 N 条——诚实缺席好过顶替
        planStore.recentClosed(t.getId(), t.getRoundNo(), RECENT_CLOSED_PLANS).stream()
                .filter(p -> !Boolean.TRUE.equals(p.getStale()))
                .forEach(p -> closed.add(planJson(p)));
        return MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("hasTrader", true)
                .set("livePlans", live)
                .set("recentClosedPlans", closed)
                .put("planNote", prompts.get(lang, "chat.traderQuery.planNote")));
    }

    // ===== 内部 =====

    private static ObjectNode planJson(AiTraderPlan p) {
        ObjectNode row = MAPPER.createObjectNode()
                .put("symbol", p.getSymbol())
                .put("side", p.getSide())
                .put("status", p.getStatus())
                .put("playType", p.getPlayType())
                .put("signalsUsed", p.getSignalsUsed())
                .put("invalidationCondition", p.getInvalidationCondition())
                .put("entryPrice", p.getEntryPrice())
                .put("originalStop", p.getStopLossPrice())
                .put("target", p.getTakeProfitPrice())
                .put("openedAt", TIME_FMT.format(Instant.ofEpochMilli(p.getOpenedWakeTime())));
        if (p.getClosedWakeTime() != null) {
            row.put("closedAt", TIME_FMT.format(Instant.ofEpochMilli(p.getClosedWakeTime())));
        }
        if (p.getRevisionsJson() != null && !p.getRevisionsJson().isBlank()) {
            row.set("revisions", MAPPER.readTree(p.getRevisionsJson()));
        }
        return row;
    }

    /** 动作轨迹只取工具名：全文可能上万字符，而这里要的只是"那一轮它动手没有"。 */
    private static List<String> toolNames(String actionsJson) {
        if (actionsJson == null || actionsJson.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(actionsJson, ArrayNode.class).valueStream()
                    .map(o -> o.isObject() ? o.path("tool").asString(null) : null)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String plain(java.math.BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private String noTrader(AgentLang lang) {
        return MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("hasTrader", false)
                .put("message", prompts.get(lang, "chat.traderQuery.noTrader")));
    }

}
