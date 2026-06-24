package com.mawai.wiibservice.agent.strategy.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 回测结果统计。
 * <p>
 * 包含交易记录明细和汇总统计指标（胜率、盈亏比、Sharpe、最大回撤等）。
 */
public class BacktestResult {

    private static final String PATH_BREAKOUT = "BREAKOUT";
    private static final String PATH_MR = "MR";
    private static final String PATH_LEGACY_TREND = "LEGACY_TREND";
    private static final String PATH_MA_SLOPE = "MA_SLOPE";

    // ==================== 交易记录 ====================

    /**
     * 单笔交易记录。
     */
    public record Trade(
            int barIndex,           // 开仓所在K线索引
            int closeBarIndex,      // 平仓所在K线索引
            LocalDateTime openTime,
            LocalDateTime closeTime,
            String entryDiagnosticsJson,
            String side,            // "LONG" / "SHORT"
            String strategy,        // "LEGACY_TREND" / "MR" / "BREAKOUT" / "MA_SLOPE"
            BigDecimal entryPrice,
            BigDecimal exitPrice,
            BigDecimal quantity,
            int leverage,
            BigDecimal pnl,         // 已扣手续费
            BigDecimal fee,         // 总手续费
            BigDecimal rMultiple,   // 以开仓初始风险为基准；缺失风险时为 null
            String exitReason,      // "SL" / "TP" / "SIGNAL_CLOSE" / "TRAILING_STOP" / "TIMEOUT" / "MANUAL"
            String entryMode,
            Integer failScoreAtExit,
            BigDecimal maxFavorableR,
            BigDecimal maxAdverseR,
            boolean wasLateContinuation
    ) {
        public Trade(int barIndex,
                     int closeBarIndex,
                     String side,
                     String strategy,
                     BigDecimal entryPrice,
                     BigDecimal exitPrice,
                     BigDecimal quantity,
                     int leverage,
                     BigDecimal pnl,
                     BigDecimal fee,
                     BigDecimal rMultiple,
                     String exitReason) {
            this(barIndex, closeBarIndex, null, null, null, side, strategy, entryPrice, exitPrice,
                    quantity, leverage, pnl, fee, rMultiple, exitReason, null, null, null, null, false);
        }

        public Trade(int barIndex,
                     int closeBarIndex,
                     LocalDateTime openTime,
                     LocalDateTime closeTime,
                     String side,
                     String strategy,
                     BigDecimal entryPrice,
                     BigDecimal exitPrice,
                     BigDecimal quantity,
                     int leverage,
                     BigDecimal pnl,
                     BigDecimal fee,
                     BigDecimal rMultiple,
                     String exitReason) {
            this(barIndex, closeBarIndex, openTime, closeTime, null, side, strategy, entryPrice, exitPrice,
                    quantity, leverage, pnl, fee, rMultiple, exitReason, null, null, null, null, false);
        }

        public Trade(int barIndex,
                     int closeBarIndex,
                     LocalDateTime openTime,
                     LocalDateTime closeTime,
                     String entryDiagnosticsJson,
                     String side,
                     String strategy,
                     BigDecimal entryPrice,
                     BigDecimal exitPrice,
                     BigDecimal quantity,
                     int leverage,
                     BigDecimal pnl,
                     BigDecimal fee,
                     BigDecimal rMultiple,
                     String exitReason) {
            this(barIndex, closeBarIndex, openTime, closeTime, entryDiagnosticsJson, side, strategy,
                    entryPrice, exitPrice, quantity, leverage, pnl, fee, rMultiple, exitReason,
                    null, null, null, null, false);
        }
    }

    /**
     * 单策略路径统计，便于判断哪条路径真实贡献收益/回撤。
     */
    public record StrategyStats(
            String strategy,
            int trades,
            int wins,
            int losses,
            double winRate,
            BigDecimal netPnl,
            BigDecimal ev,
            double avgR,
            double profitFactor,
            double maxDrawdownPct,
            double avgHoldBars,
            int maxConsecutiveLosses
    ) {}

