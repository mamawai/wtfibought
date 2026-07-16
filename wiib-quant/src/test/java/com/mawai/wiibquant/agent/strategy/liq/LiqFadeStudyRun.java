package com.mawai.wiibquant.agent.strategy.liq;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 强平 fade 事件研究（判决 runner·判据先立）。
 * 回答一个问题：瀑布签名事件后是否存在 ≥2×交易成本的正向漂移（fade edge）——定生死，不调参。
 *
 * <p>事件（决策时点全部已闭合可观测）：① 15m 滚动收益击穿尾分位（价格瀑布）② premium 错位
 * ③ 15m taker 卖占比尖峰，三签名至少二，60min 冷却去重（同一场瀑布只记首触）。
 * 阈值只用发现段 [2021-12, 2024-01) 分位校准并冻结，复制段 [2024-01, 数据尾) 原值套用；
 * 换币时各币用自己的发现段重校，不看复制段。</p>
 *
 * <p><b>预注册判据（先立后跑，违者即否决，不回头调阈值重跑）</b>：
 * ① 主判据：主档(q0.5%) 事件后 1h 条件均值 ≥ +30bp 且 t≥2，发现/复制两段同向（复制段 ≥ +15bp），
 *    事件频率 ≥ 50 次/年；
 * ② 机制判据：premium 错位越深反弹越大（深度分桶单调），且 4h 不回吐（fwd4h ≥ fwd1h 一半）；
 * ③ 成本标尺：fade 天然 maker 进（接被迫抛压），maker进+maker出 ≈ 4bp、全 taker+双边滑点 ≈ 12~20bp，
 *    +30bp ≈ 2×最保守成本；
 * ④ 敏感度三档（q0.25/0.5/1.0）一次性并列打印，看响应面形状，不许挑格子。
 * SHORT-fade（上冲接空）对称测量，同判据独立判。</p>
 *
 * <p>统计口径提示：事件在崩盘日成簇，t 值按独立样本算偏乐观——逐年一致性表是更硬的判据。</p>
 *
 * <p><b>5m 口径复验 gate（-Dliq.study.barMinutes=5，判据先立）</b>：实盘策略层为 5m 收盘驱动，
 * 事件必须在 5m 决策粒度下存活才进入策略化——① 五币 fwd4h 两段均&gt;0 保持 ≥4/5 币；
 * ② 五币全期事件加权 fwd4h ≥ +30bp；③ 主档事件保留率（5m 事件数/1m 事件数）≥50%。
 * 未过 → 评估 1m 决策链改造，不降判据。</p>
 *
 * <p>跑法：mvn -pl wiib-quant -am test -Dtest=LiqFadeStudyRun -DskipTests=false \
 *       -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false \
 *       [-Dliq.study.symbol=ETHUSDT] [-Dliq.study.barMinutes=5] [-Dliq.study.dumpEvents=true]</p>
 */
class LiqFadeStudyRun {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/wiib";
    private static final String DEFAULT_USER = "mawai";
    private static final String DEFAULT_PASSWORD = com.mawai.wiibquant.LocalEnv.dbPassword();

    private static final long M1 = 60_000L;                      // 1m 装载粒度（DB 数据粒度）
    private static final int MIN_FLAGS = 2;                      // 三签名至少二
    // 窗口一律以分钟定义，bar 数按决策粒度(liq.study.barMinutes: 1=研究口径 / 5=实盘口径复验)换算
    private static final int FLUSH_MIN = 15, COOLDOWN_MIN = 60, OI_BACK_MIN = 30, MFE_MIN = 240;
    private static final int[] HORIZON_MIN = {15, 60, 240, 1440};
    private static long BAR;
    private static int FLUSH_K, COOLDOWN, OI_BACK, MFE_WIN;
    private static int[] HORIZONS;
    private static final double[] TIER_Q = {0.005, 0.0025, 0.01};
    private static final String[] TIER_LABEL = {"主档q0.5%", "敏感q0.25%", "敏感q1.0%"};

    /** 一档阈值层的检测结果；阈值本身只在校准处打印留档，不重复持有。 */
    private record TierResult(String label,
                              List<LiqFadeAudit.Event> longEvs, List<LiqFadeAudit.Event> shortEvs) {
    }

