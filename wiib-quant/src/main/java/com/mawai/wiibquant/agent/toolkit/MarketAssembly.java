package com.mawai.wiibquant.agent.toolkit;

import com.mawai.wiibquant.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragilityScore;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalPanel;

import java.time.Instant;
import java.util.Map;

/**
 * 一次市场数据组装的完整产物：采集原始数据 + 特征 + 信号面板 + 脆弱度。
 * available=false 时 snapshot 为 null、signalPanel/fragility 为空壳，工具层据此输出"数据不可用"。
 */
public record MarketAssembly(
        String symbol,
        boolean available,
        Map<String, Object> rawData,
        Map<String, Object> featureOutput,
        FeatureSnapshot snapshot,
        SignalPanel signalPanel,
        FragilityScore fragility,
        Instant assembledAt
) {
    public static MarketAssembly unavailable(String symbol, Map<String, Object> rawData) {
        return new MarketAssembly(symbol, false, rawData == null ? Map.of() : rawData,
                Map.of(), null, SignalPanel.empty(), FragilityScore.empty(), Instant.now());
    }
}
