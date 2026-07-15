package com.mawai.wiibquant.agent.strategy.turtle;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.strategy.backtest.BacktestResult;
import com.mawai.wiibquant.agent.strategy.backtest.StrategyKlineBacktestEngine;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * TURTLE 参数 sweep runner（六主流币 BTC/ETH/SOL/DOGE/XRP/BNB），defaults=90/15 触价的选参依据。
 *
 * <p>目的不是摘峰值，是验证参数坐在平坦高原上：每币 5m bars 只加载一次，内存并行跑全部组合，
 * 输出六币合并绩效 + 最差币 + SL 出场占比 + 持仓天数。细网格验证 exit=15 是 C/T 双模式脊线、
 * entry 85~100 是平顶；止损消融验证 s2.0 是收益/回撤最优区（放宽止损 PF 不变但资金效率大降）。
 * 结果落 target/ 下不进 git。</p>
 */
class TurtleSweepDbRun {

    private static final String URL = "jdbc:postgresql://localhost:5432/wiib";
    private static final String USER = "mawai";
    private static final String PASSWORD = "LOCAL_PASSWORD";
    private static final List<String> COINS =
            List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "DOGEUSDT", "XRPUSDT", "BNBUSDT");
    private static final BigDecimal BALANCE = new BigDecimal("100000");
    private static final int LEVERAGE = 5;

    private static final long H4 = 4 * 3_600_000L;
    private static final long D1 = 24 * 3_600_000L;

    private static final int ATR_PERIOD = 20;
    private static final double STOP_ATR = 2.0;

    private record Combo(long tf, int entry, int exit, double stopMult, boolean stopEntry) {
        String key() {
            return String.format(Locale.ROOT, "%-3s %3d/%2d s%.1f %s",
                    tfLabel(tf), entry, exit, stopMult, stopEntry ? "T" : "C");
        }
    }

    /** 单币单组合的原始统计，主线程合并，避免并发累加。holdDays* 存总和便于跨币合并求均值。 */
    private record RunStat(Combo combo, String symbol, int trades, int wins,
                           BigDecimal grossWin, BigDecimal grossLoss, double sumR,
                           double retPct, double maxDd, double pf, int slExits,
                           double holdDaysWin, double holdDaysLoss) {}

    /** 止损消融：代表性组合 × stopAtrMult，s6.0≈纯通道退出，回答"2ATR止损是保护还是拖累"。 */
    @Test
    void sweepStopMult() throws Exception {
        double[] mults = {1.5, 2.0, 2.5, 3.0, 4.0, 6.0};
        long[][] cells = {{H4, 55, 20}, {H4, 90, 15}, {H4, 20, 15}, {D1, 30, 20}};
        List<Combo> combos = new ArrayList<>();
        for (long[] cell : cells) {
            for (double m : mults) combos.add(new Combo(cell[0], (int) cell[1], (int) cell[2], m, false));
        }
        execute(combos,
                LocalDate.of(2021, 5, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
                "turtle-sweep-stopmult");
    }

    /** 邻域细网格：90/15 周边加密采样，验证高原平滑非网格伪影；C/T 双模式，全窗。 */
    @Test
    void sweepFineGrid() throws Exception {
        int[] entries = {70, 75, 80, 85, 90, 95, 100, 110, 120};
        int[] exits = {10, 12, 15, 18, 20, 25};
        List<Combo> combos = new ArrayList<>();
        for (boolean stopEntry : new boolean[]{false, true}) {
            for (int entry : entries) {
                for (int exit : exits) combos.add(new Combo(H4, entry, exit, STOP_ATR, stopEntry));
            }
        }
        execute(combos, yearStartUtc(2021), "turtle-sweep-fine");
    }

    private void execute(List<Combo> combos, long tradingStartMs, String outName) throws Exception {
        Connection con = openDb();
        if (con == null) return;

        long maxWarmupMs = 0;
        for (Combo c : combos) {
            maxWarmupMs = Math.max(maxWarmupMs, (long) (Math.max(c.entry(), ATR_PERIOD) + 4) * c.tf());
        }

        List<RunStat> all = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() - 2));
        try (con) {
            for (String symbol : COINS) {
                Long latest = latestCloseTime(con, symbol);
                if (latest == null) {
                    System.out.println(symbol + " 无数据，跳过");
                    continue;
                }
                long end = latest + 1;
                List<KlineBar> bars = loadBars(con, symbol, tradingStartMs - maxWarmupMs, end);
                int warmupBars = (int) bars.stream().filter(b -> b.closeTime() < tradingStartMs).count();
                System.out.printf(Locale.ROOT, "%s bars=%d warmup=%d，跑 %d 组合...%n",
                        symbol, bars.size(), warmupBars, combos.size());

                List<Callable<RunStat>> tasks = new ArrayList<>();
                for (Combo c : combos) {
                    tasks.add(() -> runOne(c, symbol, bars, warmupBars, tradingStartMs, end));
                }
                for (Future<RunStat> f : pool.invokeAll(tasks)) {
                    all.add(f.get());
                }
            }
        } finally {
            pool.shutdown();
        }

        report(combos, all, tradingStartMs, outName);
    }

    private RunStat runOne(Combo c, String symbol, List<KlineBar> bars,
                           int warmupBars, long startMs, long endMs) {
        TurtleParams params = new TurtleParams(c.tf(), c.entry(), c.exit(), ATR_PERIOD, c.stopMult(), c.stopEntry());
        BacktestResult r = new StrategyKlineBacktestEngine(
                new TurtleStrategy(params, List.of(symbol)), symbol, bars,
                BALANCE, LEVERAGE, warmupBars, startMs, endMs).run();
        BigDecimal grossWin = BigDecimal.ZERO;
        BigDecimal grossLoss = BigDecimal.ZERO;
        double sumR = 0;
        int slExits = 0;
        double holdWin = 0;
        double holdLoss = 0;
        for (BacktestResult.Trade t : r.getTrades()) {
            double holdDays = (t.closeBarIndex() - t.barIndex()) * 5.0 / 1440.0;  // 5m bar 数折算天
            if (t.pnl().signum() > 0) {
                grossWin = grossWin.add(t.pnl());
                holdWin += holdDays;
            } else {
                grossLoss = grossLoss.add(t.pnl().abs());
                holdLoss += holdDays;
            }
            if (t.rMultiple() != null) sumR += t.rMultiple().doubleValue();
            if ("SL".equals(t.exitReason())) slExits++;
        }
        return new RunStat(c, symbol, r.totalTrades(), r.wins(), grossWin, grossLoss, sumR,
                r.returnPct(), r.maxDrawdownPct(), r.profitFactor(), slExits, holdWin, holdLoss);
    }

    /** 汇总输出：每组合一行六币合并口径 + 逐币明细文件。 */
    private void report(List<Combo> combos, List<RunStat> all, long startMs, String outName) throws Exception {
        Map<Combo, List<RunStat>> byCombo = new LinkedHashMap<>();
        for (Combo c : combos) byCombo.put(c, new ArrayList<>());
        for (RunStat s : all) byCombo.get(s.combo()).add(s);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "#### TURTLE sweep %s | %s 起 | ATR%d risk=1%% lev=%d taker万5 | 六币合并 ####%n",
                outName, LocalDate.ofEpochDay(startMs / 86_400_000L), ATR_PERIOD, LEVERAGE));
        sb.append(String.format("%-16s %7s %6s %7s %7s %9s %8s %8s %6s %5s %7s %7s%n",
                "combo", "trades", "win%", "poolPF", "avgR", "meanRet%", "meanDD%", "worstPF", "green", "SL%", "holdW(d)", "holdL(d)"));
        for (Map.Entry<Combo, List<RunStat>> e : byCombo.entrySet()) {
            List<RunStat> rs = e.getValue();
            int trades = rs.stream().mapToInt(RunStat::trades).sum();
            int wins = rs.stream().mapToInt(RunStat::wins).sum();
            int slExits = rs.stream().mapToInt(RunStat::slExits).sum();
            BigDecimal gw = rs.stream().map(RunStat::grossWin).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal gl = rs.stream().map(RunStat::grossLoss).reduce(BigDecimal.ZERO, BigDecimal::add);
            double poolPf = gl.signum() == 0 ? 0 : gw.divide(gl, 4, java.math.RoundingMode.HALF_UP).doubleValue();
            double sumR = rs.stream().mapToDouble(RunStat::sumR).sum();
            double meanRet = rs.stream().mapToDouble(RunStat::retPct).average().orElse(0);
            double worstPf = rs.stream().mapToDouble(RunStat::pf).min().orElse(0);
            double meanDd = rs.stream().mapToDouble(RunStat::maxDd).average().orElse(0);
            long green = rs.stream().filter(s -> s.trades() > 0 && s.pf() > 1).count();
            double holdWinSum = rs.stream().mapToDouble(RunStat::holdDaysWin).sum();
            double holdLossSum = rs.stream().mapToDouble(RunStat::holdDaysLoss).sum();
            int losses = trades - wins;
            sb.append(String.format(Locale.ROOT,
                    "%-16s %7d %6.1f %7.3f %7.3f %9.2f %8.2f %8.3f %5d/%d %5.1f %7.1f %7.1f%n",
                    e.getKey().key(), trades, trades == 0 ? 0 : 100.0 * wins / trades,
                    poolPf, trades == 0 ? 0 : sumR / trades, meanRet * 100, meanDd * 100,
                    worstPf, green, rs.size(), trades == 0 ? 0 : 100.0 * slExits / trades,
                    wins == 0 ? 0 : holdWinSum / wins, losses == 0 ? 0 : holdLossSum / losses));
        }

        StringBuilder detail = new StringBuilder();
        detail.append("combo,symbol,trades,winPct,pf,avgR,retPct,maxDdPct,slPct\n");
        for (RunStat s : all) {
            detail.append(String.format(Locale.ROOT, "%s,%s,%d,%.1f,%.3f,%.3f,%.2f,%.2f,%.1f%n",
                    s.combo().key().replace(' ', '_'), s.symbol(), s.trades(),
                    s.trades() == 0 ? 0 : 100.0 * s.wins() / s.trades(), s.pf(),
                    s.trades() == 0 ? 0 : s.sumR() / s.trades(),
                    s.retPct() * 100, s.maxDd() * 100,
                    s.trades() == 0 ? 0 : 100.0 * s.slExits() / s.trades()));
        }

        System.out.println(sb);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", outName + ".txt"), sb.toString());
        Files.writeString(Path.of("target", outName + "-bycoin.csv"), detail.toString());
        System.out.println("已写出 target/" + outName + ".txt 与 -bycoin.csv");
    }

    private static String tfLabel(long tf) {
        return tf == D1 ? "1D" : (tf / 3_600_000L) + "H";
    }

    private static Connection openDb() {
        try {
            return DriverManager.getConnection(URL, USER, PASSWORD);
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过 sweep: " + e.getMessage());
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

    private static long yearStartUtc(int year) {
        return LocalDate.of(year, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
