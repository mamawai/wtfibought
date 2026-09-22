package com.mawai.wiibagent.toolkit;
import com.mawai.wiibquant.market.service.MarketAssembly;

import com.mawai.wiibquant.market.domain.FeatureSnapshot;
import com.mawai.wiibquant.market.domain.MarketRegime;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

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
                "BTCUSDT", LocalDateTime.now(), new BigDecimal("65000"), null, null, null,
                null, null,
                0.1, null, 4.2, 0,
                0.15, 0.05, 0, 0.1, 0.02,
                0.35, 0, 0, 0.4,
                -0.2, 1_200_000,
                0.3, -0.1, 72, "Greed",
                null, new BigDecimal("350"), null, false,
                dvolIndex, atmIv, -0.05, 0.02,
                MarketRegime.RANGE, null);
        return new MarketAssembly("BTCUSDT", true, Map.of(),
                Map.of("price_change_map", Map.of("24h", "+2.3%")),
                s, Instant.now());
    }
}
