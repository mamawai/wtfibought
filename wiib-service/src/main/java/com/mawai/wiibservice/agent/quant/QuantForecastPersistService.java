package com.mawai.wiibservice.agent.quant;

import com.alibaba.fastjson2.JSON;
import com.mawai.wiibcommon.entity.*;
import com.mawai.wiibservice.agent.quant.domain.*;
import com.mawai.wiibservice.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuantForecastPersistService {

    private final QuantForecastCycleMapper cycleMapper;
    private final QuantAgentVoteMapper voteMapper;
    private final QuantHorizonForecastMapper horizonMapper;
    private final QuantSignalDecisionMapper decisionMapper;

    @Transactional
    public void persist(ForecastResult result) {
        persist(result, null, null, null);
    }

    @Transactional
    public void persist(ForecastResult result, String debateSummary) {
        persist(result, debateSummary, null, null);
    }

    @Transactional
    public void persist(ForecastResult result, String debateSummary,
                        String rawSnapshotJson, String rawReportJson) {
        if (result == null || result.cycleId() == null) return;

        try {
            saveCycle(result, debateSummary, rawSnapshotJson, rawReportJson);
            saveVotes(result.cycleId(), result.allVotes());
            saveHorizonForecasts(result.cycleId(), result.horizons());
            saveSignalDecisions(result.cycleId(), result.horizons(), result.riskStatus());
            log.info("[Q7] 预测结果落库完成 cycleId={}", result.cycleId());
        } catch (Exception e) {
            log.error("[Q7] 预测结果落库失败 cycleId={}", result.cycleId(), e);
        }
    }

    private void saveCycle(ForecastResult r, String debateSummary,
                           String rawSnapshotJson, String rawReportJson) {
        QuantForecastCycle cycle = new QuantForecastCycle();
        cycle.setCycleId(r.cycleId());
        cycle.setParentCycleId(r.parentCycleId());   // 轻周期存父重周期 id；重周期为 null
        cycle.setSymbol(r.symbol());
        cycle.setForecastTime(r.forecastTime());
        cycle.setOverallDecision(r.overallDecision());
        cycle.setRiskStatus(r.riskStatus());
        if (rawSnapshotJson != null && !rawSnapshotJson.isBlank()) {
            cycle.setSnapshotJson(rawSnapshotJson);
        } else if (r.snapshot() != null) {
            cycle.setSnapshotJson(JSON.toJSONString(r.snapshot()));
        }
        if (rawReportJson != null && !rawReportJson.isBlank()) {
            cycle.setReportJson(rawReportJson);
        } else if (r.report() != null) {
            cycle.setReportJson(JSON.toJSONString(r.report()));
        }
        if (debateSummary != null) {
            cycle.setDebateJson(debateSummary);
        }
        cycleMapper.insert(cycle);
    }

    private void saveVotes(String cycleId, List<AgentVote> votes) {
        if (votes == null) return;
        for (AgentVote v : votes) {
            QuantAgentVote entity = new QuantAgentVote();
            entity.setCycleId(cycleId);
            entity.setAgent(v.agent());
            entity.setHorizon(v.horizon());
            entity.setDirection(v.direction().name());
            entity.setScore(BigDecimal.valueOf(v.score()));
            entity.setConfidence(BigDecimal.valueOf(v.confidence()));
            entity.setExpectedMoveBps(v.expectedMoveBps());
            entity.setVolatilityBps(v.volatilityBps());
            entity.setReasonCodes(String.join(",", v.reasonCodes()));
            entity.setRiskFlags(String.join(",", v.riskFlags()));
            voteMapper.insert(entity);
        }
    }

    private void saveHorizonForecasts(String cycleId, List<HorizonForecast> forecasts) {
        if (forecasts == null) return;
        for (HorizonForecast f : forecasts) {
            QuantHorizonForecast entity = new QuantHorizonForecast();
            entity.setCycleId(cycleId);
            entity.setHorizon(f.horizon());
            entity.setDirection(f.direction().name());
            entity.setConfidence(BigDecimal.valueOf(f.confidence()));
            entity.setWeightedScore(BigDecimal.valueOf(f.weightedScore()));
            entity.setDisagreement(BigDecimal.valueOf(f.disagreement()));
            entity.setEntryLow(f.entryLow());
            entity.setEntryHigh(f.entryHigh());
            entity.setInvalidationPrice(f.invalidationPrice());
            entity.setTp1(f.tp1());
            entity.setTp2(f.tp2());
            entity.setMaxLeverage(f.maxLeverage());
            entity.setMaxPositionPct(BigDecimal.valueOf(f.maxPositionPct()));
            horizonMapper.insert(entity);
        }
    }

    private void saveSignalDecisions(String cycleId, List<HorizonForecast> forecasts, String riskStatus) {
        if (forecasts == null) return;
        for (HorizonForecast f : forecasts) {
            QuantSignalDecision entity = new QuantSignalDecision();
            entity.setCycleId(cycleId);
            entity.setHorizon(f.horizon());
            entity.setDirection(f.direction().name());
            entity.setConfidence(BigDecimal.valueOf(f.confidence()));
            entity.setMaxLeverage(f.maxLeverage());
            entity.setMaxPositionPct(BigDecimal.valueOf(f.maxPositionPct()));
            entity.setRiskStatus(riskStatus);
            decisionMapper.insert(entity);
        }
    }
}
