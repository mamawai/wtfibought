package com.mawai.wiibagent.toolkit;

import com.mawai.wiibquant.whale.WhaleQueryService;
import com.mawai.wiibquant.whale.WhaleQueryService.CoinDetail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.StreamSupport;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * Hyperliquid 大户持仓工具（chat 主 agent 挂）：地址池在一个币上的大额杠杆仓位，多空两边的成本与强平分布。
 * 取的是 Coin 页那份最新快照（{@link WhaleQueryService#coin}），分桶只给名义最大的几桶。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhaleToolkit {

    /** 成本 / 强平分桶每边各取名义最大的几桶 */
    static final int TOP_BUCKETS = 5;

    private final WhaleQueryService whales;

    // 描述在词表 tool.whale_positions（见 LocalizedToolCallbacks）
    @Tool(name = "whale_positions")
    public String whalePositions(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol) {
        String coin = symbol.strip().toUpperCase(Locale.ROOT).replaceFirst("USDT$", "");
        CoinDetail d = whales.coin(coin);
        if (d == null) {
            log.info("[WhaleTool] whale_positions {} 无快照", coin);
            ObjectNode out = MAPPER.createObjectNode();
            out.put("available", false);
            out.put("reason", "no whale snapshot for " + coin + " (tracked: BTC / ETH / SOL / XRP / DOGE)");
            return MAPPER.writeValueAsString(out);
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("available", true);
        out.put("observedAt", BeijingTime.format(d.observedAt()));
        out.put("poolSize", d.poolSize());
        out.setAll((ObjectNode) MAPPER.valueToTree(d.view()));
        out.set("entryClusters", clusters(MAPPER.readTree(d.entryBuckets())));
        out.set("liqClusters", clusters(MAPPER.readTree(d.liqBuckets())));
        log.info("[WhaleTool] whale_positions {} 多 {} 空 {}", coin, d.view().longSide().count(), d.view().shortSide().count());
        return MAPPER.writeValueAsString(out);
    }

    /** 桶 JSON {"width":w,"buckets":[[下界,多头名义,空头名义],...]} → 两边各取名义最大的几桶 */
    private static ObjectNode clusters(JsonNode bucketJson) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("bucketWidth", bucketJson.path("width").asDecimal(null));
        o.set("long", top(bucketJson.get("buckets"), 1));
        o.set("short", top(bucketJson.get("buckets"), 2));
        return o;
    }

    private static ArrayNode top(JsonNode buckets, int col) {
        ArrayNode arr = MAPPER.createArrayNode();
        StreamSupport.stream(buckets.spliterator(), false)
                .filter(b -> b.get(col).asDecimal(null).signum() > 0)
                .sorted(Comparator.comparing((JsonNode b) -> b.get(col).asDecimal(null)).reversed())
                .limit(TOP_BUCKETS)
                .forEach(b -> arr.addObject()
                        .put("price", b.get(0).asDecimal(null))
                        .put("notional", b.get(col).asDecimal(null)));
        return arr;
    }
}
