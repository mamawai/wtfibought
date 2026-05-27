package com.mawai.wiibservice.agent.trading.backtest;

import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibservice.agent.trading.DeterministicTradingExecutor;
import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyCandidate;
import com.mawai.wiibservice.agent.trading.entry.strategy.EntryStrategySupport;
import com.mawai.wiibservice.agent.trading.entry.strategy.MaSlopeEntryStrategy;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPlan;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPlanFactory;
import com.mawai.wiibservice.agent.trading.exit.playbook.PlaybookExitEngine;
import com.mawai.wiibservice.agent.trading.maslope.MaSlopeFailureEvaluator;
import com.mawai.wiibservice.agent.trading.maslope.MaSlopeKlineStrategy;
import com.mawai.wiibservice.agent.trading.maslope.MaSlopeStateClassifier;
import com.mawai.wiibservice.agent.trading.maslope.MaSlopeStateClassifier.MaState;
import com.mawai.wiibservice.agent.trading.runtime.MarketContext;
import com.mawai.wiibservice.agent.trading.runtime.SymbolProfile;
import com.mawai.wiibservice.agent.trading.runtime.TradingDecisionContext;
import com.mawai.wiibservice.agent.trading.runtime.TradingExecutionState;
import com.mawai.wiibservice.agent.trading.runtime.TradingRuntimeToggles;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.PATH_MA_SLOPE;
import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.getCurrentStopLossPrice;

/**
 * 纯 K 线 MaSlope 回测引擎。
 *
 * <p>不构造 forecast / referenceSignal / riskStatus，只逐根闭合 K 线喂给 MaSlopeKlineStrategy。
 * 这里保留交易执行保护：强平距离、同向加仓最小位移、Playbook 管仓、SL/TP 撮合。</p>
 */
@Slf4j
public final class MaSlopeKlineBacktestEngine {

    private static final int MIN_KLINES_FOR_INDICATOR = 240;
    private static final double ROUND_TRIP_FEE_RATE = 0.0008;
    private static final double MIN_PROFIT_AFTER_FEE_ATR = 0.5;
    private static final BigDecimal DEFAULT_MIN_ENTRY_RR = new BigDecimal("1.2");
    private static final double LOW_VOL_SL_EXPAND_MAX = 3.0;
    private static final double MAINTENANCE_MARGIN_RATE = 0.005;
    private static final double MAX_SL_TO_LIQ_DISTANCE_RATIO = 0.80;
    private static final int FAILED_WAVE_LAUNCH_LIMIT = 2;
    private static final int SHADOW_FORWARD_BARS = 24;

    private final String symbol;
    private final List<BigDecimal[]> baseKlines;
    private final KlineInterval baseInterval;
    private final BigDecimal initialBalance;
    private final BigDecimal marginPerPosition;
    private final int leverage;
    private final Long tradingStartMs;
    private final Long tradingEndMs;
    private final SymbolProfile profile;
    private final MaSlopeKlineStrategy strategy = new MaSlopeKlineStrategy();
    private final PlaybookExitEngine exitEngine = new PlaybookExitEngine();

    public MaSlopeKlineBacktestEngine(String symbol,
                                      List<BigDecimal[]> baseKlines,
                                      KlineInterval baseInterval,
                                      BigDecimal initialBalance,
                                      BigDecimal marginPerPosition,
                                      int leverage) {
        this(symbol, baseKlines, baseInterval, initialBalance, marginPerPosition, leverage, null, null);
    }

    public MaSlopeKlineBacktestEngine(String symbol,
                                      List<BigDecimal[]> baseKlines,
                                      KlineInterval baseInterval,
                                      BigDecimal initialBalance,
                                      BigDecimal marginPerPosition,
                                      int leverage,
                                      Long tradingStartMs,
                                      Long tradingEndMs) {
        this.symbol = symbol;
        this.baseKlines = baseKlines != null ? baseKlines : List.of();
        this.baseInterval = baseInterval != null ? baseInterval : KlineInterval.M3;
        this.initialBalance = initialBalance;
        this.marginPerPosition = marginPerPosition;
        this.leverage = leverage;
        this.tradingStartMs = tradingStartMs;
        this.tradingEndMs = tradingEndMs;
        this.profile = SymbolProfile.of(symbol);
    }

