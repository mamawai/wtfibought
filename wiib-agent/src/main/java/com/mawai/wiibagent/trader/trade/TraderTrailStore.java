package com.mawai.wiibagent.trader.trade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderTrailMapper;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.AiTraderTrail;
import com.mawai.wiibcommon.enums.AgentLang;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 跟踪止损规则的存取。一个仓位（trader + round + symbol + side）至多一条：开启就是整条换掉，结束就是删行。
 * 名单一变广播 {@link TrailsChanged}，哨兵重载。开启和结束各往计划的修订史记一条，中间每一步不记。
 */
@Component
@RequiredArgsConstructor
public class TraderTrailStore {

    private final AiTraderTrailMapper mapper;
    private final TraderPlanStore planStore;
    private final PromptCatalog prompts;
    private final ApplicationEventPublisher events;

    /** 全站的跟随规则，哨兵装名单用 */
    public List<AiTraderTrail> all() {
        return mapper.selectList(null);
    }

    /** 本局的跟随规则，按 {@link TraderPlanStore#key} */
    public Map<String, AiTraderTrail> byKey(long traderId, int roundNo) {
        Map<String, AiTraderTrail> out = new HashMap<>();
        for (AiTraderTrail t : mapper.selectList(scope(traderId, roundNo))) {
            out.put(TraderPlanStore.key(t.getSymbol(), t.getSide()), t);
        }
        return out;
    }

    /**
     * 开启或换掉一个仓位的跟随规则。
     *
     * @param positionId 仓位 id；限价单还没成交传 null，成交后 {@link #bind}
     * @param stopPrice  此刻挂着的止损价
     */
    public void start(long traderId, int roundNo, String symbol, String side, Long positionId,
                      BigDecimal callbackPct, BigDecimal activationPrice, BigDecimal stopPrice) {
        mapper.delete(scope(traderId, roundNo).eq(AiTraderTrail::getSymbol, symbol).eq(AiTraderTrail::getSide, side));
        AiTraderTrail t = new AiTraderTrail();
        t.setTraderId(traderId);
        t.setRoundNo(roundNo);
        t.setSymbol(symbol);
        t.setSide(side);
        t.setPositionId(positionId);
        t.setCallbackPct(callbackPct);
        t.setActivationPrice(activationPrice);
        t.setStopPrice(stopPrice);
        mapper.insert(t);
        events.publishEvent(new TrailsChanged(traderId));
    }

    /** 限价单成交：还没绑仓位的那条绑上，从这一刻开始跟 */
    public void bind(long traderId, int roundNo, String symbol, String side, long positionId) {
        int bound = mapper.update(null, new LambdaUpdateWrapper<AiTraderTrail>()
                .eq(AiTraderTrail::getTraderId, traderId)
                .eq(AiTraderTrail::getRoundNo, roundNo)
                .eq(AiTraderTrail::getSymbol, symbol)
                .eq(AiTraderTrail::getSide, side)
                .isNull(AiTraderTrail::getPositionId)
                .set(AiTraderTrail::getPositionId, positionId)
                .set(AiTraderTrail::getUpdatedAt, LocalDateTime.now()));
        if (bound > 0) {
            events.publishEvent(new TrailsChanged(traderId));
        }
    }

    /** 跟到哪了：最有利价和止损价落库。不广播：哨兵内存里已经是这两个数 */
    public void advance(long id, BigDecimal bestPrice, BigDecimal stopPrice) {
        mapper.update(null, new LambdaUpdateWrapper<AiTraderTrail>()
                .eq(AiTraderTrail::getId, id)
                .set(AiTraderTrail::getBestPrice, bestPrice)
                .set(AiTraderTrail::getStopPrice, stopPrice)
                .set(AiTraderTrail::getUpdatedAt, LocalDateTime.now()));
    }

