package com.mawai.wiibquant.agent.quant.judge;

import com.mawai.wiibquant.agent.quant.domain.AgentVote;
import com.mawai.wiibquant.agent.quant.domain.Direction;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 共识裁决器：融合 research 主票 + evidence 辅助票，输出方向/置信度/分歧。
 *
 * <p>evidence 只用<b>静态基础权重</b>——按方向命中率"调权"的死回路已移除：
 * research 框架实证方向预测不跑赢 naive，调一个死信号无意义。</p>
 *
 * <p>裁决逻辑：
 * <ol>
 *   <li>research 主票提供 direction/directionConfidence 作为锚定方向</li>
 *   <li>evidence 辅助票按静态加权投票修正置信度和分歧度</li>
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

    private final String horizon;
    private final int researchDirectionSign;
    private final double researchDirectionConfidence;

    public ConsensusJudge(String horizon, int researchDirectionSign, double researchDirectionConfidence) {
        this.horizon = horizon;
        this.researchDirectionSign = researchDirectionSign;
        this.researchDirectionConfidence = researchDirectionConfidence;
    }

    /**
     * 裁决：融合 research 主票 + evidence 辅助票。
     *
     * @param evidenceVotes 来自 Evidence Agents 的投票（horizon 已过滤到本区间）
     * @return 融合后的方向/置信度/分歧度
     */
    public ConsensusForecast judge(List<AgentVote> evidenceVotes) {
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

        // 第二步：evidence 加权投票（静态基础权重）
        double longScore = 0;
        double shortScore = 0;
        double longConfSum = 0;
        double shortConfSum = 0;

        for (AgentVote vote : evidenceVotes) {
            double weight = baseEvidenceWeight(vote.agent(), horizon);

            double score = weight * Math.abs(vote.score());
            if (vote.score() > EPSILON) {
                longScore += score;
                longConfSum += weight * vote.confidence();
            } else if (vote.score() < -EPSILON) {
                shortScore += score;
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

        // evidence 方向归一化得分 [-1, 1]
        double evidenceNormScore = (longScore - shortScore) / evidenceTotal;
        // evidence 置信度：取主导方向的加权置信度和做近似；无有效 evidence 时给中性 0.3。
        double evidenceConf = evidenceTotal > EPSILON
                ? Math.max(longConfSum, shortConfSum) / (longScore + shortScore) * evidenceTotal
                : 0.3;

        // research 方向信号 [-1, 1]
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
}
