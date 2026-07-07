package com.mawai.wiibquant.agent.toolkit;

import com.mawai.wiibquant.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibquant.agent.quant.domain.MarketRegime;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragileDirection;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragilityLevel;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragilityScore;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalPanel;

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
        FeatureSnapshot s = new FeatureSnapshot(
                "BTCUSDT", LocalDateTime.now(), new BigDecimal("65000"), null, null, null,
                null, null,
                0.1, null, 4.2, 0,
                0.15, 0.05, 0, 0.1, 0.02,
                0.35, 0, 0, 0.4,
                -0.2, 1_200_000,
                0.3, -0.1, 72, "Greed",
                null, new BigDecimal("350"), null, false,
                52.0, 48.0, -0.05, 0.02,
                MarketRegime.RANGE, null, null, 0.6, "NONE");
        return new MarketAssembly("BTCUSDT", true, Map.of(),
                Map.of("price_change_map", Map.of("24h", "+2.3%")),
                s, SignalPanel.empty(),
                new FragilityScore(61, FragilityLevel.HIGH, 0.7, 0.4, 0.7,
                        FragileDirection.DOWN, "多头拥挤+清算邻近，下行脆弱"),
                Instant.now());
    }
}
