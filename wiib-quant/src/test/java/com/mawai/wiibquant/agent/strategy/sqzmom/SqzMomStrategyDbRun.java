package com.mawai.wiibquant.agent.strategy.sqzmom;

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
 * 手动 SQZMOM DB 回测 runner（定稿配置：4H 仅空头，见 SqueezeMomentumStrategy 类注释）。
 * 显式 -Dtest=SqzMomStrategyDbRun 时直连 Postgres 读 kline_history，绕开 Spring/Redis/外部行情。
 * 建议统一加 -Dbacktest.slippageBps=5（部署判决口径）。
 */
class SqzMomStrategyDbRun {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/wiib";
    private static final String DEFAULT_USER = "mawai";
    private static final String DEFAULT_PASSWORD = "LOCAL_PASSWORD";
    private static final int[] CHECK_YEARS = {2021, 2022, 2023, 2024, 2025, 2026};
    /** 部署篮子（多币消融+双口径过线+主流流动性筛出）。 */
    private static final String DEPLOY_SYMBOLS = "SOLUSDT,DOGEUSDT,XRPUSDT";

    private record SegResult(boolean hasGap, BacktestResult r) {}

    /**
     * 逐年体检：部署篮子逐自然年总览，纯证伪、不挑窗、不调参。
     *
     * 跑法：mvn -pl wiib-quant -am test -Dtest=SqzMomStrategyDbRun#runYearlyCheck -DskipTests=false \
     *       -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false -Dbacktest.slippageBps=5
     */
    @Test
    void runYearlyCheck() throws Exception {
        List<String> symbols = symbols(DEPLOY_SYMBOLS);
        int leverage = Integer.getInteger("sqzmom.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("sqzmom.bt.balance", "100000"));
        SqzMomParams p = SqzMomParams.defaults();

        Connection con = openDb();
        if (con == null) return;
        try (con) {
            System.out.printf("%n#### SQZMOM 逐年体检 (4H仅空头, 1%%风险) balance=%s leverage=%d symbols=%s ####%n",
                    balance.toPlainString(), leverage, symbols);
            for (String symbol : symbols) {
                Long latest = latestCloseTime(con, symbol);
                if (latest == null) {
                    System.out.printf("%s 无数据%n", symbol);
                    continue;
                }
                System.out.printf("%n==== %s ====%n", symbol);
                System.out.printf("%-8s %7s %7s %8s %9s %8s %8s %5s%n",
                        "year", "trades", "win%", "pf", "ret%", "avgR", "sharpe", "gap");
                int posYears = 0, validYears = 0;
                double retSum = 0;
                for (int year : CHECK_YEARS) {
                    SegResult sr = runWindow(con, symbol, p,
                            yearStartUtc(year), yearStartUtc(year + 1), balance, leverage, latest);
                    if (sr == null) continue;
                    BacktestResult r = sr.r();
                    System.out.printf(Locale.ROOT, "%-8d %7d %7.1f %8.3f %9.2f %8.3f %8.3f %5s%n",
                            year, r.totalTrades(), r.winRate() * 100, r.profitFactor(),
                            r.returnPct() * 100, r.avgR(), r.sharpeRatio(), sr.hasGap() ? "Y" : "-");
                    validYears++;
                    retSum += r.returnPct() * 100;   // 各年同起点独立回测，%可直接相加看累计
                    if (r.returnPct() > 0) posYears++;
                }
                System.out.printf(Locale.ROOT, "=> %s posYears=%d/%d retSum=%.2f%%%n",
                        symbol, posYears, validYears, retSum);
            }
        }
    }

    /**
     * 多币横截面裁决：每币 6 个自然年独立窗汇总一行，验证"edge 是否仍是市场层面现象"。
     * 判据：多数币 retSum>0 且费后 pf>1；只剩零星币存活 → edge 衰退预警。
     *
     * 跑法：mvn -pl wiib-quant -am test -Dtest=SqzMomStrategyDbRun#runMultiCoin4h -DskipTests=false \
     *       -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false -Dbacktest.slippageBps=5
     */
    @Test
    void runMultiCoin4h() throws Exception {
        List<String> symbols = symbols(System.getProperty("sqzmom.bt.symbols",
                "BTCUSDT,ETHUSDT,SOLUSDT,DOGEUSDT,BNBUSDT,XRPUSDT,ADAUSDT,BCHUSDT,DOTUSDT,"
                        + "UNIUSDT,LINKUSDT,ATOMUSDT,AVAXUSDT,FILUSDT,LTCUSDT"));
        int leverage = Integer.getInteger("sqzmom.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("sqzmom.bt.balance", "100000"));
        SqzMomParams p = SqzMomParams.defaults();

        Connection con = openDb();
        if (con == null) return;
        try (con) {
            System.out.printf("%n#### SQZMOM 多币4H裁决 (仅空头, 1%%风险, 2021-2026逐年汇总) leverage=%d ####%n",
                    leverage);
            System.out.printf("%-10s %7s %7s %7s %8s %9s %9s%n",
                    "symbol", "trades", "win%", "pf", "avgR", "retSum%", "posYears");
            int aliveCoins = 0, totalCoins = 0;
            for (String symbol : symbols) {
                Long latest = latestCloseTime(con, symbol);
                if (latest == null) {
                    System.out.printf("%-10s (无数据)%n", symbol);
                    continue;
                }
                long trades = 0, wins = 0;
                int posYears = 0, validYears = 0;
                double retSum = 0, grossWin = 0, grossLoss = 0, rSum = 0;
                long rCount = 0;
                for (int year : CHECK_YEARS) {
                    SegResult sr = runWindow(con, symbol, p,
                            yearStartUtc(year), yearStartUtc(year + 1), balance, leverage, latest);
                    if (sr == null) continue;
                    BacktestResult r = sr.r();
                    trades += r.totalTrades();
                    wins += r.wins();
                    validYears++;
                    retSum += r.returnPct() * 100;
                    if (r.returnPct() > 0) posYears++;
                    for (BacktestResult.Trade t : r.getTrades()) {
                        double pnl = t.pnl().doubleValue();
                        if (pnl > 0) grossWin += pnl; else grossLoss -= pnl;
                        if (t.rMultiple() != null) {
                            rSum += t.rMultiple().doubleValue();
                            rCount++;
                        }
                    }
                }
                double pf = grossLoss > 0 ? grossWin / grossLoss : Double.NaN;
                boolean alive = retSum > 0 && pf > 1;
                totalCoins++;
                if (alive) aliveCoins++;
                System.out.printf(Locale.ROOT, "%-10s %7d %7.1f %7.3f %8.3f %9.2f %6d/%d %s%n",
                        symbol, trades, trades == 0 ? 0 : 100.0 * wins / trades, pf,
                        rCount == 0 ? 0 : rSum / rCount, retSum, posYears, validYears, alive ? "✓" : "");
            }
            System.out.printf(Locale.ROOT, "%n=> 存活币数=%d/%d (判据: retSum>0 且 费后pf>1)%n",
                    aliveCoins, totalCoins);
        }
    }

    /**
     * 单币部署口径模拟：连续窗复利，仓位=权益×marginPct、命令行杠杆，打逐年盈亏+近8笔明细。
     *
     * 跑法：mvn -pl wiib-quant -am test -Dtest=SqzMomStrategyDbRun#runDeploySim -DskipTests=false \
     *   -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false -Dbacktest.slippageBps=5 \
     *   -Dsqzmom.bt.symbols=SOLUSDT -Dsqzmom.bt.balance=1000 -Dsqzmom.bt.leverage=5 \
     *   -Dbacktest.marginPctOfEquity=0.05
     */
    @Test
    void runDeploySim() throws Exception {
        String symbol = symbols(System.getProperty("sqzmom.bt.symbols", "SOLUSDT")).getFirst();
        int leverage = Integer.getInteger("sqzmom.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("sqzmom.bt.balance", "1000"));
        double marginPct = Double.parseDouble(System.getProperty("backtest.marginPctOfEquity", "0"));
        int fromYear = Integer.getInteger("sqzmom.bt.fromYear", 2024);
        SqzMomParams p = SqzMomParams.defaults();

        Connection con = openDb();
        if (con == null) return;
        try (con) {
            Long latest = latestCloseTime(con, symbol);
            if (latest == null) {
                System.out.printf("%s 无数据%n", symbol);
                return;
            }
            SegResult sr = runWindow(con, symbol, p, yearStartUtc(fromYear), latest + 1,
                    balance, leverage, latest);
            if (sr == null) {
                System.out.printf("%s 窗口无数据%n", symbol);
                return;
            }
            BacktestResult r = sr.r();
            String sizing = marginPct > 0
                    ? String.format(Locale.ROOT, "每仓保证金=%.0f%%权益", marginPct * 100)
                    : "每笔风险=1%权益(引擎默认风险定量)";
            System.out.printf(Locale.ROOT,
                    "%n==== SQZMOM 部署模拟 %s 4H仅空头 | %d~今 本金=%s lev=%dx %s ====%n",
                    symbol, fromYear, balance.toPlainString(), leverage, sizing);
            System.out.printf(Locale.ROOT,
                    "trades=%d win=%.1f%% pf=%.3f | 期末权益=%s ret=%.1f%% maxDD=%.1f%% 手续费=%s%n",
                    r.totalTrades(), r.winRate() * 100, r.profitFactor(),
                    r.finalEquity().toPlainString(), r.returnPct() * 100,
                    r.maxDrawdownPct() * 100, r.totalFees().toPlainString());
            java.util.Map<String, Integer> byExit = new java.util.LinkedHashMap<>();
            java.util.Map<Integer, double[]> byYear = new java.util.TreeMap<>();   // year -> {pnl, trades}
            for (BacktestResult.Trade t : r.getTrades()) {
                byExit.merge(t.exitReason(), 1, Integer::sum);
                int y = t.openTime() == null ? 0 : t.openTime().getYear();
                byYear.computeIfAbsent(y, k -> new double[2]);
                byYear.get(y)[0] += t.pnl().doubleValue();
                byYear.get(y)[1]++;
            }
            System.out.printf("exit分布=%s%n", byExit);
            for (var e : byYear.entrySet()) {
                System.out.printf(Locale.ROOT, "  %d: trades=%.0f pnl=%+.2f$%n",
                        e.getKey(), e.getValue()[1], e.getValue()[0]);
            }
            System.out.printf("最近8笔:%n");
            List<BacktestResult.Trade> ts = r.getTrades();
            for (int i = Math.max(0, ts.size() - 8); i < ts.size(); i++) {
                BacktestResult.Trade t = ts.get(i);
                System.out.printf(Locale.ROOT, "  %-17s %-5s entry=%-10s exit=%-10s pnl=%+9.2f r=%s %s%n",
                        t.openTime() == null ? "?" : t.openTime().toString().replace('T', ' '),
                        t.side(), t.entryPrice().stripTrailingZeros().toPlainString(),
                        t.exitPrice().stripTrailingZeros().toPlainString(), t.pnl().doubleValue(),
                        t.rMultiple() == null ? "-" : String.format(Locale.ROOT, "%.2f", t.rMultiple().doubleValue()),
                        t.exitReason());
            }
        }
    }

    /** 组合模拟的最小交易视图（两阶段 CSV 的行结构；pnl/qty/r 均为源回测 1% 风险口径，费率滑点已含）。 */
    private record PfTrade(String symbol, long openMs, long closeMs, double entry,
                           double pnl, double qty, double r, double mae) {
    }

    /** 组合模拟事件：kind 0=close 1=open。 */
    private record PfEv(long timeMs, int kind, PfTrade t) {
    }

    /**
     * 组合模拟两阶段-第一步：把一批币的交易流(定稿口径)追加写入 CSV——大币池单次抽取会超时，分批绕开。
     * 部署篮子(3币)直接跑 runPortfolioSim 的 DB 路径即可，不需要本方法。
     *
     * 跑法：mvn ... -Dtest=SqzMomStrategyDbRun#dumpPortfolioTrades -Dsqzmom.bt.symbols=... \
     *      -Dsqzmom.pf.csv=<路径> -Dbacktest.slippageBps=5
     */
    @Test
    void dumpPortfolioTrades() throws Exception {
        String csvPath = System.getProperty("sqzmom.pf.csv", "").trim();
        String rawSymbols = System.getProperty("sqzmom.bt.symbols", "").trim();
        if (csvPath.isBlank() || rawSymbols.isBlank()) {
            System.out.println("需显式传 -Dsqzmom.bt.symbols 与 -Dsqzmom.pf.csv");
            return;
        }
        int fromYear = Integer.getInteger("sqzmom.bt.fromYear", 2021);
        SqzMomParams p = SqzMomParams.defaults();
        Connection con = openDb();
        if (con == null) return;
        try (con; java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(csvPath, true))) {
            for (String symbol : symbols(rawSymbols)) {
                Long latest = latestCloseTime(con, symbol);
                if (latest == null) {
                    System.out.printf("%-10s (无数据)%n", symbol);
                    continue;
                }
                SegResult sr = runWindow(con, symbol, p, yearStartUtc(fromYear), latest + 1,
                        new BigDecimal("100000"), 5, latest);
                if (sr == null) continue;
                int n = 0;
                for (BacktestResult.Trade t : sr.r().getTrades()) {
                    if (t.openTime() == null || t.closeTime() == null) continue;
                    w.printf(Locale.ROOT, "%s,%d,%d,%s,%s,%s,%s,%s%n",
                            symbol,
                            t.openTime().toInstant(ZoneOffset.UTC).toEpochMilli(),
                            t.closeTime().toInstant(ZoneOffset.UTC).toEpochMilli(),
                            t.entryPrice().toPlainString(), t.pnl().toPlainString(),
                            t.quantity().toPlainString(),
                            t.rMultiple() == null ? "0" : t.rMultiple().toPlainString(),
                            t.maxAdverseR() == null ? "0" : t.maxAdverseR().toPlainString());
                    n++;
                }
                w.flush();   // 逐币落盘，中断不留半行脏数据
                System.out.printf("%-10s dumped=%d%n", symbol, n);
            }
        }
    }

    /** 两阶段-第二步的输入：CSV 行 → 开/平事件对。 */
    private static List<PfEv> loadPfEventsFromCsv(String path) throws Exception {
        List<PfEv> events = new ArrayList<>();
        for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(path))) {
            if (line.isBlank()) continue;
            String[] f = line.split(",");
            PfTrade t = new PfTrade(f[0], Long.parseLong(f[1]), Long.parseLong(f[2]),
                    Double.parseDouble(f[3]), Double.parseDouble(f[4]), Double.parseDouble(f[5]),
                    Double.parseDouble(f[6]), Double.parseDouble(f[7]));
            events.add(new PfEv(t.openMs(), 1, t));
            events.add(new PfEv(t.closeMs(), 0, t));
        }
        return events;
    }

