package com.mawai.wiibquant.agent.toolkit;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibquant.agent.quant.domain.FeatureSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 市场状态工具：实时快照 / 期权IV / 脆弱度，全部走 MarketDataService 共享组装（60s缓存），
 * 一轮对话内多工具调用不重复采集。
 */
@Component
@RequiredArgsConstructor
public class MarketToolkit {

    private final MarketDataService dataService;

    @Tool(name = "market_snapshot", description = """
            Get real-time market snapshot for a crypto perpetual symbol: price, price changes,
            funding deviation, open-interest change, long/short ratios, top-trader bias, taker pressure,
            liquidation pressure, orderbook imbalance, fear&greed index, regime.
            All signal fields are normalized scores in [-1,1] unless stated otherwise.""")
    public String marketSnapshot(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol) {
        MarketAssembly a = dataService.assemble(symbol);
        if (!a.available()) {
            return unavailableJson(a);
        }
        FeatureSnapshot s = a.snapshot();
        JSONObject out = new JSONObject();
        out.put("available", true);
        out.put("symbol", s.symbol());
        out.put("lastPrice", s.lastPrice());
        out.put("atr", s.atr());
        out.put("regime", s.regime().name());
        out.put("price_change", a.featureOutput().get("price_change_map"));
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
            out.put("qualityFlags", s.qualityFlags());
        }
        return out.toJSONString();
    }

    @Tool(name = "option_iv", description = """
            Get option implied-volatility context for a crypto symbol from Deribit:
            DVOL index and ATM IV summary. Useful for judging whether the options market
            is pricing in larger moves than realized volatility suggests.""")
    public String optionIv(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol) {
        MarketAssembly a = dataService.assemble(symbol);
        if (!a.available()) {
            return unavailableJson(a);
        }
        JSONObject out = new JSONObject();
        out.put("available", true);
        out.put("symbol", a.snapshot().symbol());
        out.put("dvolIndex", a.snapshot().dvolIndex());
        out.put("ivSummary", a.snapshot().toIvSummary());
        return out.toJSONString();
    }

    @Tool(name = "fragility", description = """
            Get the deterministic market fragility score (0-100) for a crypto symbol,
            composed of positioning crowdedness + deleveraging intensity + vol-state.
            Includes fragile direction (which way the market breaks easier - a structural
            inference, NOT a directional prediction) and a one-line headline.""")
    public String fragility(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol) {
        MarketAssembly a = dataService.assemble(symbol);
        if (!a.available()) {
            return unavailableJson(a);
        }
        JSONObject out = (JSONObject) JSON.toJSON(a.fragility());
        out.put("available", true);
        return out.toJSONString();
    }

    private static String unavailableJson(MarketAssembly a) {
        JSONObject out = new JSONObject();
        out.put("available", false);
        out.put("reason", "market data unavailable for " + a.symbol());
        return out.toJSONString();
    }
}