    @Test
    void runFadeStudy() throws Exception {
        String symbol = System.getProperty("liq.study.symbol", "ETHUSDT").trim().toUpperCase(Locale.ROOT);
        int barMin = Integer.getInteger("liq.study.barMinutes", 1);
        BAR = barMin * M1;
        FLUSH_K = FLUSH_MIN / barMin;
        COOLDOWN = COOLDOWN_MIN / barMin;
        OI_BACK = OI_BACK_MIN / barMin;
        MFE_WIN = MFE_MIN / barMin;
        HORIZONS = new int[HORIZON_MIN.length];
        for (int i = 0; i < HORIZON_MIN.length; i++) HORIZONS[i] = HORIZON_MIN[i] / barMin;

        Connection con;
        try {
            con = DriverManager.getConnection(
                    System.getProperty("liq.dbUrl", DEFAULT_URL),
                    System.getProperty("liq.dbUser", DEFAULT_USER),
                    System.getProperty("liq.dbPassword", DEFAULT_PASSWORD));
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过: " + e.getMessage());
            return;
        }

        LiqFadeAudit.Series s;
        try (con) {
            con.setAutoCommit(false);   // 让 fetchSize 生效, 2.4M 行流式读不憋内存
            long t0 = System.nanoTime();
            Loaded k = load(con, symbol, "kline_history", "open_time",
                    "SELECT open_time, high, low, close, volume FROM kline_history WHERE symbol=? AND interval_code='1m' ORDER BY open_time", 4);
            Loaded tk = load(con, symbol, "taker_flow_1m", "open_time",
                    "SELECT open_time, taker_buy_volume FROM taker_flow_1m WHERE symbol=? ORDER BY open_time", 1);
            Loaded pr = load(con, symbol, "premium_index_1m", "open_time",
                    "SELECT open_time, close FROM premium_index_1m WHERE symbol=? ORDER BY open_time", 1);
            Loaded mt = load(con, symbol, "futures_metrics_5m", "create_time",
                    "SELECT create_time, sum_open_interest FROM futures_metrics_5m WHERE symbol=? ORDER BY create_time", 1);
            assertThat(k.t()).as(symbol + " 1m klines").isNotEmpty();

            s = new LiqFadeAudit.Series(k.t(), k.cols()[0], k.cols()[1], k.cols()[2], k.cols()[3],
                    LiqFadeAudit.alignByTime(k.t(), tk.t(), tk.cols()[0]),
                    LiqFadeAudit.alignByTime(k.t(), pr.t(), pr.cols()[0]),
                    LiqFadeAudit.carryForward(k.t(), M1, mt.t(), mt.cols()[0]));
            if (barMin > 1) s = LiqFadeAudit.aggregate(s, barMin);   // 决策粒度复验：先聚合再算一切
            System.out.printf(Locale.ROOT,
                    "%n==== LiqFade 事件研究 %s | tf=%dm bars=%d [%s ~ %s] load=%.1fs ====%n",
                    symbol, barMin, s.n(), day(s.t()[0]), day(s.t()[s.n() - 1]), (System.nanoTime() - t0) / 1e9);
            System.out.printf(Locale.ROOT, "覆盖率: taker=%.2f%% premium=%.2f%% oi=%.2f%%%n",
                    cover(s.takerBuy()), cover(s.prem()), cover(s.oi()));
        }

        double[] ret15 = LiqFadeAudit.rollingRet(s.t(), s.close(), FLUSH_K, BAR);
        double[] sell15 = LiqFadeAudit.rollingSellShare(s.t(), s.vol(), s.takerBuy(), FLUSH_K, BAR);
        double[] oiChg30 = LiqFadeAudit.rollingRet(s.t(), s.oi(), OI_BACK, BAR);   // 同一滚动变化率函数, OI 骤降口径

        long dFrom = Math.max(utc(2021, 12, 1), s.t()[0]);
        long split = utc(2024, 1, 1);
        long tail = s.t()[s.n() - 1] + BAR;

        // 阈值：发现段分位校准并冻结（long=左尾, short=右尾对称）
        System.out.printf(Locale.ROOT, "%n---- 阈值 (发现段 %s~%s 分位校准, 冻结) ----%n", day(dFrom), day(split));
        System.out.printf("%-12s | LONG: flush15m<=  prem(bp)<=  sell>=  | SHORT: flush15m>=  prem(bp)>=  sell<=%n", "tier");
        TierResult[] tiers = new TierResult[TIER_Q.length];
        for (int ti = 0; ti < TIER_Q.length; ti++) {
            double q = TIER_Q[ti];
            double[] thL = {
                    LiqFadeAudit.quantile(ret15, s.t(), dFrom, split, q),
                    LiqFadeAudit.quantile(s.prem(), s.t(), dFrom, split, q),
                    LiqFadeAudit.quantile(sell15, s.t(), dFrom, split, 1 - q)};
            double[] thS = {
                    LiqFadeAudit.quantile(ret15, s.t(), dFrom, split, 1 - q),
                    LiqFadeAudit.quantile(s.prem(), s.t(), dFrom, split, 1 - q),
                    LiqFadeAudit.quantile(sell15, s.t(), dFrom, split, q)};
            List<LiqFadeAudit.Event> longEvs = LiqFadeAudit.detect(s, ret15, sell15, oiChg30,
                    true, thL[0], thL[1], thL[2], MIN_FLAGS, COOLDOWN, HORIZONS, MFE_WIN);
            List<LiqFadeAudit.Event> shortEvs = LiqFadeAudit.detect(s, ret15, sell15, oiChg30,
                    false, thS[0], thS[1], thS[2], MIN_FLAGS, COOLDOWN, HORIZONS, MFE_WIN);
            tiers[ti] = new TierResult(TIER_LABEL[ti], longEvs, shortEvs);
            System.out.printf(Locale.ROOT, "%-12s |       %+7.2f%%  %+9.1f  %6.3f  |        %+7.2f%%  %+9.1f  %6.3f%n",
                    TIER_LABEL[ti], thL[0] * 100, thL[1] * 10_000, thL[2],
                    thS[0] * 100, thS[1] * 10_000, thS[2]);
        }

        // 主档双侧全套明细
        printSide("LONG-fade (下杀接多)", tiers[0].longEvs(), s, dFrom, split, tail, true);
        printSide("SHORT-fade (上冲接空)", tiers[0].shortEvs(), s, dFrom, split, tail, false);

        // 敏感度三档并列（fwd1h）：看响应面, 不挑格
        System.out.printf(Locale.ROOT, "%n==== 敏感度三档汇总 (fwd1h, bp) ====%n");
        System.out.printf("%-6s %-12s | %6s %9s %6s | %6s %9s %6s%n",
                "side", "tier", "discN", "discFwd1h", "t", "replN", "replFwd1h", "t");
        for (TierResult tr : tiers) {
            sensRow("LONG", tr.label(), tr.longEvs(), dFrom, split, tail);
            sensRow("SHORT", tr.label(), tr.shortEvs(), dFrom, split, tail);
        }

        if (Boolean.getBoolean("liq.study.dumpEvents")) {
            System.out.println("EVENTHDR,side,timeUtc,flags,flush%,premBp,sell,oiChg%,fwd15m,fwd1h,fwd4h,fwd24h,mfe4h,mae4h(bp)");
            for (LiqFadeAudit.Event e : tiers[0].longEvs()) dumpEvent("LONG", e);
            for (LiqFadeAudit.Event e : tiers[0].shortEvs()) dumpEvent("SHORT", e);
        }
        assertThat(s.n()).isPositive();
    }

