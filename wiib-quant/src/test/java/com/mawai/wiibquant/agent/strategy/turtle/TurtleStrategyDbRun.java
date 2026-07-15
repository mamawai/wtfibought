package com.mawai.wiibquant.agent.strategy.turtle;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.strategy.backtest.BacktestResult;
import com.mawai.wiibquant.agent.strategy.backtest.StrategyKlineBacktestEngine;
import com.mawai.wiibquant.agent.strategy.core.WindowedMarketView;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * TURTLE 手动 DB 回测 runner。显式指定本类时只读本地 kline_history，不启动 Spring/Redis/外部行情。
 * 口径跟随 TurtleParams.defaults()（4H 90/15、ATR20、2ATR止损）、每笔风险1%、5倍杠杆。
 */
class TurtleStrategyDbRun {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/wiib";
    private static final String DEFAULT_USER = "mawai";
    private static final String DEFAULT_PASSWORD = "LOCAL_PASSWORD";
    private static final String DEFAULT_SYMBOLS = "BTCUSDT,ETHUSDT,SOLUSDT,DOGEUSDT,XRPUSDT";

    private record SegResult(boolean hasGap, BacktestResult result) {}

    /**
     * 连续窗基线：逐币从指定年份跑到库内最后一根，输出多空拆分和整体绩效。
     * -Dturtle.bt.symbols、-Dturtle.bt.fromYear、-Dturtle.bt.balance、-Dturtle.bt.leverage 可覆盖。
     */
    @Test
    void runBaseline() throws Exception {
        List<String> symbols = symbols();
        int fromYear = Integer.getInteger("turtle.bt.fromYear", 2021);
        int leverage = Integer.getInteger("turtle.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("turtle.bt.balance", "100000"));
        TurtleParams params = TurtleParams.defaults();

        Connection con = openDb();
        if (con == null) return;
        try (con) {
            System.out.printf(Locale.ROOT,
                    "%n#### TURTLE 基线 (4H %d/%d, ATR%d×%.1f, risk=1%%, %d~今) ####%n",
                    params.entryLookback(), params.exitLookback(), params.atrPeriod(),
                    params.stopAtrMult(), fromYear);
            System.out.printf("%-10s %7s %7s %8s %9s %8s %8s %8s %6s %6s %5s%n",
                    "symbol", "trades", "win%", "pf", "ret%", "maxDD%", "avgR", "fees", "long", "short", "gap");
            for (String symbol : symbols) {
                Long latest = latestCloseTime(con, symbol);
                if (latest == null) {
                    System.out.printf("%-10s (无数据)%n", symbol);
                    continue;
                }
                SegResult segment = runWindow(con, symbol, params, yearStartUtc(fromYear), latest + 1,
                        balance, leverage, latest);
                if (segment == null) continue;
                BacktestResult r = segment.result();
                long longs = r.getTrades().stream().filter(t -> "LONG".equals(t.side())).count();
                long shorts = r.getTrades().stream().filter(t -> "SHORT".equals(t.side())).count();
                System.out.printf(Locale.ROOT,
                        "%-10s %7d %7.1f %8.3f %9.2f %8.2f %8.3f %8.2f %6d %6d %5s%n",
                        symbol, r.totalTrades(), r.winRate() * 100, r.profitFactor(),
                        r.returnPct() * 100, r.maxDrawdownPct() * 100, r.avgR(),
                        r.totalFees().doubleValue(), longs, shorts, segment.hasGap() ? "Y" : "-");
            }
        }
    }

    /** 逐自然年独立起点体检，防止连续窗总收益掩盖策略阶段性失效。 */
    @Test
    void runYearly() throws Exception {
        int leverage = Integer.getInteger("turtle.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("turtle.bt.balance", "100000"));
        TurtleParams params = TurtleParams.defaults();
        Connection con = openDb();
        if (con == null) return;
        try (con) {
            for (String symbol : symbols()) {
                Long latest = latestCloseTime(con, symbol);
                if (latest == null) continue;
                System.out.printf("%n==== TURTLE %s 逐年 ====%n", symbol);
                System.out.printf("%-6s %7s %7s %8s %9s %8s %8s%n",
                        "year", "trades", "win%", "pf", "ret%", "maxDD%", "avgR");
                for (int year = 2021; year <= 2026; year++) {
                    SegResult segment = runWindow(con, symbol, params,
                            yearStartUtc(year), yearStartUtc(year + 1), balance, leverage, latest);
                    if (segment == null) continue;
                    BacktestResult r = segment.result();
                    System.out.printf(Locale.ROOT, "%-6d %7d %7.1f %8.3f %9.2f %8.2f %8.3f%n",
                            year, r.totalTrades(), r.winRate() * 100, r.profitFactor(),
                            r.returnPct() * 100, r.maxDrawdownPct() * 100, r.avgR());
                }
            }
        }
    }

    private static SegResult runWindow(Connection con, String symbol, TurtleParams params,
                                       long tradingStartMs, long tradingEndExclusive,
                                       BigDecimal balance, int leverage, long latestClose) throws Exception {
        long end = Math.min(tradingEndExclusive, latestClose + 1);
        if (tradingStartMs >= end) return null;
        long warmupMs = warmupMs(params);
        List<KlineBar> bars = loadBars(con, symbol, tradingStartMs - warmupMs, end);
        if (bars.isEmpty()) return null;
        boolean gap = WindowedMarketView.firstBaseGapDescription(bars).isPresent();
        int warmupBars = (int) bars.stream().filter(b -> b.closeTime() < tradingStartMs).count();
        TurtleStrategy strategy = new TurtleStrategy(params, List.of(symbol));
        BacktestResult result = new StrategyKlineBacktestEngine(
                strategy, symbol, bars, balance, leverage, warmupBars, tradingStartMs, end).run();
        return new SegResult(gap, result);
    }

    private static long warmupMs(TurtleParams p) {
        return (long) (Math.max(p.entryLookback(), p.atrPeriod()) + 4) * p.decisionTfMillis();
    }

    private static Connection openDb() {
        try {
            return DriverManager.getConnection(
                    System.getProperty("turtle.bt.dbUrl", DEFAULT_URL),
                    System.getProperty("turtle.bt.dbUser", DEFAULT_USER),
                    System.getProperty("turtle.bt.dbPassword", DEFAULT_PASSWORD));
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过 TURTLE DB 回测: " + e.getMessage());
            return null;
        }
    }

    private static Long latestCloseTime(Connection con, String symbol) throws Exception {
        String sql = """
                SELECT close_time FROM kline_history
                WHERE symbol=? AND interval_code='5m'
                ORDER BY open_time DESC LIMIT 1
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static List<KlineBar> loadBars(Connection con, String symbol, long fromMs, long toMs) throws Exception {
        String sql = """
                SELECT open_time, close_time, open, high, low, close, volume
                FROM kline_history
                WHERE symbol=? AND interval_code='5m' AND open_time>=? AND open_time<?
                ORDER BY open_time
                """;
        List<KlineBar> bars = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setLong(2, fromMs);
            ps.setLong(3, toMs);
            ps.setFetchSize(5000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    bars.add(new KlineBar(rs.getLong(1), rs.getLong(2),
                            rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5),
                            rs.getBigDecimal(6), rs.getBigDecimal(7)));
                }
            }
        }
        return bars;
    }

    private static long yearStartUtc(int year) {
        return LocalDate.of(year, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static List<String> symbols() {
        String raw = System.getProperty("turtle.bt.symbols", DEFAULT_SYMBOLS);
        List<String> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            String symbol = token.trim().toUpperCase(Locale.ROOT);
            if (!symbol.isBlank()) out.add(symbol);
        }
        return out.isEmpty() ? List.of("BTCUSDT") : List.copyOf(out);
    }
}
