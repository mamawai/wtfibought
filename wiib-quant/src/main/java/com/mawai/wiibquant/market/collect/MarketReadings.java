package com.mawai.wiibquant.market.collect;

import com.mawai.wiibcommon.market.OrderFlowAggregator;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 衍生品与微结构的原始读数：每个数据源取最新一个点原样给，再带一两个对照点，不打分。
 * 哪个数据源这次没取到、或者回包算不出来，它那一段就不出现；爆仓那段只有平台在采的币才有，在采而没有记录是 0。
 * 时刻都按北京时间成文。
 */
@Slf4j
public final class MarketReadings {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
    /** 持仓量、大户多空比都是 5 分钟一个点：1 小时前就是往回数 12 个 */
    private static final int POINTS_PER_HOUR = 12;
    /** 盘口合计前几档 */
    private static final int BOOK_LEVELS = 5;
    /** 逐笔成交看最近多少秒 */
    private static final int TRADES_WINDOW_SECONDS = 180;
    /** 逐笔成交少于这么多笔当没有 */
    private static final int MIN_TRADES = 10;

    private MarketReadings() {
    }

    /** @param rawData {@link CollectDataNode#collect} 的回包 */
    public static ObjectNode build(String symbol, Map<String, Object> rawData, OrderFlowAggregator orderFlow, long nowMs) {
        ObjectNode out = MAPPER.createObjectNode();
        section(out, "accountLongShort", symbol, () -> accountLongShort(out, raw(rawData, "long_short_ratio_map", symbol)));
        section(out, "openInterest", symbol, () -> openInterest(out, raw(rawData, "oi_hist_map", symbol)));
        section(out, "topTraderPositionLongShort", symbol, () -> topTrader(out, raw(rawData, "top_trader_position_map", symbol)));
        section(out, "takerVolume5m", symbol, () -> takerVolume(out, raw(rawData, "taker_long_short_map", symbol)));
        section(out, "funding", symbol, () -> funding(out, raw(rawData, "funding_rate_map", symbol)));
        section(out, "liquidations", symbol, () -> liquidations(out, raw(rawData, "force_orders_map", symbol), nowMs));
        section(out, "futuresBookTop5", symbol, () -> book(out, "futuresBookTop5", raw(rawData, "orderbook_map", symbol)));
        section(out, "spotBookTop5", symbol, () -> book(out, "spotBookTop5", raw(rawData, "spot_orderbook_map", symbol)));
        section(out, "trades3m", symbol, () -> trades(out, symbol, orderFlow));
        return out;
    }

