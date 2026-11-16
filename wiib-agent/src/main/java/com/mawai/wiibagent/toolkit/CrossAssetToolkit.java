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

    // 描述在词表 tool.cross_asset_snapshot（见 LocalizedToolCallbacks）
    @Tool(name = "cross_asset_snapshot")
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
