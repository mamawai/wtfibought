package com.mawai.wiibservice.task;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.Phase0aDailyStats;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibservice.mapper.Phase0aDailyStatsMapper;
import com.mawai.wiibservice.mapper.UserMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Phase 0A 观察期每日指标自动采集。每天 00:10 聚合昨日数据，启动时补跑最多 14 天缺口。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Phase0aStatsCollectionTask {

    private static final String AI_LINUX_DO_ID = "AI_TRADER";
    private static final int MAX_BACKFILL_DAYS = 14;

    private final Phase0aDailyStatsMapper statsMapper;
    private final UserMapper userMapper;

    @PostConstruct
    public void backfillOnStartup() {
        Thread.startVirtualThread(() -> {
            try {
                Long userId = resolveAiUserId();
                if (userId == null) {
                    log.warn("[Phase0aStats] AI_TRADER 账户未初始化，启动补跑跳过");
                    return;
                }
                LocalDate yesterday = LocalDate.now().minusDays(1);
                LocalDate lastRecorded = statsMapper.selectMaxStatDate();
                LocalDate startFrom = (lastRecorded == null)
                        ? yesterday.minusDays(MAX_BACKFILL_DAYS - 1)
                        : lastRecorded.plusDays(1);
                if (startFrom.isAfter(yesterday)) {
                    log.info("[Phase0aStats] 无需补跑，最新已有 {}", lastRecorded);
                    return;
                }
                long span = yesterday.toEpochDay() - startFrom.toEpochDay() + 1;
                if (span > MAX_BACKFILL_DAYS) {
                    log.warn("[Phase0aStats] 补跑区间 {} 天超上限，截断到 {} 天", span, MAX_BACKFILL_DAYS);
                    startFrom = yesterday.minusDays(MAX_BACKFILL_DAYS - 1);
                }
                log.info("[Phase0aStats] 启动补跑 [{} ~ {}]", startFrom, yesterday);
                for (LocalDate d = startFrom; !d.isAfter(yesterday); d = d.plusDays(1)) {
                    collectAndUpsert(d, userId);
                }
            } catch (Exception e) {
                log.error("[Phase0aStats] 启动补跑异常", e);
            }
        });
    }

    @Scheduled(cron = "0 10 0 * * *")
    public void collectYesterday() {
        Long userId = resolveAiUserId();
        if (userId == null) {
            log.warn("[Phase0aStats] AI_TRADER 账户未初始化，日度采集跳过");
            return;
        }
        collectAndUpsert(LocalDate.now().minusDays(1), userId);
    }

    private Long resolveAiUserId() {
        User ai = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getLinuxDoId, AI_LINUX_DO_ID));
        return ai != null ? ai.getId() : null;
    }

    private void collectAndUpsert(LocalDate date, Long userId) {
        try {
            LocalDateTime start = date.atStartOfDay();
            LocalDateTime end = date.plusDays(1).atStartOfDay();

            Map<String, Object> counts = safe(statsMapper.aggregateDecisionCounts(start, end));
            int totalOpens = intOf(counts, "totalOpens");
            int totalCloses = intOf(counts, "totalCloses");
            if (totalOpens == 0 && totalCloses == 0) {
                log.info("[Phase0aStats] {} 无交易决策，跳过写入", date);
                return;
            }

            Map<String, Object> equity = safe(statsMapper.aggregateEquityTrajectory(start, end));
            Map<String, Object> closed = safe(statsMapper.aggregateClosedPositions(userId, start, end));
            List<Map<String, Object>> strategyRows = statsMapper.aggregateStrategyBreakdown(userId, start, end);

            Phase0aDailyStats s = new Phase0aDailyStats();
            s.setStatDate(date);
            s.setUserId(userId);
            s.setTotalOpens(totalOpens);
            s.setOpensBtc(intOf(counts, "opensBtc"));
            s.setOpensEth(intOf(counts, "opensEth"));
            s.setTotalCloses(totalCloses);
            s.setDailyLossBlocks(intOf(counts, "dailyLossBlocks"));

            int wins = intOf(closed, "wins");
            int losses = intOf(closed, "losses");
            s.setWins(wins);
            s.setLosses(losses);
            s.setPositionsClosed(intOf(closed, "positionsClosed"));
            if (wins + losses > 0) {
                s.setWinRate(BigDecimal.valueOf(wins * 100.0 / (wins + losses))
                        .setScale(2, RoundingMode.HALF_UP));
            }
            s.setRealizedPnl(bdOf(closed, "realizedPnl", BigDecimal.ZERO));
            BigDecimal avgWin = bdOf(closed, "avgWin", null);
            BigDecimal avgLoss = bdOf(closed, "avgLoss", null);
            s.setAvgWin(scale2(avgWin));
            s.setAvgLoss(scale2(avgLoss));
            if (avgWin != null && avgLoss != null && avgLoss.signum() != 0) {
                s.setPnlRatio(avgWin.divide(avgLoss.abs(), 3, RoundingMode.HALF_UP));
            }
            s.setAvgHoldingMinutes(scale2(bdOf(closed, "avgHoldingMinutes", null)));

            BigDecimal eqStart = bdOf(equity, "equityStart", null);
            BigDecimal eqEnd = bdOf(equity, "equityEnd", null);
            BigDecimal eqHigh = bdOf(equity, "equityHigh", null);
            BigDecimal eqLow = bdOf(equity, "equityLow", null);
            s.setEquityStart(eqStart);
            s.setEquityEnd(eqEnd);
            s.setEquityHigh(eqHigh);
            s.setEquityLow(eqLow);
            if (eqHigh != null && eqLow != null && eqHigh.signum() > 0) {
                s.setDailyDrawdownPct(eqHigh.subtract(eqLow)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(eqHigh, 2, RoundingMode.HALF_UP));
            }
            if (eqStart != null && eqEnd != null && eqStart.signum() > 0) {
                s.setDailyReturnPct(eqEnd.subtract(eqStart)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(eqStart, 2, RoundingMode.HALF_UP));
            }

            s.setStrategyBreakdown(buildBreakdown(strategyRows));

            statsMapper.deleteById(date);
            statsMapper.insert(s);
            log.info("[Phase0aStats] {} 写入完成 opens={} closed={} pnl={} dd%={}",
                    date, totalOpens, s.getPositionsClosed(), s.getRealizedPnl(), s.getDailyDrawdownPct());
        } catch (Exception e) {
            log.error("[Phase0aStats] {} 采集异常", date, e);
        }
    }

    private static String buildBreakdown(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return null;
        JSONObject breakdown = new JSONObject();
        for (Map<String, Object> row : rows) {
            String strategy = String.valueOf(row.getOrDefault("strategy", "UNKNOWN"));
            JSONObject item = new JSONObject();
            item.put("count", row.get("count"));
            item.put("wins", row.get("wins"));
            item.put("losses", row.get("losses"));
            item.put("pnl", row.get("pnl"));
            breakdown.put(strategy, item);
        }
        return breakdown.toJSONString();
    }

    private static Map<String, Object> safe(Map<String, Object> m) {
        return m != null ? m : Collections.emptyMap();
    }

    private static int intOf(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }

    private static BigDecimal bdOf(Map<String, Object> m, String k, BigDecimal dflt) {
        Object v = m.get(k);
        if (v == null) return dflt;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return dflt; }
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v != null ? v.setScale(2, RoundingMode.HALF_UP) : null;
    }
}