    private final List<Trade> trades = new ArrayList<>();
    private final List<BigDecimal> equityCurve = new ArrayList<>();
    private final Map<String, Integer> rejectCounts = new LinkedHashMap<>();
    private final Map<String, ShadowOpportunityStats> shadowOpportunityStats = new LinkedHashMap<>();
    private final BigDecimal initialEquity;

    public BacktestResult(BigDecimal initialEquity) {
        this.initialEquity = initialEquity;
        this.equityCurve.add(initialEquity);
    }

    public void addTrade(Trade trade) {
        trades.add(trade);
    }

    public void recordEquity(BigDecimal equity) {
        equityCurve.add(equity);
    }

    /** 累加一次入场被拒，key 取拒因前缀（剥掉 "PATH: " 后按首空格切分），便于报告聚合。 */
    public void recordRejectReason(String rejectReason) {
        if (rejectReason == null || rejectReason.isBlank()) {
            return;
        }
        String key = rejectKey(rejectReason);
        if (key != null) {
            rejectCounts.merge(key, 1, Integer::sum);
        }
    }

    public void recordShadowOpportunity(String rejectReason, boolean isLong, double maxFavorableR, double maxAdverseR) {
        String reasonKey = rejectKey(rejectReason);
        if (reasonKey == null || !Double.isFinite(maxFavorableR) || !Double.isFinite(maxAdverseR)) {
            return;
        }
        String key = reasonKey + "|" + (isLong ? "LONG" : "SHORT");
        shadowOpportunityStats
                .computeIfAbsent(key, ignored -> new ShadowOpportunityStats(reasonKey, isLong))
                .record(maxFavorableR, maxAdverseR);
    }

    public Map<String, ShadowOpportunityStats> getShadowOpportunityStats() {
        return shadowOpportunityStats;
    }

    public static String rejectKey(String rejectReason) {
        if (rejectReason == null || rejectReason.isBlank()) {
            return null;
        }
        String body = rejectReason;
        int colon = body.indexOf(": ");
        if (colon >= 0) {
            body = body.substring(colon + 2);
        }
        int sp = body.indexOf(' ');
        return sp > 0 ? body.substring(0, sp) : body;
    }

    public Map<String, Integer> getRejectCounts() {
        return rejectCounts;
    }

    public static final class ShadowOpportunityStats {
        private final String rejectReason;
        private final boolean isLong;
        private int count;
        private int reachedHalfR;
        private int heldQuarterR;
        private int cleanHalfR;
        private double totalMaxFavorableR;
        private double totalMaxAdverseR;

        private ShadowOpportunityStats(String rejectReason, boolean isLong) {
            this.rejectReason = rejectReason;
            this.isLong = isLong;
        }

        private void record(double maxFavorableR, double maxAdverseR) {
            count++;
            totalMaxFavorableR += maxFavorableR;
            totalMaxAdverseR += maxAdverseR;
            if (maxFavorableR >= 0.50) {
                reachedHalfR++;
            }
            if (maxAdverseR > -0.25) {
                heldQuarterR++;
            }
            if (maxFavorableR >= 0.50 && maxAdverseR > -0.25) {
                cleanHalfR++;
            }
        }

        public String rejectReason() {
            return rejectReason;
        }

        public String side() {
            return isLong ? "LONG" : "SHORT";
        }

        public int count() {
            return count;
        }

        public int reachedHalfR() {
            return reachedHalfR;
        }

        public int heldQuarterR() {
            return heldQuarterR;
        }

        public int cleanHalfR() {
            return cleanHalfR;
        }

        public double halfRRate() {
            return count == 0 ? 0.0 : (double) reachedHalfR / count;
        }

        public double heldQuarterRRate() {
            return count == 0 ? 0.0 : (double) heldQuarterR / count;
        }

        public double cleanHalfRRate() {
            return count == 0 ? 0.0 : (double) cleanHalfR / count;
        }

        public double avgMaxFavorableR() {
            return count == 0 ? 0.0 : totalMaxFavorableR / count;
        }

