package com.mawai.wiibservice.agent.quant;

import com.alibaba.fastjson2.JSON;
import com.mawai.wiibservice.agent.quant.domain.*;
import com.mawai.wiibservice.agent.quant.factor.*;
import com.mawai.wiibservice.agent.quant.judge.ConsensusBuilder;
import com.mawai.wiibservice.agent.quant.judge.HorizonJudge;
import com.mawai.wiibservice.agent.quant.memory.MemoryService;
import com.mawai.wiibservice.agent.quant.node.BuildFeaturesNode;
import com.mawai.wiibservice.agent.quant.node.CollectDataNode;
import com.mawai.wiibservice.agent.quant.risk.RiskGate;
import com.mawai.wiibservice.agent.quant.domain.QuantCycleCompleteEvent;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.config.DeribitClient;
import com.mawai.wiibservice.service.DepthStreamCache;
import com.mawai.wiibservice.service.ForceOrderService;
import com.mawai.wiibservice.service.OrderFlowAggregator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * 轻周期服务：每10min刷新量化信号，零LLM调用。
 * <p>
 * 复用上一次重周期的 news_votes、reportJson、regimeConfidence、regimeTransition，
 * regime使用实时数据重算。重新采集市场数据 + 重算特征 + 跑4个纯代码Agent + Judge + RiskGate。
 */
@Slf4j
@Service
public class QuantLightCycleService {

    private final CollectDataNode collectDataNode;
    private final BuildFeaturesNode buildFeaturesNode;
    private final List<FactorAgent> pureAgents;
    private final MemoryService memoryService;
    private final QuantForecastPersistService persistService;
    private final ApplicationEventPublisher eventPublisher;

    /** 波动哨兵，轻周期完成后更新ATR */
    private PriceVolatilitySentinel volatilitySentinel;

    /** 防止同一symbol轻周期并发执行 */
    private final Set<String> runningSymbols = ConcurrentHashMap.newKeySet();

    /** 每个symbol的重周期缓存 */
    private final Map<String, HeavyCycleCache> cacheBySymbol = new ConcurrentHashMap<>();

    public record HeavyCycleCache(
            List<AgentVote> newsVotes,
            MarketRegime regime,
            double regimeConfidence,
            String regimeTransition,
            String reportJson,
            Instant heavyCycleTime
    ) {}

    /** 每个symbol最近一轮的forecast缓存（重/轻周期都写入），供方向修正用 */
    private final Map<String, ForecastCache> forecastCacheBySymbol = new ConcurrentHashMap<>();

    public record ForecastCache(List<HorizonForecast> forecasts) {}


    public QuantLightCycleService(BinanceRestClient binanceRestClient,
                                   ForceOrderService forceOrderService,
                                   OrderFlowAggregator orderFlowAggregator,
                                   QuantForecastPersistService persistService,
                                   DepthStreamCache depthStreamCache,
                                   DeribitClient deribitClient,
                                   MemoryService memoryService,
                                   ApplicationEventPublisher eventPublisher,
                                   @org.springframework.context.annotation.Lazy PriceVolatilitySentinel volatilitySentinel) {
        this.collectDataNode = new CollectDataNode(
                binanceRestClient, forceOrderService, depthStreamCache, deribitClient);
        this.buildFeaturesNode = new BuildFeaturesNode(orderFlowAggregator);
        this.pureAgents = List.of(
                new MicrostructureAgent(),
                new MomentumAgent(),
                new RegimeAgent(),
                new VolatilityAgent()
        );
        this.memoryService = memoryService;
        this.persistService = persistService;
        this.eventPublisher = eventPublisher;
        this.volatilitySentinel = volatilitySentinel;
    }

