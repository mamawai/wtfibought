package com.mawai.wiibservice.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.mawai.wiibservice.agent.quant.domain.*;
import com.mawai.wiibservice.agent.quant.risk.RiskGate;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 风控节点：对裁决结果做硬规则裁剪。
 */
@Slf4j
public class RiskGateNode implements NodeAction {

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) {
        long startMs = System.currentTimeMillis();
        List<HorizonForecast> forecasts =
                (List<HorizonForecast>) state.value("horizon_forecasts").orElse(List.of());
        FeatureSnapshot snapshot =
                (FeatureSnapshot) state.value("feature_snapshot").orElse(null);
        String upstreamRiskStatus = (String) state.value("risk_status").orElse("UNKNOWN");

        MarketRegime regime = snapshot != null ? snapshot.regime() : MarketRegime.RANGE;
        var atr5m = snapshot != null ? snapshot.atr5m() : null;
        var lastPrice = snapshot != null ? snapshot.lastPrice() : null;
        var qualityFlags = snapshot != null ? snapshot.qualityFlags() : List.<String>of();
        int fearGreedIndex = snapshot != null ? snapshot.fearGreedIndex() : -1;

        log.info("[Q5.0] risk_gate开始 regime={} forecasts={} qualityFlags={} fearGreed={} upstreamRiskStatus={}",
                regime, forecasts.size(), qualityFlags, fearGreedIndex, upstreamRiskStatus);

        RiskGate.RiskResult result = RiskGate.apply(forecasts, regime, atr5m, lastPrice, qualityFlags, fearGreedIndex);
        String mergedRiskStatus = mergeRiskStatus(upstreamRiskStatus, result.riskStatus());

        for (HorizonForecast f : result.forecasts()) {
            log.info("[Q5.result] {} → {} conf={} lev={}x pos={}%",
                    f.horizon(), f.direction(),
                    String.format("%.2f", f.confidence()), f.maxLeverage(),
                    String.format("%.1f", f.maxPositionPct() * 100));
        }
        log.info("[Q5.end] risk_gate完成 status={} mergedStatus={} 耗时{}ms",
                result.riskStatus(), mergedRiskStatus, System.currentTimeMillis() - startMs);

        Map<String, Object> out = new HashMap<>();
        out.put("horizon_forecasts", result.forecasts());
        out.put("risk_status", mergedRiskStatus);
        return out;
    }

    private String mergeRiskStatus(String upstreamRiskStatus, String currentRiskStatus) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        addRiskParts(parts, upstreamRiskStatus);
        addRiskParts(parts, currentRiskStatus);
        if (parts.isEmpty()) {
            return "NORMAL";
        }
        if (parts.size() > 1) {
            parts.remove("NORMAL");
            parts.remove("UNKNOWN");
        }
        return parts.isEmpty() ? "NORMAL" : String.join(",", parts);
    }

    private void addRiskParts(LinkedHashSet<String> parts, String riskStatus) {
        if (riskStatus == null || riskStatus.isBlank()) {
            return;
        }
        for (String part : riskStatus.split(",")) {
            String normalized = part.trim();
            if (!normalized.isEmpty()) {
                parts.add(normalized);
            }
        }
    }
}
