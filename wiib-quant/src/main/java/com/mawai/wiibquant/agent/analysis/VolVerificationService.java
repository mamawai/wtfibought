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
 *   <li>naive 基准 = 预测时点上一个 horizon 窗口的 |实际对数收益|（"上期 vol"），从不可变历史 K 线 PIT 重建</li>
 *   <li>vol-state 实际态用<b>快照携带的档界</b>分类——PIT 铁律，禁止验证时重算档界（分布漂移污染对账）</li>
 * </ul>
 * 游标在内存（重启重扫），(snapshot_id, horizon) 唯一键幂等兜底；K 线空洞的预测点直接丢弃不阻塞游标。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VolVerificationService {

    private static final int BATCH_LIMIT = 200;

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

        // 一次 load 覆盖 batch 全部点的 baseline 窗(t-h)与 realized 窗(t+h)
        long from = due.getFirst().getCloseTime() - horizon.millis() - KlineHistoryStore.DEFAULT_BAR_MILLIS;
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
        KlineBar barPrev = bars.get(t - horizon.millis());
        KlineBar barNext = bars.get(t + horizon.millis());
        if (barT == null || barPrev == null || barNext == null) {
            log.debug("[VolVerify] K线空洞丢点 symbol={} t={} horizon={}", snap.getSymbol(), t, horizon);
            return null;
        }

        double forecastSigma = leg.getIntValue("sigmaBps") / 10_000.0;
        double realized = Math.log(barNext.close().doubleValue() / barT.close().doubleValue());
        double baselineSigma = Math.abs(Math.log(barT.close().doubleValue() / barPrev.close().doubleValue()));

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
