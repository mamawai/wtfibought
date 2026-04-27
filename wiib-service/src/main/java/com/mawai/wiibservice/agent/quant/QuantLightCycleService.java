package com.mawai.wiibservice.agent.quant;

import com.alibaba.fastjson2.JSON;
import com.mawai.wiibcommon.entity.QuantForecastAdjustment;
import com.mawai.wiibservice.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibservice.agent.quant.domain.*;
import com.mawai.wiibservice.agent.quant.factor.*;
import com.mawai.wiibservice.agent.quant.judge.ConsensusBuilder;
import com.mawai.wiibservice.agent.quant.judge.HorizonJudge;
import com.mawai.wiibservice.agent.quant.memory.MemoryService;
import com.mawai.wiibservice.agent.quant.memory.AgentPerformanceMemoryService.AgentStat;
import com.mawai.wiibservice.agent.quant.node.BuildFeaturesNode;
import com.mawai.wiibservice.agent.quant.node.CollectDataNode;
import com.mawai.wiibservice.agent.quant.risk.RiskGate;
import com.mawai.wiibservice.agent.quant.domain.QuantCycleCompleteEvent;
import com.mawai.wiibservice.agent.quant.service.FactorWeightOverrideService;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.config.DeribitClient;
import com.mawai.wiibservice.mapper.QuantForecastAdjustmentMapper;
import com.mawai.wiibservice.mapper.QuantForecastCycleMapper;
import com.mawai.wiibservice.mapper.QuantHorizonForecastMapper;
import com.mawai.wiibservice.mapper.QuantSignalDecisionMapper;
import com.mawai.wiibservice.service.DepthStreamCache;
import com.mawai.wiibservice.service.ForceOrderService;
import com.mawai.wiibservice.service.OrderFlowAggregator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * 轻周期服务：每5min刷新量化信号（D10），零LLM调用（纯计算路径）。
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
    private final QuantHorizonForecastMapper horizonForecastMapper;
    private final QuantSignalDecisionMapper signalDecisionMapper;
    private final QuantForecastCycleMapper forecastCycleMapper;
    private final QuantForecastAdjustmentMapper adjustmentMapper;
    private final FactorWeightOverrideService weightOverrideService;

    /** 浅LLM新闻刷新用：直接拉 news + runtime chatModel */
    private final BinanceRestClient binanceRestClient;
    private final AiAgentRuntimeManager runtimeManager;
    private final LightNewsAgent lightNewsAgent = new LightNewsAgent();

    /** 波动哨兵，轻周期完成后更新ATR */
    private final PriceVolatilitySentinel volatilitySentinel;

    /** 防止同一symbol轻周期并发执行 */
    private final Set<String> runningSymbols = ConcurrentHashMap.newKeySet();

    /** 每个symbol的重周期缓存 */
    private final Map<String, HeavyCycleCache> cacheBySymbol = new ConcurrentHashMap<>();

    public record HeavyCycleCache(
            String heavyCycleId,
            List<AgentVote> newsVotes,
            MarketRegime regime,
            double regimeConfidence,
            String regimeTransition,
            String reportJson,
            Instant heavyCycleTime,
            LocalDateTime heavyWindowStart
    ) {}

    /**
     * 父重周期「当前最新」forecast 快照：
     *   - 重周期完成时由 cacheFromHeavyCycle 写入原始 forecast；
     *   - 每次轻周期 applyHeavyCycleCorrection 后整体更新为修正后的重周期 forecast。
     *   供下一个轻周期继续 diff。
     */
    private final Map<String, ForecastCache> forecastCacheBySymbol = new ConcurrentHashMap<>();

    public record ForecastCache(List<HorizonForecast> forecasts) {}

    /**
     * 反转票内存状态：symbol → heavyHorizon(0_10/10_20/20_30) → ReversalState。
     * 连续反向强信号累票，达 2 票否决旧方向；方向回同向/轻conf<0.5/新重周期来 → 清零。
     * 服务重启丢失——最多多跑一轮才能否决，可接受（重周期本身就 30 min 一个）。
     */
    private final Map<String, Map<String, ReversalState>> reversalStateBySymbol = new ConcurrentHashMap<>();

    public record ReversalState(Direction reversalDir, int voteCount) {}

    public QuantLightCycleService(BinanceRestClient binanceRestClient,
                                   ForceOrderService forceOrderService,
                                   OrderFlowAggregator orderFlowAggregator,
                                   QuantForecastPersistService persistService,
                                   DepthStreamCache depthStreamCache,
                                   DeribitClient deribitClient,
                                   MemoryService memoryService,
                                   ApplicationEventPublisher eventPublisher,
                                   QuantHorizonForecastMapper horizonForecastMapper,
                                   QuantSignalDecisionMapper signalDecisionMapper,
                                   QuantForecastCycleMapper forecastCycleMapper,
                                   QuantForecastAdjustmentMapper adjustmentMapper,
                                   AiAgentRuntimeManager runtimeManager,
                                   FactorWeightOverrideService weightOverrideService,
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
        this.horizonForecastMapper = horizonForecastMapper;
        this.signalDecisionMapper = signalDecisionMapper;
        this.forecastCycleMapper = forecastCycleMapper;
        this.adjustmentMapper = adjustmentMapper;
        this.weightOverrideService = weightOverrideService;
        this.binanceRestClient = binanceRestClient;
        this.runtimeManager = runtimeManager;
        this.volatilitySentinel = volatilitySentinel;
    }

    /**
     * 重周期完成后调用：缓存 news_votes、regime、reportJson 供轻周期复用。
     * heavyCycleId 用于后续轻周期修正时定位父重周期的 forecast/signal 记录。
     */
    public void cacheFromHeavyCycle(String symbol, String heavyCycleId, List<AgentVote> allVotes,
                                     FeatureSnapshot snapshot, String reportJson,
                                     List<HorizonForecast> forecasts) {
        List<AgentVote> newsVotes = allVotes.stream()
                .filter(v -> "news_event".equals(v.agent()))
                .toList();
        cacheBySymbol.put(symbol, new HeavyCycleCache(
                heavyCycleId, newsVotes, snapshot.regime(), snapshot.regimeConfidence(),
                snapshot.regimeTransition(), reportJson, Instant.now(),
                halfHourWindowStart(snapshot.snapshotTime() != null ? snapshot.snapshotTime() : LocalDateTime.now())
        ));
        if (forecasts != null && !forecasts.isEmpty()) {
            forecastCacheBySymbol.put(symbol, new ForecastCache(forecasts));
        }
        // 新重周期来了 → 清掉上一轮留下的反转票状态，防跨周期误累积
        reversalStateBySymbol.remove(symbol);
        log.info("[LightCycle] 缓存重周期数据 symbol={} heavyCycleId={} newsVotes={} regime={} forecasts={} reportJson={}chars",
                symbol, heavyCycleId, newsVotes.size(), snapshot.regime(),
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
        LocalDateTime lightCycleStart = LocalDateTime.now();
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

            // 4. 跑4个纯代码Agent（并行）+ news 票：只在 10min 墙钟边界附近刷新 news。
            //    重周期按半小时窗口归属，不能用实际完成时间做锚点；12:05 这种跨段窗口不修。
            Long lightOffsetMin = lightOffsetInHeavyWindow(lightCycleStart, cache.heavyWindowStart());
            List<String> activeHorizons = activeLightHorizonsForOffset(lightOffsetMin);

            List<AgentVote> newsVotes = cache.newsVotes();
            if (!activeHorizons.isEmpty()) {
                try {
                    ChatModel chatModel = runtimeManager.current().quantChatModel();
                    ChatClient.Builder builder = ChatClient.builder(chatModel);
                    LightNewsAgent.Result r = lightNewsAgent.evaluate(
                            symbol, snapshot,
                            cache.heavyCycleTime().getEpochSecond(),
                            activeHorizons, builder, LlmCallMode.STREAMING, binanceRestClient);
                    if (!r.useCache() && r.votes() != null && !r.votes().isEmpty()) {
                        // 用新票替换对应 horizon，其他 horizon 保留 cache（兼顾轻周期自身 forecast 完整性）
                        newsVotes = mergeNewsVotes(cache.newsVotes(), r.votes(), activeHorizons);
                        log.info("[LightCycle.news] symbol={} offset={}min 刷新 {} 条 horizons={} status={}",
                                symbol, lightOffsetMin, r.votes().size(), activeHorizons, r.status());
                    } else {
                        log.info("[LightCycle.news] symbol={} offset={}min 复用 cache status={}",
                                symbol, lightOffsetMin, r.status());
                    }
                } catch (Exception e) {
                    log.warn("[LightCycle.news] LightNewsAgent 异常，复用 cache symbol={}: {}", symbol, e.getMessage());
                }
            }

            List<AgentVote> allVotes = runPureAgents(snapshot);
            allVotes.addAll(newsVotes);

            // 5. 三个 HorizonJudge 并行裁决
            // 轻周期也用记忆调权，但调权明细只在重周期 snapshot_json 持久化
            Map<String, Map<String, AgentStat>> agentStats = loadAgentStats(symbol, snapshot.regime());
            List<HorizonForecast> forecasts = runJudges(allVotes, snapshot.lastPrice(),
                    snapshot.qualityFlags(), agentStats, snapshot.regime());

            // 6. RiskGate
            RiskGate.RiskResult riskResult = RiskGate.apply(
                    forecasts, snapshot.regime(), snapshot.atr5m(), snapshot.lastPrice(),
                    snapshot.qualityFlags(), snapshot.fearGreedIndex(), snapshot.dvolIndex(), symbol);

            // 6.5 关键改动：轻周期不再自我修正，而是「修正父重周期」
            //     AI-Trader 读的是最新重周期的 forecast/signal，轻周期通过 UPDATE 父重周期表反映影响
            List<HorizonForecast> lightForecasts = riskResult.forecasts();
            String lightCycleIdPreview = "light-" + LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0xFFFF))
                    + "-" + symbol;
            try {
                applyHeavyCycleCorrection(symbol, lightCycleIdPreview, lightForecasts, cache, lightCycleStart);
            } catch (Exception e) {
                // 修正失败不阻断轻周期自己的落库与事件发布——交易链路仍然用父重周期上一次快照
                log.error("[LightCycle.correct] 父重周期修正失败 symbol={}", symbol, e);
            }

            // 轻周期自己的 overallDecision/riskStatus 基于自己的 forecasts 计算（仅供展示/验证，不驱动交易）
            String overallDecision = ConsensusBuilder.buildDecision(lightForecasts);
            String riskStatus = mergeRiskStatus(ConsensusBuilder.buildRiskStatus(lightForecasts), riskResult.riskStatus());

            // 7. 构建 ForecastResult（复用重周期的 reportJson，用预生成的 cycleId 保持一致）
            String cycleId = lightCycleIdPreview;

            ForecastResult forecastResult = new ForecastResult(
                    symbol, cycleId, lightCycleStart,
                    lightForecasts, overallDecision, riskStatus,
                    allVotes, snapshot, cache.reportJson(),
                    cache.heavyCycleId());  // 轻周期挂载的父重周期

            // 8. 持久化
            String snapshotJson = JSON.toJSONString(snapshot);
            persistService.persist(forecastResult, null, snapshotJson, cache.reportJson());

            // 9. 更新波动哨兵的ATR基准
            if (volatilitySentinel != null && snapshot.atr5m() != null) {
                volatilitySentinel.updateAtr(symbol, snapshot.atr5m());
            }

            for (HorizonForecast f : lightForecasts) {
                log.info("[LightCycle.result] {} → {} conf={} pos={}%",
                        f.horizon(), f.direction(),
                        String.format("%.2f", f.confidence()),
                        String.format("%.1f", f.maxPositionPct() * 100));
            }
            log.info("[LightCycle] 轻周期完成 symbol={} decision={} cycleId={} 耗时{}ms",
                    symbol, overallDecision, cycleId, System.currentTimeMillis() - startMs);
            eventPublisher.publishEvent(new QuantCycleCompleteEvent(this, symbol, "light"));

        } catch (Exception e) {
            log.error("[LightCycle] 轻周期异常 symbol={}", symbol, e);
        } finally {
            runningSymbols.remove(symbol);
        }
    }

    // ===================== 父重周期修正（核心算法：heavy=30min / light=5min） =====================
    //
    // 轻周期根据「light horizon 起点是否贴近半小时墙钟窗口内的 0/10/20/30 边界」决定修正目标。
    // 重周期实际完成时间不做锚点：12:03:40 完成的重周期仍归属 12:00-12:30。
    // 例：12:05 的 05-15 跨 0_10/10_20，不修；12:08 的 08-18 近似修 10_20。
    //
    // 三分支裁决（prev=父重周期当前 horizon forecast，light=本轮轻周期对应 horizon forecast）：
    //   A 同向         ：newConf = prev.conf + light.conf×0.2（加成），dir 不变，清票
    //   B 反向弱(l<p)  ：newConf = prev.conf - light.conf×0.3（轻罚），dir 不变，清票
    //   C 反向强(l≥p) ：newConf = prev.conf - light.conf×0.5（重罚）
    //                    ├─ light.conf<0.5 → 不累票，清零（信号绝对值太弱，防噪音反转）
    //                    └─ light.conf≥0.5 → 累票
    //                         ├─ 达 2 票 → LIGHT_VETO：置 NO_TRADE，否决旧方向但不直接反向
    //                         └─ <2 票 → OPPO_STRONG_PENALTY，dir 不变，票数留存
    //
    // 为什么 veto 而不是 flip：
    //   轻周期能证明旧方向危险，但不应直接给出反方向；真正反向交给下一轮重周期确认。
    //
    // 为什么不动 weightedScore / entryLow/High / tp / sl：
    //   这些字段关联历史快照，VerificationService 验证路径依赖原值。修正只改方向/置信度，保留快照完整性。

    private static final int HORIZON_WINDOW_MINUTES = 10;
    private static final int HEAVY_WINDOW_MINUTES = 30;
    private static final int HORIZON_ALIGN_TOLERANCE_MINUTES = 2;
    private static final String[] HORIZONS = {"0_10", "10_20", "20_30"};

    /** adjust_type 枚举值（落库字符串），与前端 TS union 对齐 */
    private static final String ADJ_SAME_DIR_BOOST = "SAME_DIR_BOOST";
    private static final String ADJ_OPPO_WEAK_PENALTY = "OPPO_WEAK_PENALTY";
    private static final String ADJ_OPPO_STRONG_PENALTY = "OPPO_STRONG_PENALTY";
    private static final String ADJ_LIGHT_VETO = "LIGHT_VETO";

    /** confidence 统一 4 位小数落库（与 DB NUMERIC(5,4) 精度对齐） */
    private static BigDecimal toConf4(double v) {
        return BigDecimal.valueOf(v).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    /** 需要让 LightNewsAgent 刷新的 light horizons；无映射则复用重周期 cache。 */
    private static List<String> activeLightHorizonsForOffset(Long lightOffsetMin) {
        if (lightOffsetMin == null) return List.of();
        List<String> active = new ArrayList<>(3);
        for (String lightH : HORIZONS) {
            if (mapLightToHeavyHorizon(lightH, lightOffsetMin) != null) {
                active.add(lightH);
            }
        }
        return active;
    }

    /** 将 light horizon 的起点映射到父重周期 0_10 / 10_20 / 20_30。 */
    private static String mapLightToHeavyHorizon(String lightHorizon, long lightOffsetMin) {
        int lightStart = horizonStartMinute(lightHorizon);
        if (lightStart < 0) return null;
        Integer heavyStart = alignedHeavyStartMinute(lightOffsetMin + lightStart);
        if (heavyStart == null) return null;
        return switch (heavyStart) {
            case 0 -> "0_10";
            case 10 -> "10_20";
            case 20 -> "20_30";
            default -> null;
        };
    }

    /** 只接受贴近 10min 边界的起点；05/15/25 这种中点返回 null。 */
    private static Integer alignedHeavyStartMinute(long minuteOffset) {
        if (minuteOffset < 0) return null;
        long mod = minuteOffset % HORIZON_WINDOW_MINUTES;
        long alignedStart;
        if (mod <= HORIZON_ALIGN_TOLERANCE_MINUTES) {
            alignedStart = minuteOffset - mod;
        } else if (mod >= HORIZON_WINDOW_MINUTES - HORIZON_ALIGN_TOLERANCE_MINUTES) {
            alignedStart = minuteOffset + (HORIZON_WINDOW_MINUTES - mod);
        } else {
            return null;
        }
        if (alignedStart < 0 || alignedStart >= HEAVY_WINDOW_MINUTES) return null;
        return (int) alignedStart;
    }

    /** horizon 名称转父重周期内起点分钟。 */
    private static int horizonStartMinute(String horizon) {
        return switch (horizon) {
            case "0_10" -> 0;
            case "10_20" -> 10;
            case "20_30" -> 20;
            default -> -1;
        };
    }

    /** 半小时墙钟窗口起点：12:03/12:29 → 12:00，12:30/12:59 → 12:30。 */
    private static LocalDateTime halfHourWindowStart(LocalDateTime time) {
        int startMinute = time.getMinute() < HEAVY_WINDOW_MINUTES ? 0 : HEAVY_WINDOW_MINUTES;
        return time.withMinute(startMinute).withSecond(0).withNano(0);
    }

    /**
     * 轻周期在父重周期半小时窗口内的分钟偏移。
     * 不在同一半小时窗口说明父缓存已过窗口，不允许用新窗口轻周期修旧父周期。
     */
    private static Long lightOffsetInHeavyWindow(LocalDateTime lightCycleStart, LocalDateTime heavyWindowStart) {
        if (lightCycleStart == null || heavyWindowStart == null) return null;
        if (!halfHourWindowStart(lightCycleStart).equals(heavyWindowStart)) return null;
        return Duration.between(heavyWindowStart, lightCycleStart).toMinutes();
    }

    /**
     * 合并 news 票：activeHorizons 中的 horizon 用 fresh 的，其他 horizon 保留 cache 的。
     * 保证轻周期的三个 horizon 都有 news_event 票（不在 activeHorizons 的由 cache 兜底）。
     */
    private static List<AgentVote> mergeNewsVotes(List<AgentVote> cached, List<AgentVote> fresh,
                                                   List<String> activeHorizons) {
        List<AgentVote> merged = new ArrayList<>(3);
        Map<String, AgentVote> freshByHorizon = new HashMap<>();
        for (AgentVote v : fresh) freshByHorizon.put(v.horizon(), v);
        for (AgentVote c : cached) {
            if (activeHorizons.contains(c.horizon()) && freshByHorizon.containsKey(c.horizon())) {
                merged.add(freshByHorizon.get(c.horizon()));
            } else {
                merged.add(c);
            }
        }
        return merged;
    }

    private void applyHeavyCycleCorrection(String symbol, String lightCycleId,
                                           List<HorizonForecast> lightForecasts,
                                           HeavyCycleCache cache,
                                           LocalDateTime lightCycleStart) {
        String heavyCycleId = cache.heavyCycleId();
        if (heavyCycleId == null) {
            log.warn("[LightCycle.correct] heavyCycleId 缺失，跳过父重周期修正 symbol={}", symbol);
            return;
        }
        ForecastCache prevSnapshot = forecastCacheBySymbol.get(symbol);
        if (prevSnapshot == null || prevSnapshot.forecasts() == null || prevSnapshot.forecasts().isEmpty()) {
            log.warn("[LightCycle.correct] 无父重周期 forecast 快照 symbol={}", symbol);
            return;
        }

        Long lightOffsetMin = lightOffsetInHeavyWindow(lightCycleStart, cache.heavyWindowStart());
        List<String> activeLightHorizons = activeLightHorizonsForOffset(lightOffsetMin);
        if (activeLightHorizons.isEmpty()) {
            log.info("[LightCycle.correct] lightStart={} heavyWindow={} offset={}min 未贴近 10min 边界，跳过父重修正 symbol={}",
                    lightCycleStart, cache.heavyWindowStart(), lightOffsetMin, symbol);
            return;
        }

        Map<String, HorizonForecast> heavyByHorizon = new HashMap<>();
        for (HorizonForecast f : prevSnapshot.forecasts()) heavyByHorizon.put(f.horizon(), f);
        Map<String, HorizonForecast> lightByHorizon = new HashMap<>();
        for (HorizonForecast f : lightForecasts) lightByHorizon.put(f.horizon(), f);

        Map<String, ReversalState> voteMap = reversalStateBySymbol.computeIfAbsent(
                symbol, k -> new ConcurrentHashMap<>());

        Map<String, HorizonForecast> newHeavyByHorizon = new HashMap<>(heavyByHorizon);
        boolean vetoApplied = false;

        for (String lightH : activeLightHorizons) {
            String heavyH = mapLightToHeavyHorizon(lightH, lightOffsetMin);
            if (heavyH == null) continue;

            HorizonForecast lightF = lightByHorizon.get(lightH);
            HorizonForecast prev = heavyByHorizon.get(heavyH);
            if (lightF == null || prev == null) continue;
            if (lightF.direction() == Direction.NO_TRADE || prev.direction() == Direction.NO_TRADE) continue;

            double lightConf = lightF.confidence();
            double prevConf = prev.confidence();
            Direction newDir;
            double newConf;
            String adjustType;
            int voteAfter;

            if (lightF.direction() == prev.direction()) {
                // A 同向加成
                newDir = prev.direction();
                newConf = Math.clamp(prevConf + lightConf * 0.2, 0.1, 1.0);
                adjustType = ADJ_SAME_DIR_BOOST;
                voteAfter = 0;
                voteMap.remove(heavyH);
            } else if (lightConf < prevConf) {
                // B 反向弱削弱（轻周期信心不足以挑战父重）
                newDir = prev.direction();
                newConf = Math.clamp(prevConf - lightConf * 0.3, 0.1, 1.0);
                adjustType = ADJ_OPPO_WEAK_PENALTY;
                voteAfter = 0;
                voteMap.remove(heavyH);
            } else {
                // C 反向强（light ≥ prev）
                newConf = Math.clamp(prevConf - lightConf * 0.5, 0.1, 1.0);
                if (lightConf < 0.5) {
                    // 绝对值太弱，不累票，算作反向弱削弱（区分于 B：这里比 prev 强但自身 <0.5，只罚不记票）
                    newDir = prev.direction();
                    adjustType = ADJ_OPPO_STRONG_PENALTY;
                    voteAfter = 0;
                    voteMap.remove(heavyH);
                } else {
                    ReversalState current = voteMap.get(heavyH);
                    int nextCount = (current != null && current.reversalDir() == lightF.direction())
                            ? current.voteCount() + 1 : 1;
                    if (nextCount >= 2) {
                        // 轻周期只否决旧方向，不直接给反方向；下一轮重周期负责重新定性
                        newDir = Direction.NO_TRADE;
                        newConf = 0.1;
                        adjustType = ADJ_LIGHT_VETO;
                        voteAfter = 0;
                        voteMap.remove(heavyH);
                    } else {
                        newDir = prev.direction();
                        adjustType = ADJ_OPPO_STRONG_PENALTY;
                        voteAfter = nextCount;
                        voteMap.put(heavyH, new ReversalState(lightF.direction(), nextCount));
                    }
                }
            }

            // ===== 持久化 3 张表 =====
            BigDecimal newConfBd = toConf4(newConf);
            try {
                horizonForecastMapper.updateDirectionAndConfidence(heavyCycleId, heavyH, newDir.name(), newConfBd);
                signalDecisionMapper.updateDirectionAndConfidence(heavyCycleId, heavyH, newDir.name(), newConfBd);

                QuantForecastAdjustment adj = new QuantForecastAdjustment();
                adj.setLightCycleId(lightCycleId);
                adj.setHeavyCycleId(heavyCycleId);
                adj.setSymbol(symbol);
                adj.setLightHorizon(lightH);
                adj.setHeavyHorizon(heavyH);
                adj.setAdjustType(adjustType);
                adj.setLightDirection(lightF.direction().name());
                adj.setLightConfidence(toConf4(lightConf));
                adj.setPrevHeavyDirection(prev.direction().name());
                adj.setPrevHeavyConfidence(toConf4(prevConf));
                adj.setNewHeavyDirection(newDir.name());
                adj.setNewHeavyConfidence(newConfBd);
                adj.setVoteCountAfter(voteAfter);
                adjustmentMapper.insert(adj);
                if (ADJ_LIGHT_VETO.equals(adjustType)) {
                    vetoApplied = true;
                }
            } catch (Exception e) {
                log.error("[LightCycle.correct] 持久化失败 heavyCycleId={} heavyH={}", heavyCycleId, heavyH, e);
                continue;
            }

            // ===== 内存缓存同步（weightedScore 符号跟 dir 走，其他字段保留 prev） =====
            double newScore = switch (newDir) {
                case LONG -> Math.abs(prev.weightedScore());
                case SHORT -> -Math.abs(prev.weightedScore());
                case NO_TRADE -> 0;
            };
            HorizonForecast updated = new HorizonForecast(
                    prev.horizon(), newDir, newConf, newScore,
                    prev.disagreement(), prev.entryLow(), prev.entryHigh(),
                    prev.invalidationPrice(), prev.tp1(), prev.tp2(),
                    prev.maxLeverage(), prev.maxPositionPct());
            newHeavyByHorizon.put(heavyH, updated);

            log.info("[LightCycle.correct] heavy={} lightH={}→heavyH={} type={} light={}({}) prev={}({}) → new={}({}) vote={} offset={}min",
                    heavyCycleId, lightH, heavyH, adjustType,
                    lightF.direction(), String.format("%.2f", lightConf),
                    prev.direction(), String.format("%.2f", prevConf),
                    newDir, String.format("%.2f", newConf), voteAfter, lightOffsetMin);
        }

        // 写回 forecastCacheBySymbol（语义：父重周期「当前最新」快照，供下一个轻周期继续 diff）
        List<HorizonForecast> newHeavyList = new ArrayList<>(newHeavyByHorizon.values());
        forecastCacheBySymbol.put(symbol, new ForecastCache(newHeavyList));

        // 基于新 heavyForecasts 重算 overallDecision/riskStatus 回写 cycle 表（AI-Trader 会读）
        try {
            String newOverall = ConsensusBuilder.buildDecision(newHeavyList);
            String newRisk = ConsensusBuilder.buildRiskStatus(newHeavyList);
            if (vetoApplied) {
                newRisk = mergeRiskStatus(newRisk, ADJ_LIGHT_VETO);
            }
            forecastCycleMapper.updateDecisionAndRisk(heavyCycleId, newOverall, newRisk);
            log.info("[LightCycle.correct] 重算重周期 cycle decision={} risk={} heavyCycleId={}",
                    newOverall, newRisk, heavyCycleId);
        } catch (Exception e) {
            log.error("[LightCycle.correct] 重周期 cycle 汇总更新失败 heavyCycleId={}", heavyCycleId, e);
        }
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

    private List<HorizonForecast> runJudges(List<AgentVote> allVotes, BigDecimal lastPrice,
                                             List<String> qualityFlags,
                                             Map<String, Map<String, AgentStat>> agentStats,
                                             MarketRegime regime) {
        List<HorizonForecast> forecasts = new ArrayList<>(3);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HorizonForecast>> futures = new ArrayList<>(3);
            for (String horizon : HORIZONS) {
                futures.add(executor.submit(() -> {
                    HorizonJudge judge = new HorizonJudge(horizon, agentStats, weightOverrideService);
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

    private Map<String, Map<String, AgentStat>> loadAgentStats(String symbol, MarketRegime regime) {
        try {
            return memoryService.getAgentFullStats(symbol, regime != null ? regime.name() : null);
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