    // ==================== 打印 ====================

    /** 主档单侧全套：分段表 + 逐年一致性 + 机制分桶。 */
    private static void printSide(String title, List<LiqFadeAudit.Event> evs, LiqFadeAudit.Series s,
                                  long dFrom, long split, long tail, boolean longSide) {
        System.out.printf(Locale.ROOT, "%n==== %s 主档 ====%n", title);
        System.out.printf("%-12s %5s %6s | %8s %8s %8s %8s | %6s %6s | %8s %8s | %8s%n",
                "segment", "n", "ev/yr", "fwd15m", "fwd1h", "fwd4h", "fwd24h", "win1h%", "t1h", "mfe4h", "mae4h", "base1h");
        segRow("DISC(21-23)", filter(evs, dFrom, split), s, dFrom, split, longSide);
        segRow("REPL(24-26)", filter(evs, split, tail), s, split, tail, longSide);

        System.out.printf("%-6s %5s %9s %7s%n", "year", "n", "fwd1h", "win1h%");
        for (int y = 2021; y <= 2026; y++) {
            int yy = y;
            List<LiqFadeAudit.Event> ys = evs.stream()
                    .filter(e -> Instant.ofEpochMilli(e.time()).atZone(ZoneOffset.UTC).getYear() == yy).toList();
            LiqFadeAudit.Agg a = agg1h(ys);
            System.out.printf(Locale.ROOT, "%-6d %5d %+8.1fbp %6.1f%n", y, a.n(), bp(a.mean()), a.winPct());
        }

        System.out.println("-- 机制分桶 (主档全期; long 侧 premium 越负=错位越深) --");
        bucketTercile("premium(bp)", evs, e -> e.prem() * 10_000);
        bucketTercile("sell占比", evs, LiqFadeAudit.Event::sellShare);
        bucketSplit("OI30m骤降<=-0.5%", evs, e -> e.oiChg() <= -0.005,
                "OI其余", e -> !Double.isNaN(e.oiChg()) && e.oiChg() > -0.005);
        bucketSplit("签名flags=3", evs, e -> e.flags() == 3, "签名flags=2", e -> e.flags() == 2);
    }

