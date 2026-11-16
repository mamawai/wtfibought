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

    // 本类工具的描述都在词表 tool.<工具名>（见 LocalizedToolCallbacks）
    @Tool(name = "market_snapshot")
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

    @Tool(name = "option_iv")
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

    @Tool(name = "funding_history")
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

    @Tool(name = "orderbook_depth")
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
