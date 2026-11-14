package com.mawai.wiibagent.trader.trade;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibcommon.dto.FuturesOrderProtectionRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibquant.external.sim.SimTradeClient;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 同组还挂着的档，以及逐张改保护单的回执。 */
public final class PendingProtection {

    public static final String UPDATED = "updated";
    public static final String RETAINED = "retained";
    public static final String FAILED = "failed";

    private PendingProtection() {
    }

    /** 一张挂单改保护单的结果：status 是 updated 改了 / retained 保留原样 / failed 没改成；previous 是改之前挂的 */
    public record Change(Long orderId, String status, Object previous, String error) {
    }

    /** 这个币这个方向、属于这个订单组、还挂着的开仓单；计划没有订单组给空表 */
    public static List<FuturesOrderResponse> orders(SimTradeClient sim, long userId, String symbol, String side, String group) {
        if (group == null) {
            return List.of();
        }
        return sim.getPendingOrders(userId, symbol).stream()
                .filter(o -> group.equals(o.getGroupId()) && ("OPEN_" + side).equals(o.getOrderSide()))
                .toList();
    }

    /** 改一张挂单带的保护单；sim 拒了记成没改成，不往外抛 */
    public static Change update(SimTradeClient sim, long userId, FuturesOrderResponse order,
                                FuturesOrderProtectionRequest request, Object previous, Function<Exception, String> describe) {
        try {
            sim.setOrderProtection(userId, order.getOrderId(), request);
            return new Change(order.getOrderId(), UPDATED, previous, null);
        } catch (Exception e) {
            return new Change(order.getOrderId(), FAILED, previous, describe.apply(e));
        }
    }

    public static long count(List<Change> changes, String status) {
        return changes.stream().filter(c -> status.equals(c.status())).count();
    }

    /** 修订史和预案结果里跟在后面的一句：同组挂单改了几档、保留几档、没改成几档；没有挂单给空串 */
    public static String summary(List<Change> changes, PromptCatalog prompts, AgentLang lang) {
        return changes.isEmpty() ? "" : prompts.get(lang, "trader.revise.syncedEntries", Map.of(
                "updated", count(changes, UPDATED), "retained", count(changes, RETAINED), "failed", count(changes, FAILED)));
    }
}