    public BacktestResult run() {
        if (baseKlines.size() < MIN_KLINES_FOR_INDICATOR) {
            throw new IllegalArgumentException(
                    "K线数量不足: " + baseKlines.size() + " < " + MIN_KLINES_FOR_INDICATOR);
        }

        int barsPer15m = baseInterval.aggregateRatioTo(KlineInterval.M15);
        int barsPer1h = baseInterval.aggregateRatioTo(KlineInterval.H1);

        BacktestTradingTools tools = new BacktestTradingTools(initialBalance, symbol);
        TradingExecutionState state = new TradingExecutionState();
        BacktestResult result = new BacktestResult(initialBalance);
        User user = createMockUser(initialBalance);
        long fallbackBaseTimeMs = System.currentTimeMillis();

        log.info("[MaSlopeKlineBacktest] start symbol={} interval={} bars={} balance={} margin={} leverage={}",
                symbol, baseInterval.getCode(), baseKlines.size(), initialBalance, marginPerPosition, leverage);

        for (int i = MIN_KLINES_FOR_INDICATOR; i < baseKlines.size(); i++) {
            BigDecimal[] bar = baseKlines.get(i);
            long nowMs = klineCloseTimeMs(bar, i, fallbackBaseTimeMs);
            state.setMockNowMs(nowMs);
            LocalDateTime now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.systemDefault());
            BigDecimal high = bar[0];
            BigDecimal low = bar[1];
            BigDecimal close = bar[2];
            if (tradingEndMs != null && nowMs >= tradingEndMs) {
                break;
            }

            tools.setCurrentTime(now);
            tools.setCurrentPrice(close);
            tools.setCurrentBarIndex(i);

            List<BigDecimal[]> baseWindow = baseKlines.subList(0, i + 1);
            List<BigDecimal[]> klines15m = BacktestEngine.aggregate(baseWindow, barsPer15m, KlineInterval.M15);
            List<BigDecimal[]> klines1h = BacktestEngine.aggregate(baseWindow, barsPer1h, KlineInterval.H1);

            MaSlopeKlineStrategy.Evaluation evaluation = strategy.evaluate(new MaSlopeKlineStrategy.Input(
                    symbol, baseInterval, baseWindow, klines15m, klines1h, close, profile));
            MarketContext market = evaluation.market();
            if (market == null) {
                if (inTradingWindow(nowMs)) {
                    result.recordEquity(tools.getTotalEquity());
                    if (!evaluation.accepted()) {
                        result.recordRejectReason(evaluation.rejectReason());
                    }
                }
                continue;
            }
            if (!inTradingWindow(nowMs)) {
                continue;
            }
            if (!evaluation.accepted()) {
                result.recordRejectReason(evaluation.rejectReason());
                recordRejectedShadowOpportunity(result, evaluation, close, i);
            }

            recordExitSignalDiagnostics(tools, state, market);
            tools.tickBar(high, low, close, i);
            tools.setCurrentPrice(close);
            tools.setCurrentBarIndex(i);

            PlaybookExitEngine.PlaybookEvaluation exitEvaluation =
                    exitEngine.evaluateDetailed(decisionContext(user, tools, state, market, now), true);
            boolean canEvaluateEntry = exitEvaluation.entryEvaluationAllowed();

            if (canEvaluateEntry && evaluation.accepted()) {
                String openReject = tryOpen(evaluation, tools, state, close, now, i);
                if (openReject != null) {
                    result.recordRejectReason(openReject);
                    recordCandidateShadowOpportunity(result, evaluation, openReject, close, i);
                }
            }

            result.recordEquity(tools.getTotalEquity());
        }

        forceCloseAll(tools);
        result.recordEquity(tools.getTotalEquity());

