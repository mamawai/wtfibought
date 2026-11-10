package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.trader.trade.StopOrders;
import com.mawai.wiibagent.trader.trade.TargetRung;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibquant.external.sim.SimOrderRetry;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 预案执行：带动作的盯盘条件命中时，由代码当场把动作做掉——移止损、平掉一部分或全部、撤一张挂单，都只会减风险。
 * 结果记三处：条件行（action_ok / action_result）、那个仓位的计划修订史、时间线上一行 PRESET。
 * 不看 trader 是不是在运行、在不在唤醒时段：预案和止损止盈单一样是挂出去的承诺。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PresetExecutor {

    private final SimTradeClient simTradeClient;
    private final AiTraderMapper traderMapper;
    private final AiTraderDecisionMapper decisionMapper;
    private final TraderPlanStore planStore;
    private final TraderWatchStore watchStore;
    private final UserLangResolver userLangResolver;
    private final PromptCatalog prompts;
    private final MessageCatalog messages;

    /** 一次执行的结果：做没做成，和一句话 */
    private record Done(boolean ok, String text) {
    }

    /**
     * 执行这条命中的预案，结果写回 w 和库里。
     *
     * @param price 命中时的价
     * @param at    命中时刻 ms
     * @return 要不要为这次命中叫醒模型：移止损和撤单做成了不叫，平仓做成了叫，没做成的都叫
     */
    public boolean execute(AiTraderWatch w, BigDecimal price, long at) {
        AiTrader trader = traderMapper.selectById(w.getTraderId());
        if (trader == null) {
            return false;
        }
        AgentLang lang = userLangResolver.of(trader.getUserId());
        JsonNode action = MAPPER.readTree(w.getActionJson());
        String type = action.path("type").asString();
        Done done;
        try {
            done = switch (type) {
                case AiTraderWatch.ACTION_MOVE_STOP -> moveStop(trader, w, action, at, lang);
                case AiTraderWatch.ACTION_CLOSE -> close(trader, w, action, at, lang);
                default -> cancel(trader, action, lang);
            };
        } catch (Exception e) {
            // sim 拒了或连不上：按没做成报，叫醒模型自己处理
            log.warn("[Preset] 执行失败 traderId={} watchId={} msg={}", trader.getId(), w.getId(), e.toString());
            done = new Done(false, SimTradeClient.describe(e, messages, lang));
        }
        w.setActionOk(done.ok());
        w.setActionResult(done.text());
        watchStore.recordAction(w.getId(), done.ok(), done.text());
        timeline(trader, w, action, price, at, done, lang);
        log.info("[Preset] traderId={} watchId={} {} ok={} {}", trader.getId(), w.getId(), type, done.ok(), done.text());
        return !done.ok() || AiTraderWatch.ACTION_CLOSE.equals(type);
    }

    private Done moveStop(AiTrader trader, AiTraderWatch w, JsonNode action, long at, AgentLang lang) {
        FuturesPositionDTO pos = position(trader, action);
        if (pos == null) {
            return new Done(false, prompts.get(lang, "trader.preset.positionGone"));
        }
        BigDecimal stop = action.path("stopLossPrice").asDecimal();
        return switch (StopOrders.move(simTradeClient, trader.getSimUserId(), pos, stop, pos.getMarkPrice())) {
            case StopOrders.Moved m -> {
                String from = m.from() == null ? prompts.get(lang, "trader.revise.none") : WatchText.num(m.from());
                revise(trader, pos, at, prompts.get(lang, "trader.revise.presetMoveStop"),
                        from + "→" + WatchText.num(stop), w.getNote());
                yield new Done(true, prompts.get(lang, "trader.preset.movedStop", Map.of(
                        "symbol", pos.getSymbol(), "side", pos.getSide(), "from", from, "to", WatchText.num(stop))));
            }
            case StopOrders.WrongSide _ -> new Done(false, prompts.get(lang, "trader.preset.stopWrongSide", Map.of(
                    "stop", WatchText.num(stop), "mark", WatchText.num(pos.getMarkPrice()))));
            case StopOrders.Looser l -> new Done(false, prompts.get(lang, "trader.preset.stopLooser", Map.of(
                    "stop", WatchText.num(stop), "current", WatchText.num(l.current()))));
        };
    }

    private Done close(AiTrader trader, AiTraderWatch w, JsonNode action, long at, AgentLang lang) {
        FuturesPositionDTO pos = position(trader, action);
        if (pos == null) {
            return new Done(false, prompts.get(lang, "trader.preset.positionGone"));
        }
        // 数量按命中这一刻的仓位折
        BigDecimal qty = TargetRung.share(action.path("closePercent").asDecimal(), pos.getQuantity(), pos.getSymbol());
        FuturesCloseRequest req = new FuturesCloseRequest();
        req.setPositionId(pos.getId());
        req.setQuantity(qty);
        req.setOrderType("MARKET");
        req.setClientRequestId(UUID.randomUUID().toString());
        FuturesOrderResponse resp = SimOrderRetry.send(() -> simTradeClient.closePosition(trader.getSimUserId(), req));
        revise(trader, pos, at, prompts.get(lang, "trader.revise.presetClose"), WatchText.num(qty), w.getNote());
        return new Done(true, prompts.get(lang, "trader.preset.closed", Map.of(
                "symbol", pos.getSymbol(), "side", pos.getSide(),
                "qty", WatchText.num(qty), "price", WatchText.num(resp.getFilledPrice()))));
    }

    private Done cancel(AiTrader trader, JsonNode action, AgentLang lang) {
        simTradeClient.cancelOrder(trader.getSimUserId(), action.path("orderId").asLong());
        return new Done(true, prompts.get(lang, "trader.preset.cancelled", Map.of(
                "symbol", action.path("symbol").asString(), "side", action.path("side").asString(),
                "orderId", action.path("orderId").asLong())));
    }

    /** 动作指的那个仓位现在还在不在；不在给 null */
    private FuturesPositionDTO position(AiTrader trader, JsonNode action) {
        long positionId = action.path("positionId").asLong();
        return simTradeClient.getAllPositions(trader.getSimUserId()).stream()
                .filter(p -> p.getId() == positionId).findFirst().orElse(null);
    }

    /** 往这个仓位的计划记一条修订，理由是模型布预案时写的备注 */
    private void revise(AiTrader trader, FuturesPositionDTO pos, long at, String type, String change, String reason) {
        AiTraderPlan plan = planStore.liveFor(trader.getId(), trader.getRoundNo(), pos.getSymbol(), pos.getSide());
        if (plan != null) {
            planStore.revise(plan, at, type, change, reason);
        }
    }

    /** 时间线上记一行：哪条条件命中、做了什么。没跑模型，没有权益 */
    private void timeline(AiTrader trader, AiTraderWatch w, JsonNode action, BigDecimal price, long at, Done done, AgentLang lang) {
        AiTraderDecision d = new AiTraderDecision();
        d.setTraderId(trader.getId());
        d.setRoundNo(trader.getRoundNo());
        d.setWakeTime(at);
        d.setIntervalCode(trader.getIntervalCode());
        d.setKind(AiTraderDecision.KIND_PRESET);
        d.setStatus(done.ok() ? AiTraderDecision.STATUS_OK : AiTraderDecision.STATUS_ERROR);
        d.setReasoning(prompts.get(lang, "trader.preset.timeline", Map.of(
                "detail", WatchText.detail(w, price, prompts, lang),
                "note", w.getNote(),
                "result", done.text())));
        ObjectNode row = MAPPER.createObjectNode().put("tool", "preset");
        row.set("args", action);
        row.put("status", done.ok() ? "ok" : "error").put(done.ok() ? "result" : "error", done.text());
        d.setActionsJson(MAPPER.writeValueAsString(List.of(row)));
        d.setToolCalls(1);
        if (!done.ok()) {
            d.setError(done.text());
        }
        decisionMapper.insert(d);
    }
}