    /**
     * 重周期完成后调用：缓存 news_votes、regime、reportJson 供轻周期复用
     */
    public void cacheFromHeavyCycle(String symbol, List<AgentVote> allVotes,
                                     FeatureSnapshot snapshot, String reportJson,
                                     List<HorizonForecast> forecasts) {
        List<AgentVote> newsVotes = allVotes.stream()
                .filter(v -> "news_event".equals(v.agent()))
                .toList();
        cacheBySymbol.put(symbol, new HeavyCycleCache(
                newsVotes, snapshot.regime(), snapshot.regimeConfidence(),
                snapshot.regimeTransition(), reportJson, Instant.now()
        ));
        if (forecasts != null && !forecasts.isEmpty()) {
            forecastCacheBySymbol.put(symbol, new ForecastCache(forecasts));
        }
        log.info("[LightCycle] 缓存重周期数据 symbol={} newsVotes={} regime={} forecasts={} reportJson={}chars",
                symbol, newsVotes.size(), snapshot.regime(),
                forecasts != null ? forecasts.size() : 0,
                reportJson != null ? reportJson.length() : 0);
    }

    public boolean hasCacheFor(String symbol) {
        return cacheBySymbol.containsKey(symbol);
    }

    /**
     * 轻周期主流程：零LLM，纯计算刷新信号
     */
    public void runLightRefresh(String symbol, String fearGreedData) {
        if (!runningSymbols.add(symbol)) {
            log.info("[LightCycle] {} 轻周期正在执行中，跳过本次触发", symbol);
            return;
        }
        long startMs = System.currentTimeMillis();
        HeavyCycleCache cache = cacheBySymbol.get(symbol);
        if (cache == null) {
            runningSymbols.remove(symbol);
            log.warn("[LightCycle] 无重周期缓存，跳过 symbol={}", symbol);
            return;
        }

        log.info("[LightCycle] 轻周期开始 symbol={} cacheAge={}s",
                symbol, (Instant.now().getEpochSecond() - cache.heavyCycleTime().getEpochSecond()));

        try {
            // 1. 采集市场数据
            Map<String, Object> rawData = collectDataNode.collect(symbol, fearGreedData);
            Boolean dataAvailable = (Boolean) rawData.get("data_available");
            if (!Boolean.TRUE.equals(dataAvailable)) {
                log.warn("[LightCycle] 数据采集失败 symbol={}", symbol);
                return;
            }

            // 2. 构建特征
            Map<String, Object> featureResult = buildFeaturesNode.buildFeatures(symbol, rawData);
            FeatureSnapshot snapshot = (FeatureSnapshot) featureResult.get("feature_snapshot");
            if (snapshot == null) {
                log.warn("[LightCycle] 特征构建失败 symbol={}", symbol);
                return;
            }

            // 3. 使用最新数据计算的 regime（纯ADX/ATR计算，不需要LLM），
            //    但保留缓存的 regimeTransition（需要LLM跨周期判断）和 regimeConfidence
            snapshot = snapshot.withRegimeReview(
                    snapshot.regime(), snapshot.qualityFlags(),
                    cache.regimeConfidence(), cache.regimeTransition());
            log.info("[LightCycle] 使用实时regime={} (缓存regime={})",
                    snapshot.regime(), cache.regime());

            // 4. 跑4个纯代码Agent（并行）+ 合并缓存的 news votes
            List<AgentVote> allVotes = runPureAgents(snapshot);
            allVotes.addAll(cache.newsVotes());

            // 5. 三个 HorizonJudge 并行裁决
            Map<String, Map<String, Double>> agentAccuracy = loadAgentAccuracy(symbol);
            List<HorizonForecast> forecasts = runJudges(allVotes, snapshot.lastPrice(),
                    snapshot.qualityFlags(), agentAccuracy, snapshot.regime());

            // 6. RiskGate
            RiskGate.RiskResult riskResult = RiskGate.apply(
                    forecasts, snapshot.regime(), snapshot.atr5m(), snapshot.lastPrice(),
                    snapshot.qualityFlags(), snapshot.fearGreedIndex(), snapshot.dvolIndex(), symbol);

            // 6.5 方向修正：用当前轮分析校验上一轮预测
            ForecastCache previousForecast = forecastCacheBySymbol.get(symbol);
            List<HorizonForecast> confirmedForecasts = applyDirectionConfirmation(riskResult.forecasts(), previousForecast, LocalDateTime.now());
            String overallDecision = ConsensusBuilder.buildDecision(confirmedForecasts);
            String riskStatus = mergeRiskStatus(ConsensusBuilder.buildRiskStatus(confirmedForecasts), riskResult.riskStatus());

            // 7. 构建 ForecastResult（复用重周期的 reportJson）
            String cycleId = "light-" + LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0xFFFF))
                    + "-" + symbol;

