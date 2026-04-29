package com.mawai.wiibservice.agent.trading;

import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import com.mawai.wiibcommon.entity.User;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.PATH_BREAKOUT;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.PATH_LEGACY_TREND;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.PATH_MR;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.PATH_SHADOW_5OF7;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.currentDateTime;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.currentTimeMillis;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.findBestSignalWithPriority;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.fmt;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.fmtPrice;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.hold;

/**
 * 开仓判断引擎。
 * 规则从原 DeterministicTradingExecutor 等价迁移，先不改变模拟盘已验证的入场能力。
 */
@Slf4j
final class EntryDecisionEngine {

    // 同 symbol 开仓后的最短等待时间，避免短时间重复追单。
    private static final long ENTRY_COOLDOWN_MS = 10 * 60 * 1000L;

    // 单次开平仓往返手续费估算比例，用于过滤扣费后不划算的入场。
    private static final double ROUND_TRIP_FEE_RATE = 0.0008;
    // 扣除手续费后，TP 至少还要留下的 ATR 利润空间。
    private static final double MIN_PROFIT_AFTER_FEE_ATR = 0.5;

    // 单笔交易允许承担的账户权益风险比例。
    private static final BigDecimal RISK_PER_TRADE = new BigDecimal("0.02");
    // 开仓最低盈亏比要求，低于该值直接放弃。
    private static final BigDecimal MIN_ENTRY_RR = new BigDecimal("1.2");
    // RR 保护被触发的累计次数，仅用于日志观测。
    private static final AtomicLong RR_GUARD_TRIGGERED_COUNT = new AtomicLong();
    // 保证金上限保护被触发的累计次数，仅用于日志观测。
    private static final AtomicLong MARGIN_CAP_TRIGGERED_COUNT = new AtomicLong();
    // 单笔仓位最多占用可用余额的保证金比例。
    private static final BigDecimal MAX_MARGIN_PCT = new BigDecimal("0.15");
    // 账户权益低于初始资金该比例时，认为进入回撤保护状态。
    private static final BigDecimal DRAWDOWN_THRESHOLD = new BigDecimal("0.85");
    // 回撤保护状态下，对杠杆和数量统一打折的比例。
    private static final BigDecimal DRAWDOWN_REDUCTION = new BigDecimal("0.7");
    // 数据质量低置信时的仓位缩放比例。
    private static final double LOW_CONFIDENCE_POSITION_SCALE = 0.5;

    // 共振评分检查的维度总数。
    private static final int CONFLUENCE_DIMENSIONS = 7;
    // 允许开仓的最低共振得分。
    private static final int MIN_CONFLUENCE_SCORE = 6;

    // 趋势兜底策略的最低信号置信度。
    private static final double TREND_MIN_CONFIDENCE = 0.35;
    // 趋势兜底策略允许使用的最大杠杆。
    private static final int TREND_MAX_LEVERAGE = 25;

    // 均值回归策略允许使用的最大杠杆。
    private static final int REVERT_MAX_LEVERAGE = 20;
    // 均值回归策略的基础仓位缩放比例。
    private static final double REVERT_POSITION_SCALE = 0.6;
    // 做多均值回归时，BB%B 必须低于该值才算超卖。
    private static final double REVERT_BB_PB_LONG_MAX = 10.0;
    // 做空均值回归时，BB%B 必须高于该值才算超买。
    private static final double REVERT_BB_PB_SHORT_MIN = 90.0;
    // 做多均值回归时，RSI 低于该值才确认反转环境。
    private static final double REVERT_RSI_LONG_MAX = 35.0;
    // 做空均值回归时，RSI 高于该值才确认反转环境。
    private static final double REVERT_RSI_SHORT_MIN = 65.0;

    // 突破策略允许使用的最大杠杆。
    private static final int BREAKOUT_MAX_LEVERAGE = 20;
    // 突破策略要求的最低成交量放大倍数。
    private static final double BREAKOUT_VOLUME_MIN = 1.3;
    // 突破策略的基础仓位缩放比例。
    private static final double BREAKOUT_POSITION_SCALE = 0.8;

