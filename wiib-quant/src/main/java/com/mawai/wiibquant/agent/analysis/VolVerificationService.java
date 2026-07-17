package com.mawai.wiibquant.agent.analysis;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.QuantSnapshot;
import com.mawai.wiibcommon.entity.QuantVolVerification;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.agent.research.ForecastHorizon;
import com.mawai.wiibquant.agent.research.forecast.VolStateClassifier;
import com.mawai.wiibquant.agent.research.metrics.VolForecastScore;
import com.mawai.wiibquant.mapper.QuantSnapshotMapper;
import com.mawai.wiibquant.mapper.QuantVolVerificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * vol 预测验证服务（P3）：扫到期预测点（快照×horizon），用实际走势对账。
 * <ul>
 *   <li>QLIKE 口径 = research 的 {@link VolForecastScore}（Patton 2011），不另造指标</li>
 *   <li>naive 基准 = 预测时点前 3 天、5m 步进的重叠 horizon 收益 RMS（research rolling3d 同款构造），
 *       从不可变历史 K 线 PIT 重建。曾用"上一个单窗 |收益|"——安静时段塌零后 QLIKE 爆到 1e5+，弃用</li>
 *   <li>vol-state 实际态用<b>快照携带的档界</b>分类——PIT 铁律，禁止验证时重算档界（分布漂移污染对账）</li>
 * </ul>
 * 游标在内存（重启重扫），(snapshot_id, horizon) 唯一键幂等兜底；K 线空洞的预测点直接丢弃不阻塞游标。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VolVerificationService {

    private static final int BATCH_LIMIT = 200;
    /** naive 基准回看窗：3 天（对齐 research rolling3d 口径） */
    static final long BASELINE_LOOKBACK_MILLIS = java.time.Duration.ofDays(3).toMillis();
    /** 基准最少有效窗数：低于此数（K 线大面积空洞）丢点，禁止退化回易塌零的少窗基准 */
    private static final int MIN_BASELINE_WINDOWS = 12;

    private final QuantSnapshotMapper snapshotMapper;
    private final QuantVolVerificationMapper verificationMapper;
    private final KlineHistoryStore historyStore;

    /** symbol:horizon → 已处理的最大快照 closeTime（内存游标，唯一键兜底幂等） */
    private final Map<String, Long> cursors = new ConcurrentHashMap<>();

    /** 验证某 symbol 某 horizon 的全部到期预测点，返回写入行数。 */
    public int verifyDue(String symbol, ForecastHorizon horizon) {
        String cursorKey = symbol + ":" + horizon.name();
        long cursor = cursors.getOrDefault(cursorKey, 0L);
        long now = System.currentTimeMillis();
        List<QuantSnapshot> due = snapshotMapper.selectList(new LambdaQueryWrapper<QuantSnapshot>()
                .eq(QuantSnapshot::getSymbol, symbol)
                .gt(QuantSnapshot::getCloseTime, cursor)
                .le(QuantSnapshot::getCloseTime, now - horizon.millis())
                .orderByAsc(QuantSnapshot::getCloseTime)
                .last("LIMIT " + BATCH_LIMIT));
        if (due.isEmpty()) {
            return 0;
        }

        // 一次 load 覆盖 batch 全部点的 baseline 回看窗(t-3d-h)与 realized 窗(t+h)
        long from = due.getFirst().getCloseTime() - BASELINE_LOOKBACK_MILLIS - horizon.millis() - KlineHistoryStore.DEFAULT_BAR_MILLIS;
        long to = due.getLast().getCloseTime() + horizon.millis() + KlineHistoryStore.DEFAULT_BAR_MILLIS;
        Map<Long, KlineBar> barsByCloseTime = new HashMap<>();
        for (KlineBar bar : historyStore.load(symbol, KlineHistoryStore.DEFAULT_INTERVAL, from, to)) {
            barsByCloseTime.put(bar.closeTime(), bar);
        }

        int written = 0;
        for (QuantSnapshot snap : due) {
            QuantVolVerification row = verifyOne(snap, horizon, barsByCloseTime);
            if (row != null && insertIdempotent(row)) {
                written++;
            }
            cursors.merge(cursorKey, snap.getCloseTime(), Math::max);
        }
        log.info("[VolVerify] symbol={} horizon={} due={} written={}", symbol, horizon, due.size(), written);
        return written;
    }

    /** 单点对账；K 线空洞返回 null（丢点不阻塞）。包级可见供测试直调。 */
    QuantVolVerification verifyOne(QuantSnapshot snap, ForecastHorizon horizon, Map<Long, KlineBar> bars) {
        JSONObject legs = JSONObject.parseObject(snap.getVolLegsJson());
        JSONObject leg = legs != null ? legs.getJSONObject(horizon.name()) : null;
        if (leg == null) {
            return null;
        }
        long t = snap.getCloseTime();
        KlineBar barT = bars.get(t);
        KlineBar barNext = bars.get(t + horizon.millis());
        double baselineSigma = rollingBaselineSigma(t, horizon, bars);
        if (barT == null || barNext == null || Double.isNaN(baselineSigma)) {
            log.debug("[VolVerify] K线空洞丢点 symbol={} t={} horizon={}", snap.getSymbol(), t, horizon);
            return null;
        }

        double forecastSigma = leg.getIntValue("sigmaBps") / 10_000.0;
        double realized = Math.log(barNext.close().doubleValue() / barT.close().doubleValue());

        // 单点 QLIKE：直接用 research 现成 public API（单元素数组），不复制公式
        double qlike = VolForecastScore.qlikeLosses(new double[]{forecastSigma}, new double[]{realized})[0];
        double baselineQlike = VolForecastScore.qlikeLosses(new double[]{baselineSigma}, new double[]{realized})[0];

        // vol-state 实际态：快照 PIT 档界喂给分类单一真相源（界点归 MID）
        String actual = VolStateClassifier.classifyWithCuts(
                Math.abs(realized), leg.getDoubleValue("lowCut"), leg.getDoubleValue("highCut")).name();
        String predicted = leg.getString("volState");

        QuantVolVerification row = new QuantVolVerification();
        row.setSnapshotId(snap.getId());
        row.setSymbol(snap.getSymbol());
        row.setCloseTime(t);
        row.setHorizon(horizon.name());
        row.setForecastSigmaBps(leg.getIntValue("sigmaBps"));
        row.setBaselineSigmaBps((int) Math.round(baselineSigma * 10_000));
        row.setRealizedReturnBps((int) Math.round(realized * 10_000));
        row.setQlike(qlike);
        row.setBaselineQlike(baselineQlike);
        row.setVolStatePredicted(predicted);
        row.setVolStateActual(actual);
        row.setVolStateHit(predicted != null && predicted.equals(actual));
        row.setVerifiedAt(LocalDateTime.now());
        return row;
    }

    /**
     * naive 基准 σ：t 往前 3 天、5m 步进的重叠 horizon 收益 RMS（PIT：窗口终点 ≤ t，不碰未来）。
     * 有效窗不足 {@link #MIN_BASELINE_WINDOWS} 返回 NaN 由调用方丢点。
     */
    private static double rollingBaselineSigma(long t, ForecastHorizon horizon, Map<Long, KlineBar> bars) {
        double sumSq = 0;
        int n = 0;
        for (long s = t; s >= t - BASELINE_LOOKBACK_MILLIS; s -= KlineHistoryStore.DEFAULT_BAR_MILLIS) {
            KlineBar end = bars.get(s);
            KlineBar start = bars.get(s - horizon.millis());
            if (end == null || start == null) continue;
            double r = Math.log(end.close().doubleValue() / start.close().doubleValue());
            sumSq += r * r;
            n++;
        }
        return n >= MIN_BASELINE_WINDOWS ? Math.sqrt(sumSq / n) : Double.NaN;
    }

    private boolean insertIdempotent(QuantVolVerification row) {
        try {
            verificationMapper.insert(row);
            return true;
        } catch (DuplicateKeyException e) {
            // 重启后游标归零重扫的正常路径
            return false;
        }
    }
}
