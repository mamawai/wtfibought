package com.mawai.wiibservice.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.StrategyPathStatus;
import com.mawai.wiibcommon.entity.TradeAttribution;
import com.mawai.wiibservice.agent.risk.CircuitBreakerService;
import com.mawai.wiibservice.mapper.StrategyPathStatusMapper;
import com.mawai.wiibservice.mapper.TradeAttributionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeAttributionService {

    private static final String PATH_BREAKOUT = "BREAKOUT";
    private static final String PATH_MR = "MR";
    private static final String PATH_LEGACY_TREND = "LEGACY_TREND";
    private static final int LOSS_DISABLE_THRESHOLD = 5;

    private final TradeAttributionMapper tradeAttributionMapper;
    private final StrategyPathStatusMapper strategyPathStatusMapper;
    private final CacheService cacheService;
    private final CircuitBreakerService circuitBreakerService;

    @Value("${trade.attribution.enabled:${TRADE_ATTRIBUTION_ENABLED:true}}")
    private boolean enabled;

    /** 全平后写交易归因；失败只记日志，不能影响真实平仓流程。 */
    public void recordExit(FuturesPosition position, BigDecimal pnl, String exitReason) {
        if (!enabled || position == null) return;

        String strategyPath = normalizeStrategyPath(position.getMemo());
        if (strategyPath == null) return;

        try {
            Long existed = tradeAttributionMapper.selectCount(new LambdaQueryWrapper<TradeAttribution>()
                    .eq(TradeAttribution::getPositionId, position.getId()));
            if (existed != null && existed > 0) return;

            LocalDateTime exitTime = LocalDateTime.now();
            LocalDateTime entryTime = position.getCreatedAt() != null ? position.getCreatedAt() : exitTime;
            BigDecimal realizedPnl = pnl != null ? pnl : position.getClosedPnl();

            TradeAttribution attribution = new TradeAttribution();
            attribution.setPositionId(position.getId());
            attribution.setSymbol(position.getSymbol());
            attribution.setStrategyPath(strategyPath);
            attribution.setEntryTime(entryTime);
            attribution.setExitTime(exitTime);
            attribution.setEntryFactorsJson(buildEntryFactorsJson(position, strategyPath));
            attribution.setPnl(realizedPnl);
            attribution.setHoldingMinutes((int) Math.max(0, Duration.between(entryTime, exitTime).toMinutes()));
            attribution.setExitReason(normalizeExitReason(exitReason));
            tradeAttributionMapper.insert(attribution);

            checkConsecutiveLoss(strategyPath);
            circuitBreakerService.onTradeClosed(position);
            log.info("[TradeAttr] recorded posId={} path={} pnl={} exitReason={}",
                    position.getId(), strategyPath, realizedPnl, attribution.getExitReason());
        } catch (Exception e) {
            // 归因失败不能影响真实平仓；错误留日志，后续按 position_id 补数。
            log.warn("[TradeAttr] record failed posId={} reason={}",
                    position.getId(), e.getMessage());
        }
    }

    /** 每小时聚合近 7 天路径表现到 Redis，供轻量看板/告警读取。 */
    @Scheduled(cron = "0 0 * * * *")
    public void aggregatePathStats() {
        if (!enabled) return;

        LocalDateTime from = LocalDateTime.now().minusDays(7);
        List<TradeAttribution> rows = tradeAttributionMapper.selectList(new LambdaQueryWrapper<TradeAttribution>()
                .select(TradeAttribution::getStrategyPath, TradeAttribution::getPnl)
                .ge(TradeAttribution::getEntryTime, from)
                .isNotNull(TradeAttribution::getPnl));

        Map<String, List<BigDecimal>> byPath = new LinkedHashMap<>();
        byPath.put(PATH_BREAKOUT, new ArrayList<>());
        byPath.put(PATH_MR, new ArrayList<>());
        byPath.put(PATH_LEGACY_TREND, new ArrayList<>());
        for (TradeAttribution row : rows) {
            List<BigDecimal> pnlList = byPath.get(row.getStrategyPath());
            if (pnlList != null) pnlList.add(row.getPnl());
        }

        for (Map.Entry<String, List<BigDecimal>> entry : byPath.entrySet()) {
            Map<String, Object> stats = calcStats(entry.getKey(), entry.getValue());
            cacheService.set("trade:attribution:path:" + entry.getKey(), JSON.toJSONString(stats), Duration.ofHours(2));
        }
        log.info("[TradeAttr] hourly stats refreshed rows={}", rows.size());
    }

    /** 检查单路径最近连续亏损笔数，达到阈值后落库禁用该路径。 */
    public void checkConsecutiveLoss(String strategyPath) {
        List<TradeAttribution> rows = tradeAttributionMapper.selectList(new LambdaQueryWrapper<TradeAttribution>()
                .select(TradeAttribution::getPnl)
                .eq(TradeAttribution::getStrategyPath, strategyPath)
                .isNotNull(TradeAttribution::getPnl)
                .orderByDesc(TradeAttribution::getExitTime)
                .last("LIMIT " + LOSS_DISABLE_THRESHOLD));

        int lossCount = 0;
        for (TradeAttribution row : rows) {
            if (row.getPnl().compareTo(BigDecimal.ZERO) < 0) lossCount++;
            else break;
        }

        StrategyPathStatus status = strategyPathStatusMapper.selectById(strategyPath);
        if (status == null) {
            status = new StrategyPathStatus();
            status.setPath(strategyPath);
            status.setEnabled(true);
        }
        status.setConsecutiveLossCount(lossCount);
        status.setUpdatedAt(LocalDateTime.now());
        if (lossCount >= LOSS_DISABLE_THRESHOLD) {
            status.setEnabled(false);
            status.setDisabledReason("连续亏损 " + lossCount + " 笔");
            status.setDisabledAt(LocalDateTime.now());
            log.warn("[TradeAttr] strategy path disabled path={} lossCount={}", strategyPath, lossCount);
        }

        if (strategyPathStatusMapper.selectById(strategyPath) == null) {
            strategyPathStatusMapper.insert(status);
        } else {
            strategyPathStatusMapper.updateById(status);
        }
    }

    /** 保存入场时能从 futures_position 还原的关键上下文，后续可补更细因子。 */
    private String buildEntryFactorsJson(FuturesPosition position, String strategyPath) {
        Map<String, Object> factors = new LinkedHashMap<>();
        factors.put("strategyPath", strategyPath);
        factors.put("source", "futures_position");
        factors.put("side", position.getSide());
        factors.put("entryPrice", position.getEntryPrice());
        factors.put("quantity", position.getQuantity());
        factors.put("leverage", position.getLeverage());
        factors.put("margin", position.getMargin());
        factors.put("memo", position.getMemo());
        factors.put("stopLosses", position.getStopLosses());
        factors.put("takeProfits", position.getTakeProfits());
        return JSON.toJSONString(factors);
    }

    /** 计算路径基础统计：样本、胜率、EV、总盈亏和简化 sharpe。 */
    private Map<String, Object> calcStats(String path, List<BigDecimal> pnlList) {
        int samples = pnlList.size();
        double total = pnlList.stream().mapToDouble(BigDecimal::doubleValue).sum();
        double avg = samples == 0 ? 0.0 : total / samples;
        double variance = samples == 0 ? 0.0 : pnlList.stream()
                .mapToDouble(BigDecimal::doubleValue)
                .map(v -> (v - avg) * (v - avg))
                .sum() / samples;
        double std = Math.sqrt(variance);
        long wins = pnlList.stream().filter(v -> v.compareTo(BigDecimal.ZERO) > 0).count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("path", path);
        stats.put("samples", samples);
        stats.put("winRate", samples == 0 ? 0.0 : (double) wins / samples);
        stats.put("ev", avg);
        stats.put("sharpe", std == 0.0 ? 0.0 : avg / std);
        stats.put("totalPnl", total);
        stats.put("updatedAt", LocalDateTime.now().toString());
        return stats;
    }

    /** 统一策略路径命名，兼容旧 TREND/MEAN_REVERSION memo。 */
    private String normalizeStrategyPath(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String path = extractStrategyTag(raw.trim());
        return switch (path) {
            case PATH_BREAKOUT -> PATH_BREAKOUT;
            case PATH_MR, "MEAN_REVERSION" -> PATH_MR;
            case PATH_LEGACY_TREND, "TREND", "LEGACY-TREND" -> PATH_LEGACY_TREND;
            default -> null;
        };
    }

    /** 从 memo 中抽取 [Strategy-X] 的 X；没有标签时按旧字符串处理。 */
    private String extractStrategyTag(String raw) {
        int start = raw.indexOf("[Strategy-");
        if (start < 0) return raw;
        int end = raw.indexOf(']', start);
        return end > start ? raw.substring(start + "[Strategy-".length(), end) : raw;
    }

    /** exitReason 只保留可枚举值；TP/SL 自动平仓拿不到来源时允许 UNKNOWN。 */
    private String normalizeExitReason(String reason) {
        if (reason == null || reason.isBlank()) return "UNKNOWN";
        String r = reason.trim().toUpperCase();
        return switch (r) {
            case "TP", "TRAILING", "SL", "TIMEOUT", "REVERSAL", "MANUAL" -> r;
            default -> "UNKNOWN";
        };
    }
}
