package com.mawai.wiibservice.agent.quant.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantForecastVerification;
import com.mawai.wiibcommon.entity.QuantHorizonForecast;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.mapper.QuantForecastCycleMapper;
import com.mawai.wiibservice.mapper.QuantForecastVerificationMapper;
import com.mawai.wiibservice.mapper.QuantHorizonForecastMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationService {

    private final BinanceRestClient binanceRestClient;
    private final QuantForecastCycleMapper cycleMapper;
    private final QuantForecastVerificationMapper verificationMapper;
    private final QuantHorizonForecastMapper horizonMapper;

    private static final int CORRECT_THRESHOLD_BPS = 2;
    private static final int NO_TRADE_THRESHOLD_BPS = 10;
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    public record VerificationCycleResult(
            String cycleId,
            String symbol,
            LocalDateTime forecastTime,
            String overallDecision,
            String riskStatus,
            LocalDateTime verifiedAt,
            List<QuantForecastVerification> items
    ) {}

    public record VerificationSummary(
            int total,
            int correct,
            String accuracyRate,
            List<VerificationCycleResult> cycles
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
                    List.copyOf(items)
            ));
        }
        String accuracyRate = total > 0 ? (correct * 100 / total) + "%" : "0%";
        return new VerificationSummary(total, correct, accuracyRate, result);
    }

    /**
     * 验证一个预测周期的所有区间裁决。
     * 拉取区间内全部1m K线，走一遍路径判断TP/SL谁先触达。
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
            if ("NO_TRADE".equals(f.getDirection())) continue;
            if (verifiedHorizons.contains(f.getHorizon())) continue;

            int minutesAfter = horizonToMinutes(f.getHorizon());
            if (minutesAfter <= 0) continue;

            LocalDateTime targetTime = forecastTime.plusMinutes(minutesAfter);
            if (targetTime.isAfter(now)) {
                log.debug("[Verify] {} {} 目标时间{}未到，跳过", cycle.getCycleId(), f.getHorizon(), targetTime);
                continue;
            }

            // 拉区间内全部1m K线（含路径信息）
            List<BigDecimal[]> klines = getKlineRange(cycle.getSymbol(), forecastTime, minutesAfter);
            BigDecimal priceAfter = null;
            if (klines != null && !klines.isEmpty()) {
                priceAfter = klines.getLast()[2]; // 最后一根close
            }
            if (priceAfter == null) {
                priceAfter = getHistoricalPrice(cycle.getSymbol(), targetTime);
            }
            if (priceAfter == null) continue;

            int changeBps = calcChangeBps(priceAtForecast, priceAfter);

            // 路径分析
            PathResult path = analyzePath(klines, priceAtForecast, f);

            // 综合判定
            String tradeQuality = judgeQuality(f.getDirection(), changeBps, path);
            boolean correct = "GOOD".equals(tradeQuality);

            QuantForecastVerification v = new QuantForecastVerification();
            v.setCycleId(cycle.getCycleId());
            v.setSymbol(cycle.getSymbol());
            v.setHorizon(f.getHorizon());
            v.setPredictedDirection(f.getDirection());
            v.setPredictedConfidence(f.getConfidence());
            v.setActualPriceAtForecast(priceAtForecast);
            v.setActualPriceAfter(priceAfter);
            v.setActualChangeBps(changeBps);
            v.setMaxFavorableBps(path.maxFavorableBps);
            v.setMaxAdverseBps(path.maxAdverseBps);
            v.setTp1HitFirst(path.tp1HitFirst);
            v.setPredictionCorrect(correct);
            v.setTradeQuality(tradeQuality);
            v.setResultSummary(buildResultSummary(f.getDirection(), changeBps, tradeQuality, path));
            v.setVerifiedAt(LocalDateTime.now(SYSTEM_ZONE));
            verificationMapper.insert(v);
            verified++;

            log.info("[Verify] {} {} predicted={} conf={} actual={}bps maxFav={}bps maxAdv={}bps tp1First={} quality={}",
                    cycle.getCycleId(), f.getHorizon(), f.getDirection(),
                    f.getConfidence(), changeBps, path.maxFavorableBps, path.maxAdverseBps,
                    path.tp1HitFirst, tradeQuality);
        }
        log.info("[Verify] cycle={} 验证完成 {}/{}", cycle.getCycleId(), verified, forecasts.size());
        return verified;
    }

    // ==================== 路径分析 ====================

    record PathResult(int maxFavorableBps, int maxAdverseBps, Boolean tp1HitFirst) {}

    /**
     * 遍历K线序列，计算最大有利偏移、最大不利偏移、TP1/SL谁先触达。
     */
    private PathResult analyzePath(List<BigDecimal[]> klines, BigDecimal entryPrice,
                                    QuantHorizonForecast forecast) {
        if (klines == null || klines.isEmpty() || entryPrice == null || entryPrice.signum() <= 0) {
            return new PathResult(0, 0, null);
        }

        boolean isLong = "LONG".equals(forecast.getDirection());
        boolean isShort = "SHORT".equals(forecast.getDirection());
        BigDecimal tp1 = forecast.getTp1();
        BigDecimal sl = forecast.getInvalidationPrice();

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
     * GOOD — TP1先于SL触达，或方向正确且最大有利 > volatility的30%
     * LUCKY — 终点方向对，但路径中SL先触达（运气好）
     * BAD — 方向错误或SL先触达
     * FLAT — NO_TRADE且波动在阈值内
     */
    private String judgeQuality(String direction, int changeBps, PathResult path) {
        if ("NO_TRADE".equals(direction)) {
            return Math.abs(changeBps) < NO_TRADE_THRESHOLD_BPS ? "GOOD" : "BAD";
        }

        boolean directionCorrect = ("LONG".equals(direction) && changeBps > CORRECT_THRESHOLD_BPS)
                || ("SHORT".equals(direction) && changeBps < -CORRECT_THRESHOLD_BPS);

        // 有TP/SL价位时，用路径判定
        if (path.tp1HitFirst != null) {
            if (path.tp1HitFirst) return "GOOD";
            return directionCorrect ? "LUCKY" : "BAD";
        }

        // 无TP/SL时，看有利偏移是否足够（> 5bps才不是噪声）
        if (directionCorrect && path.maxFavorableBps > 5) return "GOOD";
        if (directionCorrect) return "LUCKY";
        return "BAD";
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

    private int horizonToMinutes(String horizon) {
        return switch (horizon) {
            case "0_10" -> 10;
            case "10_20" -> 20;
            case "20_30" -> 30;
            default -> 0;
        };
    }

    private String buildResultSummary(String direction, int changeBps,
                                       String quality, PathResult path) {
        String dirLabel = "LONG".equals(direction) ? "看涨" : "看跌";
        String actualDir = changeBps > 0 ? "上涨" : changeBps < 0 ? "下跌" : "持平";
        String pct = bpsToPercent(Math.abs(changeBps));
        boolean dirCorrect = ("LONG".equals(direction) && changeBps > 0)
                || ("SHORT".equals(direction) && changeBps < 0);

        StringBuilder sb = new StringBuilder();
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

        sb.append("，评级");
        sb.append(switch (quality) {
            case "GOOD" -> "优";
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