        for (BacktestTradingTools.ClosedTrade ct : tools.getClosedTrades()) {
            result.addTrade(new BacktestResult.Trade(
                    ct.openBarIndex(), ct.closeBarIndex(), ct.openTime(), ct.closeTime(),
                    ct.entryDiagnosticsJson(), ct.side(), ct.strategy(),
                    ct.entryPrice(), ct.exitPrice(), ct.quantity(), ct.leverage(),
                    ct.pnl(), ct.fee(), ct.rMultiple(), ct.exitReason(),
                    ct.entryMode(), ct.failScoreAtExit(), ct.maxFavorableR(), ct.maxAdverseR(),
                    ct.wasLateContinuation()
            ));
        }
        log.info("[MaSlopeKlineBacktest] done symbol={} trades={} equity={} pnl={}",
                symbol, result.totalTrades(), result.finalEquity(), result.netProfit());
        return result;
    }

    private boolean inTradingWindow(long nowMs) {
        return (tradingStartMs == null || nowMs >= tradingStartMs)
                && (tradingEndMs == null || nowMs < tradingEndMs);
    }

    private void recordRejectedShadowOpportunity(BacktestResult result,
                                                 MaSlopeKlineStrategy.Evaluation evaluation,
                                                 BigDecimal entryPrice,
                                                 int barIndex) {
        if (result == null || evaluation == null || evaluation.market() == null
                || evaluation.rejectReason() == null) {
            return;
        }
        Boolean isLong = inferShadowDirection(evaluation.market());
        if (isLong == null) {
            return;
        }
        recordShadowOpportunity(result, evaluation.rejectReason(), isLong, evaluation.market(), entryPrice, barIndex);
    }

    private void recordCandidateShadowOpportunity(BacktestResult result,
                                                  MaSlopeKlineStrategy.Evaluation evaluation,
                                                  String rejectReason,
                                                  BigDecimal entryPrice,
                                                  int barIndex) {
        EntryStrategyCandidate candidate = evaluation != null ? evaluation.candidate() : null;
        if (candidate == null || evaluation.market() == null || rejectReason == null) {
            return;
        }
        recordShadowOpportunity(result, rejectReason, candidate.isLong(), evaluation.market(), entryPrice, barIndex);
    }

    private void recordShadowOpportunity(BacktestResult result,
                                         String rejectReason,
                                         boolean isLong,
                                         MarketContext market,
                                         BigDecimal entryPrice,
                                         int barIndex) {
        if (entryPrice == null || entryPrice.signum() <= 0 || market.atrClosed == null
                || market.atrClosed.signum() <= 0) {
            return;
        }
        BigDecimal risk = market.atrClosed.multiply(BigDecimal.valueOf(profile.trendSlAtr()));
        if (risk.signum() <= 0 || barIndex + 1 >= baseKlines.size()) {
            return;
        }

        double entry = entryPrice.doubleValue();
        double riskValue = risk.doubleValue();
        double maxFavorableR = 0.0;
        double maxAdverseR = 0.0;
        int end = Math.min(baseKlines.size() - 1, barIndex + SHADOW_FORWARD_BARS);
        for (int j = barIndex + 1; j <= end; j++) {
            BigDecimal[] bar = baseKlines.get(j);
            double high = bar[0].doubleValue();
            double low = bar[1].doubleValue();
            if (isLong) {
                maxFavorableR = Math.max(maxFavorableR, (high - entry) / riskValue);
                maxAdverseR = Math.min(maxAdverseR, (low - entry) / riskValue);
            } else {
                maxFavorableR = Math.max(maxFavorableR, (entry - low) / riskValue);
                maxAdverseR = Math.min(maxAdverseR, (entry - high) / riskValue);
            }
        }
        result.recordShadowOpportunity(rejectReason, isLong, maxFavorableR, maxAdverseR);
    }

    private Boolean inferShadowDirection(MarketContext market) {
        MaState primary = MaSlopeStateClassifier.classifyPrimary(market);
        if (primary.hasCandidate()) {
            return primary.isLong();
        }
        if ("golden".equals(market.macdCross)) {
            return true;
        }
        if ("death".equals(market.macdCross)) {
            return false;
        }

        boolean macdLong = market.macdDif != null && market.macdDea != null
                && market.macdDif.compareTo(market.macdDea) > 0
                && EntryStrategySupport.isMacdHistBullish(market.macdHistTrend);
        boolean macdShort = market.macdDif != null && market.macdDea != null
                && market.macdDif.compareTo(market.macdDea) < 0
                && EntryStrategySupport.isMacdHistBearish(market.macdHistTrend);
        boolean closeLong = EntryStrategySupport.closeTrendSupports(market.closeTrendClosed3, true);
        boolean closeShort = EntryStrategySupport.closeTrendSupports(market.closeTrendClosed3, false);
        if (macdLong && closeLong) {
            return true;
        }
        if (macdShort && closeShort) {
            return false;
        }
        if (primary.sameBroadDirection(true) && !EntryStrategySupport.directionConflicts(market.maAlignment1h, true)) {
            return true;
        }
        if (primary.sameBroadDirection(false) && !EntryStrategySupport.directionConflicts(market.maAlignment1h, false)) {
            return false;
        }
        return null;
    }

    private String tryOpen(MaSlopeKlineStrategy.Evaluation evaluation,
                           BacktestTradingTools tools,
                           TradingExecutionState state,
                           BigDecimal price,
                           LocalDateTime now,
                           int barIndex) {
        EntryStrategyCandidate candidate = evaluation.candidate();
        if (candidate == null || price == null || price.signum() <= 0) {
            return "MASLOPE_OPEN_CANDIDATE_INVALID";
        }
        SlTpPlan slTp = slTpPlan(candidate, price, evaluation.market());
        if (slTp == null) {
            return "MASLOPE_OPEN_SLTP_INVALID";
        }
        if (liquidationUnsafe(candidate.isLong(), price, slTp.slDistance())) {
            return "MASLOPE_OPEN_LIQUIDATION_UNSAFE";
        }
        if (failedWaveLaunchReject(candidate, state)) {
            return "MASLOPE_FAILED_WAVE_REJECT";
        }
        String scaleInReject = sameSideScaleInReject(
                tools.getOpenPositions(symbol), candidate, evaluation.market(), state);
        if (scaleInReject != null) {
            return scaleInReject;
        }

        BigDecimal effectiveMargin = effectiveMargin(candidate);
        BigDecimal quantity = effectiveMargin.multiply(BigDecimal.valueOf(leverage))
                .divide(price, 8, RoundingMode.DOWN);
        if (quantity.signum() <= 0) {
            return "MASLOPE_OPEN_QUANTITY_INVALID";
        }
        BigDecimal stopLoss = candidate.isLong()
                ? price.subtract(slTp.slDistance())
                : price.add(slTp.slDistance());
        BigDecimal takeProfit = candidate.isLong()
                ? price.add(slTp.tpDistance())
                : price.subtract(slTp.tpDistance());

        var open = tools.openPositionWithResult(candidate.side(), quantity, leverage,
                "MARKET", null, stopLoss, takeProfit, PATH_MA_SLOPE);
        if (!open.success() || open.positionId() == null) {
            return "MASLOPE_OPEN_FAILED";
        }
        tools.markOpenBarIndex(barIndex, entryDiagnosticsJson(evaluation, price, effectiveMargin));
        MaState entryState = MaSlopeStateClassifier.classifyPrimary(evaluation.market());
        ExitPlan plan = ExitPlanFactory.fromMaSlopeEntry(candidate.side(), price, stopLoss,
                evaluation.market().atrClosed, evaluation.market().bollPb,
                evaluation.market().rsi != null ? evaluation.market().rsi.doubleValue() : null,
                evaluation.market().maAlignment1h, evaluation.market().maAlignment15m,
                entryState.ma7SlopeAtr(), candidate.entryMode(), candidate.wasLateContinuation(), now);
        state.putExitPlan(open.positionId(), plan);
        if (!"LAUNCH".equals(candidate.entryMode())) {
            state.clearMaSlopeFastFailStreak(symbol, candidate.side());
        }
        return null;
    }

    private SlTpPlan slTpPlan(EntryStrategyCandidate candidate, BigDecimal price, MarketContext market) {
        if (candidate.slDistance() == null || candidate.tpDistance() == null
                || candidate.slDistance().signum() <= 0 || candidate.tpDistance().signum() <= 0) {
            return null;
        }
        BigDecimal slDistance = candidate.slDistance();
        BigDecimal tpDistance = candidate.tpDistance();
        double minSlDistance = profile.slMinPct() * price.doubleValue();
        if (slDistance.doubleValue() < minSlDistance) {
            double expandRatio = minSlDistance / slDistance.doubleValue();
            if (expandRatio > LOW_VOL_SL_EXPAND_MAX) {
                return null;
            }
            BigDecimal ratio = BigDecimal.valueOf(expandRatio);
            slDistance = slDistance.multiply(ratio).setScale(8, RoundingMode.HALF_UP);
            // 低波动扩 SL 后，硬 TP 必须跟最终 SL 保持固定 R。
            tpDistance = tpDistance.multiply(ratio).setScale(8, RoundingMode.HALF_UP);
        }

        tpDistance = slDistance.multiply(BigDecimal.valueOf(profile.trendHardTpR()))
                .setScale(8, RoundingMode.HALF_UP);
        if (feeAwareTpTooSmall(price, market.atrClosed, tpDistance)) {
            return null;
        }
        BigDecimal rr = tpDistance.divide(slDistance, 4, RoundingMode.HALF_UP);
        if (rr.compareTo(DEFAULT_MIN_ENTRY_RR) < 0) {
            return null;
        }
        return new SlTpPlan(slDistance, tpDistance);
    }

    private boolean feeAwareTpTooSmall(BigDecimal price, BigDecimal atr, BigDecimal tpDistance) {
        if (atr == null || atr.signum() <= 0) {
            return false;
        }
        double feeAbsolute = price.doubleValue() * ROUND_TRIP_FEE_RATE;
        double feeInAtr = feeAbsolute / atr.doubleValue();
        double tpInAtr = tpDistance.doubleValue() / atr.doubleValue();
        return tpInAtr < feeInAtr + MIN_PROFIT_AFTER_FEE_ATR;
    }

    private boolean liquidationUnsafe(boolean isLong, BigDecimal price, BigDecimal slDistance) {
        if (price == null || price.signum() <= 0 || slDistance == null || slDistance.signum() <= 0) {
            return true;
        }
        double liqDistancePct = estimateLiquidationDistancePct(isLong);
        if (liqDistancePct <= 0) {
            return true;
        }
        double slPct = slDistance.divide(price, 8, RoundingMode.HALF_UP).doubleValue();
        return slPct > liqDistancePct * MAX_SL_TO_LIQ_DISTANCE_RATIO;
    }

    private double estimateLiquidationDistancePct(boolean isLong) {
        if (leverage <= 0) return 0;
        double initialMarginRate = 1.0 / leverage;
        double numerator = initialMarginRate - MAINTENANCE_MARGIN_RATE;
        if (numerator <= 0) return 0;
        double denominator = isLong ? (1.0 - MAINTENANCE_MARGIN_RATE) : (1.0 + MAINTENANCE_MARGIN_RATE);
        return numerator / denominator;
    }

    private String sameSideScaleInReject(List<FuturesPositionDTO> positions,
                                         EntryStrategyCandidate candidate,
                                         MarketContext market,
                                         TradingExecutionState state) {
        if (positions == null || positions.isEmpty() || market == null || market.price == null) {
            return null;
        }
        boolean hasSameSide = false;
        boolean hasProtectedSameSide = false;
        boolean candidateLong = candidate.isLong();
        for (FuturesPositionDTO position : positions) {
            if (position == null || !"OPEN".equals(position.getStatus())
                    || !candidate.side().equals(position.getSide())
                    || !PATH_MA_SLOPE.equals(position.getMemo())
                    || position.getEntryPrice() == null) {
                continue;
            }
            hasSameSide = true;
            hasProtectedSameSide |= isBreakevenProtected(state, position, candidateLong);
        }
        if (!hasSameSide) {
            return null;
        }

        boolean pullbackReclaim = MaSlopeEntryStrategy.pullbackReclaimSupports(market, candidateLong);
        if ("PULLBACK_RECLAIM".equals(candidate.entryMode())
                || (hasProtectedSameSide && pullbackReclaim)) {
            return null;
        }
        return "MASLOPE_SAME_WAVE_REJECT";
    }

    private boolean failedWaveLaunchReject(EntryStrategyCandidate candidate, TradingExecutionState state) {
        if (candidate == null || state == null || !failedWaveLimitedMode(candidate.entryMode())) {
            return false;
        }
        return state.getMaSlopeFastFailStreak(symbol, candidate.side()) >= FAILED_WAVE_LAUNCH_LIMIT;
    }

    private boolean failedWaveLimitedMode(String entryMode) {
        return "LAUNCH".equals(entryMode)
                || "MACD_RECLAIM".equals(entryMode);
    }

    private BigDecimal effectiveMargin(EntryStrategyCandidate candidate) {
        if (candidate == null || !usesScaledMaSlopeMargin(candidate.entryMode())) {
            return marginPerPosition;
        }
        double scale = Math.clamp(candidate.positionScale(), 0.05, 1.0);
        return marginPerPosition.multiply(BigDecimal.valueOf(scale))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private boolean usesScaledMaSlopeMargin(String entryMode) {
        return "MACD_RECLAIM".equals(entryMode);
    }

    private boolean isBreakevenProtected(TradingExecutionState state, FuturesPositionDTO position, boolean isLong) {
        ExitPlan plan = state != null && position.getId() != null ? state.getExitPlan(position.getId()) : null;
        if (plan != null && plan.breakevenDone()) {
            return true;
        }
        BigDecimal stop = getCurrentStopLossPrice(position);
        if (stop == null || stop.signum() <= 0 || position.getEntryPrice() == null) {
            return false;
        }
        return isLong ? stop.compareTo(position.getEntryPrice()) >= 0 : stop.compareTo(position.getEntryPrice()) <= 0;
    }

    private TradingDecisionContext decisionContext(User user,
                                                   BacktestTradingTools tools,
                                                   TradingExecutionState state,
                                                   MarketContext market,
                                                   LocalDateTime now) {
        return new TradingDecisionContext(symbol, user, tools.getOpenPositions(symbol),
                null, List.of(), List.of(), market.price, market.price, tools.getTotalEquity(),
                tools, state, new TradingRuntimeToggles(true, true),
                profile, market, now, false);
    }

    private void recordExitSignalDiagnostics(BacktestTradingTools tools,
                                             TradingExecutionState state,
                                             MarketContext market) {
        if (tools == null || market == null) {
            return;
        }
        MaState current = MaSlopeStateClassifier.classifyPrimary(market);
        for (FuturesPositionDTO position : tools.getOpenPositions(symbol)) {
            if (position == null || position.getId() == null || !PATH_MA_SLOPE.equals(position.getMemo())) {
                continue;
            }
            boolean isLong = "LONG".equals(position.getSide());
            ExitPlan plan = state != null ? state.getExitPlan(position.getId()) : null;
            Double entrySlope = plan != null ? plan.entryMa7SlopeAtr() : null;
            MaSlopeFailureEvaluator.FailureScore score =
                    MaSlopeFailureEvaluator.score(market, current, isLong, entrySlope);
            tools.recordExitSignalDiagnostics(position.getId(), score.score());
        }
    }

    private String entryDiagnosticsJson(MaSlopeKlineStrategy.Evaluation evaluation,
                                        BigDecimal price,
                                        BigDecimal effectiveMargin) {
        MarketContext ctx = evaluation.market();
        MaState primary = MaSlopeStateClassifier.classifyPrimary(ctx);
        MaState previous = MaSlopeStateClassifier.classifyPreviousPrimary(ctx);
        MaState confirm = MaSlopeStateClassifier.classifyConfirm(ctx);

        JSONObject json = new JSONObject();
        json.put("mode", "KLINE_ONLY");
        json.put("entryMode", evaluation.candidate() != null ? evaluation.candidate().entryMode() : null);
        json.put("wasLateContinuation", evaluation.candidate() != null
                && evaluation.candidate().wasLateContinuation());
        json.put("positionScale", evaluation.candidate() != null
                ? evaluation.candidate().positionScale() : null);
        json.put("effectiveMargin", effectiveMargin);
        json.put("decisionInterval", baseInterval.getCode());
        json.put("price", price);
        json.put("regime", ctx.regime);
        json.put("adx", ctx.adx);
        json.put("macdCross", ctx.macdCross);
        json.put("macdHistTrend", ctx.macdHistTrend);
        json.put("macdDif", ctx.macdDif);
        json.put("macdDea", ctx.macdDea);
        json.put("macdHist", evaluation.primaryIndicators().get("macd_hist"));
        json.put("macdAboveZero", macdAboveZero(ctx));
        json.put("closeTrend", ctx.closeTrend);
        json.put("closeTrendClosed3", ctx.closeTrendClosed3);
        json.put("maAlignment15m", ctx.maAlignment15m);
        json.put("maAlignment1h", ctx.maAlignment1h);
        json.put("primaryState", primary.state().name());
        json.put("primaryMa7SlopeAtr", primary.ma7SlopeAtr());
        json.put("primaryMa7PrevSlopeAtr", primary.ma7PrevSlopeAtr());
        json.put("primaryMa25SlopeAtr", primary.ma25SlopeAtr());
        json.put("primarySpreadAtr", primary.spreadAtr());
        json.put("primarySpreadDeltaAtr", primary.spreadDeltaAtr());
        json.put("primaryPriceDistanceAtr", primary.priceDistanceAtr());
        json.put("previousPrimaryState", previous.state().name());
        json.put("confirmState", confirm.state().name());
        json.put("confirmMa7SlopeAtr", confirm.ma7SlopeAtr());
        json.put("confirmMa25SlopeAtr", confirm.ma25SlopeAtr());
        json.put("bollExpanding5", ctx.bollExpanding5);
        json.put("avgVolumeRatioClosed", ctx.volumeRatioClosedSeries.stream()
                .filter(v -> v != null && Double.isFinite(v))
                .mapToDouble(Double::doubleValue)
                .average().orElse(0.0));
        json.put("closePositionClosed", ctx.closePositionClosed);
        json.put("rangeAtrClosed", ctx.rangeAtrClosed);
        json.put("closeBreakoutHigh10Closed", ctx.closeBreakoutHigh10Closed);
        json.put("closeBreakdownLow10Closed", ctx.closeBreakdownLow10Closed);
        return json.toJSONString();
    }

    private boolean macdAboveZero(MarketContext ctx) {
        return ctx != null
                && ctx.macdDif != null && ctx.macdDif.signum() > 0
                && ctx.macdDea != null && ctx.macdDea.signum() > 0;
    }

    private User createMockUser(BigDecimal balance) {
        User user = new User();
        user.setId(0L);
        user.setUsername("kline-backtest");
        user.setBalance(balance);
        user.setFrozenBalance(BigDecimal.ZERO);
        user.setMarginLoanPrincipal(BigDecimal.ZERO);
        user.setMarginInterestAccrued(BigDecimal.ZERO);
        user.setIsBankrupt(false);
        user.setBankruptCount(0);
        return user;
    }

    private long klineCloseTimeMs(BigDecimal[] bar, int index, long fallbackBaseTimeMs) {
        if (bar != null && bar.length > 6 && bar[6] != null && bar[6].signum() > 0) {
            return bar[6].longValue();
        }
        if (bar != null && bar.length > 5 && bar[5] != null && bar[5].signum() > 0) {
            return bar[5].longValue() + (long) baseInterval.getMinutes() * 60_000L - 1;
        }
        return fallbackBaseTimeMs
                + (index - MIN_KLINES_FOR_INDICATOR) * (long) baseInterval.getMinutes() * 60_000L;
    }

    private void forceCloseAll(BacktestTradingTools tools) {
        List<FuturesPositionDTO> remaining = new ArrayList<>(tools.getOpenPositions(symbol));
        for (FuturesPositionDTO pos : remaining) {
            tools.closePositionWithReason(pos.getId(), pos.getQuantity(), "FORCE_CLOSE");
        }
    }

    private record SlTpPlan(BigDecimal slDistance, BigDecimal tpDistance) {
    }
}
