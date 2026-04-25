package com.mawai.wiibservice.agent.trading;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.*;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 确定性交易执行器 V2 — 基于信号共振的多策略引擎。
 * <p>
 * 核心原则（来自freqtrade/binance-futures-bot开源策略研究）：
 * 1. 高周期趋势过滤 — 1h MA方向是入场门槛，不做逆势交易
 * 2. 多指标共振 — MACD+RSI+Volume+BB+多周期+微结构，至少3个确认才入场
 * 3. 手续费意识 — TP必须覆盖往返手续费+最低盈利
 * 4. 动态出场 — BB均值回归用BB中轨出场，趋势用纯追踪止损
 * 5. 选择性交易 — 宁可不交易，也不做低质量信号的交易
 * 6. 策略选择基于指标状态 — 而非仅靠regime标签
 */
@Slf4j
public class DeterministicTradingExecutor {

    public static final BigDecimal INITIAL_BALANCE = new BigDecimal("100000.00");

    private static final String PATH_BREAKOUT = "BREAKOUT";
    private static final String PATH_MR = "MR";
    private static final String PATH_LEGACY_TREND = "LEGACY_TREND";
    private static final String PATH_SHADOW_5OF7 = "SHADOW_5OF7";
    private static final String LEGACY_PATH_TREND = "TREND";
    private static final String LEGACY_PATH_MR = "MEAN_REVERSION";
    private static final long ENTRY_COOLDOWN_MS = 30 * 60 * 1000L;
    private static final ConcurrentHashMap<String, Long> LAST_ENTRY_MS = new ConcurrentHashMap<>();

    // ==================== 手续费 ====================
    // 0.0008 与模拟盘记账一致（开仓0.04% + 平仓0.04%）；真盘迁移时改 0.0010（Binance VIP0 taker 0.05% 往返）
    private static final double ROUND_TRIP_FEE_RATE = 0.0008;
    private static final double MIN_PROFIT_AFTER_FEE_ATR = 0.5; // 扣除手续费后最低盈利0.5ATR

    // ==================== 风控常量 ====================
    private static final BigDecimal RISK_PER_TRADE = new BigDecimal("0.02");
    private static final BigDecimal MIN_ENTRY_RR = new BigDecimal("1.2");
    private static final AtomicLong RR_GUARD_TRIGGERED_COUNT = new AtomicLong();
    private static final AtomicLong MARGIN_CAP_TRIGGERED_COUNT = new AtomicLong();
    // Phase 0A 止血：单笔保证金占余额上限 0.35 → 0.15，单笔最大亏损下降约 57%
    private static final BigDecimal MAX_MARGIN_PCT = new BigDecimal("0.15");
    private static final BigDecimal DAILY_LOSS_LIMIT_PCT = new BigDecimal("0.05");
    private static final BigDecimal DRAWDOWN_THRESHOLD = new BigDecimal("0.85");
    private static final BigDecimal DRAWDOWN_REDUCTION = new BigDecimal("0.7"); // 回撤时杠杆和仓位缩减到70%
    private static final double LOW_CONFIDENCE_POSITION_SCALE = 0.5;

    // ==================== 信号共振门槛 ====================
    // Phase 0A 止血：3→5，且删除 regime 降档，RANGE/SQUEEZE 不再放宽
    private static final int MIN_CONFLUENCE_SCORE = 6; // 7维评分，>=6才入场

    // ==================== 策略A: EMA趋势跟踪 ====================
    private static final double TREND_MIN_CONFIDENCE = 0.35;
    private static final int TREND_MAX_LEVERAGE = 25;

    // ==================== 策略B: BB均值回归 ====================
    private static final int REVERT_MAX_LEVERAGE = 20;
    private static final double REVERT_POSITION_SCALE = 0.6;
    private static final double REVERT_BB_PB_LONG_MAX = 10.0;  // BB%B < 10% = 超卖（注意: bollPercentB返回0-100）
    private static final double REVERT_BB_PB_SHORT_MIN = 90.0; // BB%B > 90% = 超买
    private static final double REVERT_RSI_LONG_MAX = 35.0;
    private static final double REVERT_RSI_SHORT_MIN = 65.0;
    private static final int REVERT_MAX_HOLD_HOURS = 3;

    // ==================== 策略C: BB压缩突破 ====================
    private static final int BREAKOUT_MAX_LEVERAGE = 20;
    private static final double BREAKOUT_VOLUME_MIN = 1.3; // 成交量>=1.3倍均量
    private static final double BREAKOUT_POSITION_SCALE = 0.8;

    // ==================== 低波动小仓位模式 ====================
    // 盘整期ATR过窄，SL会落入noise floor内被tools层拒。
    // 策略：撑SL到noise floor，TP按同比例扩保持R:R，qty缩到60%试探性入场。
    // 扩张倍数>3.0 → 极端横盘（如PAXG日内窄幅），放弃入场。
    private static final double LOW_VOL_SL_EXPAND_MAX = 3.0;
    private static final double LOW_VOL_POSITION_SCALE = 0.6;
    /**
     * 低波动小仓位交易开关（Admin运行时切换，重启恢复默认false保守档）。
     * Phase 0A 期间严禁通过 Admin 端打开——SL 落噪音带是 §2.3 已确认的负 EV 机制。
     */
    public static volatile boolean LOW_VOL_TRADING_ENABLED = false;
    /**
     * LEGACY_TREND 5/7 实盘开仓开关。
     * false：默认保守，只允许 6/7 共振分数实盘开仓。
     * true：放宽为 5/7 也可继续走开仓逻辑，需先观察 SHADOW_5OF7 样本质量后再手动开启。
     */
    public static volatile boolean LEGACY_THRESHOLD_5OF7_ENABLED = false;
    /**
     * LEGACY_TREND 5/7 影子样本开关。
     * true：5/7 但未开启实盘时，只写 HOLD 决策和 [Strategy-SHADOW_5OF7] reasoning，不下单。
     * false：5/7 直接按共振不足 HOLD，不额外保留候选交易样本。
     */
    public static volatile boolean LEGACY_5OF7_SHADOW_ENABLED = true;

    // ==================== 持仓管理 ====================
    // 反转平仓置信度阈值：0.82要求强反转信号才平仓，防5m级噪音打掉浮盈
    // 配合 REVERSAL_STREAK_REQUIRED 连续校验 + 手续费门槛使用
    private static final double REVERSAL_CONFIDENCE = 0.82;
    private static final int REVERSAL_STREAK_REQUIRED = 2;
    private static final double TIME_STOP_MIN_R = 0.5;
    private static final long TREND_TIME_STOP_MINUTES = 60;
    private static final long REVERT_TIME_STOP_MINUTES = 60;
    private static final long BREAKOUT_TIME_STOP_MINUTES = 25;
    // Phase 0A 止血：3.0 → 5.0，反转平仓需更厚浮盈才触发，避免被噪音震出（§2.3 A）
    private static final double REVERSAL_MIN_PROFIT_FEE_MULTIPLE = 5.0;
    // 仓位反转信号连续次数：key=positionId，value=连续出现的反向信号周期数
    // 仓位平仓/反转中断时自动清零
    private static final ConcurrentHashMap<Long, Integer> REVERSAL_STREAK = new ConcurrentHashMap<>();

    /**
     * 仓位峰值浮盈记忆（跨 cycle 保存）。
     * 为什么需要：原追踪止损基于当前 profit 计算 SL，两次 cycle 之间若浮盈冲高回落，
     * 下次 cycle 只看到回落值，shouldMoveSl 不动 SL → 峰值利润永远丢失。
     * 用 peak.maxProfit 显式记录历史最高浮盈，Sentinel/分批止盈/追踪SL 共享。
     * partialTpDone：已在 partialTpAtr 处平过半仓的标记，防重复分批。
     */
    private record PositionPeak(BigDecimal maxProfit, boolean partialTpDone) {
        PositionPeak withMaxProfit(BigDecimal p) { return new PositionPeak(p, partialTpDone); }
        PositionPeak withPartialTpDone() { return new PositionPeak(maxProfit, true); }
    }
    private static final ConcurrentHashMap<Long, PositionPeak> POSITION_PEAKS = new ConcurrentHashMap<>();

    /** 供外部（Sentinel）读取峰值浮盈，null 表示无记录。 */
    public static BigDecimal getPositionMaxProfit(Long positionId) {
        PositionPeak p = POSITION_PEAKS.get(positionId);
        return p == null ? null : p.maxProfit();
    }

    public static long getRrGuardTriggeredCount() {
        return RR_GUARD_TRIGGERED_COUNT.get();
    }

    public static long getMarginCapTriggeredCount() {
        return MARGIN_CAP_TRIGGERED_COUNT.get();
    }

    public record ExecutionResult(String action, String reasoning, String executionLog) {}

    /** SL/TP低波动调整结果。positionScale<1表示小仓位模式；note用于日志标注。 */
    private record SlTpAdjustment(BigDecimal slDistance, BigDecimal tpDistance,
                                  double positionScale, String note) {}

    // ==================== 丰富的市场上下文 ====================

    private record MarketContext(
            String regime, String regimeTransition,
            BigDecimal atr5m, BigDecimal rsi5m,
            BigDecimal price,
            // 高周期趋势过滤
            Integer maAlignment1h,   // 1h均线排列: +1=多头, -1=空头, 0=纠缠
            Integer maAlignment15m,
            Integer maAlignment5m,
            // 5m MACD
            String macdCross5m,      // "golden"=金叉, "death"=死叉, null=无
            String macdHistTrend5m,  // "rising"/"falling"/"sideways"
            BigDecimal macdDif5m,    // MACD快线（DIF）
            BigDecimal macdDea5m,    // MACD慢线（DEA/Signal）
            BigDecimal ema20,        // EMA20，用于价格趋势确认
            // 5m 布林带
            Double bollPb5m,         // BB%B: 0=下轨, 50=中轨, 100=上轨（bollPercentB返回0-100）
            Double bollBandwidth5m,
            boolean bollSqueeze,
            // 5m 成交量 & 趋势
            Double volumeRatio5m,    // >1 = 放量
            String closeTrend5m,     // "rising"/"falling"/"sideways"
            // 15m RSI
            Double rsi15m,
            // 微结构
            Double bidAskImbalance,
            Double takerPressure,
            Double oiChangeRate,
            Double fundingDeviation,
            // 数据质量标记（STALE_AGG_TRADE 等，从 snapshot.qualityFlags 解析）
            List<String> qualityFlags
    ) {}

