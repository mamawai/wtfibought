package com.mawai.wiibservice.service.impl;

import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.FuturesLiquidationService;
import com.mawai.wiibservice.service.FuturesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FuturesLiquidationServiceImpl implements FuturesLiquidationService {

    private final FuturesService futuresService;
    private final CacheService cacheService;

    private static final String LIQ_LONG_PREFIX = "futures:liq:long:";
    private static final String LIQ_SHORT_PREFIX = "futures:liq:short:";
    private static final String SL_LONG_PREFIX = "futures:sl:long:";
    private static final String SL_SHORT_PREFIX = "futures:sl:short:";
    private static final String TP_LONG_PREFIX = "futures:tp:long:";
    private static final String TP_SHORT_PREFIX = "futures:tp:short:";

    private static class PositionHitGroup {
        boolean liq;
        final List<String> slIds = new ArrayList<>();
        final List<String> tpIds = new ArrayList<>();
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
                        futuresService.forceClose(pid, markPrice);
                    } else if (!group.slIds.isEmpty()) {
                        futuresService.batchTriggerStopLoss(pid, group.slIds, markPrice);
                    } else if (!group.tpIds.isEmpty()) {
                        futuresService.batchTriggerTakeProfit(pid, group.tpIds, currentPrice);
                    }
                } catch (Exception e) {
                    log.error("futures仓位处理失败 posId={}", posId, e);
                }
            });
        }
    }

    private void collectLiq(String key, double min, double max, Map<String, PositionHitGroup> groups) {
        Set<String> hits = cacheService.zRangeByScore(key, min, max);
        if (hits == null || hits.isEmpty()) return;
        cacheService.zRemove(key, hits.toArray());
        for (String posId : hits) {
            groups.computeIfAbsent(posId, k -> new PositionHitGroup()).liq = true;
        }
    }

    private void collectSl(String key, double min, double max, Map<String, PositionHitGroup> groups) {
        Set<String> hits = cacheService.zRangeByScore(key, min, max);
        if (hits == null || hits.isEmpty()) return;
        cacheService.zRemove(key, hits.toArray());
        for (String member : hits) {
            int sep = member.indexOf(':');
            String posId = member.substring(0, sep);
            String slId = member.substring(sep + 1);
            groups.computeIfAbsent(posId, k -> new PositionHitGroup()).slIds.add(slId);
        }
    }

    private void collectTp(String key, double min, double max, Map<String, PositionHitGroup> groups) {
        Set<String> hits = cacheService.zRangeByScore(key, min, max);
        if (hits == null || hits.isEmpty()) return;
        cacheService.zRemove(key, hits.toArray());
        for (String member : hits) {
            int sep = member.indexOf(':');
            String posId = member.substring(0, sep);
            String tpId = member.substring(sep + 1);
            groups.computeIfAbsent(posId, k -> new PositionHitGroup()).tpIds.add(tpId);
        }
    }
}
