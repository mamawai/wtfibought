package com.mawai.wiibagent.toolkit;
import com.mawai.wiibquant.market.service.MarketAssembly;
import com.mawai.wiibquant.market.service.MarketDataService;

import com.mawai.wiibquant.market.domain.FeatureSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 市场状态工具：实时快照 / 期权IV 走 MarketDataService 共享组装缓存；
 * 盘口深度优先取 WS 快照，断流才回退 REST 缓存（老化粒度独立于快照）。
 *
 * <p>本类所有取数一律经 MarketDataService，自己不直连 REST——ReAct 循环里工具会被反复调，
 * 裸奔的真请求既吃配额又绕开熔断兜底。算钱的路径（下单量/结算价）另有直调
 * BinanceRestClient 的实时通道，不受这层缓存影响。</p>
 */
@Component
@RequiredArgsConstructor
public class MarketToolkit {

    /** orderbook_depth 的 tool description 承诺 top10，输出前按这个档数截齐 */
    private static final int DEPTH_LEVELS = 10;

    private final MarketDataService dataService;

    @Tool(name = "market_snapshot", description = """
            Get a real-time cross-section of a crypto perpetual symbol: the latest raw reading from each data
            source, with no scoring. A section that is absent means that source was not available this time.
            Times are UTC+8.
            - asOf: when this snapshot was collected (it is cached for up to a minute)
            - lastPrice; atr is the 5m atr14 — for stop distances use the atr14 of your own timeframe from `indicators`
            - price_change: {5m,15m,30m,1h,4h,24h} percent change per window
            - accountLongShort: Binance long/short account ratio, latest 5m point: ratio, longAccount and shortAccount
              (shares of accounts), plus low24h / high24h = the lowest and highest ratio over the last 24h
            - openInterest: latest 5m point in coins and usd, the same one hour earlier (coins1hAgo / usd1hAgo) and
              at the earliest point fetched, about 4h back (coinsEarliest / usdEarliest, earliestTime)
            - topTraderPositionLongShort: top traders' position long/short ratio, latest 5m point, and the earliest
              point fetched, about 2h back (ratioEarliest, earliestTime)
            - takerVolume5m: taker buy and sell volume (in coins) and their ratio over the latest 5m
            - funding: current rate as a raw decimal per settlement (0.0001 = 0.01%), nextFundingTime, markPrice, indexPrice
            - liquidations: last5m and last30m, each with longUsd (longs liquidated), shortUsd (shorts liquidated) and
              count. Binance only; its liquidation stream is sampled, not complete, so the amounts are a lower
              bound and fall further short the faster the market moves
            - futuresBookTop5 / spotBookTop5: total bid quantity and ask quantity (coins) over the top 5 levels
            - trades3m: taker buy and taker sell notional (USDT) over the last 3 minutes, how much of each came from
              single trades above largeThresholdUsd (the same threshold for every coin), and the trade count;
              only for coins the platform streams trades for
            - spotPerpBasisBps: perp premium over spot in basis points; positive = perp trading rich
            - fearGreed: "value(label)", 0 = extreme fear, 100 = extreme greed
            - qualityFlags: each NO_* names an input that is missing
            To see how open interest and the ratios moved over time, use derivatives_history.""")
    public String marketSnapshot(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol) {
        MarketAssembly a = dataService.assemble(symbol);
        if (!a.available()) {
            return unavailableJson(a);
        }
        FeatureSnapshot s = a.snapshot();
        ObjectNode out = MAPPER.createObjectNode();
        out.put("available", true);
        out.put("symbol", s.symbol());
        out.put("asOf", BeijingTime.format(a.assembledAt().toEpochMilli()));
        out.put("lastPrice", s.lastPrice());
        out.put("atr", s.atr());
        out.set("price_change", MAPPER.valueToTree(a.featureOutput().get("price_change_map")));
        out.setAll((ObjectNode) a.featureOutput().get("readings"));
        out.put("spotPerpBasisBps", s.spotPerpBasisBps());
        out.put("fearGreed", s.fearGreedIndex() + "(" + s.fearGreedLabel() + ")");
        if (!s.qualityFlags().isEmpty()) {
            out.set("qualityFlags", MAPPER.valueToTree(s.qualityFlags()));
        }
        return MAPPER.writeValueAsString(out);
    }