    /**
     * 组合部署生死地图：篮子共享一个账户，逐笔按当时已实现权益"风险定量"定仓复利
     * （每笔风险=权益×riskPct，仓位=风险额/止损距离；保证金定仓已消融证伪为结构性错误，不再提供）。
     * 近似口径(如实呈现)：定仓用已实现权益(忽略未实现浮盈)、回撤按平仓点阶梯、不计资金费率。
     *
     * 跑法：mvn -pl wiib-quant -am test -Dtest=SqzMomStrategyDbRun#runPortfolioSim -DskipTests=false \
     *   -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false -Dbacktest.slippageBps=5 \
     *   [-Dsqzmom.pf.csv=<两阶段CSV>]
     */
    @Test
    void runPortfolioSim() throws Exception {
        List<String> symbols = symbols(DEPLOY_SYMBOLS);
        double startEquity = Double.parseDouble(System.getProperty("sqzmom.pf.balance", "1000"));
        int fromYear = Integer.getInteger("sqzmom.bt.fromYear", 2021);
        SqzMomParams p = SqzMomParams.defaults();

        List<PfEv> events;
        String csvPath = System.getProperty("sqzmom.pf.csv", "").trim();
        if (!csvPath.isBlank()) {
            events = loadPfEventsFromCsv(csvPath);   // 两阶段模式：读 dumpPortfolioTrades 批量导出的交易流
            System.out.printf("从 CSV 装载事件 %d 条 (%s)%n", events.size(), csvPath);
        } else {
            Connection con = openDb();
            if (con == null) return;
            events = new ArrayList<>();
            try (con) {
                for (String symbol : symbols) {
                    Long latest = latestCloseTime(con, symbol);
                    if (latest == null) continue;
                    SegResult sr = runWindow(con, symbol, p, yearStartUtc(fromYear), latest + 1,
                            new BigDecimal("100000"), 5, latest);
                    if (sr == null) continue;
                    for (BacktestResult.Trade t : sr.r().getTrades()) {
                        if (t.openTime() == null || t.closeTime() == null) continue;
                        PfTrade pt = new PfTrade(symbol,
                                t.openTime().toInstant(ZoneOffset.UTC).toEpochMilli(),
                                t.closeTime().toInstant(ZoneOffset.UTC).toEpochMilli(),
                                t.entryPrice().doubleValue(), t.pnl().doubleValue(),
                                t.quantity().doubleValue(),
                                t.rMultiple() == null ? 0 : t.rMultiple().doubleValue(),
                                t.maxAdverseR() == null ? 0 : t.maxAdverseR().doubleValue());
                        events.add(new PfEv(pt.openMs(), 1, pt));
                        events.add(new PfEv(pt.closeMs(), 0, pt));
                    }
                    System.out.printf("%-10s trades=%d%n", symbol, sr.r().getTrades().size());
                }
            }
        }
        // 同一毫秒 open(1) 必须先于 close(0)：同根进出的交易(openMs==closeMs)否则会"先平后开"，
        // 开仓事件永久泄漏(并发/保证金虚高、盈亏丢失)。代价是同毫秒跨笔的保证金释放晚半拍，保守可接受。
        events.sort(java.util.Comparator.comparingLong(PfEv::timeMs)
                .thenComparing(java.util.Comparator.comparingInt(PfEv::kind).reversed()));

        int lev = Integer.getInteger("sqzmom.pf.leverage", 5);   // 杠杆只影响保证金占用；5x 强平距≈18% 远超一切止损
        System.out.printf(Locale.ROOT,
                "%n==== SQZMOM 组合部署生死地图 (4H仅空头, %d币, %d~今, 本金=%.0f, lev=%dx) ====%n",
                symbols.size(), fromYear, startEquity, lev);
        System.out.printf("%-11s %11s %8s %8s %6s %8s %6s%n",
                "档位", "期末权益$", "ret%", "maxDD%", "并发", "占用%", "连败");
        for (double riskPct : new double[]{0.005, 0.01, 0.02}) {
            double equity = startEquity, peakEq = startEquity, maxDD = 0;
            double marginInUse = 0, marginPeakPct = 0;
            int concurrent = 0, maxConcurrent = 0;
            int streak = 0, worstStreak = 0;
            java.util.Map<PfTrade, double[]> open = new java.util.IdentityHashMap<>();   // t -> {pnlScaled, margin}
            for (PfEv ev : events) {
                PfTrade t = ev.t();
                if (ev.kind() == 1) {   // OPEN：按当时已实现权益风险定量
                    if (Math.abs(t.r()) < 1e-9) continue;   // r=0 无法回推止损距离，弃仓（极罕见）
                    double riskDist = Math.abs(t.pnl() / (t.qty() * t.r()));
                    double qty = equity * riskPct / riskDist;
                    double margin = qty * t.entry() / lev;
                    double perUnitPnl = t.pnl() / t.qty();
                    open.put(t, new double[]{perUnitPnl * qty, margin});
                    marginInUse += margin;
                    concurrent++;
                    maxConcurrent = Math.max(maxConcurrent, concurrent);
                    marginPeakPct = Math.max(marginPeakPct, marginInUse / equity * 100);
                } else {                // CLOSE：入账、释放保证金、记回撤/连败
                    double[] ctx = open.remove(t);
                    if (ctx == null) continue;
                    marginInUse -= ctx[1];
                    concurrent--;
                    equity += ctx[0];
                    peakEq = Math.max(peakEq, equity);
                    maxDD = Math.max(maxDD, (peakEq - equity) / peakEq * 100);
                    streak = ctx[0] < 0 ? streak + 1 : 0;
                    worstStreak = Math.max(worstStreak, streak);
                }
            }
            System.out.printf(Locale.ROOT, "%-11s %11.2f %8.1f %8.1f %6d %8.1f %6d%n",
                    String.format(Locale.ROOT, "risk%.1f%%x%d", riskPct * 100, lev),
                    equity, (equity - startEquity) / startEquity * 100, maxDD,
                    maxConcurrent, marginPeakPct, worstStreak);
        }
    }

