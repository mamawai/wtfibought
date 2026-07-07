package com.mawai.wiibquant.agent.analysis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.QuantVolVerification;
import com.mawai.wiibquant.mapper.QuantVolVerificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能记分卡（P3）：vol 预测验证行的滚动聚合——QLIKE vs naive 基准 + vol-state 命中率。
 * 诚实约束：只聚合已验证样本、如实报运行天数；样本不足时如实标注，不装战绩。
 * regime/方向刻意不在记分卡上（research 实证无 skill）。
 */
@Service
@RequiredArgsConstructor
public class ScorecardService {

    private final QuantVolVerificationMapper verificationMapper;

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

    public record Scorecard(String symbol, int windowDays, long runningDays, int totalSamples,
                            List<HorizonScore> horizons, String note) {
    }

    public Scorecard scorecard(String symbol, int windowDays) {
        long fromCloseTime = System.currentTimeMillis() - Duration.ofDays(windowDays).toMillis();
        List<QuantVolVerification> rows = verificationMapper.selectList(
                new LambdaQueryWrapper<QuantVolVerification>()
                        .eq(QuantVolVerification::getSymbol, symbol)
                        .ge(QuantVolVerification::getCloseTime, fromCloseTime));
        if (rows.isEmpty()) {
            return new Scorecard(symbol, windowDays, 0, 0, List.of(),
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
                : "QLIKE 越低越好；improvement>0 = 跑赢 naive 基准；vol-state 随机基线 33.3%";
        return new Scorecard(symbol, windowDays, runningDays, rows.size(), scores, note);
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
