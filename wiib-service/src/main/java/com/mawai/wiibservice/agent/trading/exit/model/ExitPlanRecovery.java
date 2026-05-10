package com.mawai.wiibservice.agent.trading.exit.model;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibservice.agent.trading.MarketContext;
import com.mawai.wiibservice.agent.trading.SymbolProfile;
import com.mawai.wiibservice.agent.trading.TradingDecisionContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.getCurrentStopLossPrice;

public final class ExitPlanRecovery {

    private static final BigDecimal BREAKEVEN_ATR_EPSILON = new BigDecimal("0.10");
    private static final BigDecimal BREAKEVEN_PRICE_EPSILON = new BigDecimal("0.0001");

    private ExitPlanRecovery() {
    }

    public static void ensureRecoveredPlans(TradingDecisionContext decision, LocalDateTime now) {
        if (decision == null || decision.state() == null) {
            return;
        }
        List<FuturesPositionDTO> positions = decision.symbolPositions();
        if (positions == null || positions.isEmpty()) {
            return;
        }
        for (FuturesPositionDTO pos : positions) {
            Long positionId = pos != null ? pos.getId() : null;
            if (positionId == null || !"OPEN".equals(pos.getStatus())
                    || decision.state().getExitPlan(positionId) != null) {
                continue;
            }
            ExitPlan recovered = recover(pos, decision.market(), decision.profile(), now);
            if (recovered != null) {
                decision.state().putExitPlan(positionId, recovered);
            }
        }
    }

    static ExitPlan recover(FuturesPositionDTO pos, MarketContext ctx, SymbolProfile profile, LocalDateTime now) {
        if (pos == null || ctx == null || profile == null || now == null) {
            return null;
        }
        BigDecimal entry = pos.getEntryPrice();
        BigDecimal atr = ctx.atr5m;
        if (entry == null || entry.signum() <= 0 || atr == null || atr.signum() <= 0) {
            return null;
        }

        boolean isLong = "LONG".equals(pos.getSide());
        if (!isLong && !"SHORT".equals(pos.getSide())) {
            return null;
        }

        ExitPath path = ExitPlanFactory.mapPath(pos.getMemo());
        BigDecimal currentSl = getCurrentStopLossPrice(pos);
        BigDecimal closeThreshold = closeThreshold(entry, atr);
        RiskSource riskSource = recoverRisk(path, isLong, entry, currentSl, atr, profile, closeThreshold);
        if (riskSource == null || riskSource.riskPerUnit().signum() <= 0) {
            return null;
        }

        boolean breakevenDone = isBreakevenStop(isLong, entry, currentSl, closeThreshold);
        LocalDateTime createdAt = pos.getCreatedAt() != null ? pos.getCreatedAt() : now;
        return ExitPlanFactory.recovered(pos.getMemo(), pos.getSide(), entry, riskSource.initialSL(),
                riskSource.riskPerUnit(), atr, breakevenDone, createdAt);
    }

    private static RiskSource recoverRisk(ExitPath path, boolean isLong, BigDecimal entry, BigDecimal currentSl,
                                          BigDecimal atr, SymbolProfile profile, BigDecimal closeThreshold) {
        if (currentSl != null && currentSl.signum() > 0) {
            BigDecimal distance = entry.subtract(currentSl).abs();
            boolean lossSide = isLong ? currentSl.compareTo(entry) < 0 : currentSl.compareTo(entry) > 0;
            BigDecimal maxReasonable = entry.multiply(BigDecimal.valueOf(profile.slMaxPct()));
            if (lossSide && distance.compareTo(closeThreshold) > 0 && distance.compareTo(maxReasonable) <= 0) {
                return new RiskSource(distance, currentSl);
            }
        }

        BigDecimal estimatedRisk = atr.multiply(BigDecimal.valueOf(slAtrFor(path, profile)));
        if (estimatedRisk.signum() <= 0) {
            return null;
        }
        // SL 接近成本时不能用当前SL反推R；用路径默认SL距离构造一个亏损侧初始SL。
        BigDecimal syntheticInitialSl = isLong ? entry.subtract(estimatedRisk) : entry.add(estimatedRisk);
        if (syntheticInitialSl.signum() <= 0) {
            syntheticInitialSl = entry.multiply(new BigDecimal("0.50"));
        }
        return new RiskSource(estimatedRisk, syntheticInitialSl);
    }

    private static double slAtrFor(ExitPath path, SymbolProfile profile) {
        return switch (path) {
            case BREAKOUT -> profile.breakoutSlAtr();
            case MR -> profile.revertSlAtr();
            case TREND -> profile.trendSlAtr();
        };
    }

    private static boolean isBreakevenStop(boolean isLong, BigDecimal entry, BigDecimal currentSl,
                                           BigDecimal closeThreshold) {
        if (currentSl == null || currentSl.signum() <= 0) {
            return false;
        }
        return isLong
                ? currentSl.compareTo(entry.subtract(closeThreshold)) >= 0
                : currentSl.compareTo(entry.add(closeThreshold)) <= 0;
    }

    private static BigDecimal closeThreshold(BigDecimal entry, BigDecimal atr) {
        return atr.multiply(BREAKEVEN_ATR_EPSILON)
                .max(entry.multiply(BREAKEVEN_PRICE_EPSILON));
    }

    private record RiskSource(BigDecimal riskPerUnit, BigDecimal initialSL) {
    }
}