    /** 各段各算各的：哪个数据源的回包不是预期的形状，只丢它那一段，别的段照出 */
    private static void section(ObjectNode out, String field, String symbol, Runnable build) {
        try {
            build.run();
        } catch (RuntimeException e) {
            out.remove(field);
            log.warn("[MarketReadings] {} 的 {} 算不出来: {}", symbol, field, e.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static ArrayNode raw(Map<String, Object> rawData, String key, String symbol) {
        String json = ((Map<String, String>) rawData.getOrDefault(key, Map.of())).get(symbol);
        if (json == null) {
            return null;
        }
        JsonNode node = MAPPER.readTree(json);
        // 资金费和盘口是单个对象，包成一个元素的数组，取法统一
        return node.isArray() ? (ArrayNode) node : MAPPER.createArrayNode().add(node);
    }

    /** 账户多空比：最新一个点的比值与多空账户占比，加这一串点（24 小时）里比值的最低和最高 */
    private static void accountLongShort(ObjectNode out, ArrayNode points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        JsonNode last = points.get(points.size() - 1);
        BigDecimal low = null;
        BigDecimal high = null;
        for (JsonNode p : points) {
            BigDecimal ratio = p.path("longShortRatio").asDecimal(null);
            low = low == null ? ratio : low.min(ratio);
            high = high == null ? ratio : high.max(ratio);
        }
        out.putObject("accountLongShort")
                .put("ratio", last.path("longShortRatio").asDecimal(null))
                .put("longAccount", last.path("longAccount").asDecimal(null))
                .put("shortAccount", last.path("shortAccount").asDecimal(null))
                .put("low24h", low)
                .put("high24h", high)
                .put("time", time(last));
    }

    /** 持仓量：最新一个点的币数和美元值，加 1 小时前、这一串点最早那个（约 4 小时前）的值 */
    private static void openInterest(ObjectNode out, ArrayNode points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        JsonNode last = points.get(points.size() - 1);
        ObjectNode oi = out.putObject("openInterest")
                .put("coins", last.path("sumOpenInterest").asDecimal(null))
                .put("usd", last.path("sumOpenInterestValue").asDecimal(null))
                .put("time", time(last));
        if (points.size() > POINTS_PER_HOUR) {
            JsonNode hourAgo = points.get(points.size() - 1 - POINTS_PER_HOUR);
            oi.put("coins1hAgo", hourAgo.path("sumOpenInterest").asDecimal(null))
                    .put("usd1hAgo", hourAgo.path("sumOpenInterestValue").asDecimal(null));
        }
        JsonNode first = points.get(0);
        oi.put("coinsEarliest", first.path("sumOpenInterest").asDecimal(null))
                .put("usdEarliest", first.path("sumOpenInterestValue").asDecimal(null))
                .put("earliestTime", time(first));
    }

    /** 大户持仓多空比：最新一个点，加这一串点最早那个（约 2 小时前） */
    private static void topTrader(ObjectNode out, ArrayNode points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        JsonNode last = points.get(points.size() - 1);
        JsonNode first = points.get(0);
        out.putObject("topTraderPositionLongShort")
                .put("ratio", last.path("longShortRatio").asDecimal(null))
                .put("time", time(last))
                .put("ratioEarliest", first.path("longShortRatio").asDecimal(null))
                .put("earliestTime", time(first));
    }

    /** 主动买卖：最新 5 分钟的买量、卖量（币数）和比值 */
    private static void takerVolume(ObjectNode out, ArrayNode points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        JsonNode last = points.get(points.size() - 1);
        out.putObject("takerVolume5m")
                .put("buy", last.path("buyVol").asDecimal(null))
                .put("sell", last.path("sellVol").asDecimal(null))
                .put("buySellRatio", last.path("buySellRatio").asDecimal(null))
                .put("time", time(last));
    }

    /** 资金费：当期费率、下次结算时刻、标记价、指数价 */
    private static void funding(ObjectNode out, ArrayNode premium) {
        if (premium == null) {
            return;
        }
        JsonNode p = premium.get(0);
        out.putObject("funding")
                .put("rate", p.path("lastFundingRate").asDecimal(null))
                .put("nextFundingTime", TIME.format(Instant.ofEpochMilli(p.path("nextFundingTime").asLong())))
                .put("markPrice", p.path("markPrice").asDecimal(null))
                .put("indexPrice", p.path("indexPrice").asDecimal(null));
    }

    /**
     * 爆仓：最近 5 分钟和最近 30 分钟各自的多头爆仓额、空头爆仓额（USDT）和笔数。
     * 平台没在采这个币的爆仓（orders 为 null）就不出这一段；在采、这段时间没有记录是 0
     */
    private static void liquidations(ObjectNode out, ArrayNode orders, long nowMs) {
        if (orders == null) {
            return;
        }
        ObjectNode liq = out.putObject("liquidations");
        liq.set("last5m", liquidationWindow(orders, nowMs - 5 * 60_000L));
        liq.set("last30m", liquidationWindow(orders, nowMs - 30 * 60_000L));
    }

    private static ObjectNode liquidationWindow(ArrayNode orders, long sinceMs) {
        BigDecimal longUsd = BigDecimal.ZERO;
        BigDecimal shortUsd = BigDecimal.ZERO;
        int count = 0;
        for (JsonNode o : orders) {
            if (o.path("time").asLong() < sinceMs) {
                continue;
            }
            BigDecimal usd = o.path("price").asDecimal(null).multiply(o.path("origQty").asDecimal(null));
            // 强平单方向 SELL 是多头被平，BUY 是空头被平
            if ("SELL".equals(o.path("side").asString(null))) {
                longUsd = longUsd.add(usd);
            } else {
                shortUsd = shortUsd.add(usd);
            }
            count++;
        }
        return MAPPER.createObjectNode()
                .put("longUsd", longUsd.setScale(0, RoundingMode.HALF_UP))
                .put("shortUsd", shortUsd.setScale(0, RoundingMode.HALF_UP))
                .put("count", count);
    }

    /** 盘口前 5 档的买量合计、卖量合计（币数） */
    private static void book(ObjectNode out, String field, ArrayNode depth) {
        if (depth == null) {
            return;
        }
        out.putObject(field)
                .put("bidQty", sumLevels(depth.get(0).path("bids")))
                .put("askQty", sumLevels(depth.get(0).path("asks")));
    }

    private static BigDecimal sumLevels(JsonNode levels) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < Math.min(levels.size(), BOOK_LEVELS); i++) {
            sum = sum.add(levels.get(i).path(1).asDecimal(null));
        }
        return sum;
    }

    /** 逐笔成交：最近 3 分钟主动买入额、主动卖出额（USDT），其中单笔过大单门槛的各多少 */
    private static void trades(ObjectNode out, String symbol, OrderFlowAggregator orderFlow) {
        if (orderFlow == null || !orderFlow.hasData(symbol)) {
            return;
        }
        OrderFlowAggregator.Metrics m = orderFlow.getMetrics(symbol, TRADES_WINDOW_SECONDS);
        if (m == null || m.tradeCount() < MIN_TRADES) {
            return;
        }
        out.putObject("trades3m")
                .put("takerBuyUsd", Math.round(m.buyUsdt()))
                .put("takerSellUsd", Math.round(m.sellUsdt()))
                .put("largeBuyUsd", Math.round(m.largeBuyUsdt()))
                .put("largeSellUsd", Math.round(m.largeSellUsdt()))
                .put("largeThresholdUsd", (long) OrderFlowAggregator.LARGE_TRADE_USDT)
                .put("count", m.tradeCount());
    }

    private static String time(JsonNode point) {
        return TIME.format(Instant.ofEpochMilli(point.path("timestamp").asLong()));
    }
}