            ForecastResult forecastResult = new ForecastResult(
                    symbol, cycleId, LocalDateTime.now(),
                    confirmedForecasts, overallDecision, riskStatus,
                    allVotes, snapshot, cache.reportJson());

            // 8. 持久化
            String snapshotJson = JSON.toJSONString(snapshot);
            persistService.persist(forecastResult, null, snapshotJson, cache.reportJson());

            // 9. 更新波动哨兵的ATR基准
            if (volatilitySentinel != null && snapshot.atr5m() != null) {
                volatilitySentinel.updateAtr(symbol, snapshot.atr5m());
            }

            for (HorizonForecast f : confirmedForecasts) {
                log.info("[LightCycle.result] {} → {} conf={} pos={}%",
                        f.horizon(), f.direction(),
                        String.format("%.2f", f.confidence()),
                        String.format("%.1f", f.maxPositionPct() * 100));
            }
            log.info("[LightCycle] 轻周期完成 symbol={} decision={} cycleId={} 耗时{}ms",
                    symbol, overallDecision, cycleId, System.currentTimeMillis() - startMs);
            eventPublisher.publishEvent(new QuantCycleCompleteEvent(this, symbol, "light"));
            // 更新forecast缓存供下一轮使用
            forecastCacheBySymbol.put(symbol, new ForecastCache(confirmedForecasts));

        } catch (Exception e) {
            log.error("[LightCycle] 轻周期异常 symbol={}", symbol, e);
        } finally {
            runningSymbols.remove(symbol);
        }
    }

    /**
     * 轻周期方向修正：用当前轮的分析结果校验上一轮的预测方向。
     * 只有触发时间与最近整10分钟点位偏差 ≤ 2min 时才修正。
     */
    private List<HorizonForecast> applyDirectionConfirmation(List<HorizonForecast> currentForecasts,
                                                             ForecastCache previous, LocalDateTime now) {
        if (previous == null || previous.forecasts() == null || previous.forecasts().isEmpty()) {
            return currentForecasts;
        }

        int minuteInCycle = now.getMinute() % 10;
        int offset = Math.min(minuteInCycle, 10 - minuteInCycle);
        if (offset > 2) {
            log.debug("[LightCycle.confirm] offset={}min > 2, skip confirmation", offset);
            return currentForecasts;
        }

        Map<String, HorizonForecast> prevByHorizon = new HashMap<>();
        for (HorizonForecast f : previous.forecasts()) {
            prevByHorizon.put(f.horizon(), f);
        }

        // 映射规则：靠近前整点 vs 靠近后整点
        Map<String, String> horizonMapping = new HashMap<>();
        if (minuteInCycle <= 2) {
            horizonMapping.put("0_10", "10_20");
            horizonMapping.put("10_20", "20_30");
        } else {
            horizonMapping.put("0_10", "20_30");
        }

        List<HorizonForecast> result = new ArrayList<>(currentForecasts.size());
        for (HorizonForecast curr : currentForecasts) {
            String mappedHorizon = horizonMapping.get(curr.horizon());
            HorizonForecast prev = mappedHorizon != null ? prevByHorizon.get(mappedHorizon) : null;

            if (prev == null || prev.direction() == Direction.NO_TRADE || curr.direction() == Direction.NO_TRADE) {
                result.add(curr);
                continue;
            }

            boolean sameDirection = curr.direction() == prev.direction();
            Direction finalDir;
            double finalConf;
            HorizonForecast base;

            if (sameDirection) {
                finalDir = curr.direction();
                finalConf = Math.min(1.0, curr.confidence() + prev.confidence() * 0.2);
                base = curr;
            } else if (curr.confidence() > prev.confidence()) {
                finalDir = curr.direction();
                finalConf = curr.confidence() - prev.confidence() * 0.3;
                base = curr;
            } else {
                finalDir = prev.direction();
                finalConf = prev.confidence() - curr.confidence() * 0.5;
                base = prev;
            }
            finalConf = Math.clamp(finalConf, 0.1, 1.0);

            log.info("[LightCycle.confirm] {} {} prev={}({}) curr={}({}) -> {}({}) offset={}min",
                    curr.horizon(), mappedHorizon,
                    prev.direction(), String.format("%.2f", prev.confidence()),
                    curr.direction(), String.format("%.2f", curr.confidence()),
                    finalDir, String.format("%.2f", finalConf), offset);

            // weightedScore 符号必须跟 finalDir 一致（正=多头优势）。base 已按方向选，取绝对值再定符号。
            double finalScore = finalDir == Direction.LONG
                    ? Math.abs(base.weightedScore())
                    : -Math.abs(base.weightedScore());
            result.add(new HorizonForecast(
                    curr.horizon(), finalDir, finalConf, finalScore,
                    curr.disagreement(), base.entryLow(), base.entryHigh(),
                    base.invalidationPrice(), base.tp1(), base.tp2(),
                    curr.maxLeverage(), curr.maxPositionPct()));
        }
        return result;
    }

    private List<AgentVote> runPureAgents(FeatureSnapshot snapshot) {
        List<AgentVote> allVotes = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<AgentVote>>> futures = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (FactorAgent agent : pureAgents) {
                names.add(agent.name());
                futures.add(executor.submit(() -> agent.evaluate(snapshot)));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    allVotes.addAll(futures.get(i).get(30, TimeUnit.SECONDS));
                } catch (Exception e) {
                    log.warn("[LightCycle] Agent[{}] 超时/异常: {}", names.get(i), e.getMessage());
                    allVotes.add(AgentVote.noTrade(names.get(i), "0_10", "TIMEOUT"));
                    allVotes.add(AgentVote.noTrade(names.get(i), "10_20", "TIMEOUT"));
                    allVotes.add(AgentVote.noTrade(names.get(i), "20_30", "TIMEOUT"));
                }
            }
        }
        return allVotes;
    }

    private static final String[] HORIZONS = {"0_10", "10_20", "20_30"};

    private List<HorizonForecast> runJudges(List<AgentVote> allVotes, BigDecimal lastPrice,
                                             List<String> qualityFlags,
                                             Map<String, Map<String, Double>> agentAccuracy,
                                             MarketRegime regime) {
        List<HorizonForecast> forecasts = new ArrayList<>(3);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HorizonForecast>> futures = new ArrayList<>(3);
            for (String horizon : HORIZONS) {
                futures.add(executor.submit(() -> {
                    HorizonJudge judge = new HorizonJudge(horizon, agentAccuracy);
                    return judge.judge(allVotes, lastPrice, qualityFlags, regime);
                }));
            }
            for (int i = 0; i < 3; i++) {
                try {
                    forecasts.add(futures.get(i).get(5, TimeUnit.SECONDS));
                } catch (Exception e) {
                    log.warn("[LightCycle] Judge[{}]裁决失败: {}", HORIZONS[i], e.getMessage());
                    forecasts.add(HorizonForecast.noTrade(HORIZONS[i], 1.0));
                }
            }
        }
        return forecasts;
    }

    private Map<String, Map<String, Double>> loadAgentAccuracy(String symbol) {
        try {
            return memoryService.getAgentAccuracy(symbol);
        } catch (Exception e) {
            log.warn("[LightCycle] agent准确率查询失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private String mergeRiskStatus(String upstream, String current) {
        Set<String> parts = new LinkedHashSet<>();
        addParts(parts, upstream);
        addParts(parts, current);
        if (parts.isEmpty()) return "NORMAL";
        if (parts.size() > 1) {
            parts.remove("NORMAL");
            parts.remove("UNKNOWN");
        }
        return parts.isEmpty() ? "NORMAL" : String.join(",", parts);
    }

    private void addParts(Set<String> parts, String status) {
        if (status == null || status.isBlank()) return;
        for (String part : status.split(",")) {
            String n = part.trim();
            if (!n.isEmpty()) parts.add(n);
        }
    }
}
