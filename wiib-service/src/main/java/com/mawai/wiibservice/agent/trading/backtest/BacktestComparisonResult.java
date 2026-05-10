package com.mawai.wiibservice.agent.trading.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 同一段历史数据的新旧退出引擎对比。
 * 正数 delta 统一表示 playbook - legacy，便于直接看新引擎是否更晚出、收益是否更高。
 */
public record BacktestComparisonResult(
        String symbol,
        String mode,
        BacktestResult legacy,
        BacktestResult playbook,
        OverallDelta overallDelta,
        List<StrategyDelta> strategyDeltas,
        TradeDiffSummary tradeDiffSummary,
        List<TradeDiff> tradeDiffs
) {

    private static final List<String> STRATEGIES = List.of("LEGACY_TREND", "MR", "BREAKOUT");

    public static BacktestComparisonResult compare(String symbol, String mode,
                                                   BacktestResult legacy,
                                                   BacktestResult playbook) {
        List<BacktestResult.Trade> legacyTrades = aggregateLifecycleTrades(legacy.getTrades());
        List<BacktestResult.Trade> playbookTrades = aggregateLifecycleTrades(playbook.getTrades());
        List<TradeDiff> diffs = diffTrades(legacyTrades, playbookTrades);
        return new BacktestComparisonResult(
                symbol,
                mode,
                legacy,
                playbook,
                overallDelta(legacy, playbook, legacyTrades, playbookTrades),
                strategyDeltas(legacy, playbook, legacyTrades, playbookTrades),
                summarizeDiffs(legacyTrades, playbookTrades, diffs),
                List.copyOf(diffs)
        );
    }

    private static OverallDelta overallDelta(BacktestResult legacy, BacktestResult playbook,
                                             List<BacktestResult.Trade> legacyTrades,
                                             List<BacktestResult.Trade> playbookTrades) {
        TradeStats legacyStats = tradeStats(legacyTrades, null);
        TradeStats playbookStats = tradeStats(playbookTrades, null);
        return new OverallDelta(
                playbookStats.trades() - legacyStats.trades(),
                playbookStats.netPnl().subtract(legacyStats.netPnl()),
                playbook.returnPct() - legacy.returnPct(),
                playbookStats.winRate() - legacyStats.winRate(),
                playbookStats.avgR() - legacyStats.avgR(),
                playbook.maxDrawdownPct() - legacy.maxDrawdownPct(),
                playbookStats.avgHoldBars() - legacyStats.avgHoldBars()
        );
    }

    private static List<StrategyDelta> strategyDeltas(BacktestResult legacy, BacktestResult playbook,
                                                      List<BacktestResult.Trade> legacyTrades,
                                                      List<BacktestResult.Trade> playbookTrades) {
        List<StrategyDelta> rows = new ArrayList<>();
        for (String strategy : STRATEGIES) {
            TradeStats oldStats = tradeStats(legacyTrades, strategy);
            TradeStats newStats = tradeStats(playbookTrades, strategy);
            rows.add(new StrategyDelta(
                    strategy,
                    oldStats.trades(),
                    newStats.trades(),
                    newStats.trades() - oldStats.trades(),
                    newStats.netPnl().subtract(oldStats.netPnl()),
                    newStats.winRate() - oldStats.winRate(),
                    newStats.avgR() - oldStats.avgR(),
                    playbook.statsByStrategy(strategy).maxDrawdownPct()
                            - legacy.statsByStrategy(strategy).maxDrawdownPct(),
                    newStats.avgHoldBars() - oldStats.avgHoldBars(),
                    oldStats.losses(),
                    newStats.losses(),
                    newStats.losses() - oldStats.losses()
            ));
        }
        return List.copyOf(rows);
    }

    private static List<BacktestResult.Trade> aggregateLifecycleTrades(List<BacktestResult.Trade> trades) {
        Map<String, List<BacktestResult.Trade>> byKey = new LinkedHashMap<>();
        for (BacktestResult.Trade trade : trades) {
            byKey.computeIfAbsent(matchKey(trade), ignored -> new ArrayList<>()).add(trade);
        }

        List<BacktestResult.Trade> result = new ArrayList<>();
        for (List<BacktestResult.Trade> fills : byKey.values()) {
            result.add(aggregateLifecycleTrade(fills));
        }
        return result;
    }

    private static BacktestResult.Trade aggregateLifecycleTrade(List<BacktestResult.Trade> fills) {
        if (fills.size() == 1) {
            BacktestResult.Trade trade = fills.getFirst();
            return new BacktestResult.Trade(
                    trade.barIndex(), trade.closeBarIndex(), trade.side(), normalizeStrategy(trade.strategy()),
                    trade.entryPrice(), trade.exitPrice(), trade.quantity(), trade.leverage(),
                    trade.pnl(), trade.fee(), trade.rMultiple(), trade.exitReason()
            );
        }

        BacktestResult.Trade first = fills.getFirst();
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalExitNotional = BigDecimal.ZERO;
        BigDecimal totalPnl = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        BigDecimal weightedR = BigDecimal.ZERO;
        BigDecimal rWeight = BigDecimal.ZERO;
        int closeBarIndex = first.closeBarIndex();
        LinkedHashSet<String> exitReasons = new LinkedHashSet<>();

        for (BacktestResult.Trade fill : fills) {
            BigDecimal qty = fill.quantity() != null ? fill.quantity() : BigDecimal.ZERO;
            totalQty = totalQty.add(qty);
            if (fill.exitPrice() != null && qty.signum() > 0) {
                totalExitNotional = totalExitNotional.add(fill.exitPrice().multiply(qty));
            }
            totalPnl = totalPnl.add(fill.pnl() != null ? fill.pnl() : BigDecimal.ZERO);
            totalFee = totalFee.add(fill.fee() != null ? fill.fee() : BigDecimal.ZERO);
            if (fill.rMultiple() != null && qty.signum() > 0) {
                weightedR = weightedR.add(fill.rMultiple().multiply(qty));
                rWeight = rWeight.add(qty);
            }
            closeBarIndex = Math.max(closeBarIndex, fill.closeBarIndex());
            if (fill.exitReason() != null && !fill.exitReason().isBlank()) {
                exitReasons.add(fill.exitReason());
            }
        }

        BigDecimal exitPrice = totalQty.signum() > 0
                ? totalExitNotional.divide(totalQty, 8, RoundingMode.HALF_UP)
                : fills.getLast().exitPrice();
        BigDecimal rMultiple = rWeight.signum() > 0
                ? weightedR.divide(rWeight, 8, RoundingMode.HALF_UP)
                : null;
        String exitReason = exitReasons.isEmpty() ? fills.getLast().exitReason() : String.join("+", exitReasons);

        // Playbook 部分平仓会产生多条 fill；对比新旧退出效果时按同一入场生命周期聚合。
        return new BacktestResult.Trade(
                first.barIndex(),
                closeBarIndex,
                first.side(),
                normalizeStrategy(first.strategy()),
                first.entryPrice(),
                exitPrice,
                totalQty,
                first.leverage(),
                totalPnl,
                totalFee,
                rMultiple,
                exitReason
        );
    }

    private static List<TradeDiff> diffTrades(List<BacktestResult.Trade> legacyTrades,
                                              List<BacktestResult.Trade> playbookTrades) {
        Map<String, ArrayDeque<BacktestResult.Trade>> legacyByKey = new LinkedHashMap<>();
        for (BacktestResult.Trade trade : legacyTrades) {
            legacyByKey.computeIfAbsent(matchKey(trade), ignored -> new ArrayDeque<>()).addLast(trade);
        }

        List<TradeDiff> diffs = new ArrayList<>();
        for (BacktestResult.Trade playbook : playbookTrades) {
            String key = matchKey(playbook);
            ArrayDeque<BacktestResult.Trade> candidates = legacyByKey.get(key);
            BacktestResult.Trade legacy = candidates != null ? candidates.pollFirst() : null;
            if (legacy != null) {
                diffs.add(matchedDiff(key, legacy, playbook));
            } else {
                diffs.add(playbookOnlyDiff(key, playbook));
            }
        }

        for (Map.Entry<String, ArrayDeque<BacktestResult.Trade>> entry : legacyByKey.entrySet()) {
            while (!entry.getValue().isEmpty()) {
                diffs.add(legacyOnlyDiff(entry.getKey(), entry.getValue().pollFirst()));
            }
        }
        return diffs;
    }

    private static TradeDiff matchedDiff(String key, BacktestResult.Trade legacy, BacktestResult.Trade playbook) {
        int closeBarDelta = playbook.closeBarIndex() - legacy.closeBarIndex();
        BigDecimal pnlDelta = playbook.pnl().subtract(legacy.pnl());
        Double legacyR = toDouble(legacy.rMultiple());
        Double playbookR = toDouble(playbook.rMultiple());
        Double rDelta = legacyR != null && playbookR != null ? playbookR - legacyR : null;
        return new TradeDiff(
                "MATCHED",
                key,
                normalizeStrategy(playbook.strategy()),
                playbook.side(),
                playbook.barIndex(),
                legacy.closeBarIndex(),
                playbook.closeBarIndex(),
                closeBarDelta,
                legacy.pnl(),
                playbook.pnl(),
                pnlDelta,
                legacyR,
                playbookR,
                rDelta,
                legacy.exitReason(),
                playbook.exitReason()
        );
    }

    private static TradeDiff legacyOnlyDiff(String key, BacktestResult.Trade legacy) {
        return new TradeDiff(
                "LEGACY_ONLY",
                key,
                normalizeStrategy(legacy.strategy()),
                legacy.side(),
                legacy.barIndex(),
                legacy.closeBarIndex(),
                null,
                null,
                legacy.pnl(),
                null,
                null,
                toDouble(legacy.rMultiple()),
                null,
                null,
                legacy.exitReason(),
                null
        );
    }

    private static TradeDiff playbookOnlyDiff(String key, BacktestResult.Trade playbook) {
        return new TradeDiff(
                "PLAYBOOK_ONLY",
                key,
                normalizeStrategy(playbook.strategy()),
                playbook.side(),
                playbook.barIndex(),
                null,
                playbook.closeBarIndex(),
                null,
                null,
                playbook.pnl(),
                null,
                null,
                toDouble(playbook.rMultiple()),
                null,
                null,
                playbook.exitReason()
        );
    }

    private static TradeDiffSummary summarizeDiffs(List<BacktestResult.Trade> legacyTrades,
                                                   List<BacktestResult.Trade> playbookTrades,
                                                   List<TradeDiff> diffs) {
        List<TradeDiff> matched = diffs.stream()
                .filter(d -> "MATCHED".equals(d.status()))
                .toList();
        int playbookEarlier = (int) matched.stream()
                .filter(d -> d.closeBarDelta() != null && d.closeBarDelta() < 0)
                .count();
        int playbookLater = (int) matched.stream()
                .filter(d -> d.closeBarDelta() != null && d.closeBarDelta() > 0)
                .count();
        int sameCloseBar = (int) matched.stream()
                .filter(d -> d.closeBarDelta() != null && d.closeBarDelta() == 0)
                .count();
        int breakoutRunnerImproved = (int) matched.stream()
                .filter(d -> "BREAKOUT".equals(d.strategy()))
                .filter(d -> d.closeBarDelta() != null && d.closeBarDelta() > 0)
                .filter(d -> d.pnlDelta() != null && d.pnlDelta().signum() > 0)
                .count();

        return new TradeDiffSummary(
                matched.size(),
                (int) diffs.stream().filter(d -> "LEGACY_ONLY".equals(d.status())).count(),
                (int) diffs.stream().filter(d -> "PLAYBOOK_ONLY".equals(d.status())).count(),
                playbookEarlier,
                playbookLater,
                sameCloseBar,
                averageCloseBarDelta(matched),
                averagePnlDelta(matched),
                losingTrades(tradesByStrategy(legacyTrades, "BREAKOUT")),
                losingTrades(tradesByStrategy(playbookTrades, "BREAKOUT")),
                losingPnl(tradesByStrategy(playbookTrades, "BREAKOUT"))
                        .subtract(losingPnl(tradesByStrategy(legacyTrades, "BREAKOUT"))),
                breakoutRunnerImproved,
                tradeStats(playbookTrades, "MR").avgHoldBars() - tradeStats(legacyTrades, "MR").avgHoldBars(),
                tradeStats(playbookTrades, "LEGACY_TREND").avgHoldBars()
                        - tradeStats(legacyTrades, "LEGACY_TREND").avgHoldBars()
        );
    }

    private static String matchKey(BacktestResult.Trade trade) {
        return normalizeStrategy(trade.strategy()) + "|" + trade.side() + "|" + trade.barIndex();
    }

    private static String normalizeStrategy(String raw) {
        if (raw == null || raw.isBlank()) return "UNKNOWN";
        String value = raw.trim();
        int start = value.indexOf("[Strategy-");
        if (start >= 0) {
            int end = value.indexOf(']', start);
            if (end > start) value = value.substring(start + "[Strategy-".length(), end);
        }
        if ("MEAN_REVERSION".equals(value)) return "MR";
        if ("TREND".equals(value) || "LEGACY-TREND".equals(value)) return "LEGACY_TREND";
        return value;
    }

    private static double averageCloseBarDelta(List<TradeDiff> matched) {
        return matched.stream()
                .filter(d -> d.closeBarDelta() != null)
                .mapToInt(TradeDiff::closeBarDelta)
                .average()
                .orElse(0);
    }

    private static BigDecimal averagePnlDelta(List<TradeDiff> matched) {
        if (matched.isEmpty()) return BigDecimal.ZERO;
        BigDecimal total = matched.stream()
                .map(TradeDiff::pnlDelta)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(matched.size()), 8, RoundingMode.HALF_UP);
    }

    private static int losingTrades(List<BacktestResult.Trade> trades) {
        return (int) trades.stream().filter(t -> t.pnl().signum() < 0).count();
    }

    private static BigDecimal losingPnl(List<BacktestResult.Trade> trades) {
        return trades.stream()
                .map(BacktestResult.Trade::pnl)
                .filter(v -> v.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<BacktestResult.Trade> tradesByStrategy(List<BacktestResult.Trade> trades, String strategy) {
        return trades.stream()
                .filter(t -> strategy.equals(normalizeStrategy(t.strategy())))
                .toList();
    }

    private static TradeStats tradeStats(List<BacktestResult.Trade> trades, String strategy) {
        List<BacktestResult.Trade> rows = strategy == null ? trades : tradesByStrategy(trades, strategy);
        int samples = rows.size();
        int wins = (int) rows.stream().filter(t -> t.pnl().signum() > 0).count();
        int losses = (int) rows.stream().filter(t -> t.pnl().signum() < 0).count();
        BigDecimal net = rows.stream()
                .map(BacktestResult.Trade::pnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        double avgR = rows.stream()
                .map(BacktestResult.Trade::rMultiple)
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0);
        double avgHoldBars = samples == 0
                ? 0
                : rows.stream().mapToInt(t -> t.closeBarIndex() - t.barIndex()).average().orElse(0);
        return new TradeStats(
                samples,
                wins,
                losses,
                samples == 0 ? 0 : (double) wins / samples,
                net,
                avgR,
                avgHoldBars
        );
    }

    private static Double toDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : null;
    }

    private record TradeStats(
            int trades,
            int wins,
            int losses,
            double winRate,
            BigDecimal netPnl,
            double avgR,
            double avgHoldBars
    ) {}

    public record OverallDelta(
            int totalTradesDelta,
            BigDecimal netProfitDelta,
            double returnPctDelta,
            double winRateDelta,
            double avgRDelta,
            double maxDrawdownPctDelta,
            double avgHoldBarsDelta
    ) {}

    public record StrategyDelta(
            String strategy,
            int legacyTrades,
            int playbookTrades,
            int tradesDelta,
            BigDecimal netPnlDelta,
            double winRateDelta,
            double avgRDelta,
            double maxDrawdownPctDelta,
            double avgHoldBarsDelta,
            int legacyLosses,
            int playbookLosses,
            int lossesDelta
    ) {}

    public record TradeDiffSummary(
            int matchedTrades,
            int legacyOnlyTrades,
            int playbookOnlyTrades,
            int playbookEarlierTrades,
            int playbookLaterTrades,
            int sameCloseBarTrades,
            double avgCloseBarDelta,
            BigDecimal avgPnlDelta,
            int legacyBreakoutLosingTrades,
            int playbookBreakoutLosingTrades,
            BigDecimal breakoutLosingPnlDelta,
            int breakoutRunnerImprovedTrades,
            double mrAvgHoldBarsDelta,
            double trendAvgHoldBarsDelta
    ) {}

    public record TradeDiff(
            String status,
            String matchKey,
            String strategy,
            String side,
            Integer entryBarIndex,
            Integer legacyCloseBarIndex,
            Integer playbookCloseBarIndex,
            Integer closeBarDelta,
            BigDecimal legacyPnl,
            BigDecimal playbookPnl,
            BigDecimal pnlDelta,
            Double legacyR,
            Double playbookR,
            Double rDelta,
            String legacyExitReason,
            String playbookExitReason
    ) {}
}
