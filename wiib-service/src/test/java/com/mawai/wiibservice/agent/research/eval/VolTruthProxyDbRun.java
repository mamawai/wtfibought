package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.eval.VolTruthProxyDiagnostic.DmRow;
import com.mawai.wiibservice.agent.research.eval.VolTruthProxyDiagnostic.TruthBlock;
import com.mawai.wiibservice.agent.research.eval.VolTruthProxyDiagnostic.VolTruthProxyReport;
import com.mawai.wiibcommon.market.KlineBar;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 手动 DB 运行器（非 *Test 命名 → 默认/全量构建不跑；仅 -Dtest=VolTruthProxyDbRun 显式触发）。
 * 直连本地 postgres 读 BTC 5m → 跑 vol 真值代理诊断 → 打印三真值并排 QLIKE/DM 决策表。不启 Spring app。
 *
 * 跑法（先 1 年子集验证，再全历史）：
 *   mvn -pl wiib-service -am test -Dtest=VolTruthProxyDbRun -DskipTests=false \
 *       -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false \
 *       -Dvol.diag.symbol=BTCUSDT -Dvol.diag.days=365 -Dvol.diag.horizon=24
 */
class VolTruthProxyDbRun {

    private static final String URL = "jdbc:postgresql://localhost:5432/wiib";
    private static final String USER = "mawai";
    private static final String PASSWORD = "LOCAL_PASSWORD";

    @Test
    void runDiagnostic() throws Exception {
        String symbol = System.getProperty("vol.diag.symbol", "BTCUSDT");
        int days = Integer.getInteger("vol.diag.days", 365);
        String horizonsCsv = System.getProperty("vol.diag.horizons", "6,12,24");

        Connection con;
        try {
            con = DriverManager.getConnection(URL, USER, PASSWORD);
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过 DB 运行器: " + e.getMessage());
            return;
        }
        try (con) {
            long max = maxOpenTime(con, symbol);
            Assumptions.assumeTrue(max > 0, "库里无 " + symbol + " 5m 数据");
            long from = max - days * 24L * 3_600_000L;
            long bars0 = System.nanoTime();
            List<KlineBar> bars = loadBars(con, symbol, from, max + 1);   // 只加载一次，多 horizon 复用
            System.out.printf("加载 %s 5m bars=%d (最近 %d 天) 耗时 %.1fs%n",
                    symbol, bars.size(), days, (System.nanoTime() - bars0) / 1e9);

            for (String h : horizonsCsv.split(",")) {
                int horizonHours = Integer.parseInt(h.trim());
                long t0 = System.nanoTime();
                VolTruthProxyReport report = VolTruthProxyDiagnostic.run(
                        symbol, ForecastHorizon.fromHours(horizonHours), bars, EvalParams.defaults());
                System.out.printf("H%d 诊断耗时 %.1fs%n", horizonHours, (System.nanoTime() - t0) / 1e9);
                printReport(report);
                assertThat(report.oosPoints()).isGreaterThan(0);
            }
        }
    }

    private static long maxOpenTime(Connection con, String symbol) throws Exception {
        String sql = "SELECT max(open_time) FROM kline_history WHERE symbol=? AND interval_code='5m'";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static List<KlineBar> loadBars(Connection con, String symbol, long fromMs, long toMs) throws Exception {
        String sql = "SELECT open_time, close_time, open, high, low, close, volume FROM kline_history "
                + "WHERE symbol=? AND interval_code='5m' AND open_time>=? AND open_time<? ORDER BY open_time";
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

    private static void printReport(VolTruthProxyReport r) {
        List<String> fc = new ArrayList<>(r.truths().get(0).qlikeByForecaster().keySet());
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%n==== VolTruthProxy %s H%d | oos=%d ====%n", r.symbol(), r.horizonHours(), r.oosPoints()));
        sb.append("QLIKE (越低越好):\n");
        sb.append(String.format("  %-13s", "truth"));
        for (String f : fc) sb.append(String.format(" %12s", f));
        sb.append('\n');
        for (TruthBlock b : r.truths()) {
            sb.append(String.format("  %-13s", b.truth()));
            for (String f : fc) sb.append(String.format(" %12.6f", b.qlikeByForecaster().get(f)));
            sb.append('\n');
        }
        sb.append("\nDM (lossDiff>0 且 p<=.05 → model 显著胜 baseline):\n");
        sb.append(String.format("  %-24s %-26s %-26s %-26s%n", "pair",
                r.truths().get(0).truth(), r.truths().get(1).truth(), r.truths().get(2).truth()));
        int pairs = r.truths().get(0).dm().size();
        for (int p = 0; p < pairs; p++) {
            DmRow d0 = r.truths().get(0).dm().get(p);
            StringBuilder line = new StringBuilder(String.format("  %-24s", d0.model() + " vs " + d0.baseline()));
            for (TruthBlock b : r.truths()) {
                DmRow d = b.dm().get(p);
                line.append(String.format(" %s p=%.4f%-5s", fmtDiff(d.meanLossDiff()), d.pValue(),
                        d.modelBetter05() ? " WIN" : ""));
            }
            sb.append(line).append('\n');
        }
        System.out.println(sb);
    }

    private static String fmtDiff(double v) {
        return String.format("%+.2e", v);
    }
}
