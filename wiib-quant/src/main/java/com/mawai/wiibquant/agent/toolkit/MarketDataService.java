package com.mawai.wiibquant.agent.toolkit;

import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.DepthStreamCache;
import com.mawai.wiibcommon.market.ForceOrderService;
import com.mawai.wiibcommon.market.OrderFlowAggregator;
import com.mawai.wiibquant.agent.quant.domain.AgentVote;
import com.mawai.wiibquant.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibquant.agent.quant.domain.MacroContext;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragilityScore;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalPanel;
import com.mawai.wiibquant.agent.quant.factor.FactorAgent;
import com.mawai.wiibquant.agent.quant.factor.FactorEvaluationContext;
import com.mawai.wiibquant.agent.quant.factor.MicrostructureAgent;
import com.mawai.wiibquant.agent.quant.factor.MomentumAgent;
import com.mawai.wiibquant.agent.quant.factor.RegimeAgent;
import com.mawai.wiibquant.agent.quant.factor.VolatilityAgent;
import com.mawai.wiibquant.agent.quant.judge.FragilityScorer;
import com.mawai.wiibquant.agent.quant.node.BuildFeaturesNode;
import com.mawai.wiibquant.agent.quant.node.CollectDataNode;
import com.mawai.wiibquant.agent.quant.service.MacroContextService;
import com.mawai.wiibquant.agent.quant.signal.SignalExtractor;
import com.mawai.wiibquant.config.DeribitClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 市场数据组装服务：采集 → 特征 → 规则信号 → 脆弱度 一条链，带 TTL 缓存。
 * 工具层（对话 agent / MCP）与后续定时轨快照段共用这条链，避免各处重复采集。
 * 只用 4 个纯规则 agent（新闻 agent 是 LLM，属对话/深研判轨，不进这里）。
 */
@Slf4j
@Service
public class MarketDataService {

    private final CollectDataNode collectNode;
    private final BuildFeaturesNode featuresNode;
    private final MacroContextService macroContextService;
    private final long ttlMillis;
    private final List<FactorAgent> ruleAgents = List.of(
            new MicrostructureAgent(), new MomentumAgent(), new RegimeAgent(), new VolatilityAgent());
    private final Map<String, MarketAssembly> cache = new ConcurrentHashMap<>();

    public MarketDataService(BinanceRestClient binanceRestClient,
                             ForceOrderService forceOrderService,
                             DepthStreamCache depthStreamCache,
                             DeribitClient deribitClient,
                             OrderFlowAggregator orderFlowAggregator,
                             MacroContextService macroContextService,
                             @Value("${trading.decision-interval:M5}") KlineInterval decisionInterval,
                             @Value("${quant.toolkit.assembly-ttl-ms:60000}") long ttlMillis) {
        this.collectNode = new CollectDataNode(binanceRestClient, forceOrderService, depthStreamCache, deribitClient);
        this.featuresNode = new BuildFeaturesNode(orderFlowAggregator, decisionInterval);
        this.macroContextService = macroContextService;
        this.ttlMillis = ttlMillis;
    }

    /** 工具层统一入口：TTL 内直接复用，避免一轮对话多个工具各采一遍。 */
    public MarketAssembly assemble(String symbol) {
        String normalized = QuantConstants.normalizeSymbolLenient(symbol);
        MarketAssembly cached = cache.get(normalized);
        if (cached != null && Instant.now().toEpochMilli() - cached.assembledAt().toEpochMilli() < ttlMillis) {
            return cached;
        }
        MarketAssembly fresh = assembleFresh(normalized);
        cache.put(normalized, fresh);
        return fresh;
    }

    MarketAssembly assembleFresh(String symbol) {
        long startMs = System.currentTimeMillis();
        Map<String, Object> raw = collectNode.collect(symbol, null);
        if (!Boolean.TRUE.equals(raw.get("data_available"))) {
            log.warn("[Toolkit] 采集不可用 symbol={}", symbol);
            return MarketAssembly.unavailable(symbol, raw);
        }
        Map<String, Object> featureOut = featuresNode.buildFeatures(symbol, raw);
        FeatureSnapshot snapshot = (FeatureSnapshot) featureOut.get("feature_snapshot");
        if (snapshot == null) {
            return MarketAssembly.unavailable(symbol, raw);
        }
        // 规则 agent 的 flag 经 SignalExtractor 重组成可读信号面板，再喂脆弱度（与旧 evidence 链同口径）
        MacroContext research = macroContextService.computeNow(symbol, 0);
        FactorEvaluationContext ctx = new FactorEvaluationContext(research);
        List<AgentVote> votes = new ArrayList<>();
        for (FactorAgent agent : ruleAgents) {
            try {
                votes.addAll(agent.evaluate(snapshot, ctx));
            } catch (Exception e) {
                log.warn("[Toolkit] 规则agent失败 agent={} msg={}", agent.name(), e.getMessage());
            }
        }
        SignalPanel panel = new SignalExtractor().extract(votes);
        FragilityScore fragility = new FragilityScorer().score(snapshot, panel);
        log.info("[Toolkit] 组装完成 symbol={} votes={} fragility={} 耗时{}ms",
                symbol, votes.size(), fragility.score(), System.currentTimeMillis() - startMs);
        return new MarketAssembly(symbol, true, raw, featureOut, snapshot, panel, fragility, Instant.now());
    }

}
