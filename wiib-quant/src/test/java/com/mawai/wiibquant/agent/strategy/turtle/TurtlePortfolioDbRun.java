package com.mawai.wiibquant.agent.strategy.turtle;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.strategy.backtest.BacktestResult;
import com.mawai.wiibquant.agent.strategy.backtest.PortfolioBacktestEngine;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TURTLE 组合回测 runner。默认口径：SOL/ETH/DOGE/BNB 等权、共享权益、1万U、20x、每笔风险1%，
 * 参数跟随 TurtleParams.defaults()。显式指定本类时只读本地 kline_history，不启动 Spring/Redis。
 */
class TurtlePortfolioDbRun {

    private static final String URL = "jdbc:postgresql://localhost:5432/wiib";
    private static final String USER = "mawai";
    private static final String PASSWORD = com.mawai.wiibquant.LocalEnv.dbPassword();
    private static final List<String> COINS = coins();

    /** 组合篮子，-Dturtle.pf.symbols 覆盖（默认=live 部署篮子）。 */
    private static List<String> coins() {
        String raw = System.getProperty("turtle.pf.symbols", "SOLUSDT,ETHUSDT,DOGEUSDT,BNBUSDT");
        List<String> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            String s = token.trim().toUpperCase(Locale.ROOT);
            if (!s.isBlank()) out.add(s);
        }
        return List.copyOf(out);
    }
    private static final BigDecimal BALANCE = new BigDecimal("10000");
    private static final int LEVERAGE = 20;
    private static final int FROM_YEAR = 2021;

    /** N=1 校验：组合引擎跑单币，必须与单币引擎逐笔一致（组合引擎正确性的地基）。C/T 两种入场模式都验。 */
    @Test
    void verifyN1MatchesSingleEngine() throws Exception {
        Connection con = openDb();
        if (con == null) return;
        try (con) {
            String sym = "SOLUSDT";
            TurtleParams base = TurtleParams.defaults();
            for (boolean stopEntry : new boolean[]{false, true}) {
                TurtleParams params = new TurtleParams(base.decisionTfMillis(), base.entryLookback(),
                        base.exitLookback(), base.atrPeriod(), base.stopAtrMult(), stopEntry);
                long start = yearStartUtc(FROM_YEAR);
                Long latest = latestCloseTime(con, sym);
                long end = latest + 1;
                List<KlineBar> bars = loadBars(con, sym, start - warmupMs(params), end);
                int warmupBars = (int) bars.stream().filter(b -> b.closeTime() < start).count();

                BacktestResult single = new StrategyKlineBacktestEngine(
                        new TurtleStrategy(params, List.of(sym)), sym, bars, BALANCE, LEVERAGE, warmupBars, start, end).run();
                BacktestResult port = new PortfolioBacktestEngine(
                        List.of(sym), Map.of(sym, bars), BALANCE, LEVERAGE, start, end, params).run();

                System.out.printf(Locale.ROOT,
                        "N=1 SOL %s | 单币引擎 trades=%d ret=%.4f%% finalEq=%s%n              组合引擎 trades=%d ret=%.4f%% finalEq=%s%n",
                        stopEntry ? "T(触价)" : "C(收盘)",
                        single.totalTrades(), single.returnPct() * 100, single.finalEquity().toPlainString(),
                        port.totalTrades(), port.returnPct() * 100, port.finalEquity().toPlainString());

                assertThat(port.totalTrades()).isEqualTo(single.totalTrades());
                assertThat(port.finalEquity().compareTo(single.finalEquity())).isZero();
                assertThat(port.maxDrawdownPct()).isEqualTo(single.maxDrawdownPct());
            }
        }
    }

    /** 4 币组合回测：输出组合绩效 + 逐币贡献。 */
    @Test
    void runPortfolio() throws Exception {
        Connection con = openDb();
        if (con == null) return;
        try (con) {
            TurtleParams params = TurtleParams.defaults();
            long start = yearStartUtc(FROM_YEAR);
            long end = Long.MAX_VALUE;
            for (String s : COINS) {
                Long latest = latestCloseTime(con, s);
                if (latest == null) {
                    System.out.println(s + " 无数据，中止");
                    return;
                }
                end = Math.min(end, latest + 1);   // 取最早结束，保证全程 4 币都在场
            }
            Map<String, List<KlineBar>> barsBySymbol = new LinkedHashMap<>();
            for (String s : COINS) barsBySymbol.put(s, loadBars(con, s, start - warmupMs(params), end));

            PortfolioBacktestEngine engine = new PortfolioBacktestEngine(
                    COINS, barsBySymbol, BALANCE, LEVERAGE, start, end, params);
            BacktestResult r = engine.run();

            System.out.printf(Locale.ROOT,
                    "%n#### TURTLE 组合 %s (4H %d/%d, 1万U, 20x, risk=1%%, %d~) ####%n",
                    COINS, params.entryLookback(), params.exitLookback(), FROM_YEAR);
            System.out.printf(Locale.ROOT,
                    "组合  trades=%d  win=%.1f%%  pf=%.3f  ret=%.2f%%  maxDD=%.2f%%  sharpe=%.3f  avgR=%.3f  fees=%.2f%n",
                    r.totalTrades(), r.winRate() * 100, r.profitFactor(), r.returnPct() * 100,
                    r.maxDrawdownPct() * 100, r.sharpeRatio(), r.avgR(), r.totalFees().doubleValue());

            Map<String, BigDecimal> pnlBy = new LinkedHashMap<>();
            Map<String, Integer> cntBy = new LinkedHashMap<>();
            for (var ct : engine.closedTrades()) {
                pnlBy.merge(ct.symbol(), ct.pnl(), BigDecimal::add);
                cntBy.merge(ct.symbol(), 1, Integer::sum);
            }
            System.out.printf(Locale.ROOT, "%n%-10s %7s %12s%n", "symbol", "trades", "pnl(U)");
            for (String s : COINS) {
                System.out.printf(Locale.ROOT, "%-10s %7d %12.2f%n",
                        s, cntBy.getOrDefault(s, 0), pnlBy.getOrDefault(s, BigDecimal.ZERO).doubleValue());
            }
        }
    }

    // ---- DB 及口径辅助（与 TurtleStrategyDbRun 同款）----
    private static long warmupMs(TurtleParams p) {
        return (long) (Math.max(p.entryLookback(), p.atrPeriod()) + 4) * p.decisionTfMillis();
    }

    private static Connection openDb() {
        try {
            return DriverManager.getConnection(URL, USER, PASSWORD);
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过组合回测: " + e.getMessage());
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
