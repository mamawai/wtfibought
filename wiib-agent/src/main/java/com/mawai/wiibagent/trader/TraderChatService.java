package com.mawai.wiibagent.trader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

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
        return new JSONObject()
                .fluentPut("hasTrader", true)
                .fluentPut("name", t.getName())
                .fluentPut("status", t.getStatus())
                .fluentPut("pausedReason", t.getPausedReason())
                .fluentPut("roundNo", t.getRoundNo())
                .fluentPut("equity", traderService.latestEquity(t))
                .fluentPut("initialBalance", TraderService.INITIAL_BALANCE)
                .fluentPut("symbols", t.getSymbols())
                .fluentPut("intervalCode", t.getIntervalCode())
                .fluentPut("model", endpoint == null ? null : endpoint.getModel())
                .fluentPut("consecutiveFailures", t.getConsecutiveFailures())
                .fluentPut("reviewEnabled", t.getReviewEnabled())
                .fluentPut("learningEnabled", t.getLearningEnabled())
                .fluentPut("alertEnabled", t.getAlertEnabled())
                .fluentPut("wakeWindow", t.getWakeWindow() == null
                        ? prompts.get(lang, "chat.traderQuery.wakeWindowAllDay")
                        : prompts.get(lang, "chat.traderQuery.wakeWindowNote", Map.of("window", t.getWakeWindow())))
                .fluentPut("leverageRange", prompts.get(lang, "chat.traderQuery.leverage",
                        Map.of("min", t.getLeverageMin(), "max", t.getLeverageMax())))
                .fluentPut("marginPctRange", plain(t.getMarginPctMin()) + "~" + plain(t.getMarginPctMax()) + "%")
                .fluentPut("memory", t.getMemory())
                .fluentPut("memoryNote", prompts.get(lang, "chat.traderQuery.memoryNote"))
                .fluentPut("learningNotes", t.getLearningNotes())
                .fluentPut("learningNotesNote", prompts.get(lang, "chat.traderQuery.learningNotesNote"))
                .fluentPut("customPrompt", t.getCustomPrompt())
                .fluentPut("pendingOwnerNote", t.getOwnerNote())
                // 剩余轮次一并给：模型答"我刚留的话还剩几次"只能靠库里这个数，卡片本身不进对话历史
                .fluentPut("pendingOwnerNoteRounds", t.getOwnerNoteRounds())
                .toJSONString();
    }

    /** 当前持仓：口径与唤醒时注入给 trader 的账户状态一致，免得两处说法对不上。 */
    public String positions(long userId, AgentLang lang) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return noTrader(lang);
        }
        JSONArray arr = new JSONArray();
        for (FuturesPositionDTO p : simTradeClient.getAllPositions(t.getSimUserId())) {
            JSONObject row = new JSONObject()
                    .fluentPut("positionId", p.getId())
                    .fluentPut("symbol", p.getSymbol())
                    .fluentPut("side", p.getSide())
                    .fluentPut("quantity", p.getQuantity())
                    .fluentPut("entryPrice", p.getEntryPrice())
                    .fluentPut("leverage", p.getLeverage())
                    .fluentPut("unrealizedPnl", p.getUnrealizedPnl())
                    .fluentPut("marginMode", p.getMarginMode());
            if (p.getStopLosses() != null && !p.getStopLosses().isEmpty()) {
                row.put("currentStopLoss", p.getStopLosses().stream().map(FuturesStopLoss::getPrice).toList());
            }
            if (p.getTakeProfits() != null && !p.getTakeProfits().isEmpty()) {
                row.put("currentTakeProfit", p.getTakeProfits().stream().map(FuturesTakeProfit::getPrice).toList());
            }
            arr.add(row);
        }
        return new JSONObject()
                .fluentPut("hasTrader", true)
                .fluentPut("equity", traderService.latestEquity(t))
                .fluentPut("positions", arr)
                .toJSONString();
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
        JSONArray arr = new JSONArray();
        for (AiTraderDecision d : traderService.decisions(t.getId(), n, null, null, null, null)) {
            String reasoning = d.getReasoning();
            boolean tradeRow = TRADE_KINDS.contains(d.getKind());
            if (tradeRow) {
                reasoning = decisionText.staleFiltered(d, allPlans);
                if (reasoning == null) {
                    continue;
                }
            }
            arr.add(new JSONObject()
                    .fluentPut("time", TIME_FMT.format(Instant.ofEpochMilli(d.getWakeTime())))
                    .fluentPut("wakeTime", d.getWakeTime())
                    .fluentPut("kind", d.getKind())
                    .fluentPut("status", d.getStatus())
                    .fluentPut("equity", d.getEquity())
                    .fluentPut("toolCalls", d.getToolCalls())
                    .fluentPut("error", d.getError())
                    // 交易行的工具名同样过 stale：被忽略交易的 open/close 动作名不出现
                    .fluentPut("tools", tradeRow ? decisionText.staleFilteredToolNames(d, allPlans)
                            : toolNames(d.getActionsJson()))
                    .fluentPut("reasoning", reasoning));
        }
        return new JSONObject()
                .fluentPut("hasTrader", true)
                .fluentPut("roundNo", t.getRoundNo())
                .fluentPut("decisions", arr)
                .fluentPut("kindNote", prompts.get(lang, "chat.traderQuery.kindNote"))
                .toJSONString();
    }

    /** 交易计划：存活的全给，另附最近归档的几条——"上一笔为什么平了"只看 LIVE 是答不了的。 */
    public String plans(long userId, AgentLang lang) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return noTrader(lang);
        }
        JSONArray live = new JSONArray();
        planStore.list(t.getId(), t.getRoundNo()).forEach(p -> live.add(planJson(p)));
        JSONArray closed = new JSONArray();
        // 主人标记忽略的不进对话教材（口径3），滤掉后可能不足 N 条——诚实缺席好过顶替
        planStore.recentClosed(t.getId(), t.getRoundNo(), RECENT_CLOSED_PLANS).stream()
                .filter(p -> !Boolean.TRUE.equals(p.getStale()))
                .forEach(p -> closed.add(planJson(p)));
        return new JSONObject()
                .fluentPut("hasTrader", true)
                .fluentPut("livePlans", live)
                .fluentPut("recentClosedPlans", closed)
                .fluentPut("planNote", prompts.get(lang, "chat.traderQuery.planNote"))
                .toJSONString();
    }

    // ===== 内部 =====

    private static JSONObject planJson(AiTraderPlan p) {
        JSONObject row = new JSONObject()
                .fluentPut("symbol", p.getSymbol())
                .fluentPut("side", p.getSide())
                .fluentPut("status", p.getStatus())
                .fluentPut("playType", p.getPlayType())
                .fluentPut("signalsUsed", p.getSignalsUsed())
                .fluentPut("invalidationCondition", p.getInvalidationCondition())
                .fluentPut("entryPrice", p.getEntryPrice())
                .fluentPut("originalStop", p.getStopLossPrice())
                .fluentPut("target", p.getTakeProfitPrice())
                .fluentPut("openedAt", TIME_FMT.format(Instant.ofEpochMilli(p.getOpenedWakeTime())));
        if (p.getClosedWakeTime() != null) {
            row.put("closedAt", TIME_FMT.format(Instant.ofEpochMilli(p.getClosedWakeTime())));
        }
        if (p.getRevisionsJson() != null && !p.getRevisionsJson().isBlank()) {
            row.put("revisions", JSON.parse(p.getRevisionsJson()));
        }
        return row;
    }

    /** 动作轨迹只取工具名：全文可能上万字符，而这里要的只是"那一轮它动手没有"。 */
    private static List<String> toolNames(String actionsJson) {
        if (actionsJson == null || actionsJson.isBlank()) {
            return List.of();
        }
        try {
            return JSON.parseArray(actionsJson).stream()
                    .map(o -> o instanceof JSONObject j ? j.getString("tool") : null)
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
        return new JSONObject()
                .fluentPut("hasTrader", false)
                .fluentPut("message", prompts.get(lang, "chat.traderQuery.noTrader"))
                .toJSONString();
    }

}
