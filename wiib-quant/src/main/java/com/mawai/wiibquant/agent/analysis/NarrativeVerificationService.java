package com.mawai.wiibquant.agent.analysis;

import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import com.mawai.wiibcommon.entity.QuantNarrativeVerification;
import com.mawai.wiibcommon.entity.QuantSnapshot;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.agent.research.ForecastHorizon;
import com.mawai.wiibquant.agent.research.forecast.VolState;
import com.mawai.wiibquant.agent.research.forecast.VolStateClassifier;
import com.mawai.wiibquant.mapper.QuantDeepAnalysisMapper;
import com.mawai.wiibquant.mapper.QuantNarrativeVerificationMapper;
import com.mawai.wiibquant.mapper.QuantSnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 叙事对账服务：Judge 的三情景概率到期（H12）后拿真实走势对答案——快照轨有记分卡，叙事轨同样要战绩。
 * <ul>
 *   <li>实际情景判定界 = 挂靠快照 H12 腿的 lowCut（90天 |收益| 下三分位）：|realized| &lt; lowCut → RANGE，
 *       否则按符号 BULL/BEAR——历史基率天然 ≈1/3 均分，与三情景先验对齐，零新调参；PIT 铁律同 vol 对账</li>
 *   <li>评分：三分类 Brier（均匀瞎猜基线 2/3≈0.667，越低越好）+ 最高概率情景是否命中</li>
 *   <li>chat 轨研判的 closeTime 是墙钟，先地板对齐到 ≤closeTime 的最近闭合 bar 再取 12h 收益</li>
 *   <li>缺档界/情景损坏（永久条件）→ 落 SKIPPED 行不再重试；K线未就绪 → 留待下轮自愈，
 *       到期超 24h 仍缺才放弃落 SKIPPED（防幽灵行长期堵批次头部）</li>
 * </ul>
 * (analysis_id) 唯一键幂等，无游标——NOT EXISTS 反连接扫"到期未对账"，重启零状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NarrativeVerificationService {

    static final String STATUS_VERIFIED = "VERIFIED";
    static final String STATUS_SKIPPED = "SKIPPED";

    private static final int BATCH_LIMIT = 200;
    private static final long HORIZON_MS = ForecastHorizon.H12.millis();
    /** K线缺口自愈宽限：到期超过此时长仍无K线则放弃对账（watchdog 回补窗口远小于它）。 */
    private static final long KLINE_GIVE_UP_MS = 24 * 3_600_000L;

    private final QuantDeepAnalysisMapper analysisMapper;
    private final QuantNarrativeVerificationMapper verificationMapper;
    private final QuantSnapshotMapper snapshotMapper;
    private final KlineHistoryStore historyStore;

    /** 扫全部到期未对账研判行（不分 symbol），返回写入行数；K线未就绪的行本轮跳过。 */
    public int verifyDue() {
        long now = System.currentTimeMillis();
        List<QuantDeepAnalysis> due = analysisMapper.selectDueUnverified(now - HORIZON_MS, BATCH_LIMIT);
        if (due.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (QuantDeepAnalysis analysis : due) {
            QuantNarrativeVerification row = verifyOne(analysis, now);
            if (row != null && insertIdempotent(row)) {
                written++;
            }
        }
        log.info("[NarrativeVerify] due={} written={}", due.size(), written);
        return written;
    }

    /** 单行对账；K线未就绪且未超宽限返回 null（下轮重试）。包级可见供测试直调。 */
    QuantNarrativeVerification verifyOne(QuantDeepAnalysis analysis, long now) {
        JSONObject scenarios = parseScenarios(analysis.getScenariosJson());
        Double lowCut = resolveRangeCut(analysis.getSnapshotId());
        if (scenarios == null || lowCut == null) {
            log.info("[NarrativeVerify] 不可对账落SKIPPED analysisId={} 情景损坏={} 缺档界={}",
                    analysis.getId(), scenarios == null, lowCut == null);
            return skipRow(analysis, scenarios);
        }

        // 定时轨 closeTime 本就在 bar 网格上；chat 轨是墙钟，地板对齐后 +12h 仍落在网格
        KlineBar start = barAtOrBefore(analysis.getSymbol(), analysis.getCloseTime());
        KlineBar end = start != null ? barAt(analysis.getSymbol(), start.closeTime() + HORIZON_MS) : null;
        if (start == null || end == null) {
            if (now - (analysis.getCloseTime() + HORIZON_MS) > KLINE_GIVE_UP_MS) {
                log.warn("[NarrativeVerify] K线缺口超宽限放弃 analysisId={} closeTime={}",
                        analysis.getId(), analysis.getCloseTime());
                return skipRow(analysis, scenarios);
            }
            return null;
        }

        double realized = Math.log(end.close().doubleValue() / start.close().doubleValue());
        // RANGE 判定 = 分类单一真相源的 LOW 档（|realized| < lowCut；界点归方向，即"界点不归 LOW"）
        boolean isRange = VolStateClassifier.classifyWithCuts(
                Math.abs(realized), lowCut, Double.MAX_VALUE) == VolState.LOW;
        String actual = isRange ? "RANGE" : (realized > 0 ? "BULL" : "BEAR");

        int bull = Math.max(0, scenarios.getIntValue("bullPct"));
        int range = Math.max(0, scenarios.getIntValue("rangePct"));
        int bear = Math.max(0, scenarios.getIntValue("bearPct"));
        double sum = bull + range + bear; // parseScenarios 已保证 > 0
        double pBull = bull / sum;
        double pRange = range / sum;
        double pBear = bear / sum;
        // 并列最大偏 RANGE：平手不硬挤方向
        String predicted = range >= bull && range >= bear ? "RANGE"
                : bull > bear ? "BULL" : bear > bull ? "BEAR" : "RANGE";
        double brier = sq(pBull - ind(actual, "BULL"))
                + sq(pRange - ind(actual, "RANGE"))
                + sq(pBear - ind(actual, "BEAR"));

        QuantNarrativeVerification row = baseRow(analysis, scenarios);
        row.setRangeCutBps((int) Math.round(lowCut * 10_000));
        row.setRealizedReturnBps((int) Math.round(realized * 10_000));
        row.setActualScenario(actual);
        row.setPredictedScenario(predicted);
        row.setScenarioHit(predicted.equals(actual));
        row.setBrier(brier);
        row.setStatus(STATUS_VERIFIED);
        return row;
    }

    /** 情景 JSON 合法性：可解析且三情景和 > 0，否则视为损坏。 */
    private static JSONObject parseScenarios(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JSONObject o = JSONObject.parseObject(json);
            if (o == null) {
                return null;
            }
            int sum = Math.max(0, o.getIntValue("bullPct"))
                    + Math.max(0, o.getIntValue("rangePct"))
                    + Math.max(0, o.getIntValue("bearPct"));
            return sum > 0 ? o : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从挂靠快照取 H12 腿 lowCut；快照缺失/腿缺失/非正值均视为不可对账。 */
    private Double resolveRangeCut(Long snapshotId) {
        if (snapshotId == null) {
            return null;
        }
        QuantSnapshot snap = snapshotMapper.selectById(snapshotId);
        if (snap == null || snap.getVolLegsJson() == null || snap.getVolLegsJson().isBlank()) {
            return null;
        }
        try {
            JSONObject legs = JSONObject.parseObject(snap.getVolLegsJson());
            JSONObject leg = legs != null ? legs.getJSONObject(ForecastHorizon.H12.name()) : null;
            double lowCut = leg != null ? leg.getDoubleValue("lowCut") : 0;
            return lowCut > 0 ? lowCut : null;
        } catch (Exception e) {
            return null;
        }
    }

    private KlineBar barAtOrBefore(String symbol, long t) {
        List<KlineBar> bars = historyStore.load(symbol, KlineHistoryStore.DEFAULT_INTERVAL,
                t - 4 * KlineHistoryStore.DEFAULT_BAR_MILLIS, t + KlineHistoryStore.DEFAULT_BAR_MILLIS);
        KlineBar best = null;
        for (KlineBar b : bars) {
            if (b.closeTime() <= t && (best == null || b.closeTime() > best.closeTime())) {
                best = b;
            }
        }
        return best;
    }

    private KlineBar barAt(String symbol, long closeTime) {
        List<KlineBar> bars = historyStore.load(symbol, KlineHistoryStore.DEFAULT_INTERVAL,
                closeTime - 2 * KlineHistoryStore.DEFAULT_BAR_MILLIS, closeTime + KlineHistoryStore.DEFAULT_BAR_MILLIS);
        for (KlineBar b : bars) {
            if (b.closeTime() == closeTime) {
                return b;
            }
        }
        return null;
    }

    private static QuantNarrativeVerification skipRow(QuantDeepAnalysis analysis, JSONObject scenarios) {
        QuantNarrativeVerification row = baseRow(analysis, scenarios);
        row.setStatus(STATUS_SKIPPED);
        return row;
    }

    private static QuantNarrativeVerification baseRow(QuantDeepAnalysis analysis, JSONObject scenarios) {
        QuantNarrativeVerification row = new QuantNarrativeVerification();
        row.setAnalysisId(analysis.getId());
        row.setSymbol(analysis.getSymbol());
        row.setCloseTime(analysis.getCloseTime());
        row.setHorizon(ForecastHorizon.H12.name());
        if (scenarios != null) {
            row.setBullPct(scenarios.getIntValue("bullPct"));
            row.setRangePct(scenarios.getIntValue("rangePct"));
            row.setBearPct(scenarios.getIntValue("bearPct"));
        }
        row.setNoDirection(analysis.getNoDirection());
        row.setVerifiedAt(LocalDateTime.now());
        return row;
    }

    private boolean insertIdempotent(QuantNarrativeVerification row) {
        try {
            verificationMapper.insert(row);
            return true;
        } catch (DuplicateKeyException e) {
            // 并发触发的正常路径（事件与 cron 兜底可能同刻扫到同一行）
            return false;
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double ind(String actual, String scenario) {
        return scenario.equals(actual) ? 1.0 : 0.0;
    }
}