    // ---- 共用底座 ----

    /** 连不上本地 postgres 时 abort 测试并返回 null。 */
    private static Connection openDb() {
        try {
            return DriverManager.getConnection(
                    System.getProperty("sqzmom.bt.dbUrl", DEFAULT_URL),
                    System.getProperty("sqzmom.bt.dbUser", DEFAULT_USER),
                    System.getProperty("sqzmom.bt.dbPassword", DEFAULT_PASSWORD));
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过 SQZMOM DB 回测: " + e.getMessage());
            return null;
        }
    }

    /** 跑单个时间窗 [tradingStart, tradingEnd)，窗前 warmup 段只喂数据不交易。 */
    private static SegResult runWindow(Connection con, String symbol, SqzMomParams params,
                                       long tradingStartMs, long tradingEndExclusive,
                                       BigDecimal balance, int leverage, long latestClose) throws Exception {
        long segEnd = Math.min(tradingEndExclusive, latestClose + 1);
        if (tradingStartMs >= segEnd) return null;
        long warmupMs = warmupMs(params);
        List<KlineBar> bars = loadBars(con, symbol, tradingStartMs - warmupMs, segEnd);
        if (bars.isEmpty()) return null;
        boolean hasGap = WindowedMarketView.firstBaseGapDescription(bars).isPresent();
        int warmupBars = (int) bars.stream().filter(b -> b.closeTime() < tradingStartMs).count();
        SqueezeMomentumStrategy strategy = new SqueezeMomentumStrategy(params, List.of(symbol));
        BacktestResult r = new StrategyKlineBacktestEngine(
                strategy, symbol, bars, balance, leverage, warmupBars, tradingStartMs, segEnd).run();
        return new SegResult(hasGap, r);
    }

    /** 预热毫秒：val 的 2L 窗口 + 压缩连击 + 余量，全按决策周期折算。 */
    private static long warmupMs(SqzMomParams p) {
        return (2L * p.length() + p.squeezeMinBars() + 40) * p.decisionTfMillis();
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

    /** 解析逗号分隔符号表；runYearlyCheck/runPortfolioSim 也可用 -Dsqzmom.bt.symbols 覆盖部署篮子。 */
    private static List<String> symbols(String defaultRaw) {
        String raw = System.getProperty("sqzmom.bt.symbols", defaultRaw);
        List<String> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            String symbol = token.trim().toUpperCase(Locale.ROOT);
            if (!symbol.isBlank()) out.add(symbol);
        }
        return out.isEmpty() ? List.of("SOLUSDT") : List.copyOf(out);
    }
}
