package com.mawai.wiibservice.agent.quant.judge;

import com.mawai.wiibservice.agent.quant.domain.AgentVote;
import com.mawai.wiibservice.agent.quant.domain.Direction;
import com.mawai.wiibservice.agent.quant.domain.MarketRegime;
import com.mawai.wiibservice.agent.quant.service.FactorWeightOverrideService;
import com.mawai.wiibservice.agent.quant.memory.AgentPerformanceMemoryService.AgentStat;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 新架构共识裁决器：融合 research 主票 + evidence 辅助票，输出方向/置信度/分歧。
 *
 * <p>与旧短线裁决器的关键区别：
 * <ul>
 *   <li>不产出 entry/tp/sl/leverage/position —— 这些下沉到 RiskGate + 交易执行层</li>
 *   <li>horizon 从旧 0-30 分钟三段改为 H6/H12/H24</li>
 *   <li>research 主票权重远高于 evidence 辅助票</li>
 *   <li>不依赖 lastPrice/regime 做入场计算</li>
 * </ul>
 *
 * <p>裁决逻辑：
 * <ol>
 *   <li>research 主票提供 direction/directionConfidence 作为锚定方向</li>
 *   <li>evidence 辅助票按加权投票修正置信度和分歧度</li>
 *   <li>弱信号衰减（比旧 judge 更保守，长 horizon 天然置信低）</li>
 * </ol>
 */
@Slf4j
public class ConsensusJudge {

    private static final double EPSILON = 1e-9;

    /** evidence agent → H6/H12/H24 基础权重（research 主票权重由输入 confidence 隐式表达） */
    private static final Map<String, Map<String, Double>> EVIDENCE_WEIGHTS = Map.of(
            "microstructure", Map.of("H6", 0.20, "H12", 0.08, "H24", 0.03),
            "momentum",       Map.of("H6", 0.22, "H12", 0.22, "H24", 0.12),
            "regime",         Map.of("H6", 0.18, "H12", 0.22, "H24", 0.25),
            "volatility",     Map.of("H6", 0.15, "H12", 0.18, "H24", 0.20),
            "news_event",     Map.of("H6", 0.25, "H12", 0.30, "H24", 0.40)
    );

    public static double baseEvidenceWeight(String agent, String horizon) {
        return EVIDENCE_WEIGHTS.getOrDefault(agent, Map.of())
                .getOrDefault(horizon, 0.05);
    }

    /** 裁决明细，供日志和调试 */
    public record Adjustment(String horizon, String agent, double baseWeight, double finalWeight,
                              double memoryMultiplier, double accuracy, int sampleCount) {}

    private final String horizon;
    private final int researchDirectionSign;
    private final double researchDirectionConfidence;
    private final Map<String, Map<String, AgentStat>> agentStats;
    private final FactorWeightOverrideService weightOverrideService;
    private final MarketRegime regime;
    private final Boolean weightOverrideEnabled;
    private final List<Adjustment> adjustments = new ArrayList<>();

    public ConsensusJudge(String horizon, int researchDirectionSign, double researchDirectionConfidence,
                          Map<String, Map<String, AgentStat>> agentStats,
                          FactorWeightOverrideService weightOverrideService) {
        this(horizon, researchDirectionSign, researchDirectionConfidence, agentStats,
                weightOverrideService, null, null);
    }

    public ConsensusJudge(String horizon, int researchDirectionSign, double researchDirectionConfidence,
                          Map<String, Map<String, AgentStat>> agentStats,
                          FactorWeightOverrideService weightOverrideService,
                          MarketRegime regime,
                          Boolean weightOverrideEnabled) {
        this.horizon = horizon;
        this.researchDirectionSign = researchDirectionSign;
        this.researchDirectionConfidence = researchDirectionConfidence;
        this.agentStats = agentStats != null ? agentStats : Map.of();
        this.weightOverrideService = weightOverrideService;
        this.regime = regime;
        this.weightOverrideEnabled = weightOverrideEnabled;
    }

    public List<Adjustment> getAdjustments() {
        return List.copyOf(adjustments);
    }

