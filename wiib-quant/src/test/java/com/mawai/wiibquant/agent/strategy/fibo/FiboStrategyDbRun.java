package com.mawai.wiibquant.agent.strategy.fibo;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.agent.strategy.backtest.StrategyKlineBacktestEngine;
import com.mawai.wiibquant.agent.strategy.core.SwingDetector;
import com.mawai.wiibquant.agent.strategy.core.WindowedMarketView;
import com.mawai.wiibquant.agent.strategy.backtest.BacktestResult;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.BinanceRestClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 手动 Fibo DB 回测 runner。
 * 显式 -Dtest=FiboStrategyDbRun 时，直连 Postgres 读 kline_history，绕开 Spring/Redis/外部行情。
 */
class FiboStrategyDbRun {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/wiib";
    private static final String DEFAULT_USER = "mawai";
    private static final String DEFAULT_PASSWORD = "LOCAL_PASSWORD";
    private static final long DAY_MS = Duration.ofDays(1).toMillis();

    @Test
    void runFiboBacktest() throws Exception {
        List<String> symbols = symbols();
        int days = Integer.getInteger("fibo.bt.days", 730);
        int leverage = Integer.getInteger("fibo.bt.leverage", 5);
        BigDecimal initialBalance = new BigDecimal(System.getProperty("fibo.bt.balance", "100000"));
        String toDate = System.getProperty("fibo.bt.toDate", "").trim();
        // 当前生产配置(趋势闸+T1+runToExt)单案例；要对比新参数时在此临时加 ParamCase。
        List<ParamCase> cases = List.of(
                new ParamCase("defaults", FiboParams.defaults()));

        Connection con;
        try {
            con = DriverManager.getConnection(
                    System.getProperty("fibo.bt.dbUrl", DEFAULT_URL),
                    System.getProperty("fibo.bt.dbUser", DEFAULT_USER),
                    System.getProperty("fibo.bt.dbPassword", DEFAULT_PASSWORD));
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过 Fibo DB 回测: " + e.getMessage());
            return;
        }

        try (con) {
            System.out.printf("%n==== FiboStrategyDbRun days=%d balance=%s leverage=%d symbols=%s cases=%s ====%n",
                    days, initialBalance.toPlainString(), leverage, symbols,
                    cases.stream().map(ParamCase::label).toList());
            for (ParamCase c : cases) {
                FiboParams params = c.params();
                System.out.printf("%n#### case=%s swing=%s entryFib=%.3f tp=%.3f slBase=%.3f trend=%b/T1=%b ####%n",
                        c.label(), tf(params.swingTfMillis()),
                        params.entryFib(), params.tpExtensionRatio(),
                        params.slFibRatio(), params.trendFilterOn(), params.trendAlignOn());
                for (String symbol : symbols) {
                    runOne(con, c.label(), symbol, days, initialBalance, leverage, toDate, params);
                }
            }
        }
    }