    private static void segRow(String label, List<LiqFadeAudit.Event> evs, LiqFadeAudit.Series s,
                               long from, long to, boolean longSide) {
        double years = (to - from) / (365.25 * 86_400_000.0);
        LiqFadeAudit.Agg[] a = new LiqFadeAudit.Agg[HORIZONS.length];
        for (int j = 0; j < HORIZONS.length; j++) {
            int jj = j;
            a[j] = LiqFadeAudit.agg(evs.stream().mapToDouble(e -> e.fwd()[jj]).toArray());
        }
        LiqFadeAudit.Agg mfe = LiqFadeAudit.agg(evs.stream().mapToDouble(LiqFadeAudit.Event::mfe).toArray());
        LiqFadeAudit.Agg mae = LiqFadeAudit.agg(evs.stream().mapToDouble(LiqFadeAudit.Event::mae).toArray());
        LiqFadeAudit.Agg base = LiqFadeAudit.baseline(s, HORIZONS[1], from, to, longSide);
        System.out.printf(Locale.ROOT,
                "%-12s %5d %6.1f | %+8.1f %+8.1f %+8.1f %+8.1f | %6.1f %6.2f | %+8.1f %+8.1f | %+8.1f%n",
                label, a[1].n(), years > 0 ? a[1].n() / years : Double.NaN,
                bp(a[0].mean()), bp(a[1].mean()), bp(a[2].mean()), bp(a[3].mean()),
                a[1].winPct(), a[1].tStat(), bp(mfe.mean()), bp(mae.mean()), bp(base.mean()));
    }

    private static void sensRow(String side, String tier, List<LiqFadeAudit.Event> evs,
                                long dFrom, long split, long tail) {
        LiqFadeAudit.Agg d = agg1h(filter(evs, dFrom, split));
        LiqFadeAudit.Agg r = agg1h(filter(evs, split, tail));
        System.out.printf(Locale.ROOT, "%-6s %-12s | %6d %+9.1f %6.2f | %6d %+9.1f %6.2f%n",
                side, tier, d.n(), bp(d.mean()), d.tStat(), r.n(), bp(r.mean()), r.tStat());
    }

    /** 事件群体按特征三分位分桶，打 fwd1h/fwd4h/win——机制单调性一眼可见。 */
    private static void bucketTercile(String name, List<LiqFadeAudit.Event> evs, ToDoubleFunction<LiqFadeAudit.Event> f) {
        double[] vals = evs.stream().mapToDouble(f).filter(x -> !Double.isNaN(x)).sorted().toArray();
        if (vals.length < 9) {
            System.out.printf("  %-18s 样本不足(%d)%n", name, vals.length);
            return;
        }
        double c1 = vals[vals.length / 3], c2 = vals[2 * vals.length / 3];
        bucketRow(String.format(Locale.ROOT, "%s 低(<%.2f)", name, c1), evs,
                e -> f.applyAsDouble(e) < c1);
        bucketRow(String.format(Locale.ROOT, "%s 中", name), evs,
                e -> f.applyAsDouble(e) >= c1 && f.applyAsDouble(e) < c2);
        bucketRow(String.format(Locale.ROOT, "%s 高(>=%.2f)", name, c2), evs,
                e -> f.applyAsDouble(e) >= c2);
    }

