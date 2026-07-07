package com.mawai.wiibquant.agent.strategy.liq;

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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LiqFade 引擎体检 runner（采纳口径：三签名2/3 → 次根市价接多 → 1h TIME_EXIT × 8%/10% 灾难框）。
 * 回答：事件研究的 close-to-close 漂移经过真实撮合（次根开盘 taker 进场、TIME_EXIT taker 出场+滑点、
 * 1% 风险定量、一次一仓）后净值还剩多少。判决口径统一加 -Dbacktest.slippageBps=5。
 *
 * <p><b>判决史（判据均先立后跑，消融 harness 已按惯例清退，明细留档提交信息）</b>：
 * ① 4h 基线终审：验证段 4/5 币净 PF&gt;1，但兑现率未达研究 fwd4h 的 60% → 归因=出场损耗非信号失效
 *    （ETH 反弹 1h 后回吐 + 固定成本 ~15bp + 次根进场滞后）；
 * ② 出场几何消融 hold{1h,2h,4h}×frame{pct,ATR}：1h×pct 唯一三判据全过（验证段 5/5 币 PF&gt;1、
 *    加权 +56.8bp vs 4h 的 +33.8、worstΔpf −0.30 压线），ATR 框因 SOL 劣化超线全灭 → 采纳 1h×pct；
 * ③ 采纳口径复检（24-26）：BTC 1.888 / ETH 1.400 / SOL 3.100(n=10) / DOGE 1.620 / XRP 1.829。
 * 注意：1h 优势偏近时代（训练段 3/5 币 PF&gt;1），最终仲裁 = testnet 前瞻。</p>
 *
 * <p>跑法：mvn -pl wiib-quant -am test -Dtest=LiqFadeStrategyDbRun -DskipTests=false \
 *   -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false -Dbacktest.slippageBps=5 \
 *   [-Dliqfade.bt.symbols=BTCUSDT,ETHUSDT,SOLUSDT,DOGEUSDT,XRPUSDT]</p>
 */
class LiqFadeStrategyDbRun {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/wiib";
    private static final String DEFAULT_USER = "mawai";
    private static final String DEFAULT_PASSWORD = "LOCAL_PASSWORD";
    private static final long M5 = 300_000L;
    private static final long WARMUP_MS = 6 * 3_600_000L;   // 策略仅需4根bar, 6h 富余

    private record SegResult(boolean hasGap, BacktestResult r) {
    }

    @Test
    void runEngineAdjudication() throws Exception {
        List<String> symbols = symbols();
        int leverage = Integer.getInteger("liqfade.bt.leverage", 5);
        BigDecimal balance = new BigDecimal(System.getProperty("liqfade.bt.balance", "100000"));
        long discFrom = utc(2021, 12, 1), split = utc(2024, 1, 1);

        Connection con;
        try {
            con = DriverManager.getConnection(
                    System.getProperty("liqfade.bt.dbUrl", DEFAULT_URL),
                    System.getProperty("liqfade.bt.dbUser", DEFAULT_USER),
                    System.getProperty("liqfade.bt.dbPassword", DEFAULT_PASSWORD));
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过: " + e.getMessage());
            return;
        }

        try (con) {
            System.out.printf(Locale.ROOT,
                    "%n#### LiqFade 引擎终审 (1%%风险, lev=%d, slippageBps=%s, 出场=%dh TIME_EXIT) symbols=%s ####%n",
                    leverage, System.getProperty("backtest.slippageBps", "0"),
                    LiqFadeParams.defaults().holdBars() / 12, symbols);
            for (String symbol : symbols) {
                Long latest = latestCloseTime(con, symbol);
                if (latest == null) {
                    System.out.printf("%-10s (无 5m 数据)%n", symbol);
                    continue;
                }
                long t0 = System.nanoTime();
                DbSide side = loadSideData(con, symbol);
                System.out.printf(Locale.ROOT, "%n==== %s (side: taker桶=%d prem桶=%d load=%.1fs) ====%n",
                        symbol, side.takerT.length, side.premT.length, (System.nanoTime() - t0) / 1e9);
                System.out.printf("%-12s %5s %6s %6s %7s %9s %8s %8s %8s  %s%n",
                        "segment", "n", "n/yr", "win%", "pf", "avgNetBp", "ret%", "maxDD%", "avgHold", "exits");

                segRow(con, "DISC(21-23)", symbol, side, discFrom, split, balance, leverage, latest);
                segRow(con, "REPL(24-26)", symbol, side, split, latest + 1, balance, leverage, latest);

                System.out.printf("%-6s %5s %8s %7s %9s%n", "year", "n", "ret%", "pf", "avgNetBp");
                int posYears = 0, validYears = 0;
                for (int y = 2021; y <= 2026; y++) {
                    SegResult sr = runWindow(con, symbol, side,
                            Math.max(utc(y, 1, 1), discFrom), utc(y + 1, 1, 1), balance, leverage, latest);
                    if (sr == null) continue;
                    BacktestResult r = sr.r();
                    System.out.printf(Locale.ROOT, "%-6d %5d %8.2f %7.3f %+9.1f%s%n",
                            y, r.totalTrades(), r.returnPct() * 100, Math.min(r.profitFactor(), 999),
                            avgNetBp(r), sr.hasGap() ? "  gap!" : "");
                    if (r.totalTrades() > 0) {
                        validYears++;
                        if (r.returnPct() > 0) posYears++;
                    }
                }
                System.out.printf(Locale.ROOT, "=> %s posYears=%d/%d%n", symbol, posYears, validYears);
            }
        }
    }

