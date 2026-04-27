package com.mawai.wiibservice.agent.risk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.StrategyPathStatus;
import com.mawai.wiibcommon.entity.TradeAttribution;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibservice.mapper.FuturesPositionMapper;
import com.mawai.wiibservice.mapper.StrategyPathStatusMapper;
import com.mawai.wiibservice.mapper.TradeAttributionMapper;
import com.mawai.wiibservice.mapper.UserMapper;
import com.mawai.wiibservice.service.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CircuitBreakerService {

    private static final BigDecimal INITIAL_EQUITY = new BigDecimal("100000.00");
    public static volatile boolean ENABLED = true;
    public static volatile double L1_DAILY_NET_LOSS_PCT = 10.0;
    public static volatile int L2_LOSS_STREAK = 4;
    public static volatile int L2_COOLDOWN_HOURS = 2;
    public static volatile double L3_DRAWDOWN_PCT = 30.0;

    private final CacheService cacheService;
    private final UserMapper userMapper;
    private final FuturesPositionMapper futuresPositionMapper;
    private final TradeAttributionMapper tradeAttributionMapper;
    private final StrategyPathStatusMapper strategyPathStatusMapper;

    @Value("${circuit.breaker.enabled:${CIRCUIT_BREAKER_ENABLED:true}}")
    private boolean propertyEnabled;

    /** 开仓前统一入口：账户熔断优先，其次检查单路径是否已被禁用。 */
    public OpenDecision allowOpen(Long userId, String strategyPath) {
        if (!isEnabled()) return OpenDecision.permit();

        refreshDailyLossBreaker(userId);
        syncLossStreakBreaker(userId);
        refreshDrawdownBreaker(userId);

        String l3 = cacheService.get(l3Key(userId));
        if (l3 != null) return OpenDecision.reject("L3回撤熔断: " + l3);

        String l1 = cacheService.get(l1Key(userId));
        if (l1 != null) return OpenDecision.reject("L1日亏损熔断: " + l1);

        String l2 = cacheService.get(l2Key(userId));
        if (l2 != null) return OpenDecision.reject("L2连亏冷却: " + l2);

        String path = normalizeStrategyPath(strategyPath);
        if (path != null) {
            StrategyPathStatus status = strategyPathStatusMapper.selectById(path);
            if (status != null && Boolean.FALSE.equals(status.getEnabled())) {
                return OpenDecision.reject("路径已禁用 path=" + path + " reason=" + status.getDisabledReason());
            }
        }

        return OpenDecision.permit();
    }

    /** Admin 看板查询当前熔断状态；查询时顺手同步状态，避免调参后旧状态卡住。 */
    public BreakerStatus status(Long userId) {
        if (!isEnabled()) {
            return new BreakerStatus(false, false, "DISABLED", null, null, null, null);
        }

        refreshDailyLossBreaker(userId);
        syncLossStreakBreaker(userId);
        refreshDrawdownBreaker(userId);

        String l1 = cacheService.get(l1Key(userId));
        String l2 = cacheService.get(l2Key(userId));
        String l3 = cacheService.get(l3Key(userId));
        String level = l3 != null ? "L3" : l2 != null ? "L2" : l1 != null ? "L1" : "NORMAL";
        return new BreakerStatus(true, !"NORMAL".equals(level), level, l1, l2, l3, cacheService.get(peakKey(userId)));
    }

    /** 平仓归因落库后调用，刷新 L1 日亏损、L2 连亏和 L3 回撤状态。 */
    public void onTradeClosed(FuturesPosition position) {
        if (!isEnabled() || position == null || position.getUserId() == null) return;

        refreshDailyLossBreaker(position.getUserId());
        refreshLossStreakBreaker(position.getUserId());
        refreshDrawdownBreaker(position.getUserId());
    }

    /** L1：统计今天已平仓净盈亏，净亏损超过阈值后熔断到明天零点。 */
    private void refreshDailyLossBreaker(Long userId) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        List<TradeAttribution> rows = tradeAttributionMapper.selectByUserSince(userId, todayStart);

        BigDecimal dailyNetPnl = rows.stream()
                .map(TradeAttribution::getPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal limit = INITIAL_EQUITY.multiply(pctRatio(L1_DAILY_NET_LOSS_PCT));
        String key = l1Key(userId);
        if (dailyNetPnl.compareTo(limit.negate()) > 0) {
            cacheService.delete(key);
            return;
        }

        String reason = "todayNetPnl=" + fmt(dailyNetPnl) + " <= -" + fmt(limit)
                + " (" + fmtPct(L1_DAILY_NET_LOSS_PCT) + "%)";
        boolean alreadyActive = cacheService.get(key) != null;
        cacheService.set(key, reason, Duration.between(LocalDateTime.now(), LocalDate.now().plusDays(1).atStartOfDay()));
        if (!alreadyActive) log.warn("[CircuitBreaker] L1 triggered userId={} {}", userId, reason);
    }

    /** L2：最近 N 笔全为亏损时冷却指定小时；只在平仓后刷新，避免查询开仓时不断续期。 */
    private void refreshLossStreakBreaker(Long userId) {
        refreshLossStreakBreaker(userId, true);
    }

    /** 查询路径只同步已有 L2 状态，不在冷却结束后凭旧连亏重新触发。 */
    private void syncLossStreakBreaker(Long userId) {
        refreshLossStreakBreaker(userId, false);
    }

    private void refreshLossStreakBreaker(Long userId, boolean triggerWhenAbsent) {
        int lossStreak = Math.max(1, L2_LOSS_STREAK);
        List<TradeAttribution> rows = tradeAttributionMapper.selectLatestByUser(userId, lossStreak);
        String key = l2Key(userId);
        if (rows.size() < lossStreak) {
            cacheService.delete(key);
            return;
        }

        boolean allLoss = rows.stream().allMatch(row -> row.getPnl().compareTo(BigDecimal.ZERO) < 0);
        if (!allLoss) {
            cacheService.delete(key);
            return;
        }
        if (!triggerWhenAbsent) return;

        int cooldownHours = Math.max(1, L2_COOLDOWN_HOURS);
        String reason = "consecutiveLoss=" + lossStreak + ", cooldown=" + cooldownHours + "h";
        boolean alreadyActive = cacheService.get(key) != null;
        cacheService.set(key, reason, Duration.ofHours(cooldownHours));
        if (!alreadyActive) log.warn("[CircuitBreaker] L2 triggered userId={} {}", userId, reason);
    }

    /** L3：用当前权益维护峰值，回撤超过阈值后停开仓；权益重回触发线上方时自动清掉旧状态。 */
    private void refreshDrawdownBreaker(Long userId) {
        BigDecimal equity = calcTotalEquity(userId);
        String peakRaw = cacheService.get(peakKey(userId));
        BigDecimal peak = peakRaw != null ? new BigDecimal(peakRaw) : INITIAL_EQUITY;
        if (equity.compareTo(peak) > 0) {
            cacheService.set(peakKey(userId), equity.toPlainString());
            cacheService.delete(l3Key(userId));
            return;
        }

        BigDecimal floor = peak.multiply(BigDecimal.ONE.subtract(pctRatio(L3_DRAWDOWN_PCT)));
        String key = l3Key(userId);
        if (equity.compareTo(floor) > 0) {
            cacheService.delete(key);
            return;
        }

        String reason = "equity=" + fmt(equity) + " <= peak(" + fmt(peak) + ")*"
                + fmt(BigDecimal.ONE.subtract(pctRatio(L3_DRAWDOWN_PCT)))
                + " (" + fmtPct(L3_DRAWDOWN_PCT) + "% drawdown)";
        boolean alreadyActive = cacheService.get(key) != null;
        cacheService.set(key, reason);
        if (!alreadyActive) log.warn("[CircuitBreaker] L3 triggered userId={} {}", userId, reason);
    }

    /** 计算账户权益：现金余额 + 冻结余额 + 开仓保证金 + 按最新 mark/期货价估算的未实现盈亏。 */
    private BigDecimal calcTotalEquity(Long userId) {
        User user = userMapper.selectById(userId);
        BigDecimal equity = user != null ? user.getBalance().add(user.getFrozenBalance()) : BigDecimal.ZERO;
        List<FuturesPosition> positions = futuresPositionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getUserId, userId)
                .eq(FuturesPosition::getStatus, "OPEN"));
        for (FuturesPosition position : positions) {
            equity = equity.add(position.getMargin());
            BigDecimal price = cacheService.getMarkPrice(position.getSymbol());
            if (price == null) price = cacheService.getFuturesPrice(position.getSymbol());
            if (price != null) equity = equity.add(calcPnl(position, price));
        }
        return equity;
    }

    /** 按方向计算单仓未实现盈亏，只用于熔断权益估算。 */
    private BigDecimal calcPnl(FuturesPosition position, BigDecimal price) {
        BigDecimal pnl = "LONG".equals(position.getSide())
                ? price.subtract(position.getEntryPrice()).multiply(position.getQuantity())
                : position.getEntryPrice().subtract(price).multiply(position.getQuantity());
        return pnl.setScale(2, RoundingMode.HALF_UP);
    }

    /** L1 Redis key 带日期，便于自然日自动恢复。 */
    private String l1Key(Long userId) {
        return "circuit:breaker:l1:" + userId + ":" + LocalDate.now();
    }

    /** L2 Redis key 不带日期，靠配置化 TTL 自动恢复。 */
    private String l2Key(Long userId) {
        return "circuit:breaker:l2:" + userId;
    }

    /** L3 Redis key 无 TTL；开仓/看板刷新时满足恢复条件会清理。 */
    private String l3Key(Long userId) {
        return "circuit:breaker:l3:" + userId;
    }

    /** 账户权益峰值 key，供 L3 回撤计算使用。 */
    private String peakKey(Long userId) {
        return "circuit:breaker:peak:" + userId;
    }

    /** 金额日志统一保留 2 位，方便人工核对。 */
    private String fmt(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private boolean isEnabled() {
        return propertyEnabled && ENABLED;
    }

    public boolean isEffectiveEnabled() {
        return isEnabled();
    }

    public boolean isPropertyEnabled() {
        return propertyEnabled;
    }

    private BigDecimal pctRatio(double pct) {
        return BigDecimal.valueOf(Math.max(0.0, pct))
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
    }

    private String fmtPct(double pct) {
        return BigDecimal.valueOf(pct).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** 兼容旧 memo 和新 [Strategy-X] 标签，只返回三条实盘路径。 */
    private String normalizeStrategyPath(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String path = extractStrategyTag(raw.trim());
        return switch (path) {
            case "BREAKOUT" -> "BREAKOUT";
            case "MR", "MEAN_REVERSION" -> "MR";
            case "LEGACY_TREND", "TREND", "LEGACY-TREND" -> "LEGACY_TREND";
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

    public record OpenDecision(boolean allowed, String reason) {
        private static OpenDecision permit() {
            return new OpenDecision(true, null);
        }

        private static OpenDecision reject(String reason) {
            return new OpenDecision(false, reason);
        }
    }

    public record BreakerStatus(
            boolean enabled,
            boolean anyActive,
            String level,
            String l1Reason,
            String l2Reason,
            String l3Reason,
            String peakEquity
    ) {}
}