    @Tool(name = "option_iv", description = """
            Get option implied-volatility context for a crypto symbol from Deribit.
            Coverage: BTC/ETH have the DVOL index plus ATM IV; SOL/XRP have ATM IV only
            (USDC-settled options, no DVOL); other symbols have no option data.
            - dvolIndex: Deribit's 30-day implied volatility index (annualized %), present only for BTC/ETH
            - ivSummary: ATM_IV = mark IV of the call nearest the money in the nearest expiry
              more than a day away, annualized %;
              25d_skew = avg IV of calls 5-8% OTM minus avg IV of puts 5-8% OTM
              (positive = calls richer; 0 = not enough strikes to compute);
              term_slope = ATM IV of the next expiry minus the nearest (positive = further expiry priced higher)
            Useful for judging whether the options market is pricing in larger moves than realized volatility suggests.""")
    public String optionIv(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol) {
        MarketAssembly a = dataService.assemble(symbol);
        if (!a.available()) {
            return unavailableJson(a);
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("available", true);
        out.put("symbol", a.snapshot().symbol());
        // 没有 DVOL 的币（SOL/XRP）不出这个字段，0 不是读数
        if (a.snapshot().dvolIndex() > 0) {
            out.put("dvolIndex", a.snapshot().dvolIndex());
        }
        out.put("ivSummary", a.snapshot().toIvSummary("no data"));
        return MAPPER.writeValueAsString(out);
    }

    @Tool(name = "funding_history", description = """
            Get recent funding rate history (last 30 settlements; the interval is per contract, 8h for
            most, 4h or 1h for some) plus the next funding time and current mark price for a crypto
            perpetual symbol. Rates are raw decimals per settlement: 0.0001 = 0.01%. Useful for carry judgment:
            persistently positive funding = longs paying shorts (crowded long), negative = the opposite.""")
    public String fundingHistory(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol) {
        String raw = dataService.fundingHistory(symbol);
        if (raw == null) {
            return errorJson("funding data unavailable");
        }
        try {
            ObjectNode out = MAPPER.createObjectNode();
            out.put("available", true);
            ArrayNode compact = MAPPER.createArrayNode();
            for (JsonNode h : MAPPER.readValue(raw, ArrayNode.class)) {
                ObjectNode row = MAPPER.createObjectNode();
                row.put("time", h.hasNonNull("fundingTime") ? h.get("fundingTime").asLong() : null);
                row.put("rate", h.path("fundingRate").asString(null));
                compact.add(row);
            }
            out.set("history", compact);
            // 先判空再解析：取不到且没有过期缓存可兜时这里给 null，readTree(null) 直接抛参数异常，
            // 异常信息会顺着 catch 喂给模型
            String premiumRaw = dataService.premiumIndex(symbol);
            if (premiumRaw == null) {
                return errorJson("funding data unavailable");
            }
            JsonNode premium = MAPPER.readTree(premiumRaw);
            out.put("nextFundingTime", premium.hasNonNull("nextFundingTime") ? premium.get("nextFundingTime").asLong() : null);
            out.put("lastFundingRate", premium.path("lastFundingRate").asString(null));
            out.put("markPrice", premium.path("markPrice").asString(null));
            return MAPPER.writeValueAsString(out);
        } catch (Exception e) {
            return errorJson("funding data unavailable: " + e.getMessage());
        }
    }

    @Tool(name = "orderbook_depth", description = """
            Get the top 10 bid/ask levels (price, quantity) of the futures orderbook for a crypto
            perpetual symbol. Useful for seeing where large resting orders (walls) sit relative to
            current price when choosing limit order placement or judging near support/resistance.""")
    public String orderbookDepth(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol) {
        String raw = dataService.orderbook(symbol);
        if (raw == null) {
            return errorJson("orderbook unavailable");
        }
        try {
            JsonNode book = MAPPER.readTree(raw);
            ObjectNode out = MAPPER.createObjectNode();
            out.put("available", true);
            // 数据源档数不定（WS 快照是 top20，REST 兜底是 top10），这里统一截到工具描述承诺的 10 档
            out.set("bids", topLevels(book.get("bids")));
            out.set("asks", topLevels(book.get("asks")));
            return MAPPER.writeValueAsString(out);
        } catch (Exception e) {
            return errorJson("orderbook unavailable: " + e.getMessage());
        }
    }

    private static JsonNode topLevels(JsonNode levels) {
        if (levels == null || levels.size() <= DEPTH_LEVELS) {
            return levels;
        }
        ArrayNode top = MAPPER.createArrayNode();
        for (int i = 0; i < DEPTH_LEVELS; i++) {
            top.add(levels.get(i));
        }
        return top;
    }

    private static String errorJson(String reason) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("available", false);
        out.put("reason", reason);
        return MAPPER.writeValueAsString(out);
    }

    private static String unavailableJson(MarketAssembly a) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("available", false);
        out.put("reason", "market data unavailable for " + a.symbol());
        return MAPPER.writeValueAsString(out);
    }
}
