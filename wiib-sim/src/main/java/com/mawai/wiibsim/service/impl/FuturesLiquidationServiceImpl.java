package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.service.FuturesLiquidationService;
import com.mawai.wiibsim.service.FuturesRiskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FuturesLiquidationServiceImpl implements FuturesLiquidationService {

    private final FuturesRiskService futuresRiskService;
    private final CacheService cacheService;

    private static final String LIQ_LONG_PREFIX = "futures:liq:long:";
    private static final String LIQ_SHORT_PREFIX = "futures:liq:short:";
    private static final String SL_LONG_PREFIX = "futures:sl:long:";
    private static final String SL_SHORT_PREFIX = "futures:sl:short:";
    private static final String TP_LONG_PREFIX = "futures:tp:long:";
    private static final String TP_SHORT_PREFIX = "futures:tp:short:";

    private record RecoveryEntry(String key, String member, double score) {}

    private static class PositionHitGroup {
        boolean liq;
        final List<String> slIds = new ArrayList<>();
        final List<String> tpIds = new ArrayList<>();
        final List<RecoveryEntry> recovery = new ArrayList<>();
    }

    @Override
    public void checkOnPriceUpdate(String symbol, BigDecimal markPrice, BigDecimal currentPrice) {
        double mp = markPrice.doubleValue();
        double cp = currentPrice.doubleValue();
        Map<String, PositionHitGroup> groups = new HashMap<>();

        // LIQ: LONG强平 markPrice≤liqPrice → score≥markPrice; SHORT反之
        collectLiq(LIQ_LONG_PREFIX + symbol, mp, Double.MAX_VALUE, groups);
        collectLiq(LIQ_SHORT_PREFIX + symbol, 0, mp, groups);

        // SL: LONG止损 markPrice≤slPrice → score≥markPrice; SHORT反之
        collectSl(SL_LONG_PREFIX + symbol, mp, Double.MAX_VALUE, groups);
        collectSl(SL_SHORT_PREFIX + symbol, 0, mp, groups);

        // TP: LONG止盈 currentPrice≥tpPrice → score≤currentPrice; SHORT反之
        collectTp(TP_LONG_PREFIX + symbol, 0, cp, groups);
        collectTp(TP_SHORT_PREFIX + symbol, cp, Double.MAX_VALUE, groups);

        for (var entry : groups.entrySet()) {
            String posId = entry.getKey();
            PositionHitGroup group = entry.getValue();
            Thread.startVirtualThread(() -> {
                try {
                    Long pid = Long.parseLong(posId);
                    if (group.liq) {
                        futuresRiskService.forceClose(pid, markPrice);
                    } else if (!group.slIds.isEmpty()) {
                        futuresRiskService.batchTriggerStopLoss(pid, group.slIds, markPrice);
                    } else if (!group.tpIds.isEmpty()) {
                        futuresRiskService.batchTriggerTakeProfit(pid, group.tpIds, currentPrice);
                    }
                } catch (Exception e) {
                    log.error("futures仓位处理失败 posId={}, 恢复索引", posId, e);
                    for (RecoveryEntry re : group.recovery) {
                        cacheService.zAdd(re.key(), re.member(), re.score());
                    }
                }
            });
        }
    }

    private void collectLiq(String key, double min, double max, Map<String, PositionHitGroup> groups) {
        Map<String, Double> hits = cacheService.zRangeByScoreAndRemove(key, min, max);
        if (hits.isEmpty()) return;
        for (var e : hits.entrySet()) {
            PositionHitGroup g = groups.computeIfAbsent(e.getKey(), k -> new PositionHitGroup());
            g.liq = true;
            g.recovery.add(new RecoveryEntry(key, e.getKey(), e.getValue()));
        }
    }

    private void collectSl(String key, double min, double max, Map<String, PositionHitGroup> groups) {
        Map<String, Double> hits = cacheService.zRangeByScoreAndRemove(key, min, max);
        if (hits.isEmpty()) return;
        for (var e : hits.entrySet()) {
            solveHits(key, e, groups, true);
        }
    }

    private void collectTp(String key, double min, double max, Map<String, PositionHitGroup> groups) {
        Map<String, Double> hits = cacheService.zRangeByScoreAndRemove(key, min, max);
        if (hits.isEmpty()) return;
        for (var e : hits.entrySet()) {
            solveHits(key, e, groups, false);
        }
    }

    private void solveHits(String key, Map.Entry<String, Double> e, Map<String, PositionHitGroup> groups, boolean isSL) {
        String member = e.getKey();
        int sep = member.indexOf(':');
        String posId = member.substring(0, sep);
        String id = member.substring(sep + 1);
        PositionHitGroup g = groups.computeIfAbsent(posId, k -> new PositionHitGroup());
        if (isSL) g.slIds.add(id);
        else g.tpIds.add(id);
        g.recovery.add(new RecoveryEntry(key, member, e.getValue()));
    }
}
