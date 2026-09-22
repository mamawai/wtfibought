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
            Get a real-time cross-section of a crypto perpetual symbol (short-timeframe view, not candles).
            Fields and sign conventions:
            - lastPrice; atr is the 5m atr14 — for stop distances use the atr14 of your own timeframe from `indicators`
            - price_change: {5m,15m,30m,1h,4h,24h} percent change per window
            - regime: a rule-based label on 15m indicators — TREND_UP/TREND_DOWN (adx>25, direction by DI),
              SQUEEZE (adx<15 and Bollinger bandwidth<1.5), SHOCK (recent 5m ATR > 2x its longer average),
              RANGE (everything else); a rule, not a judgement
            - fundingDeviation: (current rate - 0.01%) / 0.03%; positive = longs paying up (crowded long), negative = crowded short
            - oiChangeRate: open-interest change over the last 4h as a raw ratio (0.03 = +3%); positive = money entering, negative = leaving
            - lsrExtreme: percentile of the retail long/short account ratio within the last 24h mapped to [-1,1];
              near +1 = long accounts crowded to an extreme, -1 = short extreme
            - topTraderBias: change in the top traders' position long/short ratio, second half of the last 2h vs the first; positive = adding longs
            - takerPressure: change in the taker buy/sell ratio over the last 2h, positive = buying strengthening;
              tradeDelta: taker-buy share of recent trades mapped to [-1,1], positive = buyers dominate;
              largeTradeBias: direction of large trades, positive = mostly buys
            - liquidationPressure: share of long liquidations among recent liquidations mapped to [-1,1];
              positive = longs being liquidated (selling force), negative = shorts; liquidationVolumeUsdt is the total
            - bidAskImbalance / spotBidAskImbalance: top-5 levels (bid qty - ask qty) / total for futures / spot; positive = thicker bids
            - spotPerpBasisBps: perp premium over spot in basis points; positive = perp trading rich
            - fearGreed: "value(label)", 0 = extreme fear, 100 = extreme greed
            - qualityFlags: each NO_* means that input is missing and its score is 0 — that 0 is "no data", not "neutral"
            Apart from price_change / oiChangeRate / spotPerpBasisBps / atr, scores live in [-1,1]; larger magnitude = more extreme.""")
    public String marketSnapshot(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol) {
        MarketAssembly a = dataService.assemble(symbol);
        if (!a.available()) {
            return unavailableJson(a);
        }
        FeatureSnapshot s = a.snapshot();
        ObjectNode out = MAPPER.createObjectNode();
        out.put("available", true);
        out.put("symbol", s.symbol());
        out.put("lastPrice", s.lastPrice());
        out.put("atr", s.atr());
        out.put("regime", s.regime().name());
        out.set("price_change", MAPPER.valueToTree(a.featureOutput().get("price_change_map")));
        out.put("fundingDeviation", s.fundingDeviation());
        out.put("oiChangeRate", s.oiChangeRate());
        out.put("lsrExtreme", s.lsrExtreme());
        out.put("topTraderBias", s.topTraderBias());
        out.put("takerPressure", s.takerBuySellPressure());
        out.put("liquidationPressure", s.liquidationPressure());
        out.put("liquidationVolumeUsdt", s.liquidationVolumeUsdt());
        out.put("bidAskImbalance", s.bidAskImbalance());
        out.put("spotBidAskImbalance", s.spotBidAskImbalance());
        out.put("tradeDelta", s.tradeDelta());
        out.put("largeTradeBias", s.largeTradeBias());
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
            Get recent funding rate history (last 30 settlements, 8h apart) plus the next funding
            time and current mark price for a crypto perpetual symbol. Useful for carry judgment:
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