    /**
     * 亏损归因：导出 T1(默认) 在验证段 2024-26 四币的每一笔开平仓，CSV 一行一笔，供按小时/方向/出场/ R/时长聚合。
     * 不下结论、只吐数据；分析在外部 awk/脚本做。
     *
     * 跑法：mvn -pl wiib-quant -am test -Dtest=FiboStrategyDbRun#dumpT1Trades -DskipTests=false \
     *       -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false -Dfibo.bt.symbols=BTCUSDT,ETHUSDT,SOLUSDT,DOGEUSDT
     */
    @Test
    void dumpT1Trades() throws Exception {
        List<String> symbols = symbols();
        int leverage = Integer.getInteger("fibo.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("fibo.bt.balance", "100000"));
        FiboParams t1 = FiboParams.defaults();           // 已含 T1
        long valStart = yearStartUtc(2024), valEnd = yearStartUtc(2027);

        Connection con;
        try {
            con = DriverManager.getConnection(
                    System.getProperty("fibo.bt.dbUrl", DEFAULT_URL),
                    System.getProperty("fibo.bt.dbUser", DEFAULT_USER),
                    System.getProperty("fibo.bt.dbPassword", DEFAULT_PASSWORD));
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过: " + e.getMessage());
            return;
        }
        try (con) {
            System.out.println("TRADEHDR,symbol,hourUTC,dow,side,pnl,fee,r,exitReason,holdBars,mfeR,maeR");
            for (String symbol : symbols) {
                Long latest = latestCloseTime(con, symbol);
                if (latest == null) continue;
                SegResult s = runWindow(con, symbol, t1, valStart, valEnd, balance, leverage, latest);
                if (s == null) continue;
                for (BacktestResult.Trade t : s.r().getTrades()) {
                    int hold = t.closeBarIndex() - t.barIndex();
                    String r = t.rMultiple() == null ? "NaN" : String.format(Locale.ROOT, "%.3f", t.rMultiple().doubleValue());
                    String mfe = t.maxFavorableR() == null ? "NaN" : String.format(Locale.ROOT, "%.3f", t.maxFavorableR().doubleValue());
                    String mae = t.maxAdverseR() == null ? "NaN" : String.format(Locale.ROOT, "%.3f", t.maxAdverseR().doubleValue());
                    System.out.printf(Locale.ROOT, "TRADE,%s,%d,%s,%s,%.4f,%.4f,%s,%s,%d,%s,%s%n",
                            symbol,
                            t.openTime() == null ? -1 : t.openTime().getHour(),
                            t.openTime() == null ? "?" : t.openTime().getDayOfWeek().toString().substring(0, 3),
                            t.side(), t.pnl().doubleValue(), t.fee().doubleValue(), r, t.exitReason(), hold, mfe, mae);
                }
            }
        }
    }

    /**
     * ETH Fibo 开仓点审计(Part A)：导出当前默认策略在验证段 2024-2026 ETH 的每一笔开仓点。
     * 1% 风险口径(不传 marginPct)——避免爆仓/强平截断，捕获全部信号且 mfeR 干净。
     * 用 mfeR(入场后最大顺势幅度, R 单位)判"是否命中真回撤"：口袋被尊重(反弹续势)则 mfeR 高，
     * 被一刀击穿(接飞刀)则 mfeR≈0。CSV 一行一笔，分桶/取样在外部 awk 做。
     *
     * 跑法：mvn -pl wiib-quant -am test -Dtest=FiboStrategyDbRun#dumpEthEntries -DskipTests=false \
     *       -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false -Dfibo.bt.symbols=ETHUSDT
     */
    @Test
    void dumpEthEntries() throws Exception {
        String symbol = System.getProperty("fibo.bt.symbols", "ETHUSDT").split(",")[0].trim().toUpperCase(Locale.ROOT);
        int leverage = Integer.getInteger("fibo.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("fibo.bt.balance", "100000"));
        FiboParams p = FiboParams.defaults();          // 当前生产策略(趋势闸+T1)
        long valStart = yearStartUtc(2024), valEnd = yearStartUtc(2027);

        Connection con;
        try {
            con = DriverManager.getConnection(
                    System.getProperty("fibo.bt.dbUrl", DEFAULT_URL),
                    System.getProperty("fibo.bt.dbUser", DEFAULT_USER),
                    System.getProperty("fibo.bt.dbPassword", DEFAULT_PASSWORD));
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过: " + e.getMessage());
            return;
        }
        try (con) {
            Long latest = latestCloseTime(con, symbol);
            if (latest == null) { System.out.printf("无 %s 数据%n", symbol); return; }
            SegResult s = runWindow(con, symbol, p, valStart, valEnd, balance, leverage, latest);
            if (s == null) { System.out.printf("%s 窗口无数据%n", symbol); return; }
            List<BacktestResult.Trade> trades = s.r().getTrades();
            System.out.println("ENTRYHDR,idx,openIso,side,entry,exit,r,mfeR,maeR,exitReason,holdBars");
            int i = 0;
            for (BacktestResult.Trade t : trades) {
                System.out.printf(Locale.ROOT, "ENTRY,%d,%s,%s,%s,%s,%s,%s,%s,%s,%d%n",
                        ++i,
                        t.openTime() == null ? "?" : t.openTime().toString(),
                        t.side(),
                        t.entryPrice().toPlainString(),
                        t.exitPrice().toPlainString(),
                        r3(t.rMultiple()), r3(t.maxFavorableR()), r3(t.maxAdverseR()),
                        t.exitReason(), t.closeBarIndex() - t.barIndex());
            }
            System.out.printf("ENTRYCOUNT,%s,%d%n", symbol, trades.size());
        }
    }

    /** R 值 3 位小数；null → NaN。 */
    private static String r3(BigDecimal v) {
        return v == null ? "NaN" : String.format(Locale.ROOT, "%.3f", v.doubleValue());
    }

    /**
     * 全腿回撤因子审计(Part B)：后见枚举 15m 2024-2026 的【所有】摆动腿(分形，非策略防重绘 ZigZag)，
     * 逐腿模拟 0.66 口袋回撤的应然结果(WIN=先到延伸位/LOSS=先到止损)，并抽因子——
     * 趋势排列(align)、腿长(legAtr)、回撤速度(barsToPocket)、方向、是否被 live ZigZag 确认(confirmed)、
     * 量能(volPre=成交前回踩段均量/推动腿均量【可行动口径, 成交当根来不及反应故排除】、volFill=成交当根量/推动腿均量)。
     * 供外部 awk 交叉制表。后见合法：这是"市场给过哪些回撤"的取证复盘，不是实盘信号。
     *
     * 跑法：mvn -pl wiib-quant -am test -Dtest=FiboStrategyDbRun#auditLegFactors -DskipTests=false \
     *       -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false -Dfibo.bt.symbols=BTCUSDT,ETHUSDT,SOLUSDT,DOGEUSDT
     */
    @Test
    void auditLegFactors() throws Exception {
        List<String> symbols = symbols();
        int k = Integer.getInteger("fibo.bt.fractalK", 3);       // 分形窗口(每侧根数)
        int fwd = Integer.getInteger("fibo.bt.fwdBars", 288);    // 向后模拟窗口(~3天)
        // 审计窗口可换段(-Dfibo.bt.auditFromYear/auditToYear)：因子发现段与复制段分开跑，防单段偶合
        long valStart = yearStartUtc(Integer.getInteger("fibo.bt.auditFromYear", 2024));
        long valEnd = yearStartUtc(Integer.getInteger("fibo.bt.auditToYear", 2027));
        long warmupMs = 15L * DAY_MS;   // ≥200根1h(SMA200)预热，窗口首日趋势闸即可判

        Connection con;
        try {
            con = DriverManager.getConnection(
                    System.getProperty("fibo.bt.dbUrl", DEFAULT_URL),
                    System.getProperty("fibo.bt.dbUser", DEFAULT_USER),
                    System.getProperty("fibo.bt.dbPassword", DEFAULT_PASSWORD));
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过: " + e.getMessage());
            return;
        }
        try (con) {
            System.out.println("LEGHDR,symbol,fillIso,dir,legAtr,legPct,barsToPocket,align,confirmed,outcome,mfeR,maeR,volPre,volFill");
            for (String symbol : symbols) {
                Long latest = latestCloseTime(con, symbol);
                if (latest == null) { System.out.printf("无 %s 数据%n", symbol); continue; }
                long segEnd = Math.min(valEnd, latest + 1);
                List<KlineBar> b5 = loadBars(con, symbol, valStart - warmupMs, segEnd);
                if (b5.size() < 1000) { System.out.printf("%s 数据不足(%d)%n", symbol, b5.size()); continue; }
                List<KlineBar> b15 = FiboLegAudit.aggregate(b5, 900_000L);
                List<KlineBar> h1 = FiboLegAudit.aggregate(b5, 60 * 60_000L);
                double[] atr15 = SwingDetector.atrSeries(b15, 14);
                List<FiboLegAudit.Leg2> legs = FiboLegAudit.legs(b15, k);

                // live 防重绘 ZigZag 确认点标记(用于"是否被策略可见"因子)
                boolean[] cHigh = new boolean[b15.size()], cLow = new boolean[b15.size()];
                for (SwingDetector.Pivot p : SwingDetector.confirmedPivots(b15, 14, 2.0)) {
                    if (p.barIndex() < 0 || p.barIndex() >= b15.size()) continue;
                    if (p.type() == SwingDetector.PivotType.HIGH) cHigh[p.barIndex()] = true; else cLow[p.barIndex()] = true;
                }
                long[] h1ct = new long[h1.size()];
                for (int i = 0; i < h1.size(); i++) h1ct[i] = h1.get(i).closeTime();

                int total = 0, touched = 0, inWin = 0, win = 0, loss = 0, open = 0;
                for (FiboLegAudit.Leg2 leg : legs) {
                    total++;
                    double atrEnd = leg.endIdx() < atr15.length ? atr15[leg.endIdx()] : Double.NaN;
                    if (Double.isNaN(atrEnd) || atrEnd <= 0) continue;
                    FiboLegAudit.RetOutcome o = FiboLegAudit.simulateRetracement(leg, b15, atrEnd, 0.66, 0.88, 0.1, 1.0, fwd);
                    if (!o.touched()) continue;
                    touched++;
                    int fillIdx = leg.endIdx() + o.barsToPocket();
                    long fillTime = b15.get(fillIdx).closeTime();
                    if (fillTime < valStart) continue;   // 预热区不计
                    inWin++;
                    int ub = upperBound(h1ct, fillTime);
                    List<KlineBar> htf = h1.subList(Math.max(0, ub - 201), ub);
                    boolean align = FiboRetracementStrategy.trendGate(htf, leg.upLeg(), true);
                    boolean confirmed = confirmedNear(cHigh, cLow, leg.endIdx(), leg.upLeg(), 3);
                    double legLen = Math.abs(leg.endPrice().subtract(leg.startPrice()).doubleValue());
                    double legAtr = legLen / atrEnd;
                    double legPct = legLen / leg.endPrice().doubleValue() * 100;
                    // 量能因子：推动腿均量 = (startIdx, endIdx] 均量；volPre 只用成交前回踩 bar(endIdx+1..fillIdx-1)
                    double impulseVol = meanVolume(b15, leg.startIdx() + 1, leg.endIdx());
                    double preVol = meanVolume(b15, leg.endIdx() + 1, fillIdx - 1);
                    double volPre = impulseVol > 0 && !Double.isNaN(preVol) ? preVol / impulseVol : Double.NaN;
                    double volFill = impulseVol > 0 ? b15.get(fillIdx).volume().doubleValue() / impulseVol : Double.NaN;
                    if ("WIN".equals(o.outcome())) win++; else if ("LOSS".equals(o.outcome())) loss++; else open++;
                    System.out.printf(Locale.ROOT, "LEG,%s,%s,%s,%.2f,%.2f,%d,%b,%b,%s,%.3f,%.3f,%.3f,%.3f%n",
                            symbol, Instant.ofEpochMilli(fillTime), leg.upLeg() ? "LONG" : "SHORT",
                            legAtr, legPct, o.barsToPocket(), align, confirmed, o.outcome(), o.mfeR(), o.maeR(),
                            volPre, volFill);
                }
                System.out.printf("LEGSUMMARY,%s,totalLegs=%d,retraced=%d,inWindow=%d,WIN=%d,LOSS=%d,OPEN=%d%n",
                        symbol, total, touched, inWin, win, loss, open);
            }
        }
    }

    /** [from, to] 闭区间 15m 均量；区间空或越界返回 NaN。 */
    private static double meanVolume(List<KlineBar> bars, int from, int to) {
        if (from > to || from < 0 || to >= bars.size()) return Double.NaN;
        double sum = 0;
        for (int i = from; i <= to; i++) sum += bars.get(i).volume().doubleValue();
        return sum / (to - from + 1);
    }

    /** closeTime 升序数组里 <= key 的元素个数(二分)，用于取"截至 fillTime 的最后 N 根 1h"。 */
    private static int upperBound(long[] a, long key) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] <= key) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** 腿终点(上行腿=HIGH/下行腿=LOW) 附近 ±tol 根内是否存在 live ZigZag 确认点。 */
    private static boolean confirmedNear(boolean[] cHigh, boolean[] cLow, int endIdx, boolean upLeg, int tol) {
        boolean[] arr = upLeg ? cHigh : cLow;
        for (int d = -tol; d <= tol; d++) {
            int idx = endIdx + d;
            if (idx >= 0 && idx < arr.length && arr[idx]) return true;
        }
        return false;
    }

