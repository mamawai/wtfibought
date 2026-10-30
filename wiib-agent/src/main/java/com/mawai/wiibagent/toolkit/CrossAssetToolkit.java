package com.mawai.wiibagent.toolkit;

import com.mawai.wiibquant.market.service.CrossAssetService;
import com.mawai.wiibquant.market.service.CrossAssetService.Quote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 跨资产快照工具（chat 主 agent 挂）：美股、美元、美债、VIX、黄金、原油的现价和涨跌，取 CrossAssetService 缓存的那份。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrossAssetToolkit {

    private final CrossAssetService crossAssets;

    // 描述按语言取自词表 tool.cross_asset_snapshot，注解这份只当词表缺失时的兜底
    @Tool(name = "cross_asset_snapshot", description = """
            Macro cross-asset snapshot (Yahoo Finance): S&P 500 and Nasdaq Composite (cash indices, move
            only in US hours), S&P 500 and Nasdaq 100 futures (trade nearly 24h), US Dollar Index, US
            10-year Treasury yield (in %), VIX, gold futures (USD/oz) and WTI crude futures (USD/bbl).
            Per instrument: price, changePct1d = change vs the previous close in %, changePct5d = change vs
            the close 5 sessions ago in %, time = quote time (UTC+8). For the 10Y yield the changes are %
            moves of the yield level itself, not basis points. Compare time with now: outside US hours a
            cash index is just the last close. Unofficial source; quotes may lag by up to 15 minutes.
            Gold and oil also trade as platform contracts (XAUUSDT / CLUSDT); that is the platform contract
            price, looked up with market_snapshot.""")
    public String crossAssetSnapshot() {
        List<Quote> quotes = crossAssets.snapshot();
        log.info("[CrossAssetTool] cross_asset_snapshot {} 个品种", quotes.size());
        ObjectNode out = MAPPER.createObjectNode();
        if (quotes.isEmpty()) {
            out.put("available", false);
            out.put("reason", "cross-asset quotes unavailable");
            return MAPPER.writeValueAsString(out);
        }
        out.put("available", true);
        out.put("timezone", "UTC+8");
        out.put("now", BeijingTime.format(System.currentTimeMillis()));
        ArrayNode arr = out.putArray("quotes");
        for (Quote q : quotes) {
            arr.addObject()
                    .put("symbol", q.symbol())
                    .put("name", q.name())
                    .put("price", q.price())
                    .put("changePct1d", q.changePct1d())
                    .put("changePct5d", q.changePct5d())
                    .put("time", BeijingTime.format(q.timeMs()));
        }
        return MAPPER.writeValueAsString(out);
    }
}