    /**
     * 裁决：融合 research 主票 + evidence 辅助票。
     *
     * @param evidenceVotes 来自 Evidence Agents 的投票（horizon 已过滤到本区间）
     * @return 融合后的方向/置信度/分歧度
     */
    public ConsensusForecast judge(List<AgentVote> evidenceVotes) {
        adjustments.clear();

        // 第一步：research 主票锚定
        Direction researchDir = researchDirectionSign > 0 ? Direction.LONG
                : researchDirectionSign < 0 ? Direction.SHORT
                : Direction.NO_TRADE;
        double researchConf = Math.clamp(researchDirectionConfidence, 0.0, 1.0);

        // 无 evidence 时，直接以 research 主票为准
        if (evidenceVotes == null || evidenceVotes.isEmpty()) {
            log.info("[ConsensusJudge] {} 无evidence → research锚定 dir={} conf={}",
                    horizon, researchDir, String.format("%.2f", researchConf));
            return new ConsensusForecast(horizon, researchDir, researchConf,
                    researchDir == Direction.NO_TRADE ? 1.0 : 0.0);
        }

        // 第二步：evidence 加权投票
        double longScore = 0;
        double shortScore = 0;
        double longConfSum = 0;
        double shortConfSum = 0;

        for (AgentVote vote : evidenceVotes) {
            double weight = getEvidenceWeight(vote.agent());

            if (vote.score() > EPSILON) {
                longScore += weight * Math.abs(vote.score());
                longConfSum += weight * vote.confidence();
            } else if (vote.score() < -EPSILON) {
                shortScore += weight * Math.abs(vote.score());
                shortConfSum += weight * vote.confidence();
            }
            // score==0 的票（风险标记类）不参与方向聚合
        }

        // 第三步：融合 research 主票 + evidence。research 是主票，evidence 只做辅助修正。
        double researchWeight = 0.75;
        double evidenceTotalWeight = 0.25;

        double totalEdge = longScore - shortScore;
        double evidenceTotal = longScore + shortScore + EPSILON;
        double evidenceDisagreement = 1.0 - Math.abs(totalEdge) / evidenceTotal;

        // evidence 方向归一化得分
        double evidenceNormScore = (longScore - shortScore) / evidenceTotal; // [-1, 1]
        // evidence 置信度：取主导方向的加权置信度和（Σ weight×conf）做近似，乘 evidenceTotal/(longScore+shortScore)≈1 归一；
        // 无有效 evidence 时给中性 0.3。注意这是加权量纲、非纯 [0,1]，最终由 fusedConf 的 clamp 收敛。
        double evidenceConf = evidenceTotal > EPSILON
                ? Math.max(longConfSum, shortConfSum) / (longScore + shortScore) * evidenceTotal
                : 0.3;

        // research 方向信号: [-1, 1]
        double researchSignal = researchDirectionSign * researchConf;

        if (researchDir == Direction.NO_TRADE) {
            if (Math.abs(evidenceNormScore) >= 0.65 && evidenceConf >= 0.55) {
                Direction evidenceDir = evidenceNormScore > 0 ? Direction.LONG : Direction.SHORT;
                double cappedConf = Math.min(0.45, evidenceConf * 0.75);
                log.info("[ConsensusJudge] {} research=NO_TRADE strongEvidence={} conf={} → conservative dir={}",
                        horizon, String.format("%.3f", evidenceNormScore), String.format("%.2f", evidenceConf), evidenceDir);
                return new ConsensusForecast(horizon, evidenceDir, cappedConf,
                        Math.clamp(evidenceDisagreement + 0.20, 0.0, 1.0));
            }
            return new ConsensusForecast(horizon, Direction.NO_TRADE, researchConf,
                    Math.clamp(evidenceDisagreement + 0.20, 0.0, 1.0));
        }

        // 融合方向 = research 主票 + evidence 辅助
        double fusedSignal = researchSignal * researchWeight + evidenceNormScore * evidenceTotalWeight;
        Direction fusedDir = fusedSignal > 0.05 ? Direction.LONG
                : fusedSignal < -0.05 ? Direction.SHORT
                : Direction.NO_TRADE;

        // 融合置信度 = research conf × 0.6 + evidence conf × 0.4
        double fusedConf = Math.clamp(researchConf * 0.60 + evidenceConf * 0.40, 0.0, 1.0);

        // 分歧度 = research 方向与 evidence 方向不一致时放大
        double directionMatch = (researchSignal > 0 && evidenceNormScore > 0)
                || (researchSignal < 0 && evidenceNormScore < 0) ? 0.0 : 0.3;
        double fusedDisagreement = Math.clamp(evidenceDisagreement * 0.7 + directionMatch, 0.0, 1.0);

        log.info("[ConsensusJudge] {} research={}/{} evidenceScore={} evidenceConf={} → dir={} conf={} disagree={}",
                horizon, researchDir, String.format("%.2f", researchConf),
                String.format("%.3f", evidenceNormScore), String.format("%.2f", evidenceConf),
                fusedDir, String.format("%.2f", fusedConf), String.format("%.2f", fusedDisagreement));

        return new ConsensusForecast(horizon, fusedDir, fusedConf, fusedDisagreement);
    }

    private double getEvidenceWeight(String agent) {
        double base = baseEvidenceWeight(agent, horizon);
        if (weightOverrideService != null) {
            base = weightOverrideEnabled == null
                    ? weightOverrideService.apply(agent, horizon, regime, base)
                    : weightOverrideService.apply(agent, horizon, regime, base, weightOverrideEnabled);
        }

        // 记忆调权（保守）
        AgentStat stat = lookupStat(agent);
        if (stat != null) {
            double multiplier = accuracyMultiplier(stat.accuracy());
            double finalWeight = base * multiplier;
            if (Math.abs(multiplier - 1.0) > 0.001) {
                adjustments.add(new Adjustment(horizon, agent, base, finalWeight,
                        multiplier, stat.accuracy(), stat.sampleCount()));
            }
            return finalWeight;
        }
        return base;
    }

    private AgentStat lookupStat(String agent) {
        Map<String, AgentStat> horizons = agentStats.get(agent);
        return horizons != null ? horizons.get(horizon) : null;
    }

    private double accuracyMultiplier(double accuracy) {
        double acc = Math.clamp(accuracy, 0.0, 1.0);
        if (acc >= 0.58) {
            return Math.clamp(1.0 + (acc - 0.58) / 0.22 * 0.15, 1.0, 1.15);
        }
        if (acc <= 0.45) {
            return Math.clamp(1.0 - (0.45 - acc) / 0.25 * 0.15, 0.85, 1.0);
        }
        return 1.0;
    }
}
