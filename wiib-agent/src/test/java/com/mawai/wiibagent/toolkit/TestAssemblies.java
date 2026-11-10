package com.mawai.wiibagent.toolkit;
import com.mawai.wiibquant.market.service.MarketAssembly;

import com.mawai.wiibquant.market.domain.FeatureSnapshot;

import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/** 测试共用的 MarketAssembly 造件：record 无法可靠 mock（accessor 直通），统一真实构造。 */
final class TestAssemblies {

    private TestAssemblies() {
    }

    /** 可用 assembly：只填工具/快照输出关心的字段，其余 0/null 由 compact constructor 兜底。 */
    static MarketAssembly available() {
        return withIv(52.0, 48.0);
    }

    /** 期权面可调的版本：dvolIndex=0 模拟 SOL/XRP 这类只有期权簿、没有 DVOL 的币 */
    static MarketAssembly withIv(double dvolIndex, double atmIv) {
        FeatureSnapshot s = new FeatureSnapshot(
                "BTCUSDT", LocalDateTime.now(), new BigDecimal("65000"),
                null, null, 4.2,
                72, "Greed", new BigDecimal("350"),
                dvolIndex, atmIv, -0.05, 0.02, null);
        // 原始读数只放两段，够看工具把它们原样并进输出
        ObjectNode readings = MAPPER.createObjectNode();
        readings.putObject("accountLongShort").put("ratio", new BigDecimal("1.13"));
        readings.putObject("liquidations").putObject("last30m").put("longUsd", 562).put("shortUsd", 99).put("count", 4);
        return new MarketAssembly("BTCUSDT", true, Map.of(),
                Map.of("price_change_map", Map.of("24h", "+2.3%"), "readings", readings),
                s, Instant.now());
    }
}
