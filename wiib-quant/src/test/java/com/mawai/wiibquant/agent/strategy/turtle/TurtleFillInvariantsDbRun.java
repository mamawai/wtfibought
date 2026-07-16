package com.mawai.wiibquant.agent.strategy.turtle;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.strategy.backtest.BacktestResult;
import com.mawai.wiibquant.agent.strategy.backtest.StrategyKlineBacktestEngine;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 成交不变量校验器：把每笔成交回放到对应 5m K线上，验证回测撮合没有"拿到市场上不存在的价格"。
 *
 * <p>校验项：入场/出场价必须落在成交那根 5m K线的 [low, high] 内；收盘确认模式入场必须等于
 * 下根开盘价；触价模式入场不能优于 max(开盘, 触发价)；跨根 SL 出场不能优于出场根开盘价
 * （开盘已跳空穿越止损时按止损价成交=拿到比市场更好的价，即已知的乐观偏差，此处量化其规模）；
 * 通道/强制平仓必须等于出场根收盘价。默认滑点参数下运行（-Dbacktest.slippageBps>0 会破坏等式检查）。</p>
 */
class TurtleFillInvariantsDbRun {

    private static final String URL = "jdbc:postgresql://localhost:5432/wiib";
    private static final String USER = "mawai";
    private static final String PASSWORD = com.mawai.wiibquant.LocalEnv.dbPassword();
    private static final List<String> COINS =
            List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "DOGEUSDT", "XRPUSDT", "BNBUSDT");
    private static final BigDecimal BALANCE = new BigDecimal("100000");
    private static final int LEVERAGE = 5;
    private static final long H4 = 4 * 3_600_000L;

    private record Config(String label, TurtleParams params) {}

    private record Violation(String type, String symbol, String detail) {}

    @Test
    void verifyFillInvariants() throws Exception {
        List<Config> configs = List.of(
                new Config("4H 55/20 C", new TurtleParams(H4, 55, 20, 20, 2.0, false)),
                new Config("4H 90/15 C", new TurtleParams(H4, 90, 15, 20, 2.0, false)),
                new Config("4H 90/15 T", new TurtleParams(H4, 90, 15, 20, 2.0, true)));
        long start = LocalDate.of(2021, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        long warmupMs = (90 + 4) * H4;

        Connection con = openDb();
        if (con == null) return;
        try (con) {
            for (Config cfg : configs) {
                int totalTrades = 0;
                Map<String, Integer> violationCounts = new LinkedHashMap<>();
                List<Violation> samples = new ArrayList<>();
                BigDecimal slGapOptimism = BigDecimal.ZERO;   // SL跳空乐观偏差合计(U)，与总pnl同量纲
                BigDecimal totalPnl = BigDecimal.ZERO;

                for (String symbol : COINS) {
                    Long latest = latestCloseTime(con, symbol);
                    if (latest == null) continue;
                    List<KlineBar> bars = loadBars(con, symbol, start - warmupMs, latest + 1);
                    int warmupBars = (int) bars.stream().filter(b -> b.closeTime() < start).count();
                    BacktestResult r = new StrategyKlineBacktestEngine(
                            new TurtleStrategy(cfg.params(), List.of(symbol)), symbol, bars,
                            BALANCE, LEVERAGE, warmupBars, start, latest + 1).run();

                    totalTrades += r.totalTrades();
                    for (BacktestResult.Trade t : r.getTrades()) {
                        totalPnl = totalPnl.add(t.pnl());
                        slGapOptimism = slGapOptimism.add(
                                checkTrade(cfg, symbol, t, bars, violationCounts, samples));
                    }
                }

                System.out.printf(Locale.ROOT, "%n#### %s | trades=%d totalPnl=%.2f ####%n",
                        cfg.label(), totalTrades, totalPnl.doubleValue());
                if (violationCounts.isEmpty()) {
                    System.out.println("全部不变量通过，无违规");
                } else {
                    violationCounts.forEach((k, v) -> System.out.printf("%-20s %d 笔%n", k, v));
                    System.out.printf(Locale.ROOT, "SL跳空乐观偏差合计: %.2f U (占总pnl %.3f%%)%n",
                            slGapOptimism.doubleValue(),
                            totalPnl.signum() == 0 ? 0 : slGapOptimism.doubleValue() / Math.abs(totalPnl.doubleValue()) * 100);
                    samples.stream().limit(5).forEach(v ->
                            System.out.printf("  [%s] %s %s%n", v.type(), v.symbol(), v.detail()));
                }
            }
        }
    }

    /** 单笔校验，返回该笔的 SL 跳空乐观偏差（无违规返回0）。 */
    private BigDecimal checkTrade(Config cfg, String symbol, BacktestResult.Trade t,
                                  List<KlineBar> bars, Map<String, Integer> counts, List<Violation> samples) {
        KlineBar eb = bars.get(t.barIndex());
        KlineBar xb = bars.get(t.closeBarIndex());
        boolean isLong = "LONG".equals(t.side());
        BigDecimal entry = t.entryPrice();
        BigDecimal exit = t.exitPrice();

        if (entry.compareTo(eb.low()) < 0 || entry.compareTo(eb.high()) > 0) {
            record(counts, samples, "ENTRY_OUT_OF_RANGE", symbol, trade(t) + " 入场bar=" + ohlc(eb));
        }
        if (exit.compareTo(xb.low()) < 0 || exit.compareTo(xb.high()) > 0) {
            record(counts, samples, "EXIT_OUT_OF_RANGE", symbol, trade(t) + " 出场bar=" + ohlc(xb));
        }
        if (!cfg.params().stopEntry() && entry.compareTo(eb.open()) != 0) {
            record(counts, samples, "MARKET_ENTRY_NOT_OPEN", symbol, trade(t) + " 入场bar=" + ohlc(eb));
        }
        if (cfg.params().stopEntry()) {
            // 触价成交 = max(开盘,触发)（多）/ min(开盘,触发)（空），不可能优于开盘价
            boolean ok = isLong ? entry.compareTo(eb.open()) >= 0 : entry.compareTo(eb.open()) <= 0;
            if (!ok) record(counts, samples, "STOP_ENTRY_BETTER_THAN_OPEN", symbol, trade(t) + " 入场bar=" + ohlc(eb));
        }

        switch (t.exitReason()) {
            case "SL" -> {
                // 跨根持仓：SL 成交不可能优于出场根开盘价（开盘已穿越止损时真实成交≈开盘）。
                // 同根开平不适用：入场点在盘中，之后段内触发 SL 可以劣于/优于开盘。
                if (t.closeBarIndex() != t.barIndex()) {
                    boolean ok = isLong ? exit.compareTo(xb.open()) <= 0 : exit.compareTo(xb.open()) >= 0;
                    if (!ok) {
                        record(counts, samples, "SL_GAP_OPTIMISM", symbol, trade(t) + " 出场bar=" + ohlc(xb));
                        BigDecimal perUnit = isLong ? exit.subtract(xb.open()) : xb.open().subtract(exit);
                        return perUnit.multiply(t.quantity());
                    }
                }
            }
            case "CHANNEL_EXIT", "FORCE_CLOSE", "SIGNAL_CLOSE" -> {
                if (exit.compareTo(xb.close()) != 0) {
                    record(counts, samples, "EXIT_NOT_CLOSE", symbol, trade(t) + " 出场bar=" + ohlc(xb));
                }
            }
            default -> record(counts, samples, "UNKNOWN_EXIT_" + t.exitReason(), symbol, trade(t));
        }
        return BigDecimal.ZERO;
    }

    private static void record(Map<String, Integer> counts, List<Violation> samples,
                               String type, String symbol, String detail) {
        counts.merge(type, 1, Integer::sum);
        samples.add(new Violation(type, symbol, detail));
    }

    private static String trade(BacktestResult.Trade t) {
        return String.format(Locale.ROOT, "%s %s entry=%s exit=%s bar[%d->%d]",
                t.side(), t.exitReason(), t.entryPrice().toPlainString(),
                t.exitPrice().toPlainString(), t.barIndex(), t.closeBarIndex());
    }

    private static String ohlc(KlineBar b) {
        return String.format(Locale.ROOT, "O=%s H=%s L=%s C=%s",
                b.open().toPlainString(), b.high().toPlainString(),
                b.low().toPlainString(), b.close().toPlainString());
    }

    private static Connection openDb() {
        try {
            return DriverManager.getConnection(URL, USER, PASSWORD);
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过不变量校验: " + e.getMessage());
            return null;
        }
    }

    private static Long latestCloseTime(Connection con, String symbol) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT close_time FROM kline_history WHERE symbol=? AND interval_code='5m' ORDER BY open_time DESC LIMIT 1")) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static List<KlineBar> loadBars(Connection con, String symbol, long fromMs, long toMs) throws Exception {
        List<KlineBar> bars = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT open_time, close_time, open, high, low, close, volume FROM kline_history "
                        + "WHERE symbol=? AND interval_code='5m' AND open_time>=? AND open_time<? ORDER BY open_time")) {
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
}