    // 低波动环境下，止损距离最多允许扩大的倍数。
    private static final double LOW_VOL_SL_EXPAND_MAX = 3.0;
    // 低波动但仍允许交易时的仓位缩放比例。
    private static final double LOW_VOL_POSITION_SCALE = 0.6;

    // SL/TP 噪音地板修正结果，携带修正后距离、仓位缩放和说明。
    private record SlTpAdjustment(BigDecimal slDistance, BigDecimal tpDistance,
                                  double positionScale, String note) {
    }

    DeterministicTradingExecutor.ExecutionResult evaluate(TradingDecisionContext decision) {
        String symbol = decision.symbol();
        User user = decision.user();
        BigDecimal totalEquity = decision.totalEquity();
        QuantForecastCycle forecast = decision.forecast();
        List<QuantSignalDecision> signals = decision.signals();
        MarketContext ctx = decision.market();
        SymbolProfile profile = decision.profile();
        TradingOperations tools = decision.tools();
        TradingExecutionState state = decision.state();
        TradingRuntimeToggles toggles = decision.toggles();
        LocalDateTime forecastTime = decision.forecastTime();

        long nowMs = currentTimeMillis(state);
        LocalDateTime now = currentDateTime(nowMs);
        Long lastEntryMs = state.getLastEntryMs(symbol);
        if (ENTRY_COOLDOWN_MS > 0 && lastEntryMs != null) {
            long remainingMs = ENTRY_COOLDOWN_MS - (nowMs - lastEntryMs);
            if (remainingMs > 0) {
                long remainingMinutes = (remainingMs + 59_999) / 60_000;
                log.info("[EntryDecision] COOLDOWN_HOLD symbol={} remaining={} min", symbol, remainingMinutes);
                return hold("COOLDOWN_HOLD symbol=" + symbol + " remaining=" + remainingMinutes + " min");
            }
        }

        if (ctx.atr5m == null || ctx.atr5m.signum() <= 0) {
            return hold("ATR数据缺失→无法计算止损");
        }

        if (ctx.qualityFlags.contains("STALE_AGG_TRADE")) {
            log.info("[QualityFlag] STALE_AGG_TRADE detected symbol={}, abstain", symbol);
            return hold("STALE_AGG_TRADE: aggTrade 数据 >30s 未更新→弃权");
        }

        QuantSignalDecision bestSignal = findBestSignalWithPriority(signals, forecastTime, now);
        if (bestSignal == null || "NO_TRADE".equals(bestSignal.getDirection())) {
            return hold("无有效方向信号");
        }
        String side = bestSignal.getDirection();
        boolean isLong = "LONG".equals(side);
        double confidence = bestSignal.getConfidence() != null ? bestSignal.getConfidence().doubleValue() : 0;

        String overallDecision = forecast != null ? forecast.getOverallDecision() : null;
        boolean overallFlat = "FLAT".equals(overallDecision);

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
                log.info("[EntryDecision] 1h逆势但BB%B={}处于极值→允许MR反转入场", ctx.bollPb5m);
            }
        }

        String strategy = selectStrategy(ctx, isLong);

        int confluenceScore = calcConfluenceScore(ctx, isLong);
        if (confluenceScore < MIN_CONFLUENCE_SCORE) {
            boolean fiveOfSeven = confluenceScore == 5;
            if (fiveOfSeven && toggles.legacyThreshold5of7Enabled()) {
                log.info("[EntryDecision] 5OF7_LIVE symbol={} strategy={} side={} conf={} confluence=5/7",
                        symbol, strategy, side, fmt(confidence));
            } else if (fiveOfSeven && toggles.legacy5of7ShadowEnabled()) {
                String reason = String.format("[Strategy-%s] 5/7 shadow only side=%s conf=%s regime=%s %s",
                        PATH_SHADOW_5OF7, side, fmt(confidence), ctx.regime,
                        describeConfluence(ctx, isLong));
                log.info("[EntryDecision] 5OF7_SHADOW symbol={} strategy={} side={} conf={} confluence=5/7",
                        symbol, strategy, side, fmt(confidence));
                return new DeterministicTradingExecutor.ExecutionResult("HOLD", reason, "");
            } else {
                return hold(String.format("共振不足: score=%d/%d(需>=%d, regime=%s) %s",
                        confluenceScore, CONFLUENCE_DIMENSIONS, MIN_CONFLUENCE_SCORE, ctx.regime,
                        describeConfluence(ctx, isLong)));
            }
        }

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
            log.info("[EntryDecision] LOW_CONFIDENCE detected symbol={} strategy={} positionScale={}x",
                    symbol, strategy, LOW_CONFIDENCE_POSITION_SCALE);
        }

        log.info("[EntryDecision] 入场决策: {} strategy={} conf={} confluence={}/{} regime={} scale={}",
                side, strategy, fmt(confidence), confluenceScore, CONFLUENCE_DIMENSIONS, ctx.regime, regimeScale);

        DeterministicTradingExecutor.ExecutionResult inner = switch (strategy) {
            case PATH_MR -> bbMeanReversion(symbol, user, totalEquity, bestSignal, ctx,
                    confidence, side, isLong, regimeScale, profile, tools, toggles);
            case PATH_BREAKOUT -> bbSqueezeBreakout(symbol, user, totalEquity, bestSignal, ctx,
                    confidence, side, isLong, regimeScale, profile, tools, toggles);
            default -> trendFollowing(symbol, user, totalEquity, bestSignal, ctx,
                    confidence, side, isLong, regimeScale, profile, tools, toggles);
        };
        if (inner.action().startsWith("OPEN_")) {
            state.markEntry(symbol, nowMs);
        }
        return new DeterministicTradingExecutor.ExecutionResult(
                inner.action(),
                "[Strategy-" + strategy + "] " + inner.reasoning()
                        + (overallFlat ? " [overallDecision=FLAT仅记录]" : "")
                        + (lowConfidence && inner.action().startsWith("OPEN_") ? " [LOW_CONFIDENCE仓位减半]" : ""),
                inner.executionLog());
    }

    private DeterministicTradingExecutor.ExecutionResult trendFollowing(
            String symbol, User user, BigDecimal totalEquity,
            QuantSignalDecision signal, MarketContext ctx,
            double confidence, String side, boolean isLong,
            double regimeScale, SymbolProfile profile, TradingOperations tools,
            TradingRuntimeToggles toggles) {

        if (confidence < TREND_MIN_CONFIDENCE) {
            return hold("趋势策略: conf=" + fmt(confidence) + "<" + TREND_MIN_CONFIDENCE);
        }

        BigDecimal atr = ctx.atr5m;
        BigDecimal price = ctx.price;
        BigDecimal slDistance = atr.multiply(BigDecimal.valueOf(profile.trendSlAtr()));
        BigDecimal tpDistance = atr.multiply(BigDecimal.valueOf(profile.trendTpAtr()));

        SlTpAdjustment adj = adjustForNoiseFloor(slDistance, tpDistance, price, profile);
        if (adj == null) {
            return hold(String.format("趋势策略: 极端低波动 ATR/price=%.3f%% 扩SL>%.1fx→放弃",
                    atr.doubleValue() / price.doubleValue() * 100, LOW_VOL_SL_EXPAND_MAX));
        }
        boolean isLowVol = adj.positionScale() < 1.0;
        if (isLowVol && !toggles.lowVolTradingEnabled()) {
            return hold("趋势策略: " + adj.note() + " → HOLD(低波动交易被管理端关闭)");
        }
        slDistance = adj.slDistance();
        tpDistance = adj.tpDistance();
        double effectiveScale = regimeScale * adj.positionScale();
        BigDecimal stopLoss = isLong ? price.subtract(slDistance) : price.add(slDistance);
        BigDecimal tp = isLong ? price.add(tpDistance) : price.subtract(tpDistance);

        String feeCheck = checkFeeAwareRR(price, atr, slDistance, tpDistance);
        if (feeCheck != null) return hold("趋势策略: " + feeCheck);
        String rrGuard = checkRiskRewardGuard(symbol, PATH_LEGACY_TREND, slDistance, tpDistance);
        if (rrGuard != null) return hold("趋势策略: " + rrGuard);

        int leverage = Math.min(
                signal.getMaxLeverage() != null ? signal.getMaxLeverage() : 10,
                TREND_MAX_LEVERAGE);
        BigDecimal quantity = calcQuantityByRisk(user, totalEquity, price, slDistance, leverage, effectiveScale);
        if (quantity == null) return hold("趋势策略: 仓位计算失败(余额不足或超限)");

        if (isInDrawdown(totalEquity)) {
            leverage = Math.max(5, (int) (leverage * DRAWDOWN_REDUCTION.doubleValue()));
            quantity = quantity.multiply(DRAWDOWN_REDUCTION).setScale(8, RoundingMode.HALF_DOWN);
        }

        double actualSlAtrMult = slDistance.doubleValue() / atr.doubleValue();
        double actualTpAtrMult = tpDistance.doubleValue() / atr.doubleValue();
        String reason = String.format("趋势跟踪[%s] conf=%.2f regime=%s lev=%dx qty=%s SL=%s(%.1fATR) TP=%s(%.1fATR)",
                side, confidence, ctx.regime, leverage,
                quantity.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                fmtPrice(stopLoss), actualSlAtrMult,
                fmtPrice(tp), actualTpAtrMult);
        if (adj.positionScale() < 1.0) reason += " [" + adj.note() + "]";
        if (isInDrawdown(totalEquity)) reason += " [回撤保护]";

        String result = tools.openPosition(side, quantity, leverage, "MARKET", null, stopLoss, tp, PATH_LEGACY_TREND);
        String action = result.startsWith("开仓成功") ? ("OPEN_" + side) : "HOLD";
        if (!result.startsWith("开仓成功")) reason += " | 开仓失败: " + result;
        return new DeterministicTradingExecutor.ExecutionResult(action, reason, result);
    }

    private DeterministicTradingExecutor.ExecutionResult bbMeanReversion(
            String symbol, User user, BigDecimal totalEquity,
            QuantSignalDecision signal, MarketContext ctx,
            double confidence, String side, boolean isLong,
            double regimeScale, SymbolProfile profile, TradingOperations tools,
            TradingRuntimeToggles toggles) {

        if (ctx.bollPb5m != null) {
            if (isLong && ctx.bollPb5m > REVERT_BB_PB_LONG_MAX) {
                return hold(String.format("BB回归: 做多但BB%%B=%.2f>%.2f→非超卖区", ctx.bollPb5m, REVERT_BB_PB_LONG_MAX));
            }
            if (!isLong && ctx.bollPb5m < REVERT_BB_PB_SHORT_MIN) {
                return hold(String.format("BB回归: 做空但BB%%B=%.2f<%.2f→非超买区", ctx.bollPb5m, REVERT_BB_PB_SHORT_MIN));
            }
        }

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

        double tpAtrMult = profile.revertTpMaxAtr();
        if (ctx.bollPb5m != null && ctx.bollBandwidth5m != null
                && ctx.bollBandwidth5m > 0 && atr.signum() > 0) {
            double distToMidPct = Math.abs(ctx.bollPb5m - 50.0) / 100.0;
            double bbWidthAbsolute = ctx.bollBandwidth5m / 100.0 * price.doubleValue();
            double distToMidAbsolute = distToMidPct * bbWidthAbsolute;
            tpAtrMult = distToMidAbsolute / atr.doubleValue();
            tpAtrMult = Math.clamp(tpAtrMult, profile.revertTpMinAtr(), profile.revertTpMaxAtr());
        }

        BigDecimal slDistance = atr.multiply(BigDecimal.valueOf(profile.revertSlAtr()));
        BigDecimal tpDistance = atr.multiply(BigDecimal.valueOf(tpAtrMult));

        SlTpAdjustment adj = adjustForNoiseFloor(slDistance, tpDistance, price, profile);
        if (adj == null) {
            return hold(String.format("BB回归: 极端低波动 ATR/price=%.3f%% 扩SL>%.1fx→放弃",
                    atr.doubleValue() / price.doubleValue() * 100, LOW_VOL_SL_EXPAND_MAX));
        }
        boolean isLowVol = adj.positionScale() < 1.0;
        if (isLowVol && !toggles.lowVolTradingEnabled()) {
            return hold("BB回归: " + adj.note() + " → HOLD(低波动交易开关已关)");
        }
        slDistance = adj.slDistance();
        tpDistance = adj.tpDistance();
        double effectiveScale = REVERT_POSITION_SCALE * regimeScale * adj.positionScale();
        BigDecimal stopLoss = isLong ? price.subtract(slDistance) : price.add(slDistance);
        BigDecimal tp = isLong ? price.add(tpDistance) : price.subtract(tpDistance);

        String feeCheck = checkFeeAwareRR(price, atr, slDistance, tpDistance);
        if (feeCheck != null) return hold("BB回归: " + feeCheck);
        String rrGuard = checkRiskRewardGuard(symbol, PATH_MR, slDistance, tpDistance);
        if (rrGuard != null) return hold("BB回归: " + rrGuard);

        int leverage = Math.min(
                signal.getMaxLeverage() != null ? signal.getMaxLeverage() : 10,
                REVERT_MAX_LEVERAGE);
        BigDecimal quantity = calcQuantityByRisk(user, totalEquity, price, slDistance, leverage, effectiveScale);
        if (quantity == null) return hold("BB回归: 仓位计算失败");

        if (isInDrawdown(totalEquity)) {
            leverage = Math.max(5, (int) (leverage * DRAWDOWN_REDUCTION.doubleValue()));
            quantity = quantity.multiply(DRAWDOWN_REDUCTION).setScale(8, RoundingMode.HALF_DOWN);
        }

        double actualTpAtrMult = tpDistance.doubleValue() / atr.doubleValue();
        String reason = String.format(
                "BB均值回归[%s] conf=%.2f RSI=%.1f BB%%B=%.2f lev=%dx qty=%s SL=%s TP=%s(%.1fATR)",
                side, confidence, ctx.rsi5m != null ? ctx.rsi5m.doubleValue() : -1,
                ctx.bollPb5m != null ? ctx.bollPb5m : -1,
                leverage, quantity.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                fmtPrice(stopLoss), fmtPrice(tp), actualTpAtrMult);
        if (isLowVol) reason += " [" + adj.note() + "]";
        if (isInDrawdown(totalEquity)) reason += " [回撤保护]";

        String result = tools.openPosition(side, quantity, leverage, "MARKET", null, stopLoss, tp, PATH_MR);
        String action = result.startsWith("开仓成功") ? ("OPEN_" + side) : "HOLD";
        if (!result.startsWith("开仓成功")) reason += " | 开仓失败: " + result;
        return new DeterministicTradingExecutor.ExecutionResult(action, reason, result);
    }

    private DeterministicTradingExecutor.ExecutionResult bbSqueezeBreakout(
            String symbol, User user, BigDecimal totalEquity,
            QuantSignalDecision signal, MarketContext ctx,
            double confidence, String side, boolean isLong,
            double regimeScale, SymbolProfile profile, TradingOperations tools,
            TradingRuntimeToggles toggles) {

        if (!ctx.bollSqueeze) {
            return hold("突破策略: 非BB squeeze状态");
        }

        if (ctx.volumeRatio5m == null || ctx.volumeRatio5m < BREAKOUT_VOLUME_MIN) {
            return hold(String.format("突破策略: 成交量不足(%.1fx<%.1fx均量)→假突破风险",
                    ctx.volumeRatio5m != null ? ctx.volumeRatio5m : 0, BREAKOUT_VOLUME_MIN));
        }

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

        SlTpAdjustment adj = adjustForNoiseFloor(slDistance, tpDistance, price, profile);
        if (adj == null) {
            return hold(String.format("突破策略: 极端低波动 ATR/price=%.3f%% 扩SL>%.1fx→放弃",
                    atr.doubleValue() / price.doubleValue() * 100, LOW_VOL_SL_EXPAND_MAX));
        }
        boolean isLowVol = adj.positionScale() < 1.0;
        if (isLowVol && !toggles.lowVolTradingEnabled()) {
            return hold("突破策略: " + adj.note() + " → HOLD(低波动交易开关已关)");
        }
        slDistance = adj.slDistance();
        tpDistance = adj.tpDistance();
        double effectiveScale = BREAKOUT_POSITION_SCALE * regimeScale * adj.positionScale();
        BigDecimal stopLoss = isLong ? price.subtract(slDistance) : price.add(slDistance);
        BigDecimal tp = isLong ? price.add(tpDistance) : price.subtract(tpDistance);

        String feeCheck = checkFeeAwareRR(price, atr, slDistance, tpDistance);
        if (feeCheck != null) return hold("突破策略: " + feeCheck);
        String rrGuard = checkRiskRewardGuard(symbol, PATH_BREAKOUT, slDistance, tpDistance);
        if (rrGuard != null) return hold("突破策略: " + rrGuard);

        int leverage = Math.min(
                signal.getMaxLeverage() != null ? signal.getMaxLeverage() : 10,
                BREAKOUT_MAX_LEVERAGE);
        BigDecimal quantity = calcQuantityByRisk(user, totalEquity, price, slDistance, leverage, effectiveScale);
        if (quantity == null) return hold("突破策略: 仓位计算失败");

        if (isInDrawdown(totalEquity)) {
            leverage = Math.max(5, (int) (leverage * DRAWDOWN_REDUCTION.doubleValue()));
            quantity = quantity.multiply(DRAWDOWN_REDUCTION).setScale(8, RoundingMode.HALF_DOWN);
        }

        double actualSlAtrMult = slDistance.doubleValue() / atr.doubleValue();
        double actualTpAtrMult = tpDistance.doubleValue() / atr.doubleValue();
        String reason = String.format(
                "BB压缩突破[%s] conf=%.2f BB%%B=%.2f vol=%.1fx lev=%dx qty=%s SL=%s(%.1fATR) TP=%s(%.1fATR)",
                side, confidence, ctx.bollPb5m != null ? ctx.bollPb5m : -1,
                ctx.volumeRatio5m,
                leverage, quantity.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                fmtPrice(stopLoss), actualSlAtrMult,
                fmtPrice(tp), actualTpAtrMult);
        if (isLowVol) reason += " [" + adj.note() + "]";
        if (isInDrawdown(totalEquity)) reason += " [回撤保护]";

        String result = tools.openPosition(side, quantity, leverage, "MARKET", null, stopLoss, tp, PATH_BREAKOUT);
        String action = result.startsWith("开仓成功") ? ("OPEN_" + side) : "HOLD";
        if (!result.startsWith("开仓成功")) reason += " | 开仓失败: " + result;
        return new DeterministicTradingExecutor.ExecutionResult(action, reason, result);
    }

    private int calcConfluenceScore(MarketContext ctx, boolean isLong) {
        int score = 0;

        if (macdSupports(ctx, isLong)) score++;

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

        if (ctx.volumeRatio5m != null && ctx.volumeRatio5m >= 1.2) score++;

        if (ctx.maAlignment15m != null) {
            if ((isLong && ctx.maAlignment15m >= 0) || (!isLong && ctx.maAlignment15m <= 0)) score++;
        }

        double micro = 0;
        if (ctx.bidAskImbalance != null) micro += ctx.bidAskImbalance;
        if (ctx.takerPressure != null) micro += ctx.takerPressure;
        if ((isLong && micro > 0.15) || (!isLong && micro < -0.15)) score++;

        if (ctx.ema20 != null && ctx.price != null) {
            if ((isLong && ctx.price.compareTo(ctx.ema20) > 0)
                    || (!isLong && ctx.price.compareTo(ctx.ema20) < 0)) {
                score++;
            }
        }

        if (ctx.lsrExtreme != null) {
            if ((isLong && ctx.lsrExtreme <= 0.2) || (!isLong && ctx.lsrExtreme >= -0.2)) score++;
        }

        return score;
    }

    private boolean macdSupports(MarketContext ctx, boolean isLong) {
        if ("golden".equals(ctx.macdCross5m)) return isLong;
        if ("death".equals(ctx.macdCross5m)) return !isLong;

        boolean histBullish = isMacdHistBullish(ctx.macdHistTrend5m);
        boolean histBearish = isMacdHistBearish(ctx.macdHistTrend5m);

        if (ctx.macdDif5m != null && ctx.macdDea5m != null) {
            int cmp = ctx.macdDif5m.compareTo(ctx.macdDea5m);
            if (isLong && cmp > 0) return !histBearish;
            if (!isLong && cmp < 0) return !histBullish;
        }

        return isLong ? histBullish : histBearish;
    }

    private boolean isMacdHistBullish(String trend) {
        return trend != null && (trend.startsWith("rising") || "mostly_up".equals(trend));
    }

    private boolean isMacdHistBearish(String trend) {
        return trend != null && (trend.startsWith("falling") || "mostly_down".equals(trend));
    }

    private String describeConfluence(MarketContext ctx, boolean isLong) {
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
        sb.append(" MACD确认=").append(macdSupports(ctx, isLong) ? "Y" : "N");
        sb.append(" RSI=").append(ctx.rsi5m != null ? fmt(ctx.rsi5m.doubleValue()) : "无");
        sb.append(" Vol=").append(ctx.volumeRatio5m != null ? String.format("%.1f", ctx.volumeRatio5m) : "无");
        sb.append(" MA15m=").append(ctx.maAlignment15m != null ? ctx.maAlignment15m : "无");
        double micro = 0;
        if (ctx.bidAskImbalance != null) micro += ctx.bidAskImbalance;
        if (ctx.takerPressure != null) micro += ctx.takerPressure;
        sb.append(String.format(" 微结构=%.2f", micro));
        sb.append(" EMA20=").append(ctx.ema20 != null ? ctx.ema20.setScale(2, RoundingMode.HALF_UP) : "无");
        sb.append(" LSR=").append(ctx.lsrExtreme != null ? String.format("%.2f", ctx.lsrExtreme) : "无");
        sb.append("]");
        return sb.toString();
    }

    private String selectStrategy(MarketContext ctx, boolean isLong) {
        if (ctx.bollSqueeze && ctx.volumeRatio5m != null && ctx.volumeRatio5m >= BREAKOUT_VOLUME_MIN
                && ctx.bollPb5m != null
                && ((isLong && ctx.bollPb5m >= 70.0) || (!isLong && ctx.bollPb5m <= 30.0))) {
            return PATH_BREAKOUT;
        }
        if (ctx.bollPb5m != null) {
            double pb = ctx.bollPb5m;
            boolean longSetup = pb < REVERT_BB_PB_LONG_MAX;
            boolean shortSetup = pb > REVERT_BB_PB_SHORT_MIN;
            if ((isLong && longSetup) || (!isLong && shortSetup)) {
                return PATH_MR;
            }
        }
        return PATH_LEGACY_TREND;
    }

    private String checkFeeAwareRR(BigDecimal price, BigDecimal atr,
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

    private String checkRiskRewardGuard(String symbol, String strategy,
                                        BigDecimal slDistance, BigDecimal tpDistance) {
        BigDecimal rr = tpDistance.divide(slDistance, 4, RoundingMode.HALF_UP);
        if (rr.compareTo(MIN_ENTRY_RR) >= 0) {
            return null;
        }
        long count = RR_GUARD_TRIGGERED_COUNT.incrementAndGet();
        log.info("[EntryDecision] RR_GUARD_TRIGGERED symbol={} strategy={} rr={} slDistance={} tpDistance={} count={}",
                symbol, strategy, rr.toPlainString(), slDistance.toPlainString(), tpDistance.toPlainString(), count);
        return "RR_GUARD rr=" + rr.setScale(2, RoundingMode.HALF_UP).toPlainString()
                + " <" + MIN_ENTRY_RR.toPlainString() + " abstain";
    }

    private SlTpAdjustment adjustForNoiseFloor(
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

    private BigDecimal calcQuantityByRisk(User user, BigDecimal totalEquity,
                                          BigDecimal price,
                                          BigDecimal slDistance, int leverage,
                                          double scale) {
        BigDecimal balance = user.getBalance() != null ? user.getBalance() : BigDecimal.ZERO;
        BigDecimal frozen = user.getFrozenBalance() != null ? user.getFrozenBalance() : BigDecimal.ZERO;
        BigDecimal equity = totalEquity != null ? totalEquity : balance.add(frozen);
        BigDecimal maxLoss = equity.multiply(RISK_PER_TRADE).multiply(BigDecimal.valueOf(scale));

        if (slDistance.signum() <= 0) return null;

        BigDecimal quantity = maxLoss.divide(slDistance, 8, RoundingMode.HALF_DOWN);
        if (quantity.signum() <= 0) return null;

        BigDecimal margin = quantity.multiply(price)
                .divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        BigDecimal maxMargin = balance.multiply(MAX_MARGIN_PCT);
        if (margin.compareTo(maxMargin) > 0) {
            BigDecimal marginBudget = maxMargin.subtract(new BigDecimal("0.01")).max(BigDecimal.ZERO);
            BigDecimal maxMarginQuantity = marginBudget.multiply(BigDecimal.valueOf(leverage))
                    .divide(price, 8, RoundingMode.DOWN);
            BigDecimal cappedMargin = maxMarginQuantity.multiply(price)
                    .divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
            long count = MARGIN_CAP_TRIGGERED_COUNT.incrementAndGet();
            if (maxMarginQuantity.signum() <= 0 || cappedMargin.compareTo(maxMargin) > 0) {
                log.info("[EntryDecision] MARGIN_CAP_TRIGGERED action=hold margin={} maxMargin={} reducedMargin={} price={} leverage={} count={}",
                        margin.toPlainString(), maxMargin.toPlainString(), cappedMargin.toPlainString(),
                        price.toPlainString(), leverage, count);
                return null;
            }
            log.info("[EntryDecision] MARGIN_CAP_TRIGGERED action=cap_to_max_margin margin={} maxMargin={} cappedMargin={} price={} leverage={} count={}",
                    margin.toPlainString(), maxMargin.toPlainString(), cappedMargin.toPlainString(),
                    price.toPlainString(), leverage, count);
            quantity = maxMarginQuantity;
        }

        return quantity.signum() > 0 ? quantity : null;
    }

    private boolean isInDrawdown(BigDecimal totalEquity) {
        if (totalEquity == null) return false;
        return totalEquity.compareTo(DeterministicTradingExecutor.INITIAL_BALANCE.multiply(DRAWDOWN_THRESHOLD)) < 0;
    }
}