        public double avgMaxAdverseR() {
            return count == 0 ? 0.0 : totalMaxAdverseR / count;
        }
    }

    // ==================== 汇总指标 ====================

    public int totalTrades() {
        return trades.size();
    }

    public int wins() {
        return (int) trades.stream().filter(t -> t.pnl.signum() > 0).count();
    }

    public int losses() {
        return (int) trades.stream().filter(t -> t.pnl.signum() < 0).count();
    }

    public double winRate() {
        return totalTrades() == 0 ? 0 : (double) wins() / totalTrades();
    }

    /**
     * 盈亏比 = 总盈利 / 总亏损绝对值。
     */
    public double profitFactor() {
        BigDecimal totalWin = trades.stream()
                .map(Trade::pnl).filter(p -> p.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalLoss = trades.stream()
                .map(Trade::pnl).filter(p -> p.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add).abs();
        if (totalLoss.signum() == 0) return totalWin.signum() > 0 ? Double.MAX_VALUE : 0;
        return totalWin.divide(totalLoss, 4, RoundingMode.HALF_UP).doubleValue();
    }

    public BigDecimal netProfit() {
        return trades.stream().map(Trade::pnl).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal totalFees() {
        return trades.stream().map(Trade::fee).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public double avgR() {
        return avgR(trades);
    }

    /**
     * 最大回撤（占峰值权益的百分比）。
     */
    public double maxDrawdownPct() {
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDd = BigDecimal.ZERO;
        for (BigDecimal eq : equityCurve) {
            if (eq.compareTo(peak) > 0) peak = eq;
            BigDecimal dd = peak.subtract(eq);
            if (dd.compareTo(maxDd) > 0) maxDd = dd;
        }
        if (peak.signum() == 0) return 0;
        return maxDd.divide(peak, 6, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 简化 Sharpe Ratio（无风险利率=0），基于每笔交易PnL均值/标准差。
     */
    public double sharpeRatio() {
        if (trades.size() < 2) return 0;
        double[] pnls = trades.stream().mapToDouble(t -> t.pnl.doubleValue()).toArray();
        double mean = 0;
        for (double p : pnls) mean += p;
        mean /= pnls.length;
        double variance = 0;
        for (double p : pnls) variance += (p - mean) * (p - mean);
        variance /= (pnls.length - 1);
        double stdDev = Math.sqrt(variance);
        return stdDev == 0 ? 0 : mean / stdDev;
    }

    public double avgHoldBars() {
        return trades.isEmpty() ? 0 :
                trades.stream().mapToInt(t -> t.closeBarIndex - t.barIndex).average().orElse(0);
    }

    public BigDecimal finalEquity() {
        return equityCurve.isEmpty() ? initialEquity : equityCurve.getLast();
    }

    public double returnPct() {
        return finalEquity().subtract(initialEquity)
                .divide(initialEquity, 6, RoundingMode.HALF_UP).doubleValue();
    }

    public List<Trade> tradesByStrategy(String strategy) {
        return trades.stream().filter(t -> strategy.equals(t.strategy)).toList();
    }

    public StrategyStats statsByStrategy(String strategy) {
        return calcStrategyStats(strategy, tradesByStrategy(strategy));
    }

    public List<StrategyStats> allStrategyStats() {
        return List.of(
                statsByStrategy(PATH_LEGACY_TREND),
                statsByStrategy(PATH_MR),
                statsByStrategy(PATH_BREAKOUT),
                statsByStrategy(PATH_MA_SLOPE)
        );
    }

    public List<Trade> getTrades() {
        return List.copyOf(trades);
    }

    public List<BigDecimal> getEquityCurve() {
        return List.copyOf(equityCurve);
    }

    // ==================== 报告输出 ====================

    @Override
    public String toString() {
        return String.format("""
                ===== Backtest Report =====
                Period: %d bars
                Initial Equity: %s → Final: %s (%.2f%%)
                Trades: %d | Wins: %d | Losses: %d | WinRate: %.1f%%
                Net Profit: %s | Fees: %s
                Profit Factor: %.2f | Sharpe: %.3f
                Max Drawdown: %.2f%%
                Avg Hold: %.1f bars | Avg R: %.2f
                --- By Strategy ---
                %s
                %s
                %s
                %s
                ============================""",
                equityCurve.size(),
                initialEquity.toPlainString(), finalEquity().toPlainString(), returnPct() * 100,
                totalTrades(), wins(), losses(), winRate() * 100,
                netProfit().toPlainString(), totalFees().toPlainString(),
                profitFactor(), sharpeRatio(),
                maxDrawdownPct() * 100,
                avgHoldBars(),
                avgR(),
                formatStrategyStats(statsByStrategy(PATH_LEGACY_TREND)),
                formatStrategyStats(statsByStrategy(PATH_MR)),
                formatStrategyStats(statsByStrategy(PATH_BREAKOUT)),
                formatStrategyStats(statsByStrategy(PATH_MA_SLOPE))
        );
    }

    private StrategyStats calcStrategyStats(String strategy, List<Trade> rows) {
        int samples = rows.size();
        int wins = (int) rows.stream().filter(t -> t.pnl.signum() > 0).count();
        int losses = (int) rows.stream().filter(t -> t.pnl.signum() < 0).count();
        BigDecimal net = rows.stream().map(Trade::pnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ev = samples == 0
                ? BigDecimal.ZERO
                : net.divide(BigDecimal.valueOf(samples), 8, RoundingMode.HALF_UP);
        double avgHold = samples == 0
                ? 0
                : rows.stream().mapToInt(t -> t.closeBarIndex - t.barIndex).average().orElse(0);
        return new StrategyStats(
                strategy,
                samples,
                wins,
                losses,
                samples == 0 ? 0 : (double) wins / samples,
                net,
                ev,
                avgR(rows),
                profitFactor(rows),
                pathDrawdownPct(rows),
                avgHold,
                maxConsecutiveLosses(rows)
        );
    }

    private double profitFactor(List<Trade> rows) {
        BigDecimal totalWin = rows.stream()
                .map(Trade::pnl).filter(p -> p.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalLoss = rows.stream()
                .map(Trade::pnl).filter(p -> p.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add).abs();
        if (totalLoss.signum() == 0) return totalWin.signum() > 0 ? Double.MAX_VALUE : 0;
        return totalWin.divide(totalLoss, 4, RoundingMode.HALF_UP).doubleValue();
    }

    private double pathDrawdownPct(List<Trade> rows) {
        if (rows.isEmpty()) return 0;
        BigDecimal equity = initialEquity;
        BigDecimal peak = equity;
        BigDecimal maxDd = BigDecimal.ZERO;
        for (Trade trade : rows) {
            equity = equity.add(trade.pnl());
            if (equity.compareTo(peak) > 0) peak = equity;
            BigDecimal dd = peak.subtract(equity);
            if (dd.compareTo(maxDd) > 0) maxDd = dd;
        }
        if (peak.signum() == 0) return 0;
        return maxDd.divide(peak, 6, RoundingMode.HALF_UP).doubleValue();
    }

    private int maxConsecutiveLosses(List<Trade> rows) {
        int current = 0;
        int max = 0;
        for (Trade trade : rows) {
            if (trade.pnl().signum() < 0) {
                current++;
                max = Math.max(max, current);
            } else {
                current = 0;
            }
        }
        return max;
    }

    private double avgR(List<Trade> rows) {
        return rows.stream()
                .map(Trade::rMultiple)
                .filter(Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0);
    }

    private String formatStrategyStats(StrategyStats s) {
        return String.format("%-13s trades=%d WR=%.1f%% EV=%s AvgR=%.2f PF=%.2f MaxDD=%.2f%% MaxLossStreak=%d",
                s.strategy(), s.trades(), s.winRate() * 100,
                s.ev().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                s.avgR(), s.profitFactor(), s.maxDrawdownPct() * 100, s.maxConsecutiveLosses());
    }

}
