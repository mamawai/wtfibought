package com.mawai.wiibquant.agent.analysis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.QuantNarrativeVerification;
import com.mawai.wiibcommon.entity.QuantVolVerification;
import com.mawai.wiibquant.mapper.QuantNarrativeVerificationMapper;
import com.mawai.wiibquant.mapper.QuantVolVerificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能记分卡（P3）：vol 预测验证行的滚动聚合——QLIKE vs naive 基准 + vol-state 命中率；
 * 叙事轨（Judge 三情景）同卡展示：Brier vs 均匀基线 + 情景命中。
 * 诚实约束：只聚合已验证样本、如实报运行天数；样本不足时如实标注，不装战绩。
 * regime/方向刻意不在记分卡上（research 实证无 skill）。
 * 统计口径警示：验证行的预测窗口大量重叠（5m 间隔 vs 6-24h horizon；研判 1h vs 12h 窗同理），
 * 行间高度自相关，有效独立样本量远小于行数——均值可作点估计展示，但禁止把行数当独立样本做显著性检验。
 */
@Service
@RequiredArgsConstructor
public class ScorecardService {

    /** 三分类均匀瞎猜的 Brier：(1/3,1/3,1/3) 对任意 one-hot 真值恒为 2/3。 */
    private static final double UNIFORM_BRIER = 2.0 / 3.0;

    private final QuantVolVerificationMapper verificationMapper;
    private final QuantNarrativeVerificationMapper narrativeMapper;

    /**
     * @param avgQlike          预测平均 QLIKE（越低越好）
     * @param avgBaselineQlike  naive 基准平均 QLIKE
     * @param qlikeImprovement  相对基准改善率 (baseline-forecast)/baseline，正=跑赢基准
     * @param qlikeWinRate      单点 qlike < baseline 的占比
     * @param volStateHitRate   vol-state 三分类命中率（随机基线 1/3）
     */
    public record HorizonScore(String horizon, int samples, double avgQlike, double avgBaselineQlike,
                               double qlikeImprovement, double qlikeWinRate, double volStateHitRate) {
    }

    /**
     * Judge 三情景战绩（叙事轨）。
     * @param avgBrier         平均 Brier（越低越好）
     * @param uniformBrier     均匀瞎猜基线 2/3
     * @param brierImprovement 相对基线改善率，正=优于瞎猜
     * @param scenarioHitRate  最高概率情景命中率（随机基线 1/3）
     * @param noDirectionSamples 已验证样本中 Judge 自认看不清的行数
     * @param skippedSamples   不可对账被跳过的行数（诚实报数）
     */
    public record NarrativeScore(int samples, double avgBrier, double uniformBrier, double brierImprovement,
                                 double scenarioHitRate, int noDirectionSamples, int skippedSamples) {
    }

    public record Scorecard(String symbol, int windowDays, long runningDays, int totalSamples,
                            List<HorizonScore> horizons, NarrativeScore narrative, String note) {
    }

    public Scorecard scorecard(String symbol, int windowDays) {
        long fromCloseTime = System.currentTimeMillis() - Duration.ofDays(windowDays).toMillis();
        NarrativeScore narrative = narrativeScore(symbol, fromCloseTime);
        List<QuantVolVerification> rows = verificationMapper.selectList(
                new LambdaQueryWrapper<QuantVolVerification>()
                        .eq(QuantVolVerification::getSymbol, symbol)
                        .ge(QuantVolVerification::getCloseTime, fromCloseTime));
        if (rows.isEmpty()) {
            return new Scorecard(symbol, windowDays, 0, 0, List.of(), narrative,
                    "暂无已验证样本（预测点需到期后才可对账，H24 需运行至少一天）");
        }

        long earliest = rows.stream().mapToLong(QuantVolVerification::getCloseTime).min().orElse(0);
        long runningDays = Duration.ofMillis(System.currentTimeMillis() - earliest).toDays();

        Map<String, List<QuantVolVerification>> byHorizon = new LinkedHashMap<>();
        for (String h : List.of("H6", "H12", "H24")) {
            byHorizon.put(h, rows.stream().filter(r -> h.equals(r.getHorizon())).toList());
        }
        List<HorizonScore> scores = byHorizon.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .map(e -> score(e.getKey(), e.getValue()))
                .toList();

        String note = runningDays < 7
                ? "运行仅 " + runningDays + " 天，样本量尚小，指标波动大，谨慎解读"
                : "QLIKE 越低越好；improvement>0 = 跑赢 naive 基准；vol-state 随机基线 33.3%；叙事 Brier < 0.667 = 优于均匀瞎猜";
        return new Scorecard(symbol, windowDays, runningDays, rows.size(), scores, narrative, note);
    }

    /** 叙事轨聚合：只聚合 VERIFIED 行，SKIPPED 单独报数；窗口内无行返回 null（卡上不出现该段）。 */
    private NarrativeScore narrativeScore(String symbol, long fromCloseTime) {
        List<QuantNarrativeVerification> rows = narrativeMapper.selectList(
                new LambdaQueryWrapper<QuantNarrativeVerification>()
                        .eq(QuantNarrativeVerification::getSymbol, symbol)
                        .ge(QuantNarrativeVerification::getCloseTime, fromCloseTime));
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        List<QuantNarrativeVerification> verified = rows.stream()
                .filter(r -> NarrativeVerificationService.STATUS_VERIFIED.equals(r.getStatus()))
                .toList();
        int skipped = rows.size() - verified.size();
        if (verified.isEmpty()) {
            return new NarrativeScore(0, 0, round4(UNIFORM_BRIER), 0, 0, 0, skipped);
        }
        double avgBrier = verified.stream().mapToDouble(QuantNarrativeVerification::getBrier).average().orElse(0);
        double hitRate = (double) verified.stream()
                .filter(r -> Boolean.TRUE.equals(r.getScenarioHit())).count() / verified.size();
        int noDirection = (int) verified.stream()
                .filter(r -> Boolean.TRUE.equals(r.getNoDirection())).count();
        return new NarrativeScore(verified.size(), round4(avgBrier), round4(UNIFORM_BRIER),
                round4((UNIFORM_BRIER - avgBrier) / UNIFORM_BRIER), round4(hitRate), noDirection, skipped);
    }

    private static HorizonScore score(String horizon, List<QuantVolVerification> rows) {
        int n = rows.size();
        double avgQlike = rows.stream().mapToDouble(QuantVolVerification::getQlike).average().orElse(0);
        double avgBaseline = rows.stream().mapToDouble(QuantVolVerification::getBaselineQlike).average().orElse(0);
        double improvement = avgBaseline > 0 ? (avgBaseline - avgQlike) / avgBaseline : 0;
        double winRate = (double) rows.stream().filter(r -> r.getQlike() < r.getBaselineQlike()).count() / n;
        double hitRate = (double) rows.stream().filter(r -> Boolean.TRUE.equals(r.getVolStateHit())).count() / n;
        return new HorizonScore(horizon, n, round4(avgQlike), round4(avgBaseline),
                round4(improvement), round4(winRate), round4(hitRate));
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
