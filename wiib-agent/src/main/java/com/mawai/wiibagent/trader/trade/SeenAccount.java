package com.mawai.wiibagent.trader.trade;

import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 这一轮上次看到的账户：每个币的持仓、挂单、盯盘条件各是什么样。
 * 动手之前拿现状和它比，不一样就是这个币在它上次查看之后变了（别的轮动过、限价单成交、止损止盈触发、预案执行）。
 * "看到"包括开场白给的、get_account 查到的（这两样是全账户），和自己上一次动作之后的、被拦下时说明里带的、插进来的话里带的（这几样只是那一个币）。
 * 同一批里的几个 get_account 会并发进来，读写都同步。
 */
final class SeenAccount {

    /** 一个币的样子：持仓、挂单、盯盘条件，各是一段 JSON */
    record Coin(String positions, String orders, String watches) {
        String text() {
            return "{\"positions\":" + positions + ",\"pendingOrders\":" + orders + ",\"watches\":" + watches + "}";
        }
    }

    private final Map<String, String> positions = new HashMap<>();
    private final Map<String, String> orders = new HashMap<>();
    private final Map<String, String> watches = new HashMap<>();
    /** 上次看到的全部持仓：开仓前比别的币的仓位变没变用 */
    private List<FuturesPositionDTO> held = List.of();
    /** 这一轮看过账户没有 */
    private boolean looked;
    /** 上次看全账户时，账户闸里记到第几笔动作：之后的动作是这一轮没看到的 */
    private long actionNo;
    /** 之后又单独看过的币，各自看的时候闸里记到第几笔 */
    private final Map<String, Long> actionNoOf = new HashMap<>();

    /**
     * 看了一眼全账户的持仓和挂单：开场白给的、get_account 查到的
     *
     * @param lastActionNo 取这份账户时账户闸里记到第几笔动作（{@link AccountGate#lastNo}）
     */
    synchronized void look(List<FuturesPositionDTO> allPositions, List<FuturesOrderResponse> allOrders, long lastActionNo) {
        positions.clear();
        orders.clear();
        allPositions.forEach(p -> positions.computeIfAbsent(p.getSymbol(), s -> positionsOf(s, allPositions)));
        allOrders.forEach(o -> orders.computeIfAbsent(o.getSymbol(), s -> ordersOf(s, allOrders)));
        held = allPositions;
        looked = true;
        actionNo = lastActionNo;
        actionNoOf.clear();
    }

    /**
     * 只看了一眼这一个币的持仓、挂单和为它布的盯盘条件：自己动作的回执、被拦下时的说明、插进来的话里带的。
     * 别的币还是上次看到的样子
     */
    synchronized void lookCoin(String symbol, List<FuturesPositionDTO> allPositions, List<FuturesOrderResponse> allOrders,
                               List<AiTraderWatch> armed, long lastActionNo) {
        positions.put(symbol, positionsOf(symbol, allPositions));
        orders.put(symbol, ordersOf(symbol, allOrders));
        watches.put(symbol, watchesOf(symbol, armed));
        List<FuturesPositionDTO> merged = new ArrayList<>(held.stream().filter(p -> !symbol.equals(p.getSymbol())).toList());
        allPositions.stream().filter(p -> symbol.equals(p.getSymbol())).forEach(merged::add);
        held = merged;
        looked = true;
        actionNoOf.put(symbol, lastActionNo);
    }

    /** 看了一眼各币的仓位（编号、方向、数量）：开仓被拦下时的说明里带的 */
    synchronized void lookHoldings(List<FuturesPositionDTO> allPositions) {
        held = allPositions;
    }

    /** 看了一眼全部在盯的条件 */
    synchronized void lookWatches(List<AiTraderWatch> armed) {
        watches.clear();
        armed.forEach(w -> watches.computeIfAbsent(w.getForSymbol(), s -> watchesOf(s, armed)));
    }

    /** 上次看到这个币时，账户闸里记到第几笔动作 */
    synchronized long actionNo(String symbol) {
        return actionNoOf.getOrDefault(symbol, actionNo);
    }