    /** 结束跟随：删行，往这个仓位的计划记一条"跟到哪了"。限价单没成交就撤掉的没有仓位，不记 */
    public void end(AiTraderTrail t, AgentLang lang, String reason) {
        mapper.deleteById(t.getId());
        events.publishEvent(new TrailsChanged(t.getTraderId()));
        AiTraderPlan plan = t.getPositionId() == null ? null
                : planStore.byPosition(t.getTraderId(), t.getRoundNo(), t.getPositionId());
        if (plan != null) {
            planStore.appendRevision(plan.getId(), System.currentTimeMillis(), prompts.get(lang, "trader.revise.trailEnd"),
                    prompts.get(lang, t.getBestPrice() == null ? "trader.revise.trailEndIdle" : "trader.revise.trailEndChange",
                            Map.of("best", plain(t.getBestPrice()), "stop", plain(t.getStopPrice()))),
                    reason);
        }
    }

    /** 仓位已经不在了（止损、止盈、强平或平掉）的那种结束 */
    public void endClosed(AiTraderTrail t, AgentLang lang) {
        end(t, lang, prompts.get(lang, "trader.revise.trailEndClosed"));
    }

    /**
     * 撤掉一张开仓挂单之后：这个币向上没有别的开仓挂单了，它带的、还没绑仓位的规则一起删。
     * 不删的话，之后同币同向再开的仓会被当成这张单成交了，把规则接过去
     */
    public void orderCancelled(long traderId, int roundNo, String symbol, String orderSide,
                               Supplier<List<FuturesOrderResponse>> pendingOrders) {
        if (!orderSide.startsWith("OPEN_")) {
            return;
        }
        LambdaQueryWrapper<AiTraderTrail> unbound = scope(traderId, roundNo)
                .eq(AiTraderTrail::getSymbol, symbol)
                .eq(AiTraderTrail::getSide, orderSide.substring("OPEN_".length()))
                .isNull(AiTraderTrail::getPositionId);
        if (mapper.selectCount(unbound) > 0 && !hasPendingOpen(pendingOrders.get(), symbol, orderSide)
                && mapper.delete(unbound) > 0) {
            events.publishEvent(new TrailsChanged(traderId));
        }
    }

    private static boolean hasPendingOpen(List<FuturesOrderResponse> pendingOrders, String symbol, String orderSide) {
        return pendingOrders.stream().anyMatch(o -> symbol.equals(o.getSymbol()) && orderSide.equals(o.getOrderSide()));
    }

    /**
     * 唤醒开头对一遍。还没绑仓位的（限价单带的）：单还挂着就接着等；单没了、键上有仓位，是成交了，绑上；都没有就删。
     * 绑着的仓位不在了的结束。返回对完还在的规则，按 {@link TraderPlanStore#key}
     */
    public Map<String, AiTraderTrail> reconcile(long traderId, int roundNo, List<FuturesPositionDTO> positions,
                                                List<FuturesOrderResponse> pendingOrders, AgentLang lang) {
        Map<String, AiTraderTrail> trails = byKey(traderId, roundNo);
        if (trails.isEmpty()) {
            return trails;
        }
        Map<String, FuturesPositionDTO> openByKey = new HashMap<>();
        positions.forEach(p -> openByKey.put(TraderPlanStore.key(p.getSymbol(), p.getSide()), p));
        trails.entrySet().removeIf(e -> {
            AiTraderTrail t = e.getValue();
            FuturesPositionDTO open = openByKey.get(e.getKey());
            if (t.getPositionId() == null) {
                if (hasPendingOpen(pendingOrders, t.getSymbol(), "OPEN_" + t.getSide())) {
                    return false;
                }
                if (open != null) {
                    bind(traderId, roundNo, t.getSymbol(), t.getSide(), open.getId());
                    t.setPositionId(open.getId());
                    return false;
                }
            } else if (open != null && open.getId().equals(t.getPositionId())) {
                return false;
            }
            endClosed(t, lang);
            return true;
        });
        return trails;
    }

    /** 重置开新局或删 trader：这个 trader 的规则全清 */
    public void purgeAll(long traderId) {
        mapper.delete(new LambdaQueryWrapper<AiTraderTrail>().eq(AiTraderTrail::getTraderId, traderId));
        events.publishEvent(new TrailsChanged(traderId));
    }

    private static LambdaQueryWrapper<AiTraderTrail> scope(long traderId, int roundNo) {
        return new LambdaQueryWrapper<AiTraderTrail>()
                .eq(AiTraderTrail::getTraderId, traderId)
                .eq(AiTraderTrail::getRoundNo, roundNo);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }
}