    // ==================== 主入口 ====================

    public static ExecutionResult execute(
            String symbol, User user,
            List<FuturesPositionDTO> symbolPositions,
            QuantForecastCycle forecast,
            List<QuantSignalDecision> signals,
            List<AiTradingDecision> recentDecisions,
            BigDecimal futuresPrice, BigDecimal markPrice,
            BigDecimal totalEquity,
            TradingOperations tools) {
        return executeInternal(symbol, user, symbolPositions, forecast, signals, recentDecisions,
                futuresPrice, markPrice, totalEquity, tools, null, null);
    }

    /**
     * 支持自定义 SymbolProfile 的重载入口（回测参数扫描用）。
     */
    public static ExecutionResult execute(
            String symbol, User user,
            List<FuturesPositionDTO> symbolPositions,
            QuantForecastCycle forecast,
            List<QuantSignalDecision> signals,
            List<AiTradingDecision> recentDecisions,
            BigDecimal futuresPrice, BigDecimal markPrice,
            BigDecimal totalEquity,
            TradingOperations tools,
            SymbolProfile profileOverride) {
        return executeInternal(symbol, user, symbolPositions, forecast, signals, recentDecisions,
                futuresPrice, markPrice, totalEquity, tools, profileOverride, null);
    }

    /**
     * 生产调度传入全量open仓位ID，清理全局仓位记忆时不误删其它symbol。
     */
    public static ExecutionResult execute(
            String symbol, User user,
            List<FuturesPositionDTO> symbolPositions,
            QuantForecastCycle forecast,
            List<QuantSignalDecision> signals,
            List<AiTradingDecision> recentDecisions,
            BigDecimal futuresPrice, BigDecimal markPrice,
            BigDecimal totalEquity,
            TradingOperations tools,
            Collection<Long> allOpenPositionIds) {
        return executeInternal(symbol, user, symbolPositions, forecast, signals, recentDecisions,
                futuresPrice, markPrice, totalEquity, tools, null, allOpenPositionIds);
    }

    private static ExecutionResult executeInternal(
            String symbol, User user,
            List<FuturesPositionDTO> symbolPositions,
            QuantForecastCycle forecast,
            List<QuantSignalDecision> signals,
            List<AiTradingDecision> recentDecisions,
            BigDecimal futuresPrice, BigDecimal markPrice,
            BigDecimal totalEquity,
            TradingOperations tools,
            SymbolProfile profileOverride,
            Collection<Long> allOpenPositionIds) {

        if (user == null || futuresPrice == null || futuresPrice.signum() <= 0) {
            return new ExecutionResult("HOLD", "数据缺失", "");
        }

        SymbolProfile profile = profileOverride != null ? profileOverride : SymbolProfile.of(symbol);
        MarketContext ctx = parseMarketContext(forecast, futuresPrice);
        log.info("[Executor] {} regime={} atr={} rsi5m={} maAlign1h={} macdCross={} bbPb={} vol={} squeeze={} equity={}",
                symbol, ctx.regime, ctx.atr5m, ctx.rsi5m, ctx.maAlignment1h, ctx.macdCross5m,
                ctx.bollPb5m, ctx.volumeRatio5m, ctx.bollSqueeze, totalEquity);

        boolean hasPosition = symbolPositions != null && !symbolPositions.isEmpty();
        LocalDateTime forecastTime = forecast != null ? forecast.getForecastTime() : null;
        if (allOpenPositionIds != null) {
            cleanupPositionMemory(allOpenPositionIds);
        }

        if (hasPosition) {
            return managePositions(symbolPositions, signals, ctx, profile, tools, forecastTime, allOpenPositionIds == null);
        }
        return evaluateEntry(symbol, user, totalEquity, forecast, signals, recentDecisions, ctx, profile, tools, forecastTime);
    }

    private static void cleanupPositionMemory(Collection<Long> livePositionIds) {
        POSITION_PEAKS.keySet().removeIf(id -> !livePositionIds.contains(id));
        REVERSAL_STREAK.keySet().removeIf(id -> !livePositionIds.contains(id));
    }

    // ==================== 持仓管理（盯盘逻辑）====================