    /** 这一轮看过账户没有：一眼都没看过就没有"上次看到的"可比 */
    synchronized boolean looked() {
        return looked;
    }

    /** 这个币上次看到的样子 */
    synchronized Coin seen(String symbol) {
        return new Coin(positions.getOrDefault(symbol, "[]"), orders.getOrDefault(symbol, "[]"),
                watches.getOrDefault(symbol, "[]"));
    }

    /** 上次看到的、除了这个币以外各币的仓位 */
    synchronized String seenOthers(String except) {
        return holdingsOf(held, except);
    }

    /** 这个币现在的样子 */
    static Coin now(String symbol, List<FuturesPositionDTO> allPositions, List<FuturesOrderResponse> allOrders,
                    List<AiTraderWatch> armed) {
        return new Coin(positionsOf(symbol, allPositions), ordersOf(symbol, allOrders), watchesOf(symbol, armed));
    }

    private static String positionsOf(String symbol, List<FuturesPositionDTO> all) {
        ArrayNode out = MAPPER.createArrayNode();
        for (FuturesPositionDTO p : all) {
            if (!symbol.equals(p.getSymbol())) {
                continue;
            }
            ObjectNode row = out.addObject().put("positionId", p.getId()).put("side", p.getSide())
                    .put("quantity", p.getQuantity()).put("entryPrice", p.getEntryPrice());
            if (p.getStopLosses() != null) {
                row.set("stopLosses", MAPPER.valueToTree(p.getStopLosses().stream().map(FuturesStopLoss::getPrice).toList()));
            }
            if (p.getTakeProfits() != null) {
                ArrayNode targets = row.putArray("takeProfits");
                p.getTakeProfits().forEach(t -> targets.addObject().put("price", t.getPrice()).put("quantity", t.getQuantity()));
            }
        }
        return out.toString();
    }

    private static String ordersOf(String symbol, List<FuturesOrderResponse> all) {
        ArrayNode out = MAPPER.createArrayNode();
        for (FuturesOrderResponse o : all) {
            if (!symbol.equals(o.getSymbol())) {
                continue;
            }
            ObjectNode row = out.addObject().put("orderId", o.getOrderId()).put("orderSide", o.getOrderSide())
                    .put("limitPrice", o.getLimitPrice()).put("quantity", o.getQuantity());
            row.set("stopLosses", MAPPER.valueToTree(o.getStopLosses()));
            row.set("takeProfits", MAPPER.valueToTree(o.getTakeProfits()));
        }
        return out.toString();
    }

    /** 为这个币布的在盯条件；带了预案的连预案动作一起，别的轮只改了预案也看得出变了 */
    private static String watchesOf(String forSymbol, List<AiTraderWatch> armed) {
        ArrayNode out = MAPPER.createArrayNode();
        for (AiTraderWatch w : armed) {
            if (!forSymbol.equals(w.getForSymbol())) {
                continue;
            }
            ObjectNode row = out.addObject().put("symbol", w.getSymbol()).put("kind", w.getKind()).put("level", w.getLevel())
                    .put("period", w.getPeriod()).put("atTime", w.getAtTime()).put("note", w.getNote());
            if (w.getActionJson() != null) {
                row.set("action", MAPPER.readTree(w.getActionJson()));
            }
        }
        return out.toString();
    }

    /** 除了 except 以外各币的仓位：只看仓位编号、方向、数量，按仓位编号排 */
    static String holdingsOf(List<FuturesPositionDTO> all, String except) {
        ArrayNode out = MAPPER.createArrayNode();
        all.stream().filter(p -> !except.equals(p.getSymbol())).sorted(Comparator.comparing(FuturesPositionDTO::getId))
                .forEach(p -> out.addObject().put("symbol", p.getSymbol()).put("positionId", p.getId())
                        .put("side", p.getSide()).put("quantity", p.getQuantity()));
        return out.toString();
    }
}
