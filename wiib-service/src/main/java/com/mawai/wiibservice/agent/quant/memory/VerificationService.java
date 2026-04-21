package com.mawai.wiibservice.agent.quant.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.mawai.wiibcommon.entity.QuantForecastAdjustment;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantForecastVerification;
import com.mawai.wiibcommon.entity.QuantHorizonForecast;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.mapper.QuantForecastAdjustmentMapper;
import com.mawai.wiibservice.mapper.QuantForecastCycleMapper;
import com.mawai.wiibservice.mapper.QuantForecastVerificationMapper;
import com.mawai.wiibservice.mapper.QuantHorizonForecastMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationService {

    private final BinanceRestClient binanceRestClient;
    private final QuantForecastCycleMapper cycleMapper;
    private final QuantForecastVerificationMapper verificationMapper;
    private final QuantHorizonForecastMapper horizonMapper;
    private final QuantForecastAdjustmentMapper adjustmentMapper;

    private static final int NO_TRADE_THRESHOLD_BPS = 10;
    private static final BigDecimal HIGH_REVERSAL_SEVERITY = new BigDecimal("0.40");
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    public record VerificationCycleResult(
            String cycleId,
            String symbol,
            LocalDateTime forecastTime,
            String overallDecision,
            String riskStatus,
            LocalDateTime verifiedAt,
            LocalDateTime createdAt,
            List<QuantForecastVerification> items
    ) {}

    public record VerificationSummary(
            int total,
            int correct,
            String accuracyRate,
            List<VerificationCycleResult> cycles
    ) {}

    /** 分组结果：重周期为主，轻周期挂载在重周期下；adjustments 为这些轻周期对本重周期的修正明细 */
    public record GroupedHeavyCycle(
            VerificationCycleResult heavy,
            List<VerificationCycleResult> lightCycles,
            List<QuantForecastAdjustment> adjustments
    ) {}

    public record GroupedVerificationSummary(
            int total,
            int correct,
            String accuracyRate,
            int heavyTotal,
            int heavyCorrect,
            String heavyAccuracyRate,
            List<GroupedHeavyCycle> groups
    ) {}


    public int verifyPendingCycles(String symbol, int limit) {
        List<QuantForecastCycle> unverified = cycleMapper.selectUnverified(symbol, limit);
        if (unverified.isEmpty()) {
            log.info("[Verify] 无待验证周期 symbol={}", symbol);
            return 0;
        }

        int verifiedCycles = 0;
        for (QuantForecastCycle cycle : unverified) {
            List<QuantHorizonForecast> forecasts = horizonMapper.selectByCycleId(cycle.getCycleId());
            if (forecasts.isEmpty()) {
                continue;
            }
            if (verifyCycle(cycle, forecasts) > 0) {
                verifiedCycles++;
            }
        }
        log.info("[Verify] 手动验证完成 symbol={} verified={}/{}", symbol, verifiedCycles, unverified.size());
        return verifiedCycles;
    }

    public VerificationSummary recentVerificationResults(String symbol, int cycleLimit) {
        int normalizedLimit = Math.clamp(cycleLimit, 1, 20);
        List<QuantForecastVerification> recent = verificationMapper.selectRecent(symbol, normalizedLimit * 3);
        if (recent.isEmpty()) {
            return new VerificationSummary(0, 0, "0%", List.of());
        }

        LinkedHashMap<String, List<QuantForecastVerification>> verificationByCycle = new LinkedHashMap<>();
        for (QuantForecastVerification item : recent) {
            boolean isNew = !verificationByCycle.containsKey(item.getCycleId());
            if (isNew && verificationByCycle.size() >= normalizedLimit) {
                break;
            }
            verificationByCycle.computeIfAbsent(item.getCycleId(), ignored -> new ArrayList<>()).add(item);
        }

        List<String> cycleIds = List.copyOf(verificationByCycle.keySet());
        List<QuantForecastCycle> cycles = cycleMapper.selectList(new LambdaQueryWrapper<QuantForecastCycle>()
                .in(QuantForecastCycle::getCycleId, cycleIds));
        Map<String, QuantForecastCycle> cycleMap = new LinkedHashMap<>();
        for (QuantForecastCycle cycle : cycles) {
            cycleMap.put(cycle.getCycleId(), cycle);
        }

        int total = 0, correct = 0;
        List<VerificationCycleResult> result = new ArrayList<>(cycleIds.size());
        for (String cycleId : cycleIds) {
            List<QuantForecastVerification> items = verificationByCycle.getOrDefault(cycleId, List.of());
            items.sort(java.util.Comparator.comparing(QuantForecastVerification::getHorizon));
            QuantForecastCycle cycle = cycleMap.get(cycleId);
            LocalDateTime verifiedAt = items.stream()
                    .map(QuantForecastVerification::getVerifiedAt)
                    .filter(java.util.Objects::nonNull)
                    .max(LocalDateTime::compareTo)
                    .orElse(null);
            for (QuantForecastVerification item : items) {
                total++;
                if (item.getPredictionCorrect() != null && item.getPredictionCorrect()) correct++;
            }
            result.add(new VerificationCycleResult(
                    cycleId,
                    symbol,
                    cycle != null ? cycle.getForecastTime() : null,
                    cycle != null ? cycle.getOverallDecision() : null,
                    cycle != null ? cycle.getRiskStatus() : null,
                    verifiedAt,
                    cycle != null ? cycle.getCreatedAt() : null,
                    List.copyOf(items)
            ));
        }
        String accuracyRate = total > 0 ? (correct * 100 / total) + "%" : "0%";
        return new VerificationSummary(total, correct, accuracyRate, result);
    }


    /**
     * 分组查询：重周期为主，轻周期按时间归属到最近的前一个重周期下。
     */
    public GroupedVerificationSummary groupedVerificationResults(String symbol, int heavyLimit) {
        int normalizedLimit = Math.clamp(heavyLimit, 1, 20);
        // 拉足够多的验证记录（重+轻混合），按时间倒序
        List<QuantForecastVerification> recent = verificationMapper.selectRecent(symbol, normalizedLimit * 12);
        if (recent.isEmpty()) {
            return new GroupedVerificationSummary(0, 0, "0%", 0, 0, "0%", List.of());
        }

        // 按cycleId分组
        LinkedHashMap<String, List<QuantForecastVerification>> byCycle = new LinkedHashMap<>();
        for (QuantForecastVerification item : recent) {
            byCycle.computeIfAbsent(item.getCycleId(), k -> new ArrayList<>()).add(item);
        }

        // 查cycle元数据
        List<String> cycleIds = List.copyOf(byCycle.keySet());
        List<QuantForecastCycle> cycles = cycleMapper.selectList(new LambdaQueryWrapper<QuantForecastCycle>()
                .in(QuantForecastCycle::getCycleId, cycleIds));
        Map<String, QuantForecastCycle> cycleMap = new LinkedHashMap<>();
        for (QuantForecastCycle c : cycles) {
            cycleMap.put(c.getCycleId(), c);
        }

        // 分离重/轻周期，构建VerificationCycleResult
        List<VerificationCycleResult> heavyResults = new ArrayList<>();
        List<VerificationCycleResult> lightResults = new ArrayList<>();
        for (String cid : cycleIds) {
            List<QuantForecastVerification> items = byCycle.get(cid);
            items.sort(Comparator.comparing(QuantForecastVerification::getHorizon));
            QuantForecastCycle cycle = cycleMap.get(cid);
            LocalDateTime verifiedAt = items.stream()
                    .map(QuantForecastVerification::getVerifiedAt)
                    .filter(java.util.Objects::nonNull)
                    .max(LocalDateTime::compareTo).orElse(null);
            VerificationCycleResult r = new VerificationCycleResult(
                    cid, symbol,
                    cycle != null ? cycle.getForecastTime() : null,
                    cycle != null ? cycle.getOverallDecision() : null,
                    cycle != null ? cycle.getRiskStatus() : null,
                    verifiedAt,
                    cycle != null ? cycle.getCreatedAt() : null,
                    List.copyOf(items));
            if (cid.startsWith("light-")) {
                lightResults.add(r);
            } else {
                heavyResults.add(r);
            }
        }

        // 按createdAt倒序排列重周期，截取limit个
        heavyResults.sort((a, b) -> {
            if (a.createdAt() == null || b.createdAt() == null) return 0;
            return b.createdAt().compareTo(a.createdAt());
        });
        if (heavyResults.size() > normalizedLimit) {
            heavyResults = heavyResults.subList(0, normalizedLimit);
        }

        // 轻周期按createdAt升序，方便归属
        lightResults.sort((a, b) -> {
            if (a.createdAt() == null || b.createdAt() == null) return 0;
            return a.createdAt().compareTo(b.createdAt());
        });

        // 批量查 adjustments：按 heavyCycleId 分组（前端用来在轻周期卡上显示徽章）
        Map<String, List<QuantForecastAdjustment>> adjByHeavy = new HashMap<>();
        if (!heavyResults.isEmpty()) {
            List<String> heavyIds = heavyResults.stream().map(VerificationCycleResult::cycleId).toList();
            List<QuantForecastAdjustment> allAdj = adjustmentMapper.selectByHeavyCycleIds(heavyIds);
            for (QuantForecastAdjustment adj : allAdj) {
                adjByHeavy.computeIfAbsent(adj.getHeavyCycleId(), k -> new ArrayList<>()).add(adj);
            }
        }

        // 将轻周期归属到最近的前一个重周期（按 createdAt，窗口取整到下一个半点）
        List<GroupedHeavyCycle> groups = new ArrayList<>();
        int totalAll = 0, correctAll = 0;
        int heavyTotal = 0, heavyCorrect = 0;
        for (VerificationCycleResult heavy : heavyResults) {
            List<VerificationCycleResult> attached = getVerificationCycleResults(heavy, lightResults);

            // 统计
            for (QuantForecastVerification item : heavy.items()) {
                totalAll++;
                heavyTotal++;
                if (item.getPredictionCorrect() != null && item.getPredictionCorrect()) {
                    correctAll++;
                    heavyCorrect++;
                }
            }
            for (VerificationCycleResult light : attached) {
                for (QuantForecastVerification item : light.items()) {
                    totalAll++;
                    if (item.getPredictionCorrect() != null && item.getPredictionCorrect()) correctAll++;
                }
            }
            groups.add(new GroupedHeavyCycle(heavy, attached,
                    adjByHeavy.getOrDefault(heavy.cycleId(), List.of())));
        }

        String accuracyRate = totalAll > 0 ? (correctAll * 100 / totalAll) + "%" : "0%";
        String heavyAccuracyRate = heavyTotal > 0 ? (heavyCorrect * 100 / heavyTotal) + "%" : "0%";
        return new GroupedVerificationSummary(totalAll, correctAll, accuracyRate,
                heavyTotal, heavyCorrect, heavyAccuracyRate, groups);
    }

    private static List<VerificationCycleResult> getVerificationCycleResults(VerificationCycleResult heavy, List<VerificationCycleResult> lightResults) {
        LocalDateTime heavyCreatedAt = heavy.createdAt();
        // 窗口上界：createdAt 对齐到下一个半点（15:02 → 15:30，15:35 → 16:00）
        LocalDateTime windowEnd = null;
        if (heavyCreatedAt != null) {
            LocalDateTime truncated = heavyCreatedAt.withSecond(0).withNano(0);
            int minute = truncated.getMinute();
            if (minute < 30) {
                windowEnd = truncated.withMinute(30);
            } else {
                windowEnd = truncated.plusHours(1).withMinute(0);
            }
        }

        List<VerificationCycleResult> attached = new ArrayList<>();
        for (VerificationCycleResult light : lightResults) {
            if (light.createdAt() == null || heavyCreatedAt == null) continue;
            // 轻周期创建时间必须在重周期创建时间之后
            if (!light.createdAt().isAfter(heavyCreatedAt)) continue;
            // 轻周期创建时间必须在窗口内
            if (light.createdAt().isAfter(windowEnd)) continue;
            attached.add(light);
        }
        return attached;
    }

    /**
     * 验证一个预测周期的所有区间裁决。
     * 按分段区间(0-10/10-20/20-30)各自拉取对应时段K线做路径分析。
     * <p>
     * 验证策略：
     * - 0_10: 完整验证（方向+BPS+TP/SL触达判定）
     * - 10_20/20_30: 降级验证（仅方向+BPS），因TP/SL基于T+0价格生成，对后段无效
     */
    public int verifyCycle(QuantForecastCycle cycle, List<QuantHorizonForecast> forecasts) {
        if (cycle == null || forecasts == null) return 0;

        Set<String> verifiedHorizons = new HashSet<>();
        List<QuantForecastVerification> existing = verificationMapper.selectList(
                new LambdaQueryWrapper<QuantForecastVerification>()
                        .select(QuantForecastVerification::getHorizon)
                        .eq(QuantForecastVerification::getCycleId, cycle.getCycleId()));
        for (QuantForecastVerification v : existing) {
            verifiedHorizons.add(v.getHorizon());
        }

        LocalDateTime forecastTime = cycle.getForecastTime();
        BigDecimal priceAtForecast = getHistoricalPrice(cycle.getSymbol(), forecastTime);
        if (priceAtForecast == null) {
            log.warn("[Verify] 无法获取预测时价格 cycle={} time={}", cycle.getCycleId(), forecastTime);
            return 0;
        }

        LocalDateTime now = LocalDateTime.now(SYSTEM_ZONE);
        int verified = 0;
        for (QuantHorizonForecast f : forecasts) {
            if (verifiedHorizons.contains(f.getHorizon())) continue;

            int startMin = horizonStartMinutes(f.getHorizon());
            int endMin = horizonEndMinutes(f.getHorizon());
            if (startMin < 0 || endMin <= startMin) continue;

            LocalDateTime segmentEnd = forecastTime.plusMinutes(endMin);
            if (segmentEnd.isAfter(now)) {
                log.debug("[Verify] {} {} 目标时间{}未到，跳过", cycle.getCycleId(), f.getHorizon(), segmentEnd);
                continue;
            }

            // 分段入场价：0_10用预测时价格，10_20/20_30用分段起点实际价格
            BigDecimal entryPrice;
            if (startMin == 0) {
                entryPrice = priceAtForecast;
            } else {
                entryPrice = getHistoricalPrice(cycle.getSymbol(), forecastTime.plusMinutes(startMin));
                if (entryPrice == null) continue;
            }

            // 只拉分段区间内的1m K线
            int segmentDuration = endMin - startMin;
            LocalDateTime segmentStart = forecastTime.plusMinutes(startMin);
            List<BigDecimal[]> klines = getKlineRange(cycle.getSymbol(), segmentStart, segmentDuration);
            BigDecimal priceAfter = null;
            if (klines != null && !klines.isEmpty()) {
                priceAfter = klines.getLast()[2];
            }
            if (priceAfter == null) {
                priceAfter = getHistoricalPrice(cycle.getSymbol(), segmentEnd);
            }
            if (priceAfter == null) continue;

            int changeBps = calcChangeBps(entryPrice, priceAfter);

            // 分段TP/SL：0_10直接用；10_20/20_30按TP/SL相对priceAtForecast的偏移量平移到分段entryPrice上
            BigDecimal segTp1, segSl;
            if (startMin == 0) {
                segTp1 = f.getTp1();
                segSl = f.getInvalidationPrice();
            } else if (f.getTp1() != null && f.getInvalidationPrice() != null) {
                BigDecimal tpOffset = f.getTp1().subtract(priceAtForecast);
                BigDecimal slOffset = f.getInvalidationPrice().subtract(priceAtForecast);
                segTp1 = entryPrice.add(tpOffset);
                segSl = entryPrice.add(slOffset);
            } else {
                segTp1 = null;
                segSl = null;
            }
            PathResult path = analyzePath(klines, entryPrice, f.getDirection(), segTp1, segSl);

            String tradeQuality = judgeQuality(f.getDirection(), changeBps, path);
            // LUCKY(方向对但SL先触)真交易必亏，不算correct；防反馈统计虚高导致权重失真
            boolean correct;
            if ("NO_TRADE".equals(f.getDirection())) {
                correct = "GOOD".equals(tradeQuality); // 观望场景：波动<阈值才算对
            } else {
                correct = "GOOD".equals(tradeQuality) || "MARGINAL".equals(tradeQuality);
            }

            QuantForecastVerification v = new QuantForecastVerification();
            v.setCycleId(cycle.getCycleId());
            v.setSymbol(cycle.getSymbol());
            v.setHorizon(f.getHorizon());
            v.setPredictedDirection(f.getDirection());
            v.setPredictedConfidence(f.getConfidence());
            v.setActualPriceAtForecast(entryPrice);
            v.setActualPriceAfter(priceAfter);
            v.setActualChangeBps(changeBps);
            v.setMaxFavorableBps(path.maxFavorableBps);
            v.setMaxAdverseBps(path.maxAdverseBps);

            BigDecimal reversalSeverity;
            if ("NO_TRADE".equals(f.getDirection())) {
                reversalSeverity = null;
            } else if (path.maxAdverseBps + path.maxFavorableBps > 0) {
                reversalSeverity = BigDecimal.valueOf(path.maxAdverseBps)
                        .divide(BigDecimal.valueOf(path.maxAdverseBps + path.maxFavorableBps), 4, RoundingMode.HALF_UP);
            } else {
                reversalSeverity = BigDecimal.ZERO;
            }
            v.setReversalSeverity(reversalSeverity);

            v.setTp1HitFirst(path.tp1HitFirst);
            v.setPredictionCorrect(correct);
            v.setTradeQuality(tradeQuality);
            v.setResultSummary(buildResultSummary(f.getDirection(), changeBps, tradeQuality, path, reversalSeverity));
            v.setVerifiedAt(LocalDateTime.now(SYSTEM_ZONE));
            verificationMapper.insert(v);
            verified++;

            log.info("[Verify] {} {} predicted={} conf={} entry={} actual={}bps maxFav={}bps maxAdv={}bps tp1First={} quality={}",
                    cycle.getCycleId(), f.getHorizon(), f.getDirection(),
                    f.getConfidence(), entryPrice, changeBps, path.maxFavorableBps, path.maxAdverseBps,
                    path.tp1HitFirst, tradeQuality);
        }
        log.info("[Verify] cycle={} 验证完成 {}/{}", cycle.getCycleId(), verified, forecasts.size());
        return verified;
    }

    // ==================== 路径分析 ====================

    record PathResult(int maxFavorableBps, int maxAdverseBps, Boolean tp1HitFirst) {}

    /**
     * 遍历K线序列，计算最大有利偏移、最大不利偏移、TP1/SL谁先触达。
     * tp1/sl为null时跳过触达判定，只统计BPS偏移。
     */
    private PathResult analyzePath(List<BigDecimal[]> klines, BigDecimal entryPrice,
                                    String direction, BigDecimal tp1, BigDecimal sl) {
        if (klines == null || klines.isEmpty() || entryPrice == null || entryPrice.signum() <= 0) {
            return new PathResult(0, 0, null);
        }

        boolean isLong = "LONG".equals(direction);
        boolean isShort = "SHORT".equals(direction);

        int maxFavBps = 0, maxAdvBps = 0;
        Boolean tp1First = null;

        for (BigDecimal[] k : klines) {
            BigDecimal high = k[0], low = k[1];

            // 有利/不利偏移
            int favBps, advBps;
            if (isLong) {
                favBps = calcChangeBps(entryPrice, high);
                advBps = -calcChangeBps(entryPrice, low);
            } else if (isShort) {
                favBps = -calcChangeBps(entryPrice, low);
                advBps = calcChangeBps(entryPrice, high);
            } else {
                // NO_TRADE: 任意方向的最大偏移都算不利
                int upBps = Math.abs(calcChangeBps(entryPrice, high));
                int downBps = Math.abs(calcChangeBps(entryPrice, low));
                maxAdvBps = Math.max(maxAdvBps, Math.max(upBps, downBps));
                continue;
            }
            maxFavBps = Math.max(maxFavBps, favBps);
            maxAdvBps = Math.max(maxAdvBps, advBps);

            // TP1/SL 触达判定（只记第一次）
            if (tp1First == null && tp1 != null && sl != null) {
                boolean hitTp1 = isLong ? high.compareTo(tp1) >= 0 : low.compareTo(tp1) <= 0;
                boolean hitSl = isLong ? low.compareTo(sl) <= 0 : high.compareTo(sl) >= 0;
                if (hitTp1 && hitSl) {
                    // 同一根K线内TP和SL都碰到了，看close更靠近哪个
                    BigDecimal close = k[2];
                    tp1First = isLong ? close.compareTo(entryPrice) > 0 : close.compareTo(entryPrice) < 0;
                } else if (hitTp1) {
                    tp1First = true;
                } else if (hitSl) {
                    tp1First = false;
                }
            }
        }

        return new PathResult(maxFavBps, maxAdvBps, tp1First);
    }

    /**
     * 综合评级：
     * GOOD — 方向正确 + 路径健康（TP1先触或有利偏移充足）
     * MARGINAL — 方向正确但幅度弱或路径差
     * LUCKY — 方向正确但SL先触达（运气好没被打出去）
     * BAD — 方向错误
     * FLAT — NO_TRADE且波动在阈值内
     */
    private String judgeQuality(String direction, int changeBps, PathResult path) {
        if ("NO_TRADE".equals(direction)) {
            return Math.abs(changeBps) < NO_TRADE_THRESHOLD_BPS ? "GOOD" : "BAD";
        }

        boolean directionCorrect = ("LONG".equals(direction) && changeBps > 0)
                || ("SHORT".equals(direction) && changeBps < 0);

        if (!directionCorrect) return "BAD";

        // 方向正确，按路径质量分级
        if (path.tp1HitFirst != null) {
            return path.tp1HitFirst ? "GOOD" : "LUCKY";
        }

        // 无TP/SL时，看有利偏移幅度
        if (path.maxFavorableBps > 5) return "GOOD";
        return "MARGINAL";
    }

    // ==================== 数据获取 ====================

    /**
     * 拉取从startTime开始的count根1m K线。
     * 返回 [[high, low, close], ...] 序列。
     */
    private List<BigDecimal[]> getKlineRange(String symbol, LocalDateTime startTime, int count) {
        try {
            long startMs = startTime.atZone(SYSTEM_ZONE).toInstant().toEpochMilli();
            String json = binanceRestClient.getFuturesKlines(symbol, "1m", count, startMs + (long) count * 60_000 + 1000);
            if (json == null || json.isBlank()) return null;
            JSONArray root = JSON.parseArray(json);
            if (root == null || root.isEmpty()) return null;
            List<BigDecimal[]> result = new ArrayList<>(root.size());
            for (int i = 0; i < root.size(); i++) {
                JSONArray k = root.getJSONArray(i);
                result.add(new BigDecimal[]{
                        new BigDecimal(k.getString(2)),  // high
                        new BigDecimal(k.getString(3)),  // low
                        new BigDecimal(k.getString(4))   // close
                });
            }
            return result;
        } catch (Exception e) {
            log.warn("[Verify] K线序列获取失败 symbol={} time={}: {}", symbol, startTime, e.getMessage());
            return null;
        }
    }

    /**
     * 从Binance获取指定时刻的1m K线收盘价。
     * forecastTime是本地时区的LocalDateTime，需转成UTC毫秒给Binance API。
     */
    BigDecimal getHistoricalPrice(String symbol, LocalDateTime time) {
        try {
            long endTimeMs = time.atZone(SYSTEM_ZONE).toInstant().toEpochMilli() + 60_000;
            String json = binanceRestClient.getFuturesKlines(symbol, "1m", 1, endTimeMs);
            if (json == null || json.isBlank()) return null;
            JSONArray root = JSON.parseArray(json);
            if (root.isEmpty()) return null;
            JSONArray kline = root.getJSONArray(0);
            return new BigDecimal(kline.getString(4));
        } catch (Exception e) {
            log.warn("[Verify] 获取历史价格失败 symbol={} time={}: {}", symbol, time, e.getMessage());
            return null;
        }
    }

    private static int calcChangeBps(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.signum() == 0) return 0;
        return to.subtract(from)
                .multiply(BigDecimal.valueOf(10000))
                .divide(from, 0, RoundingMode.HALF_UP)
                .intValue();
    }

    private int horizonStartMinutes(String horizon) {
        return switch (horizon) {
            case "0_10" -> 0;
            case "10_20" -> 10;
            case "20_30" -> 20;
            default -> -1;
        };
    }

    private int horizonEndMinutes(String horizon) {
        return switch (horizon) {
            case "0_10" -> 10;
            case "10_20" -> 20;
            case "20_30" -> 30;
            default -> -1;
        };
    }

    private String buildResultSummary(String direction, int changeBps,
                                       String quality, PathResult path, BigDecimal reversalSeverity) {
        String actualDir = changeBps > 0 ? "上涨" : changeBps < 0 ? "下跌" : "持平";
        String pct = bpsToPercent(Math.abs(changeBps));

        StringBuilder sb = new StringBuilder();

        if ("NO_TRADE".equals(direction)) {
            sb.append("预测观望，实际").append(actualDir).append(pct);
            sb.append(Math.abs(changeBps) < NO_TRADE_THRESHOLD_BPS ? "，判断正确" : "，错过行情");
            if (path.maxAdverseBps > 0) {
                sb.append("，期间最大波动").append(bpsToPercent(path.maxAdverseBps));
            }
        } else {
            String dirLabel = "LONG".equals(direction) ? "看涨" : "看跌";
            boolean dirCorrect = ("LONG".equals(direction) && changeBps > 0)
                    || ("SHORT".equals(direction) && changeBps < 0);

            sb.append("预测").append(dirLabel).append("，实际").append(actualDir).append(pct);

            if (dirCorrect) {
                sb.append("，方向正确");
            } else if (changeBps == 0) {
                sb.append("，价格未变动");
            } else {
                sb.append("，方向错误");
            }

            if (path.maxFavorableBps > 0) {
                sb.append("，期间最大盈利").append(bpsToPercent(path.maxFavorableBps));
            }
            if (path.maxAdverseBps > 0) {
                sb.append("，最大回撤").append(bpsToPercent(path.maxAdverseBps));
            }
            if (path.tp1HitFirst != null) {
                sb.append(path.tp1HitFirst ? "，止盈先触达" : "，止损先触达");
            }
        }

        if (reversalSeverity != null && reversalSeverity.compareTo(HIGH_REVERSAL_SEVERITY) >= 0) {
            sb.append("，段内反转风险显著");
        }

        sb.append("，评级");
        sb.append(switch (quality) {
            case "GOOD" -> "优";
            case "MARGINAL" -> "微优";
            case "LUCKY" -> "运气";
            case "BAD" -> "差";
            default -> quality;
        });

        return sb.toString();
    }

    private static String bpsToPercent(int bps) {
        if (bps == 0) return "0%";
        return BigDecimal.valueOf(bps).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP) + "%";
    }
}