    private void segRow(Connection con, String label, String symbol, DbSide side,
                        long from, long toEx, BigDecimal balance, int leverage, long latest) throws Exception {
        SegResult sr = runWindow(con, symbol, side, from, toEx, balance, leverage, latest);
        if (sr == null) {
            System.out.printf("%-12s (无数据)%n", label);
            return;
        }
        BacktestResult r = sr.r();
        double years = (Math.min(toEx, latest + 1) - from) / (365.25 * 86_400_000.0);
        Map<String, Integer> exits = new LinkedHashMap<>();
        for (BacktestResult.Trade t : r.getTrades()) exits.merge(t.exitReason(), 1, Integer::sum);
        System.out.printf(Locale.ROOT, "%-12s %5d %6.1f %6.1f %7.3f %+9.1f %8.2f %8.2f %8.1f  %s%s%n",
                label, r.totalTrades(), r.totalTrades() / years, r.winRate() * 100,
                Math.min(r.profitFactor(), 999),   // 全胜段 grossLoss=0 → pf 溢出, 钳到999可读
                avgNetBp(r), r.returnPct() * 100, r.maxDrawdownPct() * 100, r.avgHoldBars(),
                exits, sr.hasGap() ? "  gap!" : "");
    }

    /** 每笔净收益(bp, 名义口径) = netPnl/(entry×qty)×1e4 的均值——与事件研究 fwd4h(bp) 直接可比。 */
    private static double avgNetBp(BacktestResult r) {
        return r.getTrades().stream()
                .filter(t -> t.entryPrice() != null && t.quantity() != null && t.quantity().signum() > 0)
                .mapToDouble(t -> t.pnl().doubleValue()
                        / (t.entryPrice().doubleValue() * t.quantity().doubleValue()) * 10_000)
                .average().orElse(Double.NaN);
    }

    private static SegResult runWindow(Connection con, String symbol, DbSide side,
                                       long fromMs, long toEx, BigDecimal balance, int leverage,
                                       long latestClose) throws Exception {
        long segEnd = Math.min(toEx, latestClose + 1);
        if (fromMs >= segEnd) return null;
        List<KlineBar> bars = loadBars5m(con, symbol, fromMs - WARMUP_MS, segEnd);
        if (bars.isEmpty()) return null;
        boolean hasGap = WindowedMarketView.firstBaseGapDescription(bars).isPresent();
        int warmupBars = (int) bars.stream().filter(b -> b.closeTime() < fromMs).count();
        // 每窗新建策略实例：冷却状态清零, 窗口间互不污染
        LiqFadeStrategy strategy = new LiqFadeStrategy(LiqFadeParams.defaults(), List.of(symbol), side);
        BacktestResult r = new StrategyKlineBacktestEngine(
                strategy, symbol, bars, balance, leverage, warmupBars, fromMs, segEnd).run();
        return new SegResult(hasGap, r);
    }