    private static void bucketSplit(String nameA, List<LiqFadeAudit.Event> evs, Predicate<LiqFadeAudit.Event> pa,
                                    String nameB, Predicate<LiqFadeAudit.Event> pb) {
        bucketRow(nameA, evs, pa);
        bucketRow(nameB, evs, pb);
    }

    private static void bucketRow(String label, List<LiqFadeAudit.Event> evs, Predicate<LiqFadeAudit.Event> p) {
        List<LiqFadeAudit.Event> sub = evs.stream().filter(p).toList();
        LiqFadeAudit.Agg h1 = agg1h(sub);
        LiqFadeAudit.Agg h4 = LiqFadeAudit.agg(sub.stream().mapToDouble(e -> e.fwd()[2]).toArray());
        System.out.printf(Locale.ROOT, "  %-24s n=%-5d fwd1h=%+8.1fbp fwd4h=%+8.1fbp win1h=%5.1f%%%n",
                label, h1.n(), bp(h1.mean()), bp(h4.mean()), h1.winPct());
    }

    private static void dumpEvent(String side, LiqFadeAudit.Event e) {
        System.out.printf(Locale.ROOT, "EVENT,%s,%s,%d,%.2f,%.1f,%.3f,%.2f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f%n",
                side, Instant.ofEpochMilli(e.time()), e.flags(),
                e.flushRet() * 100, e.prem() * 10_000, e.sellShare(),
                Double.isNaN(e.oiChg()) ? Double.NaN : e.oiChg() * 100,
                bp(e.fwd()[0]), bp(e.fwd()[1]), bp(e.fwd()[2]), bp(e.fwd()[3]),
                bp(e.mfe()), bp(e.mae()));
    }

    // ==================== 工具 ====================

    private static List<LiqFadeAudit.Event> filter(List<LiqFadeAudit.Event> evs, long from, long to) {
        return evs.stream().filter(e -> e.time() >= from && e.time() < to).toList();
    }

    private static LiqFadeAudit.Agg agg1h(List<LiqFadeAudit.Event> evs) {
        return LiqFadeAudit.agg(evs.stream().mapToDouble(e -> e.fwd()[1]).toArray());
    }

    private static double bp(double x) {
        return x * 10_000;
    }

    private static double cover(double[] v) {
        long ok = Arrays.stream(v).filter(x -> !Double.isNaN(x)).count();
        return 100.0 * ok / v.length;
    }

    private static long utc(int y, int m, int d) {
        return LocalDate.of(y, m, d).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static String day(long ms) {
        return Instant.ofEpochMilli(ms).toString().substring(0, 10);
    }

    /** 时间戳升序装进原始数组（count 后精确分配；查询与 count 间有并发写入则截到先到者）。 */
    private record Loaded(long[] t, double[][] cols) {
    }

    private static Loaded load(Connection con, String symbol, String table, String tsCol,
                               String dataSql, int nCols) throws Exception {
        long cnt = 0;
        String extra = table.equals("kline_history") ? " AND interval_code='1m'" : "";
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT count(*) FROM " + table + " WHERE symbol=?" + extra)) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) cnt = rs.getLong(1);
            }
        }
        long[] t = new long[(int) cnt];
        double[][] cols = new double[nCols][(int) cnt];
        int i = 0;
        try (PreparedStatement ps = con.prepareStatement(dataSql)) {
            ps.setString(1, symbol);
            ps.setFetchSize(20_000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && i < cnt) {
                    t[i] = rs.getLong(1);
                    for (int c = 0; c < nCols; c++) cols[c][i] = rs.getDouble(2 + c);
                    i++;
                }
            }
        }
        if (i < cnt) {
            t = Arrays.copyOf(t, i);
            for (int c = 0; c < nCols; c++) cols[c] = Arrays.copyOf(cols[c], i);
        }
        System.out.printf(Locale.ROOT, "  loaded %-20s rows=%d%n", table, i);
        return new Loaded(t, cols);
    }
}