    /**
     * ETH 止盈档真回测扫描：ETH 2024-2026 单窗、1% 风险口径，只换止盈档、其余全默认，看哪档 totalR 最大。
     * 引擎真实撮合(非 mfeR 算术)——回答"止盈挂哪能把给回接住最多"必须用这个，避开 mfeR 含出场过冲的坑。
     * R 倍档 = entry±K×risk(近 TP)；ext 档 = 斐波延伸位(1.0=前高/前低, >1=更远)。
     *
     * 跑法：mvn -pl wiib-quant -am test -Dtest=FiboStrategyDbRun#runEthTpSweep -DskipTests=false \
     *       -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false -Dfibo.bt.symbols=ETHUSDT
     */
    @Test
    void runEthTpSweep() throws Exception {
        String symbol = System.getProperty("fibo.bt.symbols", "ETHUSDT").split(",")[0].trim().toUpperCase(Locale.ROOT);
        int leverage = Integer.getInteger("fibo.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("fibo.bt.balance", "100000"));
        long valStart = yearStartUtc(2024), valEnd = yearStartUtc(2027);
        FiboParams d = FiboParams.defaults();
        List<ParamCase> cases = List.of(
                new ParamCase("ext1.0(base)", d),
                new ParamCase("R1.0", d.withTpRMultiple(1.0)),
                new ParamCase("R1.5", d.withTpRMultiple(1.5)),
                new ParamCase("R2.0", d.withTpRMultiple(2.0)),
                new ParamCase("R2.5", d.withTpRMultiple(2.5)),
                new ParamCase("R3.0", d.withTpRMultiple(3.0)),
                new ParamCase("ext1.272", withExtRatio(d, 1.272)),
                new ParamCase("ext1.618", withExtRatio(d, 1.618)));

        Connection con;
        try {
            con = DriverManager.getConnection(
                    System.getProperty("fibo.bt.dbUrl", DEFAULT_URL),
                    System.getProperty("fibo.bt.dbUser", DEFAULT_USER),
                    System.getProperty("fibo.bt.dbPassword", DEFAULT_PASSWORD));
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过: " + e.getMessage());
            return;
        }
        try (con) {
            Long latest = latestCloseTime(con, symbol);
            if (latest == null) { System.out.printf("无 %s 数据%n", symbol); return; }
            System.out.printf(Locale.ROOT, "%n==== %s 止盈档扫描 (2024-2026, 1%%风险) 真回测 ====%n", symbol);
            System.out.printf("%-14s %7s %7s %8s %8s %6s %8s%n",
                    "TP档", "trades", "win%", "avgR", "totalR", "pf", "ret%");
            for (ParamCase c : cases) {
                SegResult s = runWindow(con, symbol, c.params(), valStart, valEnd, balance, leverage, latest);
                if (s == null) { System.out.printf("%-14s (无数据)%n", c.label()); continue; }
                BacktestResult r = s.r();
                double totalR = r.avgR() * r.totalTrades();
                System.out.printf(Locale.ROOT, "%-14s %7d %7.1f %8.3f %8.1f %6.2f %8.1f%n",
                        c.label(), r.totalTrades(), r.winRate() * 100, r.avgR(), totalR,
                        r.profitFactor(), r.returnPct() * 100);
            }
        }
    }

    /**
     * TP 延伸档验证：远止盈(ext1.272/1.618) vs 基准(ext1.0=前高) 的 ① 四币样本外 + ② 存活性。
     * 一个方法两用——不传 marginPct=每笔1%风险(看 edge 真假)；传 -Dbacktest.marginPctOfEquity=0.10 -Dfibo.bt.leverage=50
     * =部署口径(看低胜率连败扛不扛得住)。训练21-23/验证24-26 各独立单窗。
     *
     * 跑法(边验)：mvn -pl wiib-quant -am test -Dtest=FiboStrategyDbRun#runTpValidation -DskipTests=false \
     *   -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false -Dfibo.bt.symbols=BTCUSDT,ETHUSDT,SOLUSDT,DOGEUSDT
     * 跑法(部署)：上面再加 -Dbacktest.marginPctOfEquity=0.10 -Dfibo.bt.leverage=50 -Dfibo.bt.balance=1000
     */
    @Test
    void runTpValidation() throws Exception {
        List<String> symbols = symbols();
        int leverage = Integer.getInteger("fibo.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("fibo.bt.balance", "100000"));
        String marginPct = System.getProperty("backtest.marginPctOfEquity", "(每笔1%风险)");
        FiboParams d = FiboParams.defaults();
        List<ParamCase> cases = List.of(
                new ParamCase("ext1.0", d),
                new ParamCase("ext1.272", withExtRatio(d, 1.272)),
                new ParamCase("ext1.618", withExtRatio(d, 1.618)));
        long trainStart = yearStartUtc(2021), trainEnd = yearStartUtc(2024);
        long valStart = yearStartUtc(2024), valEnd = yearStartUtc(2027);

        Connection con;
        try {
            con = DriverManager.getConnection(
                    System.getProperty("fibo.bt.dbUrl", DEFAULT_URL),
                    System.getProperty("fibo.bt.dbUser", DEFAULT_USER),
                    System.getProperty("fibo.bt.dbPassword", DEFAULT_PASSWORD));
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过: " + e.getMessage());
            return;
        }
        try (con) {
            System.out.printf(Locale.ROOT, "%n==== TP延伸档验证 (训练21-23/验证24-26) lev=%d 仓位=%s balance=%s ====%n",
                    leverage, marginPct, balance.toPlainString());
            System.out.printf("判据: 远TP要真, 验证段四币 vaPF 都要 > ext1.0; 部署口径看 vaRet/vaMaxDD 扛不扛得住%n");
            System.out.printf("%-10s %-9s %7s %8s %7s %7s %8s %8s%n",
                    "symbol", "case", "trPF", "vaTrades", "vaWin%", "vaPF", "vaRet%", "vaMaxDD%");
            for (String symbol : symbols) {
                Long latest = latestCloseTime(con, symbol);
                if (latest == null) { System.out.printf("%-10s (无数据)%n", symbol); continue; }
                for (ParamCase c : cases) {
                    SegResult tr = runWindow(con, symbol, c.params(), trainStart, trainEnd, balance, leverage, latest);
                    SegResult va = runWindow(con, symbol, c.params(), valStart, valEnd, balance, leverage, latest);
                    BacktestResult t = tr == null ? null : tr.r();
                    BacktestResult v = va == null ? null : va.r();
                    System.out.printf(Locale.ROOT, "%-10s %-9s %7s %8s %7s %7s %8s %8s%n",
                            symbol, c.label(),
                            t == null ? "-" : String.format(Locale.ROOT, "%.2f", t.profitFactor()),
                            v == null ? "-" : String.valueOf(v.totalTrades()),
                            v == null ? "-" : String.format(Locale.ROOT, "%.1f", v.winRate() * 100),
                            v == null ? "-" : String.format(Locale.ROOT, "%.2f", v.profitFactor()),
                            v == null ? "-" : String.format(Locale.ROOT, "%.1f", v.returnPct() * 100),
                            v == null ? "-" : String.format(Locale.ROOT, "%.1f", v.maxDrawdownPct() * 100));
                }
            }
        }
    }

    /** 用指定斐波延伸止盈档(tpExtensionRatio)构造 params，其余全默认(tpRMultiple=0 走延伸位)。 */
    private static FiboParams withExtRatio(FiboParams d, double e) {
        return new FiboParams(d.swingTfMillis(), d.atrPeriod(), d.reversalAtrMult(), d.minLegAtrMult(),
                d.entryFib(), d.invalidationRatio(), d.slFibRatio(), d.slBufferAtrMult(), e,
                d.orderTimeoutBars(), d.swingLookbackBars(),
                d.tpRMultiple(), d.trendFilterOn(), d.trendAlignOn());
    }

    /**
     * entry×stop 网格高原检查：回答"0.66/0.88 是稳健高原还是孤立尖峰", 不是找 argmax。
     * 判读规则(先立后跑): 训练段(2021-23)看响应面形状; 验证段(2024-26)只确认高原/尖峰定性,
     * 不许用来挑新点——当前点若是孤峰, 结论是对 T1 本身降信心, 而非搬去更好的邻格。
     * 其余参数全冻结 defaults, 1% 风险口径(不传 marginPct), invalidation 与 sl 联动(同 defaults)。
     * bars 每 symbol×窗口只装载一次, 15 格共用, 免重复 IO。
     *
     * 跑法：mvn -pl wiib-quant -am test -Dtest=FiboStrategyDbRun#runEntryStopGrid -DskipTests=false \
     *   -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false -Dfibo.bt.symbols=BTCUSDT,ETHUSDT,SOLUSDT,DOGEUSDT
     */
    @Test
    void runEntryStopGrid() throws Exception {
        double[] entries = {0.5, 0.618, 0.66, 0.705, 0.786};
        double[] stops = {0.80, 0.88, 1.00};
        int leverage = Integer.getInteger("fibo.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("fibo.bt.balance", "100000"));
        long trainStart = yearStartUtc(2021), trainEnd = yearStartUtc(2024);
        long valStart = yearStartUtc(2024), valEnd = yearStartUtc(2027);

        Connection con;
        try {
            con = DriverManager.getConnection(
                    System.getProperty("fibo.bt.dbUrl", DEFAULT_URL),
                    System.getProperty("fibo.bt.dbUser", DEFAULT_USER),
                    System.getProperty("fibo.bt.dbPassword", DEFAULT_PASSWORD));
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过: " + e.getMessage());
            return;
        }
        try (con) {
            System.out.println("GRIDHDR,symbol,window,entry,stop,trades,win%,pf,avgR,totalR");
            for (String symbol : symbols()) {
                Long latest = latestCloseTime(con, symbol);
                if (latest == null) { System.out.printf("%s 无数据%n", symbol); continue; }
                long[][] windows = {{trainStart, trainEnd}, {valStart, valEnd}};
                String[] tags = {"TRAIN", "VAL"};
                for (int w = 0; w < windows.length; w++) {
                    long winStart = windows[w][0];
                    long segEnd = Math.min(windows[w][1], latest + 1);
                    if (winStart >= segEnd) continue;
                    long warmupMs = warmupMs(FiboParams.defaults());   // 网格只动 entry/stop, warmup 不变
                    List<KlineBar> bars = loadBars(con, symbol, winStart - warmupMs, segEnd);
                    if (bars.isEmpty()) continue;
                    int warmupBars = (int) bars.stream().filter(b -> b.closeTime() < winStart).count();
                    for (double entry : entries) {
                        for (double stop : stops) {
                            if (entry >= stop) continue;   // 几何前提: 止损档必须深于入场档
                            FiboParams p = withEntryStop(entry, stop);
                            FiboRetracementStrategy strategy = new FiboRetracementStrategy(p, List.of(symbol));
                            BacktestResult r = new StrategyKlineBacktestEngine(
                                    strategy, symbol, bars, balance, leverage, warmupBars, winStart, segEnd).run();
                            double totalR = r.avgR() * r.totalTrades();
                            System.out.printf(Locale.ROOT, "GRID,%s,%s,%.3f,%.2f,%d,%.1f,%.3f,%.3f,%.1f%n",
                                    symbol, tags[w], entry, stop, r.totalTrades(), r.winRate() * 100,
                                    r.profitFactor(), r.avgR(), totalR);
                        }
                    }
                }
            }
        }
    }

    /** 网格用 params：只动 entryFib 与 invalidation/sl(联动), 其余全 defaults。 */
    private static FiboParams withEntryStop(double entry, double stop) {
        FiboParams d = FiboParams.defaults();
        return new FiboParams(d.swingTfMillis(), d.atrPeriod(), d.reversalAtrMult(), d.minLegAtrMult(),
                entry, stop, stop, d.slBufferAtrMult(), d.tpExtensionRatio(),
                d.orderTimeoutBars(), d.swingLookbackBars(),
                d.tpRMultiple(), d.trendFilterOn(), d.trendAlignOn());
    }

    /**
     * 敞口生死地图：拿最好的几何(ext1.618)在四币验证段 2024-26 连续单窗，扫一串"更低敞口"
     * （降 marginPct 与 降 leverage 两条路都走），看降到哪一档能四币一致活下来(ret 正、maxDD 可忍)。
     * 名义敞口 = marginPct × leverage × 权益；50x 无论 marginPct 多小，强平距≈1/50=2%(可能仍架空止损)。
     * balance 默认 1000。
     *
     * 跑法：mvn -pl wiib-quant -am test -Dtest=FiboStrategyDbRun#runExtExposureSweep -DskipTests=false \
     *   -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false -Dfibo.bt.symbols=BTCUSDT,ETHUSDT,SOLUSDT,DOGEUSDT
     */
    @Test
    void runExtExposureSweep() throws Exception {
        List<String> symbols = symbols();
        BigDecimal balance = new BigDecimal(System.getProperty("fibo.bt.balance", "1000"));
        FiboParams p = withExtRatio(FiboParams.defaults(), 1.618);
        long valStart = yearStartUtc(2024), valEnd = yearStartUtc(2027);
        // 敞口档：{marginPct, leverage}；前4档=固定50x降仓，后3档=降杠杆
        double[][] cfgs = {
                {0.10, 50}, {0.05, 50}, {0.03, 50}, {0.02, 50},
                {0.10, 20}, {0.10, 10}, {0.05, 10}
        };

        Connection con;
        try {
            con = DriverManager.getConnection(
                    System.getProperty("fibo.bt.dbUrl", DEFAULT_URL),
                    System.getProperty("fibo.bt.dbUser", DEFAULT_USER),
                    System.getProperty("fibo.bt.dbPassword", DEFAULT_PASSWORD));
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过: " + e.getMessage());
            return;
        }
        String savedMp = System.getProperty("backtest.marginPctOfEquity");
        try (con) {
            System.out.printf(Locale.ROOT, "%n==== ext1.618 敞口生死地图 (四币验证段 2024-2026, balance=%s) ====%n",
                    balance.toPlainString());
            for (String symbol : symbols) {
                Long latest = latestCloseTime(con, symbol);
                if (latest == null) { System.out.printf("%-10s (无数据)%n", symbol); continue; }
                System.out.printf(Locale.ROOT, "%n== %s ==%n", symbol);
                System.out.printf("%-10s %7s %11s %8s %8s %7s%n", "敞口", "名义", "finalEq$", "ret%", "maxDD%", "win%");
                for (double[] cfg : cfgs) {
                    double mp = cfg[0];
                    int lev = (int) cfg[1];
                    System.setProperty("backtest.marginPctOfEquity", String.valueOf(mp));
                    SegResult s = runWindow(con, symbol, p, valStart, valEnd, balance, lev, latest);
                    if (s == null) { System.out.printf("%.0f%%x%d (无数据)%n", mp * 100, lev); continue; }
                    BacktestResult r = s.r();
                    System.out.printf(Locale.ROOT, "%-10s %6.1fx %11.2f %8.1f %8.1f %7.1f%n",
                            String.format("%.0f%%x%d", mp * 100, lev), mp * lev,
                            r.finalEquity().doubleValue(), r.returnPct() * 100,
                            r.maxDrawdownPct() * 100, r.winRate() * 100);
                }
            }
        } finally {
            if (savedMp == null) System.clearProperty("backtest.marginPctOfEquity");
            else System.setProperty("backtest.marginPctOfEquity", savedMp);
        }
    }

    /**
     * 单月实盘演示：跑当前 default(FIBO 最优版) 一个自然月，打开仓/平仓点 + 每笔盈亏 + 累计权益。
     * 仓位用 -Dbacktest.marginPctOfEquity（每仓保证金=权益×此比例，名义=保证金×杠杆）。
     * 跑法：
     *   mvn -pl wiib-quant -am test -Dtest=FiboStrategyDbRun#runMonthLedger -DskipTests=false \
     *       -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false \
     *       -Dfibo.bt.symbols=ETHUSDT -Dfibo.bt.month=2026-06 -Dfibo.bt.balance=1000 \
     *       -Dfibo.bt.leverage=100 -Dbacktest.marginPctOfEquity=0.10
     */
    @Test
    void runMonthLedger() throws Exception {
        String symbol = System.getProperty("fibo.bt.symbols", "ETHUSDT").split(",")[0].trim();
        String month = System.getProperty("fibo.bt.month", "2026-06");           // YYYY-MM
        BigDecimal balance = new BigDecimal(System.getProperty("fibo.bt.balance", "1000"));
        int leverage = Integer.getInteger("fibo.bt.leverage", 100);
        double marginPct = Double.parseDouble(System.getProperty("backtest.marginPctOfEquity", "0.10"));

        LocalDate first = LocalDate.parse(month + "-01");
        long startMs = first.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        long endMs = first.plusMonths(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        long warmupMs = 20L * DAY_MS;   // 预热≥1h SMA200(~8.3天)+找腿窗，保证趋势闸月初即生效

        Connection con;
        try {
            con = DriverManager.getConnection(
                    System.getProperty("fibo.bt.dbUrl", DEFAULT_URL),
                    System.getProperty("fibo.bt.dbUser", DEFAULT_USER),
                    System.getProperty("fibo.bt.dbPassword", DEFAULT_PASSWORD));
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过: " + e.getMessage());
            return;
        }
        try (con) {
            Long latest = latestCloseTime(con, symbol);
            if (latest == null) { System.out.printf("无 %s 数据%n", symbol); return; }
            long segEnd = Math.min(endMs, latest + 1);
            if (startMs >= segEnd) { System.out.printf("%s %s 无数据%n", symbol, month); return; }
            List<KlineBar> bars = loadBars(con, symbol, startMs - warmupMs, segEnd);
            int warmupBars = (int) bars.stream().filter(b -> b.closeTime() < startMs).count();
            FiboRetracementStrategy strategy = new FiboRetracementStrategy(FiboParams.defaults(), List.of(symbol));
            BacktestResult r = new StrategyKlineBacktestEngine(
                    strategy, symbol, bars, balance, leverage, warmupBars, startMs, segEnd).run();

            System.out.printf(Locale.ROOT,
                    "%n==== %s %s 实盘演示 | 本金=%s lev=%dx 每仓保证金=%.0f%%权益(名义=权益×%.1f) ====%n",
                    symbol, month, balance.toPlainString(), leverage, marginPct * 100, marginPct * leverage);
            System.out.printf("data UTC %s ~ %s | 策略=FIBO最优版(趋势on+让利润跑)%n",
                    Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(segEnd));
            System.out.printf("%-3s %-17s %-5s %11s %11s %-17s %10s %5s %-7s %11s%n",
                    "#", "open(UTC)", "side", "entry", "exit", "close(UTC)", "pnl$", "R", "exit", "equity$");
            double cum = balance.doubleValue();
            int i = 0;
            for (BacktestResult.Trade t : r.getTrades()) {
                cum += t.pnl().doubleValue();
                System.out.printf(Locale.ROOT, "%-3d %-17s %-5s %11s %11s %-17s %+10.2f %5s %-7s %11.2f%n",
                        ++i, fmtTime(t.openTime()), t.side(),
                        t.entryPrice().toPlainString(), t.exitPrice().toPlainString(),
                        fmtTime(t.closeTime()), t.pnl().doubleValue(),
                        t.rMultiple() == null ? "-" : String.format(Locale.ROOT, "%.1f", t.rMultiple().doubleValue()),
                        t.exitReason(), cum);
            }
            System.out.printf(Locale.ROOT, "%n---- 汇总 ----%n");
            System.out.printf(Locale.ROOT, "交易=%d 胜=%d 负=%d 胜率=%.1f%% | 总盈亏=%s$ 手续费=%s$%n",
                    r.totalTrades(), r.wins(), r.losses(), r.winRate() * 100,
                    r.netProfit().toPlainString(), r.totalFees().toPlainString());
            System.out.printf(Locale.ROOT, "期末权益=%s$ 收益率=%.2f%% 最大回撤=%.2f%%%n",
                    r.finalEquity().toPlainString(), r.returnPct() * 100, r.maxDrawdownPct() * 100);
        }
    }

    private static String fmtTime(LocalDateTime t) {
        return t == null ? "-" : t.toString().replace('T', ' ');
    }

    /** 体检前置：列出 DB 里 5m K线的标的清单与时间跨度，据此定体检矩阵。 */
    @Test
    void listKlineCatalog() throws Exception {
        Connection con;
        try {
            con = DriverManager.getConnection(
                    System.getProperty("fibo.bt.dbUrl", DEFAULT_URL),
                    System.getProperty("fibo.bt.dbUser", DEFAULT_USER),
                    System.getProperty("fibo.bt.dbPassword", DEFAULT_PASSWORD));
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过: " + e.getMessage());
            return;
        }
        String sql = """
                SELECT symbol, count(*) AS bars, min(open_time) AS first_ms, max(open_time) AS last_ms
                FROM kline_history WHERE interval_code='5m'
                GROUP BY symbol ORDER BY bars DESC
                """;
        try (con; PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            System.out.printf("%n==== kline_history 5m catalog ====%n");
            System.out.printf("%-12s %10s %12s  %-20s %-20s%n", "symbol", "bars", "~days", "first_utc", "last_utc");
            while (rs.next()) {
                long bars = rs.getLong("bars");
                long firstMs = rs.getLong("first_ms");
                long lastMs = rs.getLong("last_ms");
                System.out.printf("%-12s %10d %12.0f  %-20s %-20s%n",
                        rs.getString("symbol"), bars, bars / 288.0,
                        Instant.ofEpochMilli(firstMs), Instant.ofEpochMilli(lastMs));
            }
        }
    }

    /**
     * 手动回填：拉 Binance 合约 5m K线 [from, now) 幂等入库(ON CONFLICT DO NOTHING，重复跳过不覆盖)。
     * from 默认 2026-06-01 UTC，可 -Dfibo.bt.backfillFrom=yyyy-MM-dd 覆盖。
     * 拉取/解析复用生产同款 BinanceRestClient + parseRawFuturesKlines，仅入库走 JDBC，口径不漂移。
     */
    @Test
    void backfill5m() throws Exception {
        List<String> symbols = symbols();
        String fromDate = System.getProperty("fibo.bt.backfillFrom", "2026-06-01").trim();
        long fromMs = LocalDate.parse(fromDate).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        long toMs = System.currentTimeMillis();

        BinanceProperties props = new BinanceProperties();
        props.setFuturesRestBaseUrl("https://fapi.binance.com");
        BinanceRestClient client = new BinanceRestClient(props);

        Connection con;
        try {
            con = DriverManager.getConnection(
                    System.getProperty("fibo.bt.dbUrl", DEFAULT_URL),
                    System.getProperty("fibo.bt.dbUser", DEFAULT_USER),
                    System.getProperty("fibo.bt.dbPassword", DEFAULT_PASSWORD));
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过回填: " + e.getMessage());
            return;
        }

        try (con) {
            System.out.printf("%n#### Fibo 5m 回填 [%s, now) UTC symbols=%s ####%n",
                    Instant.ofEpochMilli(fromMs), symbols);
            for (String symbol : symbols) {
                long before = countBars(con, symbol, fromMs, toMs);
                int attempted = backfillPages(con, client, symbol, fromMs, toMs);
                long after = countBars(con, symbol, fromMs, toMs);
                Long latest = latestCloseTime(con, symbol);
                System.out.printf("%-10s 抓取=%d 新增=%d 区间总根数=%d 最新close=%s%n",
                        symbol, attempted, after - before, after,
                        latest == null ? "-" : Instant.ofEpochMilli(latest));
            }
        }
    }

    /**
     * 手动回填(公开数据仓)：从 data.binance.vision 拉月度 5m 合约K线 ZIP 幂等入库——
     * fapi 地域受限(451)时的替代通道，无频控无地域限制。月份区间 [-Dfibo.bt.visionFrom=2020-12, 当月]；
     * 某月 DB 已近满(≥99%)则跳过下载；2025+ 文件时间戳可能为微秒，统一归一到毫秒。
     *
     * 跑法：mvn -pl wiib-quant -am test -Dtest=FiboStrategyDbRun#backfillVision -DskipTests=false \
     *       -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false \
     *       -Dfibo.bt.symbols=BNBUSDT,XRPUSDT,ADAUSDT -Dfibo.bt.visionFrom=2020-12
     */
    @Test
    void backfillVision() throws Exception {
        List<String> symbols = symbols();
        YearMonth from = YearMonth.parse(System.getProperty("fibo.bt.visionFrom", "2020-12"));
        YearMonth to = YearMonth.now(ZoneOffset.UTC);

        Connection con;
        try {
            con = DriverManager.getConnection(
                    System.getProperty("fibo.bt.dbUrl", DEFAULT_URL),
                    System.getProperty("fibo.bt.dbUser", DEFAULT_USER),
                    System.getProperty("fibo.bt.dbPassword", DEFAULT_PASSWORD));
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过回填: " + e.getMessage());
            return;
        }
        try (con) {
            System.out.printf("%n#### vision 月度5m回填 [%s, %s] symbols=%s ####%n", from, to, symbols);
            for (String symbol : symbols) {
                int added = 0, skipped = 0, missing = 0;
                for (YearMonth ym = from; !ym.isAfter(to); ym = ym.plusMonths(1)) {
                    long mStart = ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
                    long mEnd = ym.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
                    long expected = (mEnd - mStart) / 300_000L;
                    if (countBars(con, symbol, mStart, mEnd) >= expected * 99 / 100) { skipped++; continue; }
                    List<KlineBar> bars = downloadVisionMonth(symbol, ym);
                    if (bars == null) { missing++; continue; }   // 404=该月无文件(未上市/未发布)
                    added += insertIgnore(con, symbol, bars);
                }
                Long latest = latestCloseTime(con, symbol);
                System.out.printf("%-10s 写入=%d 月满跳过=%d 无文件=%d 最新close=%s%n",
                        symbol, added, skipped, missing,
                        latest == null ? "-" : Instant.ofEpochMilli(latest));
            }
        }
    }

    /** 下载并解析一个 vision 月度 5m ZIP；404 → null。CSV 头行自动跳过。 */
    private static List<KlineBar> downloadVisionMonth(String symbol, YearMonth ym) throws Exception {
        String url = String.format("https://data.binance.vision/data/futures/um/monthly/klines/%s/5m/%s-5m-%s.zip",
                symbol, symbol, ym);
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(120_000);
        int code = c.getResponseCode();
        if (code == 404) { c.disconnect(); return null; }
        if (code != 200) throw new IllegalStateException("HTTP " + code + " " + url);
        List<KlineBar> out = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(c.getInputStream())) {
            if (zis.getNextEntry() == null) return out;
            BufferedReader br = new BufferedReader(new InputStreamReader(zis));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || !Character.isDigit(line.charAt(0))) continue;   // 跳过头行
                String[] f = line.split(",");
                out.add(new KlineBar(normMs(Long.parseLong(f[0])), normMs(Long.parseLong(f[6])),
                        new BigDecimal(f[1]), new BigDecimal(f[2]), new BigDecimal(f[3]),
                        new BigDecimal(f[4]), new BigDecimal(f[5])));
            }
        }
        return out;
    }

    /** vision 2025+ 文件时间戳为微秒(16位)；>1e14 即除以 1000 归一到毫秒。 */
    private static long normMs(long ts) {
        return ts > 100_000_000_000_000L ? ts / 1000 : ts;
    }

    /** endTime 向前翻页拉 5m，只收已闭合 bar(对齐 KlineHistoryStore.backfill)；返回尝试写入根数。 */
    private static int backfillPages(Connection con, BinanceRestClient client,
                                     String symbol, long fromMs, long toMs) throws Exception {
        long cursor = toMs;
        int attempted = 0;
        List<KlineBar> buffer = new ArrayList<>();
        while (cursor > fromMs) {
            String json = client.getFuturesKlines(symbol, "5m", 1500, cursor);
            List<KlineBar> bars = KlineHistoryStore.parseRawFuturesKlines(json);
            if (bars.isEmpty()) break;
            long oldest = bars.getFirst().openTime();
            for (KlineBar b : bars) {
                if (b.openTime() < fromMs || b.closeTime() >= toMs) continue; // 跳过窗外/未收完bar
                buffer.add(b);
            }
            if (buffer.size() >= 3000) {
                attempted += insertIgnore(con, symbol, buffer);
                buffer.clear();
            }
            if (oldest <= fromMs) break;
            cursor = oldest - 1;   // 下一页：比本页最老 bar 再早 1ms
        }
        if (!buffer.isEmpty()) attempted += insertIgnore(con, symbol, buffer);
        return attempted;
    }

    /** 幂等批插，唯一键(symbol,interval_code,open_time)冲突跳过(对齐 KlineHistoryMapper.batchInsertIgnore)。 */
    private static int insertIgnore(Connection con, String symbol, List<KlineBar> bars) throws Exception {
        String sql = """
                INSERT INTO kline_history
                  (symbol, interval_code, open_time, close_time, open, high, low, close, volume)
                VALUES (?, '5m', ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (symbol, interval_code, open_time) DO NOTHING
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (KlineBar b : bars) {
                ps.setString(1, symbol);
                ps.setLong(2, b.openTime());
                ps.setLong(3, b.closeTime());
                ps.setBigDecimal(4, b.open());
                ps.setBigDecimal(5, b.high());
                ps.setBigDecimal(6, b.low());
                ps.setBigDecimal(7, b.close());
                ps.setBigDecimal(8, b.volume());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return bars.size();
    }

    /** 区间 [fromMs, toMs) 内 5m 根数，用于回填前后对比算实际新增。 */
    private static long countBars(Connection con, String symbol, long fromMs, long toMs) throws Exception {
        String sql = "SELECT count(*) FROM kline_history WHERE symbol=? AND interval_code='5m' AND open_time>=? AND open_time<?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setLong(2, fromMs);
            ps.setLong(3, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static final int[] CHECK_YEARS = {2021, 2022, 2023, 2024, 2025, 2026};

    /** 单段体检结果：hasGap 标注该窗内有 5m 缺口（策略遇缺口会局部停手，结果存疑）。 */
    private record SegResult(boolean hasGap, BacktestResult r) {}

    private record ParamCase(String label, FiboParams params) {}

    /**
     * 稳健性体检：聚焦日内 15m 框架，冻结数值参数，按自然年不重叠分段。
     * 跑当前生产配置(defaults)逐年总览+多空拆分；纯证伪、不在单窗口挑最优、不调任何数值。
     */
    @Test
    void runRobustnessCheck() throws Exception {
        List<String> symbols = symbols();
        int leverage = Integer.getInteger("fibo.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("fibo.bt.balance", "100000"));
        List<ParamCase> configs = List.of(
                new ParamCase("defaults", FiboParams.defaults()));

        Connection con;
        try {
            con = DriverManager.getConnection(
                    System.getProperty("fibo.bt.dbUrl", DEFAULT_URL),
                    System.getProperty("fibo.bt.dbUser", DEFAULT_USER),
                    System.getProperty("fibo.bt.dbPassword", DEFAULT_PASSWORD));
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过: " + e.getMessage());
            return;
        }

        try (con) {
            System.out.printf("%n#### Fibo 稳健性体检 (15m 日内, defaults) balance=%s leverage=%d ####%n",
                    balance.toPlainString(), leverage);
            runYearlyCheck(con, symbols, configs, balance, leverage);
        }
    }

    /** 逐年体检主体：每个 symbol×config 跑 6 个自然年，逐年打印总览 + 多空拆分 + 累计 posYears/retSum。 */
    private static void runYearlyCheck(Connection con, List<String> symbols, List<ParamCase> configs,
                                       BigDecimal balance, int leverage) throws Exception {
        for (String symbol : symbols) {
            Long latestClose = latestCloseTime(con, symbol);
            assertThat(latestClose).as(symbol + " latest close").isNotNull();
            for (ParamCase cfg : configs) {
                System.out.printf("%n==== %s  config=%s ====%n", symbol, cfg.label());
                System.out.printf("%-8s %7s %7s %8s %9s %8s %8s %5s%n",
                        "year", "trades", "win%", "pf", "ret%", "avgR", "sharpe", "gap");
                int posYears = 0, validYears = 0;
                double retSum = 0;
                for (int year : CHECK_YEARS) {
                    SegResult sr = runWindow(con, symbol, cfg.params(),
                            yearStartUtc(year), yearStartUtc(year + 1), balance, leverage, latestClose);
                    if (sr == null) continue;
                    BacktestResult r = sr.r();
                    System.out.printf("%-8d %7d %7.1f %8.3f %9.2f %8.3f %8.3f %5s%n",
                            year, r.totalTrades(), r.winRate() * 100, r.profitFactor(),
                            r.returnPct() * 100, r.avgR(), r.sharpeRatio(), sr.hasGap() ? "Y" : "-");
                    printDirSplit(r);
                    validYears++;
                    retSum += r.returnPct() * 100;          // 各年同起点独立回测，%可直接相加看累计
                    if (r.returnPct() > 0) posYears++;
                }
                System.out.printf("=> %s posYears=%d/%d  retSum=%.2f%%%n", cfg.label(), posYears, validYears, retSum);
            }
        }
    }

    /** 跑单个时间窗 [tradingStart, tradingEnd)，窗前 warmup 段只喂数据不交易。 */
    private static SegResult runWindow(Connection con, String symbol, FiboParams params,
                                       long tradingStartMs, long tradingEndExclusive,
                                       BigDecimal balance, int leverage, long latestClose) throws Exception {
        long segEnd = Math.min(tradingEndExclusive, latestClose + 1);
        if (tradingStartMs >= segEnd) return null;             // 该年无数据
        long warmupMs = warmupMs(params);
        List<KlineBar> bars = loadBars(con, symbol, tradingStartMs - warmupMs, segEnd);
        if (bars.isEmpty()) return null;
        boolean hasGap = WindowedMarketView.firstBaseGapDescription(bars).isPresent();
        int warmupBars = (int) bars.stream().filter(b -> b.closeTime() < tradingStartMs).count();
        FiboRetracementStrategy strategy = new FiboRetracementStrategy(params, List.of(symbol));
        BacktestResult r = new StrategyKlineBacktestEngine(
                strategy, symbol, bars, balance, leverage, warmupBars, tradingStartMs, segEnd).run();
        return new SegResult(hasGap, r);
    }

    /** 预热毫秒：max(找腿窗+ATR预热, 趋势闸SMA200预热)。趋势TF=swingTf×4，201根保证窗口首日趋势闸即生效(数据允许时)。 */
    private static long warmupMs(FiboParams p) {
        long legWarmup = (long) (p.swingLookbackBars() + p.atrPeriod() + 16) * p.swingTfMillis();
        long trendWarmup = 201L * 4 * p.swingTfMillis();
        return Math.max(legWarmup, trendWarmup);
    }

    private static long yearStartUtc(int year) {
        return LocalDate.of(year, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static void runOne(Connection con, String caseLabel, String symbol, int days,
                               BigDecimal initialBalance, int leverage, String toDate,
                               FiboParams params) throws Exception {
        Long latestClose = latestCloseTime(con, symbol);
        assertThat(latestClose).as(symbol + " latest close").isNotNull();

        long toMs = toDate.isBlank() ? latestClose : Math.min(latestClose, inclusiveDateEndUtc(toDate) - 1);
        long tradingStartMs = toMs - days * DAY_MS;
        long warmupMs = warmupMs(params);
        long fromMs = tradingStartMs - warmupMs;

        long t0 = System.nanoTime();
        List<KlineBar> bars = loadBars(con, symbol, fromMs, toMs);
        String gap = WindowedMarketView.firstBaseGapDescription(bars).orElse(null);
        assertThat(gap).as(symbol + " 5m continuity").isNull();

        int warmupBars = (int) bars.stream().filter(b -> b.closeTime() < tradingStartMs).count();
        FiboRetracementStrategy strategy = new FiboRetracementStrategy(params, List.of(symbol));
        BacktestResult result = new StrategyKlineBacktestEngine(
                strategy, symbol, bars, initialBalance, leverage, warmupBars, tradingStartMs, null)
                .run();

        double seconds = (System.nanoTime() - t0) / 1e9;
        printResult(caseLabel, symbol, fromMs, tradingStartMs, toMs, bars.size(), warmupBars, result, seconds);
        assertThat(bars).as(symbol + " bars").isNotEmpty();
    }

    private static Long latestCloseTime(Connection con, String symbol) throws Exception {
        String sql = """
                SELECT close_time
                FROM kline_history
                WHERE symbol=? AND interval_code='5m'
                ORDER BY open_time DESC
                LIMIT 1
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

    private static void printResult(String caseLabel, String symbol, long fromMs, long tradingStartMs, long toMs,
                                    int bars, int warmupBars, BacktestResult r, double seconds) {
        System.out.printf("%n==== FIBO %s %s ====%n", caseLabel, symbol);
        System.out.printf("data UTC [%s, %s] tradingStart=%s bars=%d warmupBars=%d load+run=%.2fs%n",
                Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs),
                Instant.ofEpochMilli(tradingStartMs), bars, warmupBars, seconds);
        System.out.printf("trades=%d wins=%d losses=%d winRate=%.2f%% pf=%.3f sharpe=%.3f%n",
                r.totalTrades(), r.wins(), r.losses(), r.winRate() * 100, r.profitFactor(), r.sharpeRatio());
        System.out.printf("return=%.2f%% net=%s fees=%s final=%s maxDD=%.2f%% avgR=%.3f avgHoldBars=%.1f%n",
                r.returnPct() * 100,
                r.netProfit().toPlainString(),
                r.totalFees().toPlainString(),
                r.finalEquity().toPlainString(),
                r.maxDrawdownPct() * 100,
                r.avgR(),
                r.avgHoldBars());
        printBreakdown(r.getTrades());
        printPathDiagnostics(r.getTrades());
        printEntryQualityDiagnostics(r.getTrades());
        printRecentTrades(r.getTrades(), 8);
    }

    private static void printBreakdown(List<BacktestResult.Trade> trades) {
        Map<String, Integer> bySide = new LinkedHashMap<>();
        Map<String, Integer> byExit = new LinkedHashMap<>();
        for (BacktestResult.Trade t : trades) {
            bySide.merge(t.side(), 1, Integer::sum);
            byExit.merge(t.exitReason(), 1, Integer::sum);
        }
        System.out.printf("side=%s exit=%s%n", bySide, byExit);
    }

    /**
     * SL/TP 路径诊断：区分"撮合保守(同根SL优先冤枉)"与"策略真无edge"。
     * 关键：SL 退出的交易其 MFE(最大浮盈)若 ≥ 名义RR，只可能是同根既穿TP又穿SL、被SL优先判掉
     * ——否则任一根 high 摸到 TP 当根就 TP 平了。故 SL笔曾达≥RR 的笔数≈撮合冤枉数；
     * 若 SL笔 MFE 普遍很低=入场即逆行=选点无edge。
     */
    private static void printPathDiagnostics(List<BacktestResult.Trade> trades) {
        List<BacktestResult.Trade> sl = trades.stream().filter(t -> "SL".equals(t.exitReason())).toList();
        List<BacktestResult.Trade> tp = trades.stream().filter(t -> "TP".equals(t.exitReason())).toList();
        long sameBar = trades.stream().filter(t -> t.closeBarIndex() == t.barIndex()).count();
        long slGe10 = sl.stream().filter(t -> geR(t.maxFavorableR(), 1.0)).count();
        long slGe12 = sl.stream().filter(t -> geR(t.maxFavorableR(), 1.2)).count();
        // MFE 低端分桶：量化"接飞刀"(进场即逆行)真实占比。<0.25R=进场后几乎没浮盈就被打到止损。
        long slLt025 = sl.stream().filter(t -> ltR(t.maxFavorableR(), 0.25)).count();
        long sl025to05 = sl.stream().filter(t -> geR(t.maxFavorableR(), 0.25) && ltR(t.maxFavorableR(), 0.5)).count();
        long sl05to10 = sl.stream().filter(t -> geR(t.maxFavorableR(), 0.5) && ltR(t.maxFavorableR(), 1.0)).count();
        System.out.printf("--- 路径诊断(撮合保守 vs 策略无edge) ---%n");
        System.out.printf("同根进出(开bar=平bar)=%d/%d%n", sameBar, trades.size());
        System.out.printf("SL笔=%d MFE均值=%.2fR  曾达≥1.0R=%d(%.1f%%)  曾达≥1.2R=%d(%.1f%%)%n",
                sl.size(), avgField(sl, BacktestResult.Trade::maxFavorableR),
                slGe10, pctOf(slGe10, sl.size()), slGe12, pctOf(slGe12, sl.size()));
        System.out.printf("SL笔MFE分桶: 接飞刀<0.25R=%d(%.1f%%) 0.25~0.5R=%d(%.1f%%) 0.5~1R=%d(%.1f%%) ≥1R=%d(%.1f%%)%n",
                slLt025, pctOf(slLt025, sl.size()), sl025to05, pctOf(sl025to05, sl.size()),
                sl05to10, pctOf(sl05to10, sl.size()), slGe10, pctOf(slGe10, sl.size()));
        System.out.printf("TP笔=%d MAE均值=%.2fR  rMultiple均值(≈实际RR)=%.2f%n",
                tp.size(), avgField(tp, BacktestResult.Trade::maxAdverseR),
                avgField(tp, BacktestResult.Trade::rMultiple));
    }

    private static double avgField(List<BacktestResult.Trade> rows,
                                   java.util.function.Function<BacktestResult.Trade, BigDecimal> field) {
        return rows.stream().map(field).filter(java.util.Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue).average().orElse(0.0);
    }

    private static boolean geR(BigDecimal v, double threshold) {
        return v != null && v.doubleValue() >= threshold;
    }

    private static boolean ltR(BigDecimal v, double threshold) {
        return v != null && v.doubleValue() < threshold;
    }

    /**
     * 进场质量诊断：定位负 edge 来源。
     * 方向 avgR 谁负=该方向趋势门失灵(选错方向)；SL笔高端 MFE=败笔反弹天花板，看够不够得着 TP(名义≈3.28R)。
     */
    private static void printEntryQualityDiagnostics(List<BacktestResult.Trade> trades) {
        List<BacktestResult.Trade> longs = trades.stream().filter(t -> "LONG".equals(t.side())).toList();
        List<BacktestResult.Trade> shorts = trades.stream().filter(t -> "SHORT".equals(t.side())).toList();
        System.out.printf("--- 进场质量诊断(方向 & TP兑现) ---%n");
        System.out.printf("LONG : n=%d win=%.1f%% avgR=%.3f   SHORT: n=%d win=%.1f%% avgR=%.3f%n",
                longs.size(), winPct(longs), avgField(longs, BacktestResult.Trade::rMultiple),
                shorts.size(), winPct(shorts), avgField(shorts, BacktestResult.Trade::rMultiple));
        // 败笔反弹天花板：SL 笔曾摸到 1~2R/2~3R/≥3R 的占比。≥3R=曾摸到 TP 区却最终止损(撮合同根SL优先冤枉)。
        List<BacktestResult.Trade> sl = trades.stream().filter(t -> "SL".equals(t.exitReason())).toList();
        long r12 = sl.stream().filter(t -> geR(t.maxFavorableR(), 1.0) && ltR(t.maxFavorableR(), 2.0)).count();
        long r23 = sl.stream().filter(t -> geR(t.maxFavorableR(), 2.0) && ltR(t.maxFavorableR(), 3.0)).count();
        long ge3 = sl.stream().filter(t -> geR(t.maxFavorableR(), 3.0)).count();
        System.out.printf("SL笔MFE高端: 1~2R=%d(%.1f%%) 2~3R=%d(%.1f%%) ≥3R撮合冤枉=%d(%.1f%%)%n",
                r12, pctOf(r12, sl.size()), r23, pctOf(r23, sl.size()), ge3, pctOf(ge3, sl.size()));
    }

    private static double winPct(List<BacktestResult.Trade> rows) {
        if (rows.isEmpty()) return 0.0;
        long w = rows.stream().filter(t -> t.rMultiple() != null && t.rMultiple().signum() > 0).count();
        return 100.0 * w / rows.size();
    }

    /** 逐年多空 edge 拆分：看 LONG/SHORT 的笔数+avgR，验证"做多弱、做空强"是否跨年稳健。 */
    private static void printDirSplit(BacktestResult r) {
        List<BacktestResult.Trade> longs = r.getTrades().stream().filter(t -> "LONG".equals(t.side())).toList();
        List<BacktestResult.Trade> shorts = r.getTrades().stream().filter(t -> "SHORT".equals(t.side())).toList();
        System.out.printf("           L/S: LONG n=%-4d avgR=%+.3f | SHORT n=%-4d avgR=%+.3f%n",
                longs.size(), avgField(longs, BacktestResult.Trade::rMultiple),
                shorts.size(), avgField(shorts, BacktestResult.Trade::rMultiple));
    }

    private static double pctOf(long a, long total) {
        return total == 0 ? 0.0 : 100.0 * a / total;
    }

    private static void printRecentTrades(List<BacktestResult.Trade> trades, int limit) {        if (trades.isEmpty()) return;
        int from = Math.max(0, trades.size() - limit);
        System.out.printf("recent trades (last %d):%n", trades.size() - from);
        System.out.printf("%-20s %-5s %12s %12s %12s %8s %-12s%n",
                "openTime", "side", "entry", "exit", "pnl", "r", "reason");
        for (BacktestResult.Trade t : trades.subList(from, trades.size())) {
            System.out.printf("%-20s %-5s %12s %12s %12s %8s %-12s%n",
                    t.openTime(), t.side(),
                    scale(t.entryPrice()), scale(t.exitPrice()), scale(t.pnl()),
                    t.rMultiple() == null ? "-" : scale(t.rMultiple()),
                    t.exitReason());
        }
    }

    private static List<String> symbols() {
        String raw = System.getProperty("fibo.bt.symbols", "BTCUSDT,ETHUSDT");
        List<String> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            String symbol = token.trim().toUpperCase(Locale.ROOT);
            if (!symbol.isBlank()) out.add(symbol);
        }
        return out.isEmpty() ? List.of("BTCUSDT") : List.copyOf(out);
    }

    private static String scale(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    private static String tf(long millis) {
        long minutes = millis / 60_000L;
        return minutes % 60 == 0 ? (minutes / 60) + "H" : minutes + "m";
    }

    private static long inclusiveDateEndUtc(String yyyyMmDd) {
        return LocalDate.parse(yyyyMmDd).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
