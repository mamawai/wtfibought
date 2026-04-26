package com.mawai.wiibservice.agent.quant.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.QuantAgentVote;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantForecastVerification;
import com.mawai.wiibservice.agent.quant.domain.AgentVote;
import com.mawai.wiibservice.agent.quant.domain.Direction;
import com.mawai.wiibservice.agent.quant.domain.HorizonForecast;
import com.mawai.wiibservice.agent.quant.domain.MarketRegime;
import com.mawai.wiibservice.agent.quant.judge.HorizonJudge;
import com.mawai.wiibservice.mapper.QuantAgentVoteMapper;
import com.mawai.wiibservice.mapper.QuantForecastCycleMapper;
import com.mawai.wiibservice.mapper.QuantForecastVerificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * B1-3 调权回放：历史 AgentVote 重新进 HorizonJudge，只读验证方向命中率。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorWeightReplayService {

    private static final String[] HORIZONS = {"0_10", "10_20", "20_30"};
    private static final int QUERY_CHUNK_SIZE = 500;
    private static final int NO_TRADE_THRESHOLD_BPS = 10;

    private final QuantForecastCycleMapper cycleMapper;
    private final QuantAgentVoteMapper voteMapper;
    private final QuantForecastVerificationMapper verificationMapper;
    private final FactorWeightOverrideService weightOverrideService;

    /** 使用历史 cycle/vote/verification 只读回放，对比调权前后的方向命中率。 */
    public ReplayReport replay(String symbol, LocalDateTime from, LocalDateTime to) {
        List<QuantForecastCycle> cycles = cycleMapper.selectBySymbolAndTimeRange(symbol, from, to);
        if (cycles.isEmpty()) {
            throw new IllegalStateException("该时间段内无历史cycle记录: " + symbol);
        }

        List<String> cycleIds = cycles.stream().map(QuantForecastCycle::getCycleId).toList();
        Map<String, List<AgentVote>> votesByCycle = loadVotes(cycleIds);
        Map<String, QuantForecastVerification> verificationByCycleHorizon = loadVerifications(symbol, cycleIds);

        ReplayAccumulator all = new ReplayAccumulator("ALL");
        Map<String, ReplayAccumulator> byHorizon = new LinkedHashMap<>();
        for (String horizon : HORIZONS) {
            byHorizon.put(horizon, new ReplayAccumulator(horizon));
        }

        int evaluatedCycles = 0;
        int skippedNoVote = 0;
        int skippedNoVerification = 0;

        for (QuantForecastCycle cycle : cycles) {
            List<AgentVote> votes = votesByCycle.get(cycle.getCycleId());
            if (votes == null || votes.isEmpty()) {
                skippedNoVote++;
                continue;
            }
            CycleContext ctx = parseCycleContext(cycle);
            boolean cycleUsed = false;

            for (String horizon : HORIZONS) {
                QuantForecastVerification verification =
                        verificationByCycleHorizon.get(key(cycle.getCycleId(), horizon));
                if (verification == null || verification.getActualChangeBps() == null) {
                    skippedNoVerification++;
                    continue;
                }

                HorizonForecast baseline = new HorizonJudge(horizon, Map.of(),
                        weightOverrideService, false, false)
                        .judge(votes, ctx.lastPrice(), ctx.qualityFlags(), ctx.regime());
                HorizonForecast override = new HorizonJudge(horizon, Map.of(),
                        weightOverrideService, true, false)
                        .judge(votes, ctx.lastPrice(), ctx.qualityFlags(), ctx.regime());

                SampleResult sample = evaluateSample(baseline.direction(), override.direction(),
                        verification.getActualChangeBps());
                byHorizon.get(horizon).add(sample);
                all.add(sample);
                cycleUsed = true;
            }
            if (cycleUsed) {
                evaluatedCycles++;
            }
        }

        Map<String, ReplayMetrics> horizonMetrics = byHorizon.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toMetrics(),
                        (a, b) -> a, LinkedHashMap::new));

        ReplayReport report = new ReplayReport(symbol, from, to, cycles.size(), evaluatedCycles,
                votesByCycle.values().stream().mapToInt(List::size).sum(),
                verificationByCycleHorizon.size(), skippedNoVote, skippedNoVerification,
                all.toMetrics(), horizonMetrics);

        log.info("[B1-3.replay] symbol={} cycles={}/{} samples={} baselineHit={} overrideHit={} delta={}",
                symbol, evaluatedCycles, cycles.size(), report.overall().samples(),
                report.overall().baselineHitRate(), report.overall().overrideHitRate(),
                report.overall().hitRateDelta());
        return report;
    }

    /** 分块加载 AgentVote，避免大窗口 IN 条件过长。 */
    private Map<String, List<AgentVote>> loadVotes(List<String> cycleIds) {
        List<QuantAgentVote> rows = new ArrayList<>();
        for (List<String> chunk : chunks(cycleIds)) {
            rows.addAll(voteMapper.selectList(new LambdaQueryWrapper<QuantAgentVote>()
                    .in(QuantAgentVote::getCycleId, chunk)));
        }
        Map<String, List<AgentVote>> result = new LinkedHashMap<>();
        for (QuantAgentVote row : rows) {
            result.computeIfAbsent(row.getCycleId(), k -> new ArrayList<>()).add(toAgentVote(row));
        }
        return result;
    }

    /** 分块加载后验验证结果，用 cycleId+horizon 作为匹配 key。 */
    private Map<String, QuantForecastVerification> loadVerifications(String symbol, List<String> cycleIds) {
        List<QuantForecastVerification> rows = new ArrayList<>();
        for (List<String> chunk : chunks(cycleIds)) {
            rows.addAll(verificationMapper.selectList(new LambdaQueryWrapper<QuantForecastVerification>()
                    .eq(QuantForecastVerification::getSymbol, symbol)
                    .in(QuantForecastVerification::getCycleId, chunk)));
        }
        Map<String, QuantForecastVerification> result = new LinkedHashMap<>();
        for (QuantForecastVerification row : rows) {
            result.put(key(row.getCycleId(), row.getHorizon()), row);
        }
        return result;
    }

    /** 把持久化 vote 行还原成 HorizonJudge 使用的领域对象。 */
    private AgentVote toAgentVote(QuantAgentVote row) {
        return new AgentVote(row.getAgent(), row.getHorizon(), parseDirection(row.getDirection()),
                decimal(row.getScore()), decimal(row.getConfidence()),
                row.getExpectedMoveBps() != null ? row.getExpectedMoveBps() : 0,
                row.getVolatilityBps() != null ? row.getVolatilityBps() : 0,
                splitCodes(row.getReasonCodes()), splitCodes(row.getRiskFlags()));
    }

    /** 从 cycle snapshot 取 lastPrice/regime/qualityFlags，缺失时回退为空上下文。 */
    private CycleContext parseCycleContext(QuantForecastCycle cycle) {
        if (cycle.getSnapshotJson() == null || cycle.getSnapshotJson().isBlank()) {
            return new CycleContext(null, List.of(), null);
        }
        try {
            JSONObject snap = JSON.parseObject(cycle.getSnapshotJson());
            BigDecimal lastPrice = snap.getBigDecimal("lastPrice");
            MarketRegime regime = parseRegime(snap.getString("regime"));
            JSONArray rawFlags = snap.getJSONArray("qualityFlags");
            List<String> qualityFlags = rawFlags == null
                    ? List.of()
                    : rawFlags.stream().map(String::valueOf).toList();
            return new CycleContext(lastPrice, qualityFlags, regime);
        } catch (Exception e) {
            log.warn("[B1-3.replay] snapshot解析失败 cycleId={}: {}", cycle.getCycleId(), e.getMessage());
            return new CycleContext(null, List.of(), null);
        }
    }

    /** 单个样本对比 baseline 和 override 是否交易、是否命中、方向是否改变。 */
    private SampleResult evaluateSample(Direction baseline, Direction override, int actualChangeBps) {
        boolean baselineTrade = baseline == Direction.LONG || baseline == Direction.SHORT;
        boolean overrideTrade = override == Direction.LONG || override == Direction.SHORT;
        boolean baselineCorrect = isCorrect(baseline, actualChangeBps);
        boolean overrideCorrect = isCorrect(override, actualChangeBps);
        boolean changed = baseline != override;
        return new SampleResult(baselineTrade, overrideTrade, baselineCorrect, overrideCorrect, changed);
    }

    /** 判断方向是否命中；NO_TRADE 只有实际波动小于阈值才算正确。 */
    private boolean isCorrect(Direction direction, int actualChangeBps) {
        return (direction == Direction.LONG && actualChangeBps > 0)
                || (direction == Direction.SHORT && actualChangeBps < 0)
                || (direction == Direction.NO_TRADE && Math.abs(actualChangeBps) < NO_TRADE_THRESHOLD_BPS);
    }

    /** 字符串方向转枚举，未知值按 NO_TRADE 处理。 */
    private Direction parseDirection(String raw) {
        if ("LONG".equals(raw)) return Direction.LONG;
        if ("SHORT".equals(raw)) return Direction.SHORT;
        return Direction.NO_TRADE;
    }

    /** snapshot 中的 regime 字符串转枚举，空值保持 null。 */
    private MarketRegime parseRegime(String raw) {
        return raw == null || raw.isBlank() ? null : MarketRegime.valueOf(raw);
    }

    /** BigDecimal 转 double，空值按 0 进入回放。 */
    private double decimal(BigDecimal value) {
        return value != null ? value.doubleValue() : 0;
    }

    /** reason/risk 逗号串转去空格列表。 */
    private List<String> splitCodes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 把 cycleId 分块，控制一次查询的 IN 参数数量。 */
    private List<List<String>> chunks(List<String> values) {
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < values.size(); i += QUERY_CHUNK_SIZE) {
            chunks.add(values.subList(i, Math.min(values.size(), i + QUERY_CHUNK_SIZE)));
        }
        return chunks;
    }

    /** cycleId+horizon 组合 key。 */
    private String key(String cycleId, String horizon) {
        return cycleId + "|" + horizon;
    }

    private record CycleContext(BigDecimal lastPrice, List<String> qualityFlags, MarketRegime regime) {}

    private record SampleResult(boolean baselineTrade, boolean overrideTrade,
                                boolean baselineCorrect, boolean overrideCorrect, boolean changed) {}

    public record ReplayReport(
            String symbol,
            LocalDateTime from,
            LocalDateTime to,
            int loadedCycles,
            int evaluatedCycles,
            int loadedVotes,
            int loadedVerifications,
            int skippedNoVoteCycles,
            int skippedNoVerificationSlots,
            ReplayMetrics overall,
            Map<String, ReplayMetrics> byHorizon
    ) {}

    public record ReplayMetrics(
            String bucket,
            int samples,
            int baselineTrades,
            int overrideTrades,
            int baselineCorrect,
            int overrideCorrect,
            double baselineHitRate,
            double overrideHitRate,
            double hitRateDelta,
            int changedDirections,
            int improvedSamples,
            int worsenedSamples
    ) {}

    private static class ReplayAccumulator {
        private final String bucket;
        private int samples;
        private int baselineTrades;
        private int overrideTrades;
        private int baselineCorrect;
        private int overrideCorrect;
        private int changedDirections;
        private int improvedSamples;
        private int worsenedSamples;

        private ReplayAccumulator(String bucket) {
            this.bucket = bucket;
        }

        /** 累加单个样本的 baseline/override 对比结果。 */
        private void add(SampleResult sample) {
            samples++;
            if (sample.baselineTrade()) baselineTrades++;
            if (sample.overrideTrade()) overrideTrades++;
            if (sample.baselineCorrect()) baselineCorrect++;
            if (sample.overrideCorrect()) overrideCorrect++;
            if (sample.changed()) changedDirections++;
            if (!sample.baselineCorrect() && sample.overrideCorrect()) improvedSamples++;
            if (sample.baselineCorrect() && !sample.overrideCorrect()) worsenedSamples++;
        }

        /** 输出当前 bucket 的命中率、差值和改变方向统计。 */
        private ReplayMetrics toMetrics() {
            double baselineHitRate = rate(baselineCorrect, samples);
            double overrideHitRate = rate(overrideCorrect, samples);
            return new ReplayMetrics(bucket, samples, baselineTrades, overrideTrades,
                    baselineCorrect, overrideCorrect, baselineHitRate, overrideHitRate,
                    round4(overrideHitRate - baselineHitRate),
                    changedDirections, improvedSamples, worsenedSamples);
        }

        /** 计算命中率并保留 4 位小数。 */
        private static double rate(int numerator, int denominator) {
            return denominator == 0 ? 0 : round4((double) numerator / denominator);
        }

        /** 回放报告统一 4 位小数，便于人工比较。 */
        private static double round4(double value) {
            return Math.round(value * 10000.0) / 10000.0;
        }
    }
}
