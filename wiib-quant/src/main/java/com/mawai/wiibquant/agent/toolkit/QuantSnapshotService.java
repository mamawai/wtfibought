package com.mawai.wiibquant.agent.toolkit;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.QuantSnapshot;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.agent.quant.service.ResearchFeatureAssembler;
import com.mawai.wiibquant.agent.research.ForecastHorizon;
import com.mawai.wiibquant.agent.research.forecast.MultiOutputForecast;
import com.mawai.wiibquant.agent.research.forecast.QuantCoreForecaster;
import com.mawai.wiibquant.agent.research.forecast.VolStateClassifier;
import com.mawai.wiibquant.agent.research.forecast.VolatilityRiskContext;
import com.mawai.wiibquant.mapper.QuantSnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 数值快照服务：每 5m 产一行零 LLM 预测点（vol 三腿 + vol-state + regime + 脆弱度 + 信号面板）。
 * vol-state 档界(lowCut/highCut)按预测时点历史算好随快照入库——PIT 铁律，验证侧(P3)禁止重算档界。
 * direction 腿在此丢弃（方向无 edge，不落库）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuantSnapshotService {

    private static final Duration HISTORY = Duration.ofDays(90);
    // 30 天 5m，与 MacroContextService.MIN_HISTORY 同口径
    private static final int MIN_BARS = 30 * 24 * 12;

    private final MarketDataService marketDataService;
    private final KlineHistoryStore historyStore;
    private final ResearchFeatureAssembler featureAssembler;
    private final QuantSnapshotMapper mapper;

    /** 组装一行快照；数据不足返回 null（当根 bar 跳过，不抛异常打断调度）。 */
    public QuantSnapshot buildSnapshot(String symbol, long closeTime) {
        MarketAssembly assembly = marketDataService.assemble(symbol);
        if (!assembly.available()) {
            log.warn("[Snapshot] 市场组装不可用 symbol={}", symbol);
            return null;
        }
        long toTime = closeTime > 0 ? closeTime + 1 : System.currentTimeMillis();
        List<KlineBar> bars = historyStore.load(symbol, KlineHistoryStore.DEFAULT_INTERVAL,
                toTime - HISTORY.toMillis(), toTime);
        if (bars.size() < MIN_BARS) {
            log.warn("[Snapshot] 历史不足 symbol={} bars={} required={}", symbol, bars.size(), MIN_BARS);
            return null;
        }
        var assembled = featureAssembler.assemble(symbol, bars);

        JSONObject legsJson = new JSONObject();
        String h6Regime = null;
        Double h6RegimeConf = null;
        for (ForecastHorizon horizon : ForecastHorizon.values()) {
            MultiOutputForecast f = QuantCoreForecaster.defaults(horizon).forecast(assembled.features());
            var vol = f.volatilityContext();
            // 档界与分类同一真相源：预测 sigma 对着历史 |horizon return| 分布（research 既有口径）
            double[] absReturns = VolatilityRiskContext.absoluteHorizonReturns(bars, horizon);
            VolStateClassifier classifier = VolStateClassifier.fromHistory(absReturns);
            JSONObject leg = new JSONObject();
            leg.put("sigmaBps", vol.expectedMoveBps());
            leg.put("percentile", vol.trailingPercentile());
            leg.put("tier", vol.riskTier().name());
            leg.put("volState", classifier.classify(vol.expectedVolatility()).name());
            leg.put("lowCut", classifier.lowCut());
            leg.put("highCut", classifier.highCut());
            leg.put("regime", f.regime().name());
            leg.put("regimeConfidence", f.regimeConfidence());
            legsJson.put(horizon.name(), leg);
            if (horizon == ForecastHorizon.H6) {
                h6Regime = f.regime().name();
                h6RegimeConf = f.regimeConfidence();
            }
        }

        QuantSnapshot snap = new QuantSnapshot();
        snap.setSymbol(symbol);
        snap.setCloseTime(closeTime);
        snap.setLastPrice(assembly.snapshot().lastPrice());
        snap.setVolLegsJson(legsJson.toJSONString());
        snap.setRegime(h6Regime);
        snap.setRegimeConfidence(h6RegimeConf);
        snap.setFragilityScore(assembly.fragility().score());
        snap.setFragilityLevel(assembly.fragility().level().name());
        snap.setFragilityDirection(assembly.fragility().direction().name());
        snap.setFragilityHeadline(assembly.fragility().headline());
        snap.setSignalPanelJson(JSON.toJSONString(assembly.signalPanel()));
        snap.setQualityFlagsJson(JSON.toJSONString(assembled.qualityFlags()));
        snap.setCreatedAt(LocalDateTime.now());
        return snap;
    }

    /** 幂等落库：(symbol, closeTime) 唯一键冲突时返回已存在行 id。 */
    public Long persist(QuantSnapshot snap) {
        try {
            mapper.insert(snap);
            return snap.getId();
        } catch (DuplicateKeyException e) {
            QuantSnapshot existing = mapper.selectOne(new LambdaQueryWrapper<QuantSnapshot>()
                    .eq(QuantSnapshot::getSymbol, snap.getSymbol())
                    .eq(QuantSnapshot::getCloseTime, snap.getCloseTime()));
            log.info("[Snapshot] 重复快照跳过 symbol={} closeTime={}", snap.getSymbol(), snap.getCloseTime());
            return existing != null ? existing.getId() : null;
        }
    }
}