    // ==================== side data 装载（SQL 侧聚合到 5m 桶） ====================

    /** 单币 side data：taker=完整 5m 桶买量和(缺 1m 的残桶丢弃→NaN 保守)；premium=每 5m 桶末样本。 */
    private static final class DbSide implements LiqSideData {
        final long[] takerT;
        final double[] takerV;
        final long[] premT;      // 1m 采样 openTime
        final double[] premV;
        final long staleMaxMs = LiqFadeParams.defaults().premStaleMaxMs();

        DbSide(long[] takerT, double[] takerV, long[] premT, double[] premV) {
            this.takerT = takerT;
            this.takerV = takerV;
            this.premT = premT;
            this.premV = premV;
        }

        @Override
        public double takerBuy(String symbol, long bucketOpenMs) {
            int i = Arrays.binarySearch(takerT, bucketOpenMs);
            return i >= 0 ? takerV[i] : Double.NaN;
        }

        @Override
        public double premiumAt(String symbol, long atMs) {
            // 可见性: 1m 样本在 openTime+59_999 收盘, ≤atMs 才可见; 超时效(staleMax)按缺数据
            int i = Arrays.binarySearch(premT, atMs - 59_999);
            if (i < 0) i = -i - 2;
            if (i < 0) return Double.NaN;
            long visibleAt = premT[i] + 59_999;
            return atMs - visibleAt > staleMaxMs ? Double.NaN : premV[i];
        }
    }

    private static DbSide loadSideData(Connection con, String symbol) throws Exception {
        long[] tt = new long[600_000];
        double[] tv = new double[600_000];
        int tn = 0;
        String takerSql = """
                SELECT (open_time/300000)*300000 AS b, sum(taker_buy_volume) AS s
                FROM taker_flow_1m WHERE symbol=?
                GROUP BY b HAVING count(*)=5 ORDER BY b
                """;
        try (PreparedStatement ps = con.prepareStatement(takerSql)) {
            ps.setString(1, symbol);
            ps.setFetchSize(20_000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && tn < tt.length) {
                    tt[tn] = rs.getLong(1);
                    tv[tn] = rs.getDouble(2);
                    tn++;
                }
            }
        }
        long[] pt = new long[600_000];
        double[] pv = new double[600_000];
        int pn = 0;
        String premSql = """
                SELECT DISTINCT ON (open_time/300000) open_time, close
                FROM premium_index_1m WHERE symbol=?
                ORDER BY open_time/300000, open_time DESC
                """;
        try (PreparedStatement ps = con.prepareStatement(premSql)) {
            ps.setString(1, symbol);
            ps.setFetchSize(20_000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && pn < pt.length) {
                    pt[pn] = rs.getLong(1);
                    pv[pn] = rs.getDouble(2);
                    pn++;
                }
            }
        }
        return new DbSide(Arrays.copyOf(tt, tn), Arrays.copyOf(tv, tn),
                Arrays.copyOf(pt, pn), Arrays.copyOf(pv, pn));
    }

    // ==================== 通用 ====================

    private static List<KlineBar> loadBars5m(Connection con, String symbol, long fromMs, long toMs) throws Exception {
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

    private static long utc(int y, int m, int d) {
        return LocalDate.of(y, m, d).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static List<String> symbols() {
        String raw = System.getProperty("liqfade.bt.symbols",
                "BTCUSDT,ETHUSDT,SOLUSDT,DOGEUSDT,XRPUSDT");
        List<String> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            String s = token.trim().toUpperCase(Locale.ROOT);
            if (!s.isBlank()) out.add(s);
        }
        assertThat(out).isNotEmpty();
        return List.copyOf(out);
    }
}