    private static ExecutionResult managePositions(
            List<FuturesPositionDTO> positions,
            List<QuantSignalDecision> signals,
            MarketContext ctx,
            SymbolProfile profile,
            TradingOperations tools,
            LocalDateTime forecastTime,
            boolean cleanupFromCurrentPositions) {

        StringBuilder execLog = new StringBuilder();
        StringBuilder reasons = new StringBuilder();
        String action = "HOLD";

        if (cleanupFromCurrentPositions) {
            Set<Long> livePositionIds = positions.stream()
                    .map(FuturesPositionDTO::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            cleanupPositionMemory(livePositionIds);
        }

        for (FuturesPositionDTO pos : positions) {
            if (!"OPEN".equals(pos.getStatus())) {
                // 仓位已平：清理峰值/反转状态，防内存泄漏
                POSITION_PEAKS.remove(pos.getId());
                REVERSAL_STREAK.remove(pos.getId());
                continue;
            }
            BigDecimal entryPrice = pos.getEntryPrice();
            if (entryPrice == null || entryPrice.signum() <= 0) continue;

            boolean isLong = "LONG".equals(pos.getSide());
            BigDecimal currentPrice = ctx.price;
            BigDecimal profit = isLong ? currentPrice.subtract(entryPrice) : entryPrice.subtract(currentPrice);

            // 更新峰值浮盈（只在正浮盈时记录，浮亏不入库）
            // 追踪SL/分批止盈/Sentinel 都基于此 peak 判断，防两次 cycle 之间的峰值丢失
            if (profit.signum() > 0) {
                POSITION_PEAKS.compute(pos.getId(), (k, old) -> {
                    if (old == null) return new PositionPeak(profit, false);
                    return profit.compareTo(old.maxProfit()) > 0 ? old.withMaxProfit(profit) : old;
                });
            }

            // ===== 1. SHOCK → 浮亏减仓保护 =====
            if ("SHOCK".equals(ctx.regime)) {
                if (profit.signum() < 0) {
                    BigDecimal halfQty = pos.getQuantity().divide(BigDecimal.TWO, 8, RoundingMode.HALF_DOWN);
                    if (halfQty.signum() > 0) {
                        String closeResult = tools.closePosition(pos.getId(), halfQty);
                        execLog.append("SHOCK减仓50%: ").append(closeResult).append("\n");
                        reasons.append("regime=SHOCK+浮亏→减仓50%; ");
                        action = "CLOSE";
                    }
                } else {
                    reasons.append("regime=SHOCK+浮盈→持有观察; ");
                }
                continue;
            }

            // ===== 2. 高周期趋势反转 → 平仓（最重要的盯盘信号）=====
            if (ctx.maAlignment1h != null && ctx.maAlignment1h != 0) {
                boolean trendAgainst = (isLong && ctx.maAlignment1h < 0) || (!isLong && ctx.maAlignment1h > 0);
                if (trendAgainst) {
                    String closeResult = tools.closePosition(pos.getId(), pos.getQuantity());
                    execLog.append("1h趋势反转平仓: ").append(closeResult).append("\n");
                    reasons.append("1h MA方向(").append(ctx.maAlignment1h > 0 ? "多头" : "空头")
                            .append(")与持仓(").append(pos.getSide()).append(")相反→平仓; ");
                    action = "CLOSE";
                    continue;
                }
            }

            // ===== 3. 均值回归持仓 → BB中轨动态止盈（仅对MR仓位生效）=====
            if (isMrPath(pos.getMemo())
                    && "RANGE".equals(ctx.regime) && ctx.bollPb5m != null) {
                boolean reachedMid = (isLong && ctx.bollPb5m >= 45.0) || (!isLong && ctx.bollPb5m <= 55.0);
                if (reachedMid && profit.signum() > 0) {
                    String closeResult = tools.closePosition(pos.getId(), pos.getQuantity());
                    execLog.append("BB中轨止盈: ").append(closeResult).append("\n");
                    reasons.append(String.format("RANGE+BB%%B=%.2f→到达BB中轨→止盈; ", ctx.bollPb5m));
                    action = "CLOSE";
                    continue;
                }
            }

            // ===== 4. 均值回归超时平仓（仅对MR仓位生效）=====
            if (isMrPath(pos.getMemo())
                    && "RANGE".equals(ctx.regime) && pos.getCreatedAt() != null) {
                long holdHours = Duration.between(pos.getCreatedAt(), LocalDateTime.now()).toHours();
                if (holdHours >= REVERT_MAX_HOLD_HOURS) {
                    String closeResult = tools.closePosition(pos.getId(), pos.getQuantity());
                    execLog.append("均值回归超时平仓: ").append(closeResult).append("\n");
                    reasons.append("RANGE持仓").append(holdHours).append("h>=")
                            .append(REVERT_MAX_HOLD_HOURS).append("h→超时平仓; ");
                    action = "CLOSE";
                    continue;
                }
            }

            // ===== 5. 时间止损：持仓太久仍未达到0.5R，释放风险预算 =====
            if (pos.getCreatedAt() != null) {
                Long limitMinutes = timeStopLimitMinutes(pos.getMemo());
                BigDecimal riskDistance = calcRiskDistance(pos, ctx, profile, isLong);
                long holdingMinutes = Duration.between(pos.getCreatedAt(), LocalDateTime.now()).toMinutes();
                if (limitMinutes != null && holdingMinutes > limitMinutes) {
                    BigDecimal minProfit = riskDistance.multiply(BigDecimal.valueOf(TIME_STOP_MIN_R));
                    if (profit.compareTo(minProfit) < 0) {
                        String closeResult = tools.closePosition(pos.getId(), pos.getQuantity());
                        log.info("[Executor] TIME_STOP_TRIGGERED memo={} holding={} profit={} minProfit={} riskDistance={} result={}",
                                pos.getMemo(), holdingMinutes, profit.toPlainString(), minProfit.toPlainString(),
                                riskDistance.toPlainString(), closeResult);
                        execLog.append("时间止损: ").append(closeResult).append("\n");
                        reasons.append(String.format("TIME_STOP memo=%s holding=%dmin profit=%s<0.5R=%s→平仓; ",
                                pos.getMemo(), holdingMinutes, fmtPrice(profit), fmtPrice(minProfit)));
                        action = "CLOSE";
                        continue;
                    }
                }
            }

            // ===== 6. 分批止盈（Peak 感知）=====
            // 峰值浮盈到 partialTpAtr×ATR 先平 50% 落袋，剩余跟追踪止损跑大行情
            // partialTpDone=true 后不再重复分批
            if (ctx.atr5m != null && ctx.atr5m.signum() > 0) {
                PositionPeak peak = POSITION_PEAKS.get(pos.getId());
                if (peak != null && !peak.partialTpDone()) {
                    BigDecimal partialThreshold = ctx.atr5m.multiply(BigDecimal.valueOf(profile.partialTpAtr()));
                    if (peak.maxProfit().compareTo(partialThreshold) >= 0) {
                        BigDecimal halfQty = pos.getQuantity().divide(BigDecimal.TWO, 8, RoundingMode.HALF_DOWN);
                        if (halfQty.signum() > 0) {
                            String closeResult = tools.closePosition(pos.getId(), halfQty);
                            // 无论成功与否都置标记，防失败反复重试（失败原因若是 qty 过小，重试也无意义）
                            POSITION_PEAKS.computeIfPresent(pos.getId(), (k, v) -> v.withPartialTpDone());
                            double peakAtr = peak.maxProfit().doubleValue() / ctx.atr5m.doubleValue();
                            execLog.append("分批止盈50%(peak=").append(String.format("%.2fATR", peakAtr))
                                    .append("): ").append(closeResult).append("\n");
                            reasons.append(String.format("峰值浮盈%.1fATR≥%.1fATR→分批平50%%; ",
                                    peakAtr, profile.partialTpAtr()));
                            action = "PARTIAL_TP";
                            continue;
                        }
                    }
                }
            }

            // ===== 7. 信号反转 → 平仓（分级：有稳固浮盈放宽，无稳固浮盈严格）=====
            // 有浮盈(peak≥1ATR) → 反转信号一来果断跑，错过锁利比被噪音假平更致命
            // 无浮盈 → 严格门槛 0.82+streak=2+浮盈>=3×fee，防吃手续费型平仓
            PositionPeak peak = POSITION_PEAKS.get(pos.getId());
            boolean hasStablePeak = peak != null && ctx.atr5m != null && ctx.atr5m.signum() > 0
                    && peak.maxProfit().compareTo(ctx.atr5m) >= 0;
            double confThreshold = hasStablePeak ? 0.70 : REVERSAL_CONFIDENCE;
            int streakRequired = hasStablePeak ? 1 : REVERSAL_STREAK_REQUIRED;

            QuantSignalDecision bestSignal = findBestSignal(signals, forecastTime);
            boolean isReversal = bestSignal != null && bestSignal.getConfidence() != null
                    && bestSignal.getConfidence().doubleValue() >= confThreshold
                    && ((isLong && "SHORT".equals(bestSignal.getDirection()))
                        || (!isLong && "LONG".equals(bestSignal.getDirection())));
            if (isReversal) {
                int streak = REVERSAL_STREAK.merge(pos.getId(), 1, Integer::sum);
                if (streak < streakRequired) {
                    reasons.append(String.format("反转信号streak=%d/%d→观察下个周期; ",
                            streak, streakRequired));
                    continue;
                }
                BigDecimal qty = pos.getQuantity();
                BigDecimal profitAbs = profit.multiply(qty);
                // 手续费门槛仅在"无稳固浮盈"时启用，有浮盈时直接跑
                if (!hasStablePeak) {
                    BigDecimal roundTripFee = currentPrice.multiply(qty)
                            .multiply(BigDecimal.valueOf(ROUND_TRIP_FEE_RATE));
                    BigDecimal minProfit = roundTripFee.multiply(BigDecimal.valueOf(REVERSAL_MIN_PROFIT_FEE_MULTIPLE));
                    if (profitAbs.compareTo(minProfit) < 0) {
                        reasons.append(String.format("反转streak=%d但浮盈%.2f<手续费×%.0f=%.2f→等追踪止损; ",
                                streak, profitAbs.doubleValue(), REVERSAL_MIN_PROFIT_FEE_MULTIPLE,
                                minProfit.doubleValue()));
                        continue;
                    }
                }
                String closeResult = tools.closePosition(pos.getId(), qty);
                REVERSAL_STREAK.remove(pos.getId());
                String tier = hasStablePeak ? "有浮盈放宽" : "标准";
                execLog.append("信号反转平仓[").append(tier).append("](streak=").append(streak)
                        .append(",浮盈").append(profitAbs.setScale(2, RoundingMode.HALF_UP)).append("): ")
                        .append(closeResult).append("\n");
                reasons.append("信号反转[").append(tier).append("](").append(bestSignal.getDirection())
                        .append(" conf=").append(bestSignal.getConfidence())
                        .append(" streak=").append(streak).append(")→平仓; ");
                action = "CLOSE";
                continue;
            } else {
                // 非反转信号：清零 streak，防历史反转误累积
                REVERSAL_STREAK.remove(pos.getId());
            }

            // ===== 8. Peak 感知的 ATR 追踪止损 =====
            // 用历史峰值浮盈而非当前浮盈计算 SL：防两次 cycle 之间的峰值回撤丢失
            if (ctx.atr5m != null && ctx.atr5m.signum() > 0) {
                BigDecimal atr = ctx.atr5m;
                BigDecimal currentSl = getCurrentStopLossPrice(pos);
                BigDecimal trailGap = atr.multiply(BigDecimal.valueOf(profile.trailGapAtr()));

                // peak 复用第 6 节已取的变量，未记录 peak（浮盈从未为正）时用当前 profit 兜底
                BigDecimal peakProfit = (peak != null && peak.maxProfit().signum() > 0)
                        ? peak.maxProfit() : profit;

                BigDecimal lockThreshold = atr.multiply(BigDecimal.valueOf(profile.trailLockAtr()));
                if (peakProfit.compareTo(lockThreshold) >= 0) {
                    BigDecimal slProfit = peakProfit.subtract(trailGap);
                    BigDecimal newSl = isLong ? entryPrice.add(slProfit) : entryPrice.subtract(slProfit);
                    if (shouldMoveSl(currentSl, newSl, isLong)) {
                        String r = tools.setStopLoss(pos.getId(), newSl, pos.getQuantity());
                        execLog.append("追踪止损(peak): ").append(r).append("\n");
                        double peakAtrMul = peakProfit.doubleValue() / atr.doubleValue();
                        reasons.append(String.format("峰值浮盈%.1fATR→SL=%s(peak-%.1fATR); ",
                                peakAtrMul, fmtPrice(newSl), profile.trailGapAtr()));
                        action = "TRAILING_STOP";
                    }
                } else {
                    BigDecimal breakevenThreshold = atr.multiply(BigDecimal.valueOf(profile.trailBreakevenAtr()));
                    if (peakProfit.compareTo(breakevenThreshold) >= 0) {
                        if (shouldMoveSl(currentSl, entryPrice, isLong)) {
                            String r = tools.setStopLoss(pos.getId(), entryPrice, pos.getQuantity());
                            log.info("[Executor] TRAIL_BREAKEVEN_MOVED posId={} entry={} new_sl={} peakAtr={} result={}",
                                    pos.getId(), entryPrice.toPlainString(), entryPrice.toPlainString(),
                                    String.format("%.2f", peakProfit.doubleValue() / atr.doubleValue()), r);
                            execLog.append("保本止损(peak): ").append(r).append("\n");
                            reasons.append(String.format("峰值浮盈%.1fATR→SL=成本价; ",
                                    peakProfit.doubleValue() / atr.doubleValue()));
                            action = "TRAILING_STOP";
                        }
                    }
                }
            }

            // ===== 9. 盯盘状态报告 =====
            if (reasons.isEmpty()) {
                String pnlStr = pos.getUnrealizedPnlPct() != null
                        ? pos.getUnrealizedPnlPct().setScale(2, RoundingMode.HALF_UP) + "%" : "N/A";
                String analysis = analyzePosition(ctx);
                reasons.append(pos.getSide()).append(" pnl=").append(pnlStr)
                        .append(" ").append(analysis).append("→持有; ");
            }
        }

        if (reasons.isEmpty()) reasons.append("无活跃持仓");
        return new ExecutionResult(action, reasons.toString().trim(), execLog.toString());
    }

    /** 分析当前市场状态（盯盘报告） */
    private static String analyzePosition(MarketContext ctx) {
        StringBuilder sb = new StringBuilder("[");
        if (ctx.maAlignment1h != null) sb.append("1h趋势=").append(ctx.maAlignment1h > 0 ? "多" : ctx.maAlignment1h < 0 ? "空" : "平");
        if (ctx.macdHistTrend5m != null) sb.append(" MACD=").append(ctx.macdHistTrend5m);
        if (ctx.volumeRatio5m != null) sb.append(String.format(" Vol=%.1fx", ctx.volumeRatio5m));
        if (ctx.bollPb5m != null) sb.append(String.format(" BB=%.0f%%", ctx.bollPb5m));
        sb.append("]");
        return sb.toString();
    }

    // ==================== 开仓评估（核心决策流程）====================

    private static ExecutionResult evaluateEntry(
            String symbol, User user, BigDecimal totalEquity,
            QuantForecastCycle forecast,
            List<QuantSignalDecision> signals,
            List<AiTradingDecision> recentDecisions,
            MarketContext ctx,
            SymbolProfile profile,
            TradingOperations tools,
            LocalDateTime forecastTime) {

        // ===== 0. 跨路径冷却：同 symbol 开仓后30min内不再开新仓，防 whipsaw =====
        long nowMs = System.currentTimeMillis();
        Long lastEntryMs = LAST_ENTRY_MS.get(symbol);
        if (lastEntryMs != null) {
            long remainingMs = ENTRY_COOLDOWN_MS - (nowMs - lastEntryMs);
            if (remainingMs > 0) {
                long remainingMinutes = (remainingMs + 59_999) / 60_000;
                log.info("[Executor] COOLDOWN_HOLD symbol={} remaining={} min", symbol, remainingMinutes);
                return hold("COOLDOWN_HOLD symbol=" + symbol + " remaining=" + remainingMinutes + " min");
            }
        }

        // ===== 1. 风控检查（智能冷却、连续亏损、日亏损）=====
        String riskCheck = checkRiskLimits(user, recentDecisions);
        if (riskCheck != null) return hold(riskCheck);

        // ===== 2. ATR必须可用 =====
        if (ctx.atr5m == null || ctx.atr5m.signum() <= 0) {
            return hold("ATR数据缺失→无法计算止损");
        }

        // ===== 2.5 数据新鲜度门：WS aggTrade 卡顿 >30s 即弃权 =====
        if (ctx.qualityFlags.contains("STALE_AGG_TRADE")) {
            log.info("[QualityFlag] STALE_AGG_TRADE detected symbol={}, abstain", symbol);
            return hold("STALE_AGG_TRADE: aggTrade 数据 >30s 未更新→弃权");
        }

        // ===== 3. 找信号 =====
        QuantSignalDecision bestSignal = findBestSignalWithPriority(signals, forecastTime);
        if (bestSignal == null || "NO_TRADE".equals(bestSignal.getDirection())) {
            return hold("无有效方向信号");
        }
        String side = bestSignal.getDirection();
        boolean isLong = "LONG".equals(side);
        double confidence = bestSignal.getConfidence() != null ? bestSignal.getConfidence().doubleValue() : 0;

        // overallDecision=FLAT → 不交易
        String overallDecision = forecast != null ? forecast.getOverallDecision() : "FLAT";
        if ("FLAT".equals(overallDecision)) {
            return hold("overallDecision=FLAT→观望");
        }

        // ===== 4. 高周期趋势过滤（最关键的门槛）=====
        // MR策略豁免条件：当BB%B在超买/超卖极值时，反趋势入场属于合理的均值回归setup，
        // 不应被1h趋势过滤拦截（否则MR做空分支几乎永远打不出）
        if (ctx.maAlignment1h != null && ctx.maAlignment1h != 0) {
            boolean trendConflict = (isLong && ctx.maAlignment1h < 0) || (!isLong && ctx.maAlignment1h > 0);
            boolean isMrSetup = ctx.bollPb5m != null &&
                    ((isLong && ctx.bollPb5m < REVERT_BB_PB_LONG_MAX)
                     || (!isLong && ctx.bollPb5m > REVERT_BB_PB_SHORT_MIN));
            if (trendConflict && !isMrSetup) {
                return hold(String.format("趋势过滤: 信号=%s但1h MA=%s→不做逆势交易",
                        side, ctx.maAlignment1h > 0 ? "多头排列" : "空头排列"));
            }
            if (trendConflict) {
                log.info("[Executor] 1h逆势但BB%B={}处于极值→允许MR反转入场", ctx.bollPb5m);
            }
        }

        // ===== 5. 策略选择（基于技术指标状态）=====
        String strategy = selectStrategy(ctx, isLong);

        // ===== 6. 信号共振评分（7维，LEGACY_TREND 支持5/7 shadow）=====
        int confluenceScore = calcConfluenceScore(ctx, isLong, signals);
        if (confluenceScore < MIN_CONFLUENCE_SCORE) {
            boolean legacyFiveOfSeven = PATH_LEGACY_TREND.equals(strategy) && confluenceScore == 5;
            if (legacyFiveOfSeven && LEGACY_THRESHOLD_5OF7_ENABLED) {
                log.info("[Executor] LEGACY_5OF7_LIVE symbol={} side={} conf={} confluence=5/7",
                        symbol, side, fmt(confidence));
            } else if (legacyFiveOfSeven && LEGACY_5OF7_SHADOW_ENABLED) {
                String reason = String.format("[Strategy-%s] 5/7 shadow only side=%s conf=%s regime=%s %s",
                        PATH_SHADOW_5OF7, side, fmt(confidence), ctx.regime,
                        describeConfluence(ctx, isLong));
                log.info("[Executor] LEGACY_5OF7_SHADOW symbol={} side={} conf={} confluence=5/7",
                        symbol, side, fmt(confidence));
                return new ExecutionResult("HOLD", reason, "");
            } else {
                return hold(String.format("共振不足: score=%d/%d(需>=%d, regime=%s) %s",
                        confluenceScore, 7, MIN_CONFLUENCE_SCORE, ctx.regime,
                        describeConfluence(ctx, isLong)));
            }
        }

        // ===== 7. SHOCK/SQUEEZE仓位缩减 =====
        double regimeScale = 1.0;
        if ("SHOCK".equals(ctx.regime)) {
            if (confidence < 0.60) {
                return hold("regime=SHOCK且信号弱(conf=" + fmt(confidence) + "<0.60)→观望");
            }
            regimeScale = 0.5;
        } else if ("SQUEEZE".equals(ctx.regime)) {
            regimeScale = PATH_BREAKOUT.equals(strategy) ? 0.8 : 0.6;
        }
        boolean lowConfidence = ctx.qualityFlags.contains("LOW_CONFIDENCE");
        if (lowConfidence) {
            regimeScale *= LOW_CONFIDENCE_POSITION_SCALE;
            log.info("[Executor] LOW_CONFIDENCE detected symbol={} strategy={} positionScale={}x",
                    symbol, strategy, LOW_CONFIDENCE_POSITION_SCALE);
        }

        // ===== 8. 执行策略 =====
        log.info("[Executor] 入场决策: {} strategy={} conf={} confluence={}/7 regime={} scale={}",
                side, strategy, fmt(confidence), confluenceScore, ctx.regime, regimeScale);

        ExecutionResult inner = switch (strategy) {
            case PATH_MR -> bbMeanReversion(symbol, user, totalEquity, bestSignal, ctx,
                    confidence, side, isLong, regimeScale, profile, tools);
            case PATH_BREAKOUT -> bbSqueezeBreakout(symbol, user, totalEquity, bestSignal, ctx,
                    confidence, side, isLong, regimeScale, profile, tools);
            default -> trendFollowing(symbol, user, totalEquity, bestSignal, ctx,
                    confidence, side, isLong, regimeScale, profile, tools);
        };
        if (inner.action().startsWith("OPEN_")) {
            LAST_ENTRY_MS.put(symbol, nowMs);
        }
        // A1-3: reasoning 前缀加策略路径标签，便于 trade_attribution 归因（v3 后 strategy 命名会统一）
        return new ExecutionResult(
                inner.action(),
                "[Strategy-" + strategy + "] " + inner.reasoning()
                        + (lowConfidence && inner.action().startsWith("OPEN_") ? " [LOW_CONFIDENCE仓位减半]" : ""),
                inner.executionLog());
    }

    // ==================== 策略A: EMA趋势跟踪 ====================

    private static ExecutionResult trendFollowing(
            String symbol, User user, BigDecimal totalEquity,
            QuantSignalDecision signal, MarketContext ctx,
            double confidence, String side, boolean isLong,
            double regimeScale, SymbolProfile profile, TradingOperations tools) {

        if (confidence < TREND_MIN_CONFIDENCE) {
            return hold("趋势策略: conf=" + fmt(confidence) + "<" + TREND_MIN_CONFIDENCE);
        }

        BigDecimal atr = ctx.atr5m;
        BigDecimal price = ctx.price;

        // SL / TP1（TP1锁定50%利润，剩余靠追踪止损让利润奔跑）
        BigDecimal slDistance = atr.multiply(BigDecimal.valueOf(profile.trendSlAtr()));
        BigDecimal tp1Distance = atr.multiply(BigDecimal.valueOf(profile.trendTpAtr()));

        // 低波动判定：扩SL到noise floor。扩张>3.0放弃；开关关闭且需扩张则HOLD
        SlTpAdjustment adj = adjustForNoiseFloor(slDistance, tp1Distance, price, profile);
        if (adj == null) {
            return hold(String.format("趋势策略: 极端低波动 ATR/price=%.3f%% 扩SL>%.1fx→放弃",
                    atr.doubleValue() / price.doubleValue() * 100, LOW_VOL_SL_EXPAND_MAX));
        }
        boolean isLowVol = adj.positionScale() < 1.0;
        if (isLowVol && !LOW_VOL_TRADING_ENABLED) {
            return hold("趋势策略: " + adj.note() + " → HOLD(低波动交易开关已关)");
        }
        slDistance = adj.slDistance();
        tp1Distance = adj.tpDistance();
        double effectiveScale = regimeScale * adj.positionScale();
        BigDecimal stopLoss = isLong ? price.subtract(slDistance) : price.add(slDistance);
        BigDecimal tp1 = isLong ? price.add(tp1Distance) : price.subtract(tp1Distance);

        // 手续费R:R检查
        String feeCheck = checkFeeAwareRR(price, atr, slDistance, tp1Distance);
        if (feeCheck != null) return hold("趋势策略: " + feeCheck);
        String rrGuard = checkRiskRewardGuard(symbol, PATH_LEGACY_TREND, slDistance, tp1Distance);
        if (rrGuard != null) return hold("趋势策略: " + rrGuard);

        int leverage = Math.min(
                signal.getMaxLeverage() != null ? signal.getMaxLeverage() : 10,
                TREND_MAX_LEVERAGE);
        BigDecimal quantity = calcQuantityByRisk(user, totalEquity, price, slDistance, leverage, effectiveScale);
        if (quantity == null) return hold("趋势策略: 仓位计算失败(余额不足或超限)");

        if (isInDrawdown(totalEquity)) {
            leverage = Math.max(5, (int)(leverage * DRAWDOWN_REDUCTION.doubleValue()));
            quantity = quantity.multiply(DRAWDOWN_REDUCTION).setScale(8, RoundingMode.HALF_DOWN);
        }

        double actualSlAtrMult = slDistance.doubleValue() / atr.doubleValue();
        double actualTpAtrMult = tp1Distance.doubleValue() / atr.doubleValue();
        String reason = String.format("趋势跟踪[%s] conf=%.2f regime=%s lev=%dx qty=%s SL=%s(%.1fATR) TP1=%s(%.1fATR)+追踪",
                side, confidence, ctx.regime, leverage,
                quantity.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                fmtPrice(stopLoss), actualSlAtrMult,
                fmtPrice(tp1), actualTpAtrMult);
        if (adj.positionScale() < 1.0) reason += " [" + adj.note() + "]";
        if (isInDrawdown(totalEquity)) reason += " [回撤保护]";

        // TP1锁定50%，不设TP2（靠managePositions的追踪止损管理剩余50%）
        String result = tools.openPosition(side, quantity, leverage, "MARKET", null, stopLoss, tp1, PATH_LEGACY_TREND);
        String action = result.startsWith("开仓成功") ? ("OPEN_" + side) : "HOLD";
        if (!result.startsWith("开仓成功")) reason += " | 开仓失败: " + result;
        return new ExecutionResult(action, reason, result);
    }

    // ==================== 策略B: BB均值回归 ====================

    private static ExecutionResult bbMeanReversion(
            String symbol, User user, BigDecimal totalEquity,
            QuantSignalDecision signal, MarketContext ctx,
            double confidence, String side, boolean isLong,
            double regimeScale, SymbolProfile profile, TradingOperations tools) {

        // BB %B 极值验证（硬性前提 — 必须在超买/超卖区）
        if (ctx.bollPb5m != null) {
            if (isLong && ctx.bollPb5m > REVERT_BB_PB_LONG_MAX) {
                return hold(String.format("BB回归: 做多但BB%%B=%.2f>%.2f→非超卖区", ctx.bollPb5m, REVERT_BB_PB_LONG_MAX));
            }
            if (!isLong && ctx.bollPb5m < REVERT_BB_PB_SHORT_MIN) {
                return hold(String.format("BB回归: 做空但BB%%B=%.2f<%.2f→非超买区", ctx.bollPb5m, REVERT_BB_PB_SHORT_MIN));
            }
        }

        // RSI 或 MACD柱反转至少满足一个（替代之前的全部AND要求）
        boolean rsiConfirms = false;
        boolean macdConfirms = false;

        if (ctx.rsi5m != null) {
            double rsi = ctx.rsi5m.doubleValue();
            rsiConfirms = (isLong && rsi < REVERT_RSI_LONG_MAX)
                       || (!isLong && rsi > REVERT_RSI_SHORT_MIN);
        }

        if (ctx.macdHistTrend5m != null) {
            String ht = ctx.macdHistTrend5m;
            macdConfirms = (isLong && (ht.startsWith("rising") || "mostly_up".equals(ht)))
                        || (!isLong && (ht.startsWith("falling") || "mostly_down".equals(ht)));
        }

        if (!rsiConfirms && !macdConfirms) {
            return hold(String.format("BB回归: RSI(%.1f)和MACD(%s)均未确认反转",
                    ctx.rsi5m != null ? ctx.rsi5m.doubleValue() : -1,
                    ctx.macdHistTrend5m != null ? ctx.macdHistTrend5m : "null"));
        }

        BigDecimal atr = ctx.atr5m;
        BigDecimal price = ctx.price;

        // 动态TP：基于BB中轨距离（核心创新 — 替代固定ATR倍数）
        double tpAtrMult = profile.revertTpMaxAtr();
        if (ctx.bollPb5m != null && ctx.bollBandwidth5m != null
                && ctx.bollBandwidth5m > 0 && atr.signum() > 0) {
            double distToMidPct = Math.abs(ctx.bollPb5m - 50.0) / 100.0; // bollPb是0-100，转换为0-1比例
            double bbWidthAbsolute = ctx.bollBandwidth5m / 100.0 * price.doubleValue(); // bandwidth是百分比，转换为绝对值
            double distToMidAbsolute = distToMidPct * bbWidthAbsolute;
            tpAtrMult = distToMidAbsolute / atr.doubleValue();
            tpAtrMult = Math.clamp(tpAtrMult, profile.revertTpMinAtr(), profile.revertTpMaxAtr());
        }

        BigDecimal slDistance = atr.multiply(BigDecimal.valueOf(profile.revertSlAtr()));
        BigDecimal tpDistance = atr.multiply(BigDecimal.valueOf(tpAtrMult));

        // 低波动判定：扩SL到noise floor。扩张>3.0放弃；开关关闭且需扩张则HOLD
        SlTpAdjustment adj = adjustForNoiseFloor(slDistance, tpDistance, price, profile);
        if (adj == null) {
            return hold(String.format("BB回归: 极端低波动 ATR/price=%.3f%% 扩SL>%.1fx→放弃",
                    atr.doubleValue() / price.doubleValue() * 100, LOW_VOL_SL_EXPAND_MAX));
        }
        boolean isLowVol = adj.positionScale() < 1.0;
        if (isLowVol && !LOW_VOL_TRADING_ENABLED) {
            return hold("BB回归: " + adj.note() + " → HOLD(低波动交易开关已关)");
        }
        slDistance = adj.slDistance();
        tpDistance = adj.tpDistance();
        double effectiveScale = REVERT_POSITION_SCALE * regimeScale * adj.positionScale();
        BigDecimal stopLoss = isLong ? price.subtract(slDistance) : price.add(slDistance);
        BigDecimal tp1 = isLong ? price.add(tpDistance) : price.subtract(tpDistance);

        // 手续费R:R检查
        String feeCheck = checkFeeAwareRR(price, atr, slDistance, tpDistance);
        if (feeCheck != null) return hold("BB回归: " + feeCheck);
        String rrGuard = checkRiskRewardGuard(symbol, PATH_MR, slDistance, tpDistance);
        if (rrGuard != null) return hold("BB回归: " + rrGuard);

        int leverage = Math.min(
                signal.getMaxLeverage() != null ? signal.getMaxLeverage() : 10,
                REVERT_MAX_LEVERAGE);
        BigDecimal quantity = calcQuantityByRisk(user, totalEquity, price, slDistance, leverage,
                effectiveScale);
        if (quantity == null) return hold("BB回归: 仓位计算失败");

        if (isInDrawdown(totalEquity)) {
            leverage = Math.max(5, (int)(leverage * DRAWDOWN_REDUCTION.doubleValue()));
            quantity = quantity.multiply(DRAWDOWN_REDUCTION).setScale(8, RoundingMode.HALF_DOWN);
        }

        double actualTpAtrMult = tpDistance.doubleValue() / atr.doubleValue();
        String reason = String.format(
                "BB均值回归[%s] conf=%.2f RSI=%.1f BB%%B=%.2f lev=%dx qty=%s SL=%s TP=%s(%.1fATR→BB中轨) 最长%dh",
                side, confidence, ctx.rsi5m != null ? ctx.rsi5m.doubleValue() : -1,
                ctx.bollPb5m != null ? ctx.bollPb5m : -1,
                leverage, quantity.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                fmtPrice(stopLoss), fmtPrice(tp1), actualTpAtrMult, REVERT_MAX_HOLD_HOURS);
        if (isLowVol) reason += " [" + adj.note() + "]";
        if (isInDrawdown(totalEquity)) reason += " [回撤保护]";

        String result = tools.openPosition(side, quantity, leverage, "MARKET", null, stopLoss, tp1, PATH_MR);
        String action = result.startsWith("开仓成功") ? ("OPEN_" + side) : "HOLD";
        if (!result.startsWith("开仓成功")) reason += " | 开仓失败: " + result;
        return new ExecutionResult(action, reason, result);
    }

    // ==================== 策略C: BB压缩突破 ====================

    private static ExecutionResult bbSqueezeBreakout(
            String symbol, User user, BigDecimal totalEquity,
            QuantSignalDecision signal, MarketContext ctx,
            double confidence, String side, boolean isLong,
            double regimeScale, SymbolProfile profile, TradingOperations tools) {

        if (!ctx.bollSqueeze) {
            return hold("突破策略: 非BB squeeze状态");
        }

        // 成交量必须放大（假突破最大特征 = 缩量突破）
        if (ctx.volumeRatio5m == null || ctx.volumeRatio5m < BREAKOUT_VOLUME_MIN) {
            return hold(String.format("突破策略: 成交量不足(%.1fx<%.1fx均量)→假突破风险",
                    ctx.volumeRatio5m != null ? ctx.volumeRatio5m : 0, BREAKOUT_VOLUME_MIN));
        }

        // BB%B确认价格已突破
        if (ctx.bollPb5m != null) {
            if (isLong && ctx.bollPb5m < 70.0) {
                return hold(String.format("突破策略: 做多但BB%%B=%.2f<70→未突破上轨", ctx.bollPb5m));
            }
            if (!isLong && ctx.bollPb5m > 30.0) {
                return hold(String.format("突破策略: 做空但BB%%B=%.2f>30→未突破下轨", ctx.bollPb5m));
            }
        }

        BigDecimal atr = ctx.atr5m;
        BigDecimal price = ctx.price;

        BigDecimal slDistance = atr.multiply(BigDecimal.valueOf(profile.breakoutSlAtr()));
        BigDecimal tpDistance = atr.multiply(BigDecimal.valueOf(profile.breakoutTpAtr()));

        // 低波动判定：扩SL到noise floor。扩张>3.0放弃；开关关闭且需扩张则HOLD
        SlTpAdjustment adj = adjustForNoiseFloor(slDistance, tpDistance, price, profile);
        if (adj == null) {
            return hold(String.format("突破策略: 极端低波动 ATR/price=%.3f%% 扩SL>%.1fx→放弃",
                    atr.doubleValue() / price.doubleValue() * 100, LOW_VOL_SL_EXPAND_MAX));
        }
        boolean isLowVol = adj.positionScale() < 1.0;
        if (isLowVol && !LOW_VOL_TRADING_ENABLED) {
            return hold("突破策略: " + adj.note() + " → HOLD(低波动交易开关已关)");
        }
        slDistance = adj.slDistance();
        tpDistance = adj.tpDistance();
        double effectiveScale = BREAKOUT_POSITION_SCALE * regimeScale * adj.positionScale();
        BigDecimal stopLoss = isLong ? price.subtract(slDistance) : price.add(slDistance);
        BigDecimal tp1 = isLong ? price.add(tpDistance) : price.subtract(tpDistance);

        String feeCheck = checkFeeAwareRR(price, atr, slDistance, tpDistance);
        if (feeCheck != null) return hold("突破策略: " + feeCheck);
        String rrGuard = checkRiskRewardGuard(symbol, PATH_BREAKOUT, slDistance, tpDistance);
        if (rrGuard != null) return hold("突破策略: " + rrGuard);

        int leverage = Math.min(
                signal.getMaxLeverage() != null ? signal.getMaxLeverage() : 10,
                BREAKOUT_MAX_LEVERAGE);
        BigDecimal quantity = calcQuantityByRisk(user, totalEquity, price, slDistance, leverage,
                effectiveScale);
        if (quantity == null) return hold("突破策略: 仓位计算失败");

        if (isInDrawdown(totalEquity)) {
            leverage = Math.max(5, (int)(leverage * DRAWDOWN_REDUCTION.doubleValue()));
            quantity = quantity.multiply(DRAWDOWN_REDUCTION).setScale(8, RoundingMode.HALF_DOWN);
        }

        double actualSlAtrMult = slDistance.doubleValue() / atr.doubleValue();
        double actualTpAtrMult = tpDistance.doubleValue() / atr.doubleValue();
        String reason = String.format(
                "BB压缩突破[%s] conf=%.2f BB%%B=%.2f vol=%.1fx lev=%dx qty=%s SL=%s(%.1fATR) TP=%s(%.1fATR)+追踪",
                side, confidence, ctx.bollPb5m != null ? ctx.bollPb5m : -1,
                ctx.volumeRatio5m,
                leverage, quantity.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                fmtPrice(stopLoss), actualSlAtrMult,
                fmtPrice(tp1), actualTpAtrMult);
        if (isLowVol) reason += " [" + adj.note() + "]";
        if (isInDrawdown(totalEquity)) reason += " [回撤保护]";

        String result = tools.openPosition(side, quantity, leverage, "MARKET", null, stopLoss, tp1, PATH_BREAKOUT);
        String action = result.startsWith("开仓成功") ? ("OPEN_" + side) : "HOLD";
        if (!result.startsWith("开仓成功")) reason += " | 开仓失败: " + result;
        return new ExecutionResult(action, reason, result);
    }

    // ==================== 信号共振评分系统 ====================

    /**
     * 7维共振评分。需>={@link #MIN_CONFLUENCE_SCORE}才允许入场。
     * 取代原来的"找最高confidence就交易"。
     */
    private static int calcConfluenceScore(MarketContext ctx, boolean isLong, List<QuantSignalDecision> signals) {
        int score = 0;

        // 1. MACD方向（优先看交叉事件，没有则看DIF vs DEA当前位置）
        if (ctx.macdCross5m != null) {
            if ((isLong && "golden".equals(ctx.macdCross5m))
                    || (!isLong && "death".equals(ctx.macdCross5m))) {
                score++;
            }
        } else if (ctx.macdDif5m != null && ctx.macdDea5m != null) {
            if ((isLong && ctx.macdDif5m.compareTo(ctx.macdDea5m) > 0)
                    || (!isLong && ctx.macdDif5m.compareTo(ctx.macdDea5m) < 0)) {
                score++;
            }
        }

        // 2. MACD柱状图趋势（适配calculator输出: rising_N/mostly_up/falling_N/mostly_down）
        if (ctx.macdHistTrend5m != null) {
            String ht = ctx.macdHistTrend5m;
            boolean histBullish = ht.startsWith("rising") || "mostly_up".equals(ht);
            boolean histBearish = ht.startsWith("falling") || "mostly_down".equals(ht);
            if ((isLong && histBullish) || (!isLong && histBearish)) {
                score++;
            }
        }

        // 3. RSI在安全区（趋势市：RSI同方向即得分，超买/超卖在趋势中是正常现象）
        if (ctx.rsi5m != null) {
            double rsi = ctx.rsi5m.doubleValue();
            boolean isTrend = "TREND_UP".equals(ctx.regime) || "TREND_DOWN".equals(ctx.regime);
            if (isTrend) {
                if ((isLong && rsi > 50) || (!isLong && rsi < 50)) score++;
            } else {
                if ((isLong && rsi < 70 && rsi > 20) || (!isLong && rsi > 30 && rsi < 80)) {
                    score++;
                }
            }
        }

        // 4. 成交量放大（门槛1.2，过滤缩量信号）
        if (ctx.volumeRatio5m != null && ctx.volumeRatio5m >= 1.2) {
            score++;
        }

        // 5. 15m均线方向一致
        if (ctx.maAlignment15m != null) {
            if ((isLong && ctx.maAlignment15m >= 0) || (!isLong && ctx.maAlignment15m <= 0)) {
                score++;
            }
        }

        // 6. 微结构支持（盘口偏向 + 主动买卖压力，阈值收紧到±0.15）
        double micro = 0;
        if (ctx.bidAskImbalance != null) micro += ctx.bidAskImbalance;
        if (ctx.takerPressure != null) micro += ctx.takerPressure;
        if ((isLong && micro > 0.15) || (!isLong && micro < -0.15)) {
            score++;
        }

        // 7. 价格在EMA20上方/下方（趋势确认，中期均线过滤）
        if (ctx.ema20 != null && ctx.price != null) {
            if ((isLong && ctx.price.compareTo(ctx.ema20) > 0)
                    || (!isLong && ctx.price.compareTo(ctx.ema20) < 0)) {
                score++;
            }
        }

        return score;
    }

    /** 描述共振评分细节（用于日志） */
    private static String describeConfluence(MarketContext ctx, boolean isLong) {
        StringBuilder sb = new StringBuilder("[");
        String macdState;
        if (ctx.macdCross5m != null) {
            macdState = ctx.macdCross5m;
        } else if (ctx.macdDif5m != null && ctx.macdDea5m != null) {
            macdState = ctx.macdDif5m.compareTo(ctx.macdDea5m) > 0 ? "above" : "below";
        } else {
            macdState = "无";
        }
        sb.append("MACD=").append(macdState);
        sb.append(" MACD柱=").append(ctx.macdHistTrend5m != null ? ctx.macdHistTrend5m : "无");
        sb.append(" RSI=").append(ctx.rsi5m != null ? fmt(ctx.rsi5m.doubleValue()) : "无");
        sb.append(" Vol=").append(ctx.volumeRatio5m != null ? String.format("%.1f", ctx.volumeRatio5m) : "无");
        sb.append(" MA15m=").append(ctx.maAlignment15m != null ? ctx.maAlignment15m : "无");
        double micro = 0;
        if (ctx.bidAskImbalance != null) micro += ctx.bidAskImbalance;
        if (ctx.takerPressure != null) micro += ctx.takerPressure;
        sb.append(String.format(" 微结构=%.2f", micro));
        sb.append(" EMA20=").append(ctx.ema20 != null ? ctx.ema20.setScale(2, RoundingMode.HALF_UP) : "无");
        sb.append("]");
        return sb.toString();
    }

    // ==================== 策略选择 ====================

    /**
     * 按当前技术形态选择执行策略，优先级：
     * BREAKOUT（BB压缩放量且方向已突破） > MR（BB极值回归） > LEGACY_TREND（默认趋势跟踪）。
     * <p>
     * BREAKOUT 路由必须方向一致：LONG 需 bollPb >= 70，SHORT 需 bollPb <= 30；
     * 否则继续走 MR/TREND 判定，避免 squeeze+放量把反向 setup 错路由到突破策略。
     */
    private static String selectStrategy(MarketContext ctx, boolean isLong) {
        // 1. BB压缩 + 放量 + 方向一致 → 突破策略
        if (ctx.bollSqueeze && ctx.volumeRatio5m != null && ctx.volumeRatio5m >= BREAKOUT_VOLUME_MIN
                && ctx.bollPb5m != null
                && ((isLong && ctx.bollPb5m >= 70.0) || (!isLong && ctx.bollPb5m <= 30.0))) {
            return PATH_BREAKOUT;
        }
        // 2. BB极值 → 均值回归（RSI/MACD确认在策略内部二选一检查）
        if (ctx.bollPb5m != null) {
            double pb = ctx.bollPb5m;
            boolean longSetup = pb < REVERT_BB_PB_LONG_MAX;
            boolean shortSetup = pb > REVERT_BB_PB_SHORT_MIN;
            if ((isLong && longSetup) || (!isLong && shortSetup)) {
                return PATH_MR;
            }
        }
        // 3. 默认趋势跟踪
        return PATH_LEGACY_TREND;
    }

    // ==================== 手续费感知的R:R检查 ====================

    /**
     * 确保TP覆盖往返手续费+最低盈利。
     * 往返手续费 = 名义价值 × 0.08%
     */
    private static String checkFeeAwareRR(BigDecimal price, BigDecimal atr,
                                           BigDecimal slDistance, BigDecimal tpDistance) {
        if (atr.signum() <= 0) return null;
        double feeAbsolute = price.doubleValue() * ROUND_TRIP_FEE_RATE;
        double feeInAtr = feeAbsolute / atr.doubleValue();
        double tpInAtr = tpDistance.doubleValue() / atr.doubleValue();
        double minTpRequired = feeInAtr + MIN_PROFIT_AFTER_FEE_ATR;

        if (tpInAtr < minTpRequired) {
            return String.format("R:R不划算: TP=%.1fATR < 手续费%.2fATR+最低利润%.1fATR=%.2fATR",
                    tpInAtr, feeInAtr, MIN_PROFIT_AFTER_FEE_ATR, minTpRequired);
        }
        return null;
    }

    private static String checkRiskRewardGuard(String symbol, String strategy,
                                               BigDecimal slDistance, BigDecimal tpDistance) {
        BigDecimal rr = tpDistance.divide(slDistance, 4, RoundingMode.HALF_UP);
        if (rr.compareTo(MIN_ENTRY_RR) >= 0) {
            return null;
        }
        long count = RR_GUARD_TRIGGERED_COUNT.incrementAndGet();
        log.info("[Executor] RR_GUARD_TRIGGERED symbol={} strategy={} rr={} slDistance={} tpDistance={} count={}",
                symbol, strategy, rr.toPlainString(), slDistance.toPlainString(), tpDistance.toPlainString(), count);
        return "RR_GUARD rr=" + rr.setScale(2, RoundingMode.HALF_UP).toPlainString()
                + " <" + MIN_ENTRY_RR.toPlainString() + " abstain";
    }

    // ==================== 低波动小仓位调整 ====================

    /**
     * 低波动期将SL撑到noise floor、TP同比例扩（保R:R）、仓位缩减到60%试探入场。
     * 返回null = 扩张倍数超限（极端横盘），上游应HOLD。
     * 正常波动 = positionScale=1.0 不调整。
     */
    private static SlTpAdjustment adjustForNoiseFloor(
            BigDecimal slRaw, BigDecimal tpRaw, BigDecimal price, SymbolProfile profile) {
        double rawSlDist = slRaw.doubleValue();
        double minSlDist = profile.slMinPct() * price.doubleValue();
        if (rawSlDist >= minSlDist) {
            return new SlTpAdjustment(slRaw, tpRaw, 1.0, "正常波动");
        }
        double expandRatio = minSlDist / rawSlDist;
        if (expandRatio > LOW_VOL_SL_EXPAND_MAX) {
            return null;
        }
        BigDecimal ratio = BigDecimal.valueOf(expandRatio);
        BigDecimal newSl = slRaw.multiply(ratio).setScale(8, RoundingMode.HALF_UP);
        BigDecimal newTp = tpRaw.multiply(ratio).setScale(8, RoundingMode.HALF_UP);
        return new SlTpAdjustment(newSl, newTp, LOW_VOL_POSITION_SCALE,
                String.format("低波动小仓位(扩%.2fx)", expandRatio));
    }

    // ==================== 信号选择（优先短周期）====================

    /**
     * 从未过期的 horizon 中按 confidence 最高选主信号。
     * 过期判定：forecastTime + horizon结束分钟 ≤ now 即视为过期（AI Trader当前时间段及之后的horizon才参与选优）。
     * 多 horizon 方向一致时叠加 confidence 加成。forecastTime=null 兼容回测（不过滤）。
     */
    private static QuantSignalDecision findBestSignalWithPriority(List<QuantSignalDecision> signals, LocalDateTime forecastTime) {
        if (signals == null || signals.isEmpty()) return null;

        // 过滤已过期的 horizon
        if (forecastTime != null) {
            LocalDateTime now = LocalDateTime.now();
            signals = signals.stream().filter(s -> {
                if (s.getHorizon() == null) return true;
                int endMin = switch (s.getHorizon()) {
                    case "0_10" -> 10;
                    case "10_20" -> 20;
                    case "20_30" -> 30;
                    default -> 30;
                };
                boolean valid = forecastTime.plusMinutes(endMin).isAfter(now);
                if (!valid) log.debug("[Executor] 跳过已过期horizon {} (forecast={} end={})",
                        s.getHorizon(), forecastTime, forecastTime.plusMinutes(endMin));
                return valid;
            }).toList();
            if (signals.isEmpty()) return null;
        }

        QuantSignalDecision sig010 = null, sig1020 = null, sig2030 = null;
        for (QuantSignalDecision s : signals) {
            if (s.getHorizon() == null) continue;
            switch (s.getHorizon()) {
                case "0_10" -> sig010 = s;
                case "10_20" -> sig1020 = s;
                case "20_30" -> sig2030 = s;
            }
        }

        QuantSignalDecision primary = null;
        for (QuantSignalDecision s : new QuantSignalDecision[]{sig010, sig1020, sig2030}) {
            QuantSignalDecision valid = pickValid(s);
            if (valid != null && (primary == null
                    || valid.getConfidence().compareTo(primary.getConfidence()) > 0)) {
                primary = valid;
            }
        }
        if (primary == null) return null;

        // 多horizon方向一致 → 将一致性转换为confidence加成（替代原先"只打日志"的虚假确认）
        List<QuantSignalDecision> all = new ArrayList<>();
        if (sig010 != null) all.add(sig010);
        if (sig1020 != null) all.add(sig1020);
        if (sig2030 != null) all.add(sig2030);

        QuantSignalDecision finalPrimary = primary;
        long agree = all.stream()
                .filter(s -> finalPrimary.getDirection().equals(s.getDirection()) && s.getConfidence() != null)
                .count();
        if (agree >= 2 && primary.getConfidence() != null) {
            // 3个一致 ×1.15, 2个一致 ×1.08, clamp [0, 1]
            double buff = agree >= 3 ? 1.15 : 1.08;
            double boosted = Math.min(1.0, primary.getConfidence().doubleValue() * buff);
            primary.setConfidence(BigDecimal.valueOf(boosted));
            log.info("[Executor] 多horizon一致({}个同方向{}), confidence×{}→{}",
                    agree, primary.getDirection(), buff, String.format("%.3f", boosted));
        }

        return primary;
    }

    private static QuantSignalDecision pickValid(QuantSignalDecision s) {
        if (s == null) return null;
        if (s.getDirection() == null || "NO_TRADE".equals(s.getDirection())) return null;
        if (s.getConfidence() == null) return null;
        return s;
    }

    /** 保留原始findBestSignal用于持仓管理的反转检测 */
    private static QuantSignalDecision findBestSignal(List<QuantSignalDecision> signals, LocalDateTime forecastTime) {
        if (signals == null || signals.isEmpty()) return null;
        if (forecastTime != null) {
            LocalDateTime now = LocalDateTime.now();
            signals = signals.stream().filter(s -> {
                if (s.getHorizon() == null) return true;
                int endMin = switch (s.getHorizon()) {
                    case "0_10" -> 10; case "10_20" -> 20; case "20_30" -> 30; default -> 30;
                };
                return forecastTime.plusMinutes(endMin).isAfter(now);
            }).toList();
            if (signals.isEmpty()) return null;
        }
        QuantSignalDecision best = null;
        for (QuantSignalDecision s : signals) {
            if (s.getDirection() == null || "NO_TRADE".equals(s.getDirection())) continue;
            if (s.getConfidence() == null) continue;
            if (best == null || s.getConfidence().compareTo(best.getConfidence()) > 0) {
                best = s;
            }
        }
        return best;
    }

    // ==================== 市场状态解析（丰富版）====================

    private static MarketContext parseMarketContext(QuantForecastCycle forecast, BigDecimal price) {
        String regime = "RANGE";
        String transition = null;
        BigDecimal atr5m = null, rsi5m = null;
        Integer maAlignment1h = null, maAlignment15m = null, maAlignment5m = null;
        String macdCross5m = null, macdHistTrend5m = null;
        BigDecimal macdDif5m = null, macdDea5m = null, ema20 = null;
        Double bollPb5m = null, bollBandwidth5m = null, volumeRatio5m = null;
        String closeTrend5m = null;
        Double rsi15m = null;
        Double bidAskImbalance = null, takerPressure = null, oiChangeRate = null;
        Double fundingDeviation = null;
        boolean bollSqueeze = false;
        List<String> qualityFlags = new ArrayList<>();

        if (forecast != null && forecast.getSnapshotJson() != null) {
            try {
                JSONObject snap = JSON.parseObject(forecast.getSnapshotJson());
                regime = snap.getString("regime");
                if (regime == null || regime.isBlank()) regime = "RANGE";
                transition = snap.getString("regimeTransition");
                atr5m = snap.getBigDecimal("atr5m");
                bollSqueeze = Boolean.TRUE.equals(snap.getBoolean("bollSqueeze"));

                // 微结构数据
                bidAskImbalance = snap.getDouble("bidAskImbalance");
                takerPressure = snap.getDouble("takerBuySellPressure");
                oiChangeRate = snap.getDouble("oiChangeRate");
                fundingDeviation = snap.getDouble("fundingDeviation");

                // 数据质量标记（STALE_AGG_TRADE 等）
                JSONArray flagsArr = snap.getJSONArray("qualityFlags");
                if (flagsArr != null) {
                    for (int i = 0; i < flagsArr.size(); i++) {
                        String flag = flagsArr.getString(i);
                        if (flag != null && !flag.isBlank()) qualityFlags.add(flag);
                    }
                }

                // 从indicatorsByTimeframe提取多周期技术指标
                JSONObject indicators = snap.getJSONObject("indicatorsByTimeframe");
                if (indicators != null) {
                    // ---- 5m指标 ----
                    JSONObject tf5m = indicators.getJSONObject("5m");
                    if (tf5m != null) {
                        rsi5m = tf5m.getBigDecimal("rsi14");
                        macdCross5m = tf5m.getString("macd_cross");
                        macdHistTrend5m = tf5m.getString("macd_hist_trend");
                        BigDecimal macdDif = tf5m.getBigDecimal("macd_dif");
                        BigDecimal macdDea = tf5m.getBigDecimal("macd_dea");
                        if (macdDif != null) macdDif5m = macdDif;
                        if (macdDea != null) macdDea5m = macdDea;
                        BigDecimal ema20Val = tf5m.getBigDecimal("ema20");
                        if (ema20Val != null) ema20 = ema20Val;
                        maAlignment5m = tf5m.getInteger("ma_alignment");
                        closeTrend5m = tf5m.getString("close_trend");
                        bollPb5m = tf5m.getDouble("boll_pb");
                        bollBandwidth5m = tf5m.getDouble("boll_bandwidth");
                        volumeRatio5m = tf5m.getDouble("volume_ratio");
                    }
                    // ---- 15m指标 ----
                    JSONObject tf15m = indicators.getJSONObject("15m");
                    if (tf15m != null) {
                        maAlignment15m = tf15m.getInteger("ma_alignment");
                        BigDecimal rsi15mBd = tf15m.getBigDecimal("rsi14");
                        if (rsi15mBd != null) rsi15m = rsi15mBd.doubleValue();
                    }
                    // ---- 1h指标（趋势过滤器的核心）----
                    JSONObject tf1h = indicators.getJSONObject("1h");
                    if (tf1h != null) {
                        maAlignment1h = tf1h.getInteger("ma_alignment");
                    }
                }
            } catch (Exception e) {
                log.warn("[Executor] snapshotJson解析失败: {}", e.getMessage());
            }
        }

        // ATR fallback
        if (atr5m == null || atr5m.signum() <= 0) {
            atr5m = price.multiply(new BigDecimal("0.003"));
            log.info("[Executor] ATR缺失，使用价格0.3%估算: {}", atr5m);
        }

        return new MarketContext(regime, transition, atr5m, rsi5m, price,
                maAlignment1h, maAlignment15m, maAlignment5m,
                macdCross5m, macdHistTrend5m, macdDif5m, macdDea5m, ema20,
                bollPb5m, bollBandwidth5m, bollSqueeze,
                volumeRatio5m, closeTrend5m, rsi15m,
                bidAskImbalance, takerPressure, oiChangeRate,
                fundingDeviation, qualityFlags);
    }

    // ==================== 仓位计算(风险预算法) ====================

    /**
     * 基于风险预算计算开仓数量。
     * 核心: SL被触发时最大亏损 = 净值 * 2%
     */
    private static BigDecimal calcQuantityByRisk(User user, BigDecimal totalEquity,
                                                  BigDecimal price,
                                                  BigDecimal slDistance, int leverage,
                                                  double scale) {
        BigDecimal equity = totalEquity != null ? totalEquity : user.getBalance().add(user.getFrozenBalance());
        BigDecimal maxLoss = equity.multiply(RISK_PER_TRADE).multiply(BigDecimal.valueOf(scale));

        if (slDistance.signum() <= 0) return null;

        BigDecimal quantity = maxLoss.divide(slDistance, 8, RoundingMode.HALF_DOWN);
        if (quantity.signum() <= 0) return null;

        BigDecimal margin = quantity.multiply(price)
                .divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        BigDecimal maxMargin = user.getBalance().multiply(MAX_MARGIN_PCT);
        if (margin.compareTo(maxMargin) > 0) {
            BigDecimal reducedQuantity = quantity.multiply(BigDecimal.valueOf(0.5))
                    .setScale(8, RoundingMode.HALF_DOWN);
            BigDecimal reducedMargin = reducedQuantity.multiply(price)
                    .divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
            long count = MARGIN_CAP_TRIGGERED_COUNT.incrementAndGet();
            if (reducedMargin.compareTo(maxMargin) > 0) {
                log.info("[Executor] MARGIN_CAP_TRIGGERED action=hold margin={} maxMargin={} reducedMargin={} price={} leverage={} count={}",
                        margin.toPlainString(), maxMargin.toPlainString(), reducedMargin.toPlainString(),
                        price.toPlainString(), leverage, count);
                return null;
            }
            log.info("[Executor] MARGIN_CAP_TRIGGERED action=reduce_half margin={} maxMargin={} reducedMargin={} price={} leverage={} count={}",
                    margin.toPlainString(), maxMargin.toPlainString(), reducedMargin.toPlainString(),
                    price.toPlainString(), leverage, count);
            quantity = reducedQuantity;
        }

        return quantity.signum() > 0 ? quantity : null;
    }

    // ==================== 风控检查（仅日亏损上限）====================

    private static String checkRiskLimits(User user, List<AiTradingDecision> recentDecisions) {
        if (recentDecisions == null || recentDecisions.isEmpty()) return null;

        // 日亏损上限
        BigDecimal dailyLoss = calcDailyLoss(recentDecisions);
        BigDecimal limit = user.getBalance().multiply(DAILY_LOSS_LIMIT_PCT);
        if (dailyLoss.compareTo(limit) >= 0) {
            return "日亏损=" + fmtPrice(dailyLoss) + ">=" + fmtPrice(limit);
        }

        return null;
    }

    private static boolean isInDrawdown(BigDecimal totalEquity) {
        if (totalEquity == null) return false;
        return totalEquity.compareTo(INITIAL_BALANCE.multiply(DRAWDOWN_THRESHOLD)) < 0;
    }

    // ==================== 工具方法 ====================

    private static ExecutionResult hold(String reason) {
        return new ExecutionResult("HOLD", reason, "");
    }

    private static BigDecimal getCurrentStopLossPrice(FuturesPositionDTO pos) {
        if (pos.getStopLosses() == null || pos.getStopLosses().isEmpty()) return null;
        return pos.getStopLosses().getFirst().getPrice();
    }

    private static boolean shouldMoveSl(BigDecimal currentSl, BigDecimal newSl, boolean isLong) {
        if (currentSl == null || currentSl.signum() <= 0) return true;
        return isLong ? newSl.compareTo(currentSl) > 0 : newSl.compareTo(currentSl) < 0;
    }

    private static Long timeStopLimitMinutes(String memo) {
        return switch (normalizeStrategyPath(memo)) {
            case PATH_BREAKOUT -> BREAKOUT_TIME_STOP_MINUTES;
            case PATH_MR -> REVERT_TIME_STOP_MINUTES;
            case PATH_LEGACY_TREND -> TREND_TIME_STOP_MINUTES;
            default -> null;
        };
    }

    private static boolean isMrPath(String memo) {
        return PATH_MR.equals(normalizeStrategyPath(memo));
    }

    private static String normalizeStrategyPath(String memo) {
        if (memo == null) return "";
        // 只兼容改名前已存在的OPEN仓位；新开仓只写 MR / LEGACY_TREND / BREAKOUT。
        if (LEGACY_PATH_MR.equals(memo)) return PATH_MR;
        if (LEGACY_PATH_TREND.equals(memo)) return PATH_LEGACY_TREND;
        return memo;
    }

    private static BigDecimal calcRiskDistance(
            FuturesPositionDTO pos, MarketContext ctx, SymbolProfile profile, boolean isLong) {
        BigDecimal entryPrice = pos.getEntryPrice();
        BigDecimal currentSl = getCurrentStopLossPrice(pos);
        if (currentSl != null && currentSl.signum() > 0) {
            boolean slStillRiskSide = isLong ? currentSl.compareTo(entryPrice) < 0 : currentSl.compareTo(entryPrice) > 0;
            if (slStillRiskSide) {
                return entryPrice.subtract(currentSl).abs();
            }
        }
        double slAtr = switch (normalizeStrategyPath(pos.getMemo())) {
            case PATH_BREAKOUT -> profile.breakoutSlAtr();
            case PATH_MR -> profile.revertSlAtr();
            default -> profile.trendSlAtr();
        };
        return ctx.atr5m.multiply(BigDecimal.valueOf(slAtr));
    }

    private static BigDecimal calcDailyLoss(List<AiTradingDecision> decisions) {
        if (decisions == null) return BigDecimal.ZERO;
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        BigDecimal loss = BigDecimal.ZERO;
        for (AiTradingDecision d : decisions) {
            if (d.getCreatedAt() == null || d.getCreatedAt().isBefore(todayStart)) break;
            if ("HOLD".equals(d.getAction()) || "TRAILING_STOP".equals(d.getAction())) continue;
            if (d.getBalanceBefore() != null && d.getBalanceAfter() != null) {
                BigDecimal change = d.getBalanceAfter().subtract(d.getBalanceBefore());
                if (change.signum() < 0) loss = loss.add(change.abs());
            }
        }
        return loss;
    }

    private static String fmtPrice(BigDecimal p) {
        if (p == null) return "-";
        return p.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
