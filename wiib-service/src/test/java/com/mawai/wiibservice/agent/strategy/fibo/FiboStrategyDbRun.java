package com.mawai.wiibservice.agent.strategy.fibo;

import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import com.mawai.wiibservice.agent.strategy.backtest.StrategyKlineBacktestEngine;
import com.mawai.wiibservice.agent.strategy.core.WindowedMarketView;
import com.mawai.wiibservice.agent.strategy.backtest.BacktestResult;
import com.mawai.wiibservice.config.BinanceProperties;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.config.CoinDeskProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        // 止盈实验：原版(远TP前高+1R分批) vs 1.5R全平(无分批) vs 1.5R全平(1R先落一半)
        List<ParamCase> cases = List.of(
                new ParamCase("scaleA_default", FiboParams.defaults()),
                new ParamCase("tp1.5R_noScale", FiboParams.defaults().withScaleOut(false).withTpRMultiple(1.5)),
                new ParamCase("tp1.5R_scale1R", FiboParams.defaults().withTpRMultiple(1.5)));

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
                System.out.printf("%n#### case=%s swing=%s entryFib=%.3f tp=%.3f slBase=%.3f scaleOut=%b ####%n",
                        c.label(), tf(params.swingTfMillis()),
                        params.entryFib(), params.tpExtensionRatio(),
                        params.slFibRatio(), params.scaleOutOn());
                for (String symbol : symbols) {
                    runOne(con, c.label(), symbol, days, initialBalance, leverage, toDate, params);
                }
            }
        }
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
        BinanceRestClient client = new BinanceRestClient(props, new CoinDeskProperties());

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
     * 分批止盈已定为最终配置(scaleA=defaults)；保留 M2(分批关) 对照供跨年复核。
     * 纯证伪、不在单窗口挑最优、不调任何数值。
     */
    @Test
    void runRobustnessCheck() throws Exception {
        List<String> symbols = symbols();
        int leverage = Integer.getInteger("fibo.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("fibo.bt.balance", "100000"));
        FiboParams base = FiboParams.defaults();
        List<ParamCase> configs = List.of(
                new ParamCase("M2_noScale", base.withScaleOut(false)),
                new ParamCase("scaleA_default", base));

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
            System.out.printf("%n#### Fibo 稳健性体检 (15m 日内，M2 vs scaleA默认) balance=%s leverage=%d ####%n",
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

    /**
     * 止盈倍数扫描：固定 15m/1H + 1R 落半分批，扫 TP = 远TP(前高)/1.5R/2.0R/2.5R/3.0R，
     * 每个 6 年逐年汇总(posYears/retSum/平均maxDD/trades)，找"收益没掉多少、回撤明显降"的平衡点。
     * 注：avgR 在分批下被半仓记录灌水，只看趋势；判收益看 retSum、判风险看 avgDD。
     */
    @Test
    void runTpSweep() throws Exception {
        List<String> symbols = symbols();
        int leverage = Integer.getInteger("fibo.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("fibo.bt.balance", "100000"));
        FiboParams base = FiboParams.defaults();   // 15m/1H，1R 落半分批开
        double[] tpRs = {0.0, 1.5, 2.0, 2.5, 3.0}; // 0=原版远TP(前高)，其余=R倍近TP

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
            System.out.printf("%n#### Fibo 止盈倍数扫描 (15m/1H, 1R落半分批, 6年汇总) balance=%s lev=%d ####%n",
                    balance.toPlainString(), leverage);
            for (String symbol : symbols) {
                Long latestClose = latestCloseTime(con, symbol);
                assertThat(latestClose).as(symbol + " latest close").isNotNull();
                System.out.printf("%n== %s ==%n", symbol);
                System.out.printf("%-12s %8s %9s %9s %8s %8s%n", "tpMode", "posYr", "retSum%", "avgDD%", "trades", "avgR");
                for (double tpR : tpRs) {
                    FiboParams p = tpR > 0 ? base.withTpRMultiple(tpR) : base;
                    int posYears = 0, validYears = 0, totalTrades = 0;
                    double retSum = 0, ddSum = 0, rSum = 0;
                    for (int year : CHECK_YEARS) {
                        SegResult sr = runWindow(con, symbol, p, yearStartUtc(year), yearStartUtc(year + 1),
                                balance, leverage, latestClose);
                        if (sr == null) continue;
                        BacktestResult r = sr.r();
                        validYears++;
                        retSum += r.returnPct() * 100;
                        ddSum += r.maxDrawdownPct() * 100;
                        totalTrades += r.totalTrades();
                        rSum += r.avgR();
                        if (r.returnPct() > 0) posYears++;
                    }
                    String label = tpR > 0 ? ("tp" + tpR + "R") : "远TP(前高)";
                    System.out.printf("%-12s %6d/%-2d %9.2f %9.2f %8d %8.3f%n",
                            label, posYears, validYears, retSum,
                            validYears > 0 ? ddSum / validYears : 0.0, totalTrades,
                            validYears > 0 ? rSum / validYears : 0.0);
                }
            }
        }
    }

    /**
     * 分批扫描：第二批固定 2.0R 全平(可 -Dfibo.bt.tpR 改)，扫第一批触发(0.7/1.0/1.3R)×比例(30/50/70%)，
     * 6 年汇总，定第一批"平多少、在哪"。注：仍是负 edge 框架内找最稳，非盈利寻优。
     */
    @Test
    void runScaleSweep() throws Exception {
        List<String> symbols = symbols();
        int leverage = Integer.getInteger("fibo.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("fibo.bt.balance", "100000"));
        double tpR = Double.parseDouble(System.getProperty("fibo.bt.tpR", "2.0"));
        double[] atRs = parseDoubles(System.getProperty("fibo.bt.atRs"), new double[]{0.7, 1.0, 1.3});
        double[] fracs = parseDoubles(System.getProperty("fibo.bt.fracs"), new double[]{0.3, 0.5, 0.7});

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
            System.out.printf("%n#### Fibo 分批扫描 (15m/1H, 第二批固定 %.1fR 全平, 6年汇总) balance=%s lev=%d ####%n",
                    tpR, balance.toPlainString(), leverage);
            for (String symbol : symbols) {
                Long latestClose = latestCloseTime(con, symbol);
                assertThat(latestClose).as(symbol + " latest close").isNotNull();
                System.out.printf("%n== %s (2nd=%.1fR) ==%n", symbol, tpR);
                System.out.printf("%-14s %8s %9s %9s %8s%n", "1st", "posYr", "retSum%", "avgDD%", "trades");
                for (double atR : atRs) {
                    for (double frac : fracs) {
                        FiboParams p = scaleParams(atR, frac, tpR);
                        int posYears = 0, validYears = 0, totalTrades = 0;
                        double retSum = 0, ddSum = 0;
                        for (int year : CHECK_YEARS) {
                            SegResult sr = runWindow(con, symbol, p, yearStartUtc(year), yearStartUtc(year + 1),
                                    balance, leverage, latestClose);
                            if (sr == null) continue;
                            BacktestResult r = sr.r();
                            validYears++;
                            retSum += r.returnPct() * 100;
                            ddSum += r.maxDrawdownPct() * 100;
                            totalTrades += r.totalTrades();
                            if (r.returnPct() > 0) posYears++;
                        }
                        System.out.printf("%-14s %6d/%-2d %9.2f %9.2f %8d%n",
                                String.format("%.1fR x%.0f%%", atR, frac * 100),
                                posYears, validYears, retSum,
                                validYears > 0 ? ddSum / validYears : 0.0, totalTrades);
                    }
                }
            }
        }
    }

    /**
     * 判生死实验：控制 entryFib=0.618 等全默认，只动"腿长门槛"一个变量——
     * baseline(reversal2/minLeg4,现状) vs 大腿(reversal3/minLeg8)，
     * 各扫【全仓在 X R 止盈】X=0.5/1.0/1.5/2.0R/前高，6 年汇总。
     * 实现：TP 固定前高(保证 minRR≥1.2 的进场样本对所有档一致)，用 scaleOut(fraction=1.0 全平)
     * 表达"全仓在 X R 落袋"——避开"近 TP 因 RR<1.2 被 minRR 全过滤(0 单)"的坑。
     * 判据：baseline 下"越近越好"(早跑优)；大腿下若翻转成"越远越好"=趋势过滤在大腿上有效(有救)，
     * 仍"越近越好"=进场无方向 edge(该收手)。腿长/止盈档可 -Dfibo.bt.legs / -Dfibo.bt.scaleRs 覆盖。
     */
    @Test
    void runLegSizeVerdict() throws Exception {
        List<String> symbols = symbols();
        int leverage = Integer.getInteger("fibo.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("fibo.bt.balance", "100000"));
        double[] scaleRs = parseDoubles(System.getProperty("fibo.bt.scaleRs"), new double[]{0.5, 1.0, 1.5, 2.0, 0.0});
        // 腿长档：每两数一组 {reversalAtrMult, minLegAtrMult}；默认 baseline 与"大腿"两组对照。
        double[] legFlat = parseDoubles(System.getProperty("fibo.bt.legs"), new double[]{2.0, 4.0, 3.0, 8.0});

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
            System.out.printf("%n#### Fibo 判生死(腿长×止盈远近, 全仓止盈[scaleOut全平], 6年汇总) balance=%s lev=%d ####%n",
                    balance.toPlainString(), leverage);
            for (String symbol : symbols) {
                Long latestClose = latestCloseTime(con, symbol);
                assertThat(latestClose).as(symbol + " latest close").isNotNull();
                for (int g = 0; g + 1 < legFlat.length; g += 2) {
                    double reversal = legFlat[g], minLeg = legFlat[g + 1];
                    System.out.printf("%n== %s  腿长[reversal=%.1f minLeg=%.1f] ==%n", symbol, reversal, minLeg);
                    System.out.printf("%-10s %8s %9s %9s %8s %8s%n", "止盈档", "posYr", "retSum%", "avgDD%", "trades", "avgR");
                    for (double scaleR : scaleRs) {
                        FiboParams p = verdictParams(reversal, minLeg, scaleR);
                        int posYears = 0, validYears = 0, totalTrades = 0;
                        double retSum = 0, ddSum = 0, rSum = 0;
                        for (int year : CHECK_YEARS) {
                            SegResult sr = runWindow(con, symbol, p, yearStartUtc(year), yearStartUtc(year + 1),
                                    balance, leverage, latestClose);
                            if (sr == null) continue;
                            BacktestResult r = sr.r();
                            validYears++;
                            retSum += r.returnPct() * 100;
                            ddSum += r.maxDrawdownPct() * 100;
                            totalTrades += r.totalTrades();
                            rSum += r.avgR();
                            if (r.returnPct() > 0) posYears++;
                        }
                        String label = scaleR > 0 ? ("全平" + scaleR + "R") : "前高";
                        System.out.printf("%-10s %6d/%-2d %9.2f %9.2f %8d %8.3f%n",
                                label, posYears, validYears, retSum,
                                validYears > 0 ? ddSum / validYears : 0.0, totalTrades,
                                validYears > 0 ? rSum / validYears : 0.0);
                    }
                }
            }
        }
    }

    /**
     * fill 成本梯度：固定"全关纯收割"(关趋势门+关BOS, 全仓 0.5R)，扫 maker fill 摩擦 0/0.5/1/1.5/2bp，6 年汇总。
     * 全关收割是 4000+ 笔/6年的高频，fill 成本是命门——看 0 摩擦下的 +119%/+185% 能扛住几 bp。
     * fillEpsilonBp 经 System property 注入引擎(进场 maker 限价 + 出场 maker scaleOut 都需价格穿过 ε 才成交；SL 走 taker 不受影响)。
     * 档位可 -Dfibo.bt.epsBps 覆盖；收割 R 可 -Dfibo.bt.scaleR 改。
     */
    @Test
    void runFillGradient() throws Exception {
        List<String> symbols = symbols();
        int leverage = Integer.getInteger("fibo.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("fibo.bt.balance", "100000"));
        double scaleR = Double.parseDouble(System.getProperty("fibo.bt.scaleR", "0.5"));
        double[] epsBps = parseDoubles(System.getProperty("fibo.bt.epsBps"), new double[]{0.0, 0.5, 1.0, 1.5, 2.0});
        FiboParams p = mrParams(0.618, 0.786, scaleR);   // 方向无关纯收割(baseline 几何)

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

        String saved = System.getProperty("backtest.fillEpsilonBp");  // 跑完恢复，避免污染同进程其他测试
        try (con) {
            System.out.printf("%n#### Fibo fill成本梯度(全关纯收割 %.1fR, 6年汇总) balance=%s lev=%d ####%n",
                    scaleR, balance.toPlainString(), leverage);
            for (String symbol : symbols) {
                Long latestClose = latestCloseTime(con, symbol);
                assertThat(latestClose).as(symbol + " latest close").isNotNull();
                System.out.printf("%n== %s ==%n", symbol);
                System.out.printf("%-10s %8s %9s %9s %8s %8s%n", "fill", "posYr", "retSum%", "avgDD%", "trades", "avgR");
                for (double bp : epsBps) {
                    System.setProperty("backtest.fillEpsilonBp", String.valueOf(bp));
                    int posYears = 0, validYears = 0, totalTrades = 0;
                    double retSum = 0, ddSum = 0, rSum = 0;
                    for (int year : CHECK_YEARS) {
                        SegResult sr = runWindow(con, symbol, p, yearStartUtc(year), yearStartUtc(year + 1),
                                balance, leverage, latestClose);
                        if (sr == null) continue;
                        BacktestResult r = sr.r();
                        validYears++;
                        retSum += r.returnPct() * 100;
                        ddSum += r.maxDrawdownPct() * 100;
                        totalTrades += r.totalTrades();
                        rSum += r.avgR();
                        if (r.returnPct() > 0) posYears++;
                    }
                    System.out.printf("%-10s %6d/%-2d %9.2f %9.2f %8d %8.3f%n",
                            bp + "bp", posYears, validYears, retSum,
                            validYears > 0 ? ddSum / validYears : 0.0, totalTrades,
                            validYears > 0 ? rSum / validYears : 0.0);
                }
            }
        } finally {
            if (saved == null) System.clearProperty("backtest.fillEpsilonBp");
            else System.setProperty("backtest.fillEpsilonBp", saved);
        }
    }

    /**
     * 样本外验证：固定"全关纯收割"，扫 scaleR(0.3/0.5/0.7/1.0)，分训练期(2021–23)/验证期(2024–26)两段独立汇总。
     * 回应"0.5R×全关是不是6年全样本挑的过拟合角点"——若【训练期最优 scaleR 与验证期一致(都~0.5R)且两段都正】=非过拟合、edge时间稳定。
     * 默认 0 摩擦(隔离时间稳定性；fill 衰减已由 runFillGradient 单测)；可 -Dfibo.bt.epsBp 注入摩擦、-Dfibo.bt.scaleRs 改档。
     */
    @Test
    void runOutOfSample() throws Exception {
        List<String> symbols = symbols();
        int leverage = Integer.getInteger("fibo.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("fibo.bt.balance", "100000"));
        double[] scaleRs = parseDoubles(System.getProperty("fibo.bt.scaleRs"), new double[]{0.3, 0.5, 0.7, 1.0});
        double epsBp = Double.parseDouble(System.getProperty("fibo.bt.epsBp", "0"));
        int[] trainYears = {2021, 2022, 2023};
        int[] validYears = {2024, 2025, 2026};

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

        String saved = System.getProperty("backtest.fillEpsilonBp");
        System.setProperty("backtest.fillEpsilonBp", String.valueOf(epsBp));
        try (con) {
            System.out.printf("%n#### Fibo 样本外验证(全关纯收割, 训练2021-23 / 验证2024-26, fill=%.1fbp) balance=%s lev=%d ####%n",
                    epsBp, balance.toPlainString(), leverage);
            for (String symbol : symbols) {
                Long latestClose = latestCloseTime(con, symbol);
                assertThat(latestClose).as(symbol + " latest close").isNotNull();
                System.out.printf("%n== %s ==%n", symbol);
                System.out.printf("%-8s %9s %10s %9s %10s%n", "scaleR", "训练posYr", "训练ret%", "验证posYr", "验证ret%");
                for (double scaleR : scaleRs) {
                    FiboParams p = mrParams(0.618, 0.786, scaleR);
                    YearsAgg tr = sumYears(con, symbol, p, trainYears, balance, leverage, latestClose);
                    YearsAgg va = sumYears(con, symbol, p, validYears, balance, leverage, latestClose);
                    System.out.printf("%-8s %7d/%-1d %10.2f %7d/%-1d %10.2f%n",
                            scaleR + "R", tr.posYears(), tr.validYears(), tr.retSum(),
                            va.posYears(), va.validYears(), va.retSum());
                }
            }
        } finally {
            if (saved == null) System.clearProperty("backtest.fillEpsilonBp");
            else System.setProperty("backtest.fillEpsilonBp", saved);
        }
    }

    /** 多年汇总：指定自然年逐年回测的收益率%相加(各年同起点独立)，统计正年数。 */
    private record YearsAgg(int posYears, int validYears, double retSum) {}

    private static YearsAgg sumYears(Connection con, String symbol, FiboParams p, int[] years,
                                     BigDecimal balance, int leverage, long latestClose) throws Exception {
        int pos = 0, valid = 0;
        double ret = 0;
        for (int year : years) {
            SegResult sr = runWindow(con, symbol, p, yearStartUtc(year), yearStartUtc(year + 1),
                    balance, leverage, latestClose);
            if (sr == null) continue;
            valid++;
            ret += sr.r().returnPct() * 100;
            if (sr.r().returnPct() > 0) pos++;
        }
        return new YearsAgg(pos, valid, ret);
    }

    /**
     * 进场档×止损位网格：全关纯收割(均值回归框架)下扫 entryFib(0.5/0.559/0.618/0.66) × slFibRatio(0.786/0.88)，6年汇总。
     * 回答"挂多深最好(0.5–0.618之间?)"和"止损 0.88 vs 0.786"。默认 1bp 摩擦——找实盘可用档，
     * 而非 0 摩擦下被成交量主导的浅档。档位可 -Dfibo.bt.entryFibs / -Dfibo.bt.slRatios / -Dfibo.bt.epsBp 覆盖。
     */
    @Test
    void runEntryStopGrid() throws Exception {
        List<String> symbols = symbols();
        int leverage = Integer.getInteger("fibo.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("fibo.bt.balance", "100000"));
        double scaleR = Double.parseDouble(System.getProperty("fibo.bt.scaleR", "0.5"));
        double epsBp = Double.parseDouble(System.getProperty("fibo.bt.epsBp", "1"));
        double[] entryFibs = parseDoubles(System.getProperty("fibo.bt.entryFibs"), new double[]{0.5, 0.559, 0.618, 0.66});
        double[] slRatios = parseDoubles(System.getProperty("fibo.bt.slRatios"), new double[]{0.786, 0.88});

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

        String saved = System.getProperty("backtest.fillEpsilonBp");
        System.setProperty("backtest.fillEpsilonBp", String.valueOf(epsBp));
        try (con) {
            System.out.printf("%n#### Fibo 进场×止损网格(全关纯收割 %.1fR, fill=%.1fbp, 6年汇总) balance=%s lev=%d ####%n",
                    scaleR, epsBp, balance.toPlainString(), leverage);
            for (String symbol : symbols) {
                Long latestClose = latestCloseTime(con, symbol);
                assertThat(latestClose).as(symbol + " latest close").isNotNull();
                System.out.printf("%n== %s ==%n", symbol);
                System.out.printf("%-16s %8s %9s %9s %8s %8s%n", "entry/sl", "posYr", "retSum%", "avgDD%", "trades", "avgR");
                for (double ef : entryFibs) {
                    for (double sl : slRatios) {
                        FiboParams p = mrParams(ef, sl, scaleR);
                        int posYears = 0, validYears = 0, totalTrades = 0;
                        double retSum = 0, ddSum = 0, rSum = 0;
                        for (int year : CHECK_YEARS) {
                            SegResult sr = runWindow(con, symbol, p, yearStartUtc(year), yearStartUtc(year + 1),
                                    balance, leverage, latestClose);
                            if (sr == null) continue;
                            BacktestResult r = sr.r();
                            validYears++;
                            retSum += r.returnPct() * 100;
                            ddSum += r.maxDrawdownPct() * 100;
                            totalTrades += r.totalTrades();
                            rSum += r.avgR();
                            if (r.returnPct() > 0) posYears++;
                        }
                        System.out.printf("%-16s %6d/%-2d %9.2f %9.2f %8d %8.3f%n",
                                String.format("e%.3f/sl%.3f", ef, sl), posYears, validYears, retSum,
                                validYears > 0 ? ddSum / validYears : 0.0, totalTrades,
                                validYears > 0 ? rSum / validYears : 0.0);
                    }
                }
            }
        } finally {
            if (saved == null) System.clearProperty("backtest.fillEpsilonBp");
            else System.setProperty("backtest.fillEpsilonBp", saved);
        }
    }

    /**
     * 样本外网格验证：均值回归框架(全关纯收割 scaleR)下，对 entryFib×slFibRatio 组合分训练(21-23)/验证(24-26)两段，
     * 确认 e0.66/sl0.88 不是 8 组合挑出的过拟合角——验证期仍打赢 e0.618/sl0.786 且为正才采纳。默认 1bp，可 -Dfibo.bt.epsBp 改。
     */
    @Test
    void runOutOfSampleGrid() throws Exception {
        List<String> symbols = symbols();
        int leverage = Integer.getInteger("fibo.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("fibo.bt.balance", "100000"));
        double scaleR = Double.parseDouble(System.getProperty("fibo.bt.scaleR", "0.5"));
        double epsBp = Double.parseDouble(System.getProperty("fibo.bt.epsBp", "1"));
        double[] entryFibs = parseDoubles(System.getProperty("fibo.bt.entryFibs"), new double[]{0.618, 0.66, 0.70});
        double[] slRatios = parseDoubles(System.getProperty("fibo.bt.slRatios"), new double[]{0.786, 0.88});
        int[] trainYears = {2021, 2022, 2023};
        int[] validYears = {2024, 2025, 2026};

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

        String saved = System.getProperty("backtest.fillEpsilonBp");
        System.setProperty("backtest.fillEpsilonBp", String.valueOf(epsBp));
        try (con) {
            System.out.printf("%n#### Fibo 样本外网格(entry×sl, 全关纯收割 %.1fR, 训练21-23/验证24-26, fill=%.1fbp) balance=%s lev=%d ####%n",
                    scaleR, epsBp, balance.toPlainString(), leverage);
            for (String symbol : symbols) {
                Long latestClose = latestCloseTime(con, symbol);
                assertThat(latestClose).as(symbol + " latest close").isNotNull();
                System.out.printf("%n== %s ==%n", symbol);
                System.out.printf("%-16s %9s %10s %9s %10s%n", "entry/sl", "训练posYr", "训练ret%", "验证posYr", "验证ret%");
                for (double ef : entryFibs) {
                    for (double sl : slRatios) {
                        FiboParams p = mrParams(ef, sl, scaleR);
                        YearsAgg tr = sumYears(con, symbol, p, trainYears, balance, leverage, latestClose);
                        YearsAgg va = sumYears(con, symbol, p, validYears, balance, leverage, latestClose);
                        System.out.printf("%-16s %7d/%-1d %10.2f %7d/%-1d %10.2f%n",
                                String.format("e%.3f/sl%.3f", ef, sl),
                                tr.posYears(), tr.validYears(), tr.retSum(),
                                va.posYears(), va.validYears(), va.retSum());
                    }
                }
            }
        } finally {
            if (saved == null) System.clearProperty("backtest.fillEpsilonBp");
            else System.setProperty("backtest.fillEpsilonBp", saved);
        }
    }

    /**
     * 最优几何下复核止盈 R：固定全关 + entry0.66 + sl0.88(可 -D 覆盖)，扫全仓止盈 scaleR(0.3/0.4/0.5/0.7/1.0)，6年汇总。
     * 0.5R 是旧几何(0.618/0.786, risk=0.168腿)选的；新几何 risk=0.22腿，需复核 0.5R 是否仍是甜点。默认 1bp。
     */
    @Test
    void runScaleSweepMR() throws Exception {
        List<String> symbols = symbols();
        int leverage = Integer.getInteger("fibo.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("fibo.bt.balance", "100000"));
        double entryFib = Double.parseDouble(System.getProperty("fibo.bt.entryFib", "0.66"));
        double slFib = Double.parseDouble(System.getProperty("fibo.bt.slFib", "0.88"));
        double epsBp = Double.parseDouble(System.getProperty("fibo.bt.epsBp", "1"));
        double[] scaleRs = parseDoubles(System.getProperty("fibo.bt.scaleRs"), new double[]{0.3, 0.4, 0.5, 0.7, 1.0});

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

        String saved = System.getProperty("backtest.fillEpsilonBp");
        System.setProperty("backtest.fillEpsilonBp", String.valueOf(epsBp));
        try (con) {
            System.out.printf("%n#### Fibo 最优几何止盈R复核(全关+e%.3f+sl%.3f, fill=%.1fbp, 6年汇总) balance=%s lev=%d ####%n",
                    entryFib, slFib, epsBp, balance.toPlainString(), leverage);
            for (String symbol : symbols) {
                Long latestClose = latestCloseTime(con, symbol);
                assertThat(latestClose).as(symbol + " latest close").isNotNull();
                System.out.printf("%n== %s ==%n", symbol);
                System.out.printf("%-10s %8s %9s %9s %8s %8s%n", "止盈R", "posYr", "retSum%", "avgDD%", "trades", "avgR");
                for (double scaleR : scaleRs) {
                    FiboParams p = mrParams(entryFib, slFib, scaleR);
                    int posYears = 0, validYears = 0, totalTrades = 0;
                    double retSum = 0, ddSum = 0, rSum = 0;
                    for (int year : CHECK_YEARS) {
                        SegResult sr = runWindow(con, symbol, p, yearStartUtc(year), yearStartUtc(year + 1),
                                balance, leverage, latestClose);
                        if (sr == null) continue;
                        BacktestResult r = sr.r();
                        validYears++;
                        retSum += r.returnPct() * 100;
                        ddSum += r.maxDrawdownPct() * 100;
                        totalTrades += r.totalTrades();
                        rSum += r.avgR();
                        if (r.returnPct() > 0) posYears++;
                    }
                    System.out.printf("%-10s %6d/%-2d %9.2f %9.2f %8d %8.3f%n",
                            scaleR + "R", posYears, validYears, retSum,
                            validYears > 0 ? ddSum / validYears : 0.0, totalTrades,
                            validYears > 0 ? rSum / validYears : 0.0);
                }
            }
        } finally {
            if (saved == null) System.clearProperty("backtest.fillEpsilonBp");
            else System.setProperty("backtest.fillEpsilonBp", saved);
        }
    }

    /**
     * 实盘场景模拟：最优配置(全关+0.66+0.88+0.4R) + 指定 balance/leverage + 固定每仓保证金(-Dbacktest.fixedMarginUsdt)，
     * 连续 2021–now 单窗口(看复利/最终账户)。默认 balance=1000 lev=20 fill=1bp；配 -Dbacktest.fixedMarginUsdt=30 即"每仓30U保证金"。
     */
    @Test
    void runLiveSim() throws Exception {
        List<String> symbols = symbols();
        int leverage = Integer.getInteger("fibo.bt.leverage", 20);
        BigDecimal balance = new BigDecimal(System.getProperty("fibo.bt.balance", "1000"));
        double entryFib = Double.parseDouble(System.getProperty("fibo.bt.entryFib", "0.66"));
        double slFib = Double.parseDouble(System.getProperty("fibo.bt.slFib", "0.88"));
        double scaleR = Double.parseDouble(System.getProperty("fibo.bt.scaleR", "0.4"));
        double epsBp = Double.parseDouble(System.getProperty("fibo.bt.epsBp", "1"));
        String fixedMargin = System.getProperty("backtest.fixedMarginUsdt", "0");
        String marginPct = System.getProperty("backtest.marginPctOfEquity", "0");
        FiboParams p = mrParams(entryFib, slFib, scaleR);

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

        String saved = System.getProperty("backtest.fillEpsilonBp");
        System.setProperty("backtest.fillEpsilonBp", String.valueOf(epsBp));
        try (con) {
            System.out.printf("%n#### Fibo 实盘模拟(e%.3f/sl%.3f/%.1fR 全关, balance=%s lev=%d 固定保证金=%sU 保证金%%=%s fill=%.1fbp, 连续2021-now) ####%n",
                    entryFib, slFib, scaleR, balance.toPlainString(), leverage, fixedMargin, marginPct, epsBp);
            for (String symbol : symbols) {
                Long latestClose = latestCloseTime(con, symbol);
                assertThat(latestClose).as(symbol + " latest close").isNotNull();
                SegResult sr = runWindow(con, symbol, p, yearStartUtc(2021), yearStartUtc(2027),
                        balance, leverage, latestClose);
                if (sr == null) { System.out.printf("%s: 无数据%n", symbol); continue; }
                BacktestResult r = sr.r();
                System.out.printf("%n== %s ==%n", symbol);
                System.out.printf("trades=%d winRate=%.1f%% 最终权益=%.2fU 净盈亏=%.2fU 收益率=%.1f%% maxDD=%.1f%%%n",
                        r.totalTrades(), r.winRate() * 100,
                        r.finalEquity().doubleValue(), r.netProfit().doubleValue(),
                        r.returnPct() * 100, r.maxDrawdownPct() * 100);
            }
        } finally {
            if (saved == null) System.clearProperty("backtest.fillEpsilonBp");
            else System.setProperty("backtest.fillEpsilonBp", saved);
        }
    }

    /**
     * 敞口扫描：固定最优配置(全关+0.66+0.88+0.4R)连续 2021-now，扫名义敞口(=marginPct×杠杆)，看各敞口的 maxDD 与终值，
     * 据"能扛的回撤"反推合适敞口。默认 lev=100、balance=1000、1bp；档可 -Dfibo.bt.marginPcts 覆盖。
     */
    @Test
    void runExposureSweep() throws Exception {
        List<String> symbols = symbols();
        int leverage = Integer.getInteger("fibo.bt.leverage", 100);
        BigDecimal balance = new BigDecimal(System.getProperty("fibo.bt.balance", "1000"));
        double epsBp = Double.parseDouble(System.getProperty("fibo.bt.epsBp", "1"));
        double[] marginPcts = parseDoubles(System.getProperty("fibo.bt.marginPcts"),
                new double[]{0.02, 0.03, 0.05, 0.07, 0.10});
        FiboParams p = mrParams(0.66, 0.88, 0.4);

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

        String saved = System.getProperty("backtest.fillEpsilonBp");
        String savedMp = System.getProperty("backtest.marginPctOfEquity");
        System.setProperty("backtest.fillEpsilonBp", String.valueOf(epsBp));
        try (con) {
            System.out.printf("%n#### Fibo 敞口扫描(全关 0.66/0.88/0.4R, balance=%s lev=%d fill=%.1fbp, 连续2021-now) ####%n",
                    balance.toPlainString(), leverage, epsBp);
            for (String symbol : symbols) {
                Long latestClose = latestCloseTime(con, symbol);
                assertThat(latestClose).as(symbol + " latest close").isNotNull();
                System.out.printf("%n== %s ==%n", symbol);
                System.out.printf("%-8s %8s %16s %10s %8s%n", "敞口", "保证金%", "最终权益U", "收益率%", "maxDD%");
                for (double mp : marginPcts) {
                    System.setProperty("backtest.marginPctOfEquity", String.valueOf(mp));
                    SegResult sr = runWindow(con, symbol, p, yearStartUtc(2021), yearStartUtc(2027),
                            balance, leverage, latestClose);
                    if (sr == null) continue;
                    BacktestResult r = sr.r();
                    System.out.printf("%-8s %7.0f%% %16.2f %10.1f %8.1f%n",
                            String.format("%.1fx", mp * leverage), mp * 100,
                            r.finalEquity().doubleValue(), r.returnPct() * 100, r.maxDrawdownPct() * 100);
                }
            }
        } finally {
            if (saved == null) System.clearProperty("backtest.fillEpsilonBp");
            else System.setProperty("backtest.fillEpsilonBp", saved);
            if (savedMp == null) System.clearProperty("backtest.marginPctOfEquity");
            else System.setProperty("backtest.marginPctOfEquity", savedMp);
        }
    }

    /** 解析逗号分隔的 double 列表(扫描范围用)；空则回退默认。 */
    private static double[] parseDoubles(String csv, double[] dflt) {
        if (csv == null || csv.isBlank()) return dflt;
        String[] parts = csv.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Double.parseDouble(parts[i].trim());
        return out;
    }

    /** 用 defaults 数值 + 指定第一批触发R/比例/第二批R倍止盈构造 params(scaleOut 强制开)。 */
    private static FiboParams scaleParams(double scaleAtR, double scaleFraction, double tpR) {
        FiboParams d = FiboParams.defaults();
        return new FiboParams(d.swingTfMillis(), d.atrPeriod(), d.reversalAtrMult(), d.minLegAtrMult(),
                d.entryFib(), d.invalidationRatio(), d.slFibRatio(), d.slBufferAtrMult(), d.tpExtensionRatio(),
                d.orderTimeoutBars(), d.swingLookbackBars(),
                true, scaleFraction, scaleAtR, tpR);
    }

    /**
     * 判生死专用：指定腿长(reversal/minLeg) + 全仓在 scaleAtR×R 止盈，其余全默认。
     * 关键：TP 固定前高(tpR=0)以保证 minRR≥1.2 的进场样本对所有档【完全一致】；用 scaleOut(fraction=1.0 全平)
     * 表达"全仓在 X R 落袋"——scaleOut 不过开仓 minRR 门，避开"近 TP 因 RR<1.2 被全过滤(0 单)"的坑。
     * scaleAtR<=0 表示"前高"档(关分批，全仓直接走前高 TP)。
     */
    private static FiboParams verdictParams(double reversalAtrMult, double minLegAtrMult, double scaleAtR) {
        FiboParams d = FiboParams.defaults();
        boolean scale = scaleAtR > 0;
        return new FiboParams(d.swingTfMillis(), d.atrPeriod(),
                reversalAtrMult, minLegAtrMult,
                d.entryFib(), d.invalidationRatio(), d.slFibRatio(), d.slBufferAtrMult(), d.tpExtensionRatio(),
                d.orderTimeoutBars(), d.swingLookbackBars(),
                scale, 1.0, scale ? scaleAtR : d.scaleOutAtR(), 0.0);
    }

    /** 收割网格专用：方向无关 scaleAtR 全仓收割 + 指定 entryFib/slFibRatio(invalidation 跟随 slFibRatio)。 */
    private static FiboParams mrParams(double entryFib, double slFibRatio, double scaleAtR) {
        FiboParams d = FiboParams.defaults();
        boolean scale = scaleAtR > 0;
        return new FiboParams(d.swingTfMillis(), d.atrPeriod(),
                d.reversalAtrMult(), d.minLegAtrMult(),
                entryFib, slFibRatio, slFibRatio, d.slBufferAtrMult(), d.tpExtensionRatio(),
                d.orderTimeoutBars(), d.swingLookbackBars(),
                scale, 1.0, scale ? scaleAtR : d.scaleOutAtR(), 0.0);
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

    /** 预热毫秒：覆盖找腿窗 + ATR 预热(与 controller 同口径)。 */
    private static long warmupMs(FiboParams p) {
        return (long) (p.swingLookbackBars() + p.atrPeriod() + 16) * p.swingTfMillis();
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
     * 注：分批(scaleA)一笔被拆成多条记录会失真，方向/兑现以 M2(noScale) 为准。
     */
    private static void printEntryQualityDiagnostics(List<BacktestResult.Trade> trades) {
        List<BacktestResult.Trade> longs = trades.stream().filter(t -> "LONG".equals(t.side())).toList();
        List<BacktestResult.Trade> shorts = trades.stream().filter(t -> "SHORT".equals(t.side())).toList();
        System.out.printf("--- 进场质量诊断(方向 & TP兑现, 以M2为准) ---%n");
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

    /** 逐年多空 edge 拆分：看 LONG/SHORT 的笔数+avgR，验证"做多弱、做空强"是否跨年稳健(以 M2 为准)。 */
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

    private static void printRecentTrades(List<BacktestResult.Trade> trades, int limit) {
        if (trades.isEmpty()) return;
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
