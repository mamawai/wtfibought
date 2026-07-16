package com.mawai.wiibquant.agent.strategy.liq;

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
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 强平 fade 取证 · 历史数据回填(手动 runner)。
 *
 * <p>data.binance.vision → 本地 PG 四路数据(默认 ETHUSDT, 2021-12 起=metrics 最早可得月)：
 * ① 1m K线 → kline_history(复用, interval_code='1m')
 * ② OI/多空比 metrics 5m → futures_metrics_5m(建表)
 * ③ premiumIndex 1m(perp-指数基差, 瀑布判别器) → premium_index_1m(建表)
 * ④ fundingRate 结算值 → funding_rate(建表)
 *
 * <p>幂等 ON CONFLICT DO NOTHING, 已满区段跳过下载, 断网/中断直接重跑即可续传。
 * 月度文件为主, 当月或缺月退化到 daily 文件; metrics 只有 daily, 6 线程并行拉。
 *
 * <p>跑法：mvn -pl wiib-quant -am test -Dtest=LiqDataBackfillRun#backfillAll -DskipTests=false \
 *        -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false [-Dliq.symbol=ETHUSDT]
 */
class LiqDataBackfillRun {

    private static final String DEFAULT_URL =
            "jdbc:postgresql://localhost:5432/wiib?reWriteBatchedInserts=true";
    private static final String DEFAULT_USER = "mawai";
    private static final String DEFAULT_PASSWORD = com.mawai.wiibquant.LocalEnv.dbPassword();
    private static final String VISION = "https://data.binance.vision/data/futures/um";
    private static final DateTimeFormatter METRICS_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String SYMBOL = System.getProperty("liq.symbol", "ETHUSDT").toUpperCase();
    // metrics 数据集 2021-12-01 才开始发布, 更早无 OI ⇒ 事件检测不可行, 故全部数据集统一从这天起
    private static final LocalDate START = LocalDate.parse(System.getProperty("liq.from", "2021-12-01"));
    // 数据集过滤: 逗号分隔子集(klines/premium/metrics/funding), 默认全跑。多币复制只需 klines,funding 时省 80% 时间
    private static final java.util.Set<String> DATASETS =
            java.util.Set.of(System.getProperty("liq.datasets", "klines,premium,metrics,funding").split(","));

    @Test
    void backfillAll() throws Exception {
        Connection con;
        try {
            con = connect();
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过回填: " + e.getMessage());
            return;
        }
        try (con) {
            createTables(con);
            if (DATASETS.contains("klines")) backfillKlines1m(con);
            if (DATASETS.contains("premium")) backfillPremium1m(con);
            if (DATASETS.contains("metrics")) backfillMetrics();   // daily 并行, 每线程独立连接
            if (DATASETS.contains("funding")) backfillFunding(con);
            printCatalog(con);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                System.getProperty("liq.dbUrl", DEFAULT_URL),
                System.getProperty("liq.dbUser", DEFAULT_USER),
                System.getProperty("liq.dbPassword", DEFAULT_PASSWORD));
    }

    // ==================== 建表 ====================

    private static void createTables(Connection con) throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS futures_metrics_5m (
                      symbol VARCHAR(20) NOT NULL,
                      create_time BIGINT NOT NULL,
                      sum_open_interest NUMERIC(26,8),
                      sum_open_interest_value NUMERIC(26,8),
                      count_toptrader_ls_ratio NUMERIC(18,8),
                      sum_toptrader_ls_ratio NUMERIC(18,8),
                      count_ls_ratio NUMERIC(18,8),
                      sum_taker_ls_vol_ratio NUMERIC(18,8),
                      PRIMARY KEY (symbol, create_time)
                    )""");
            st.execute("COMMENT ON TABLE futures_metrics_5m IS " +
                    "'合约OI/多空比 5m (data.binance.vision metrics, create_time=UTC epoch ms)'");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS premium_index_1m (
                      symbol VARCHAR(20) NOT NULL,
                      open_time BIGINT NOT NULL,
                      open NUMERIC(20,10),
                      high NUMERIC(20,10),
                      low NUMERIC(20,10),
                      close NUMERIC(20,10),
                      PRIMARY KEY (symbol, open_time)
                    )""");
            st.execute("COMMENT ON TABLE premium_index_1m IS " +
                    "'perp溢价指数 1m K线 (perp相对指数价的basis比例, 瀑布vs真跌判别器)'");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS funding_rate (
                      symbol VARCHAR(20) NOT NULL,
                      calc_time BIGINT NOT NULL,
                      interval_hours INT,
                      rate NUMERIC(18,10),
                      PRIMARY KEY (symbol, calc_time)
                    )""");
            st.execute("COMMENT ON TABLE funding_rate IS '资金费率结算值 (每8h一条)'");
        }
        System.out.println("[建表] futures_metrics_5m / premium_index_1m / funding_rate 就绪");
    }

    // ==================== ① 1m K线 → kline_history ====================

    private void backfillKlines1m(Connection con) throws Exception {
        System.out.printf("%n[1m K线] %s [%s, 今天) → kline_history%n", SYMBOL, START);
        YearMonth from = YearMonth.from(START);
        YearMonth now = YearMonth.now(ZoneOffset.UTC);
        int added = 0, skipped = 0;
        for (YearMonth ym = from; !ym.isAfter(now); ym = ym.plusMonths(1)) {
            long mStart = ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
            long mEnd = ym.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
            long expected = (mEnd - mStart) / 60_000L;
            if (countKlines(con, mStart, mEnd) >= expected * 99 / 100) { skipped++; continue; }
            List<String[]> rows = downloadCsvZip(
                    VISION + "/monthly/klines/" + SYMBOL + "/1m/" + SYMBOL + "-1m-" + ym + ".zip");
            if (rows != null) {
                added += insertKlines(con, rows);
                System.out.printf("  %s 月度 +%d%n", ym, rows.size());
            } else {
                // 月度文件缺(当月未发布) → 按天补到昨天为止
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                for (LocalDate d = ym.atDay(1); !d.isAfter(ym.atEndOfMonth()) && d.isBefore(today); d = d.plusDays(1)) {
                    long dStart = d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
                    if (countKlines(con, dStart, dStart + 86_400_000L) >= 1440 * 99 / 100) continue;
                    List<String[]> day = downloadCsvZip(
                            VISION + "/daily/klines/" + SYMBOL + "/1m/" + SYMBOL + "-1m-" + d + ".zip");
                    if (day == null) continue;
                    added += insertKlines(con, day);
                    System.out.printf("  %s daily +%d%n", d, day.size());
                }
            }
        }
        System.out.printf("[1m K线] 写入=%d 月满跳过=%d%n", added, skipped);
    }

    private static long countKlines(Connection con, long fromMs, long toMs) throws Exception {
        String sql = "SELECT count(*) FROM kline_history " +
                "WHERE symbol=? AND interval_code='1m' AND open_time>=? AND open_time<?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, SYMBOL);
            ps.setLong(2, fromMs);
            ps.setLong(3, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private static int insertKlines(Connection con, List<String[]> rows) throws Exception {
        String sql = """
                INSERT INTO kline_history
                  (symbol, interval_code, open_time, close_time, open, high, low, close, volume)
                VALUES (?, '1m', ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (symbol, interval_code, open_time) DO NOTHING
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int n = 0;
            for (String[] f : rows) {
                ps.setString(1, SYMBOL);
                ps.setLong(2, normMs(Long.parseLong(f[0])));
                ps.setLong(3, normMs(Long.parseLong(f[6])));
                ps.setBigDecimal(4, new BigDecimal(f[1]));
                ps.setBigDecimal(5, new BigDecimal(f[2]));
                ps.setBigDecimal(6, new BigDecimal(f[3]));
                ps.setBigDecimal(7, new BigDecimal(f[4]));
                ps.setBigDecimal(8, new BigDecimal(f[5]));
                ps.addBatch();
                if (++n % 5000 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
        return rows.size();
    }

    // ==================== ② premiumIndex 1m → premium_index_1m ====================

    private void backfillPremium1m(Connection con) throws Exception {
        System.out.printf("%n[premiumIndex 1m] %s [%s, 今天) → premium_index_1m%n", SYMBOL, START);
        YearMonth from = YearMonth.from(START);
        YearMonth now = YearMonth.now(ZoneOffset.UTC);
        int added = 0, skipped = 0;
        for (YearMonth ym = from; !ym.isAfter(now); ym = ym.plusMonths(1)) {
            long mStart = ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
            long mEnd = ym.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
            long expected = (mEnd - mStart) / 60_000L;
            if (countIn(con, "premium_index_1m", "open_time", mStart, mEnd) >= expected * 99 / 100) {
                skipped++;
                continue;
            }
            List<String[]> rows = downloadCsvZip(
                    VISION + "/monthly/premiumIndexKlines/" + SYMBOL + "/1m/" + SYMBOL + "-1m-" + ym + ".zip");
            if (rows != null) {
                added += insertPremium(con, rows);
                System.out.printf("  %s 月度 +%d%n", ym, rows.size());
            } else {
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                for (LocalDate d = ym.atDay(1); !d.isAfter(ym.atEndOfMonth()) && d.isBefore(today); d = d.plusDays(1)) {
                    long dStart = d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
                    if (countIn(con, "premium_index_1m", "open_time", dStart, dStart + 86_400_000L) >= 1440 * 99 / 100) continue;
                    List<String[]> day = downloadCsvZip(
                            VISION + "/daily/premiumIndexKlines/" + SYMBOL + "/1m/" + SYMBOL + "-1m-" + d + ".zip");
                    if (day == null) continue;
                    added += insertPremium(con, day);
                    System.out.printf("  %s daily +%d%n", d, day.size());
                }
            }
        }
        System.out.printf("[premiumIndex 1m] 写入=%d 月满跳过=%d%n", added, skipped);
    }

    private static int insertPremium(Connection con, List<String[]> rows) throws Exception {
        String sql = """
                INSERT INTO premium_index_1m (symbol, open_time, open, high, low, close)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (symbol, open_time) DO NOTHING
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int n = 0;
            for (String[] f : rows) {
                ps.setString(1, SYMBOL);
                ps.setLong(2, normMs(Long.parseLong(f[0])));
                ps.setBigDecimal(3, new BigDecimal(f[1]));
                ps.setBigDecimal(4, new BigDecimal(f[2]));
                ps.setBigDecimal(5, new BigDecimal(f[3]));
                ps.setBigDecimal(6, new BigDecimal(f[4]));
                ps.addBatch();
                if (++n % 5000 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
        return rows.size();
    }

    // ==================== ③ metrics 5m (OI/多空比, 仅daily) → futures_metrics_5m ====================

    private void backfillMetrics() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = START; d.isBefore(today); d = d.plusDays(1)) dates.add(d);
        System.out.printf("%n[metrics 5m] %s %d 天, 6线程 → futures_metrics_5m%n", SYMBOL, dates.size());

        AtomicInteger added = new AtomicInteger(), skipped = new AtomicInteger(), missing = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            // 按线程分片, 每线程独立连接顺序处理自己的日期子集
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 6; t++) {
                final int shard = t;
                futures.add(pool.submit(() -> {
                    try (Connection con = connect()) {
                        for (int i = shard; i < dates.size(); i += 6) {
                            LocalDate d = dates.get(i);
                            long dStart = d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
                            // 288根/天, ≥285视为已满
                            if (countIn(con, "futures_metrics_5m", "create_time", dStart, dStart + 86_400_000L) >= 285) {
                                skipped.incrementAndGet();
                                continue;
                            }
                            List<String[]> rows = downloadCsvZip(
                                    VISION + "/daily/metrics/" + SYMBOL + "/" + SYMBOL + "-metrics-" + d + ".zip");
                            if (rows == null) { missing.incrementAndGet(); continue; }
                            added.addAndGet(insertMetrics(con, rows));
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (Future<?> f : futures) f.get();   // 任一线程异常直接抛出, 不静默吞
        } finally {
            pool.shutdown();
        }
        System.out.printf("[metrics 5m] 写入=%d 日满跳过=%d 无文件=%d%n", added.get(), skipped.get(), missing.get());
    }

    private static int insertMetrics(Connection con, List<String[]> rows) throws Exception {
        String sql = """
                INSERT INTO futures_metrics_5m
                  (symbol, create_time, sum_open_interest, sum_open_interest_value,
                   count_toptrader_ls_ratio, sum_toptrader_ls_ratio, count_ls_ratio, sum_taker_ls_vol_ratio)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (symbol, create_time) DO NOTHING
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int n = 0;
            for (String[] f : rows) {
                // create_time 是 "yyyy-MM-dd HH:mm:ss" UTC 字符串, 统一转 epoch ms
                long ts = LocalDateTime.parse(f[0], METRICS_TS).toInstant(ZoneOffset.UTC).toEpochMilli();
                ps.setString(1, SYMBOL);
                ps.setLong(2, ts);
                for (int c = 2; c <= 7; c++) {
                    String v = f[c];
                    ps.setBigDecimal(c + 1, v == null || v.isBlank() ? null : new BigDecimal(v));
                }
                ps.addBatch();
                if (++n % 5000 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
        return rows.size();
    }

    // ==================== ④ fundingRate → funding_rate ====================

    private void backfillFunding(Connection con) throws Exception {
        System.out.printf("%n[funding] %s [%s, 当月] → funding_rate%n", SYMBOL, YearMonth.from(START));
        YearMonth from = YearMonth.from(START);
        YearMonth now = YearMonth.now(ZoneOffset.UTC);
        int added = 0, missing = 0;
        String sql = """
                INSERT INTO funding_rate (symbol, calc_time, interval_hours, rate)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (symbol, calc_time) DO NOTHING
                """;
        for (YearMonth ym = from; !ym.isAfter(now); ym = ym.plusMonths(1)) {
            // 文件极小(~90行/月), 不做已满检查, 直接幂等重灌
            List<String[]> rows = downloadCsvZip(
                    VISION + "/monthly/fundingRate/" + SYMBOL + "/" + SYMBOL + "-fundingRate-" + ym + ".zip");
            if (rows == null) { missing++; continue; }   // 当月未发布 → 尾部缺口, 审计影响可忽略
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                for (String[] f : rows) {
                    ps.setString(1, SYMBOL);
                    ps.setLong(2, normMs(Long.parseLong(f[0])));
                    ps.setInt(3, (int) Double.parseDouble(f[1]));
                    ps.setBigDecimal(4, new BigDecimal(f[2]));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            added += rows.size();
        }
        System.out.printf("[funding] 写入=%d 无文件月=%d%n", added, missing);
    }

    // ==================== ⑤ taker 流量 1m → taker_flow_1m ====================

    /**
     * taker 流量回填：同一批 1m kline 文件里当时丢弃的 7/8/9/10 列
     * (quote_volume/count/taker_buy_volume/taker_buy_quote_volume)。
     * 核心用途：主动卖占比 = 1 - taker_buy_volume/volume = 强制卖压签名(proxy v2)。
     * 默认只回填 2026-04(v2 代理校验窗口)；-Dliq.flowFrom/-Dliq.flowTo=yyyy-MM 扩区间。
     *
     * <p>跑法：mvn -pl wiib-quant -am test -Dtest=LiqDataBackfillRun#backfillTakerFlow -DskipTests=false \
     *        -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false
     */
    @Test
    void backfillTakerFlow() throws Exception {
        YearMonth from = YearMonth.parse(System.getProperty("liq.flowFrom", "2026-04"));
        YearMonth to = YearMonth.parse(System.getProperty("liq.flowTo", "2026-04"));
        Connection con;
        try {
            con = connect();
        } catch (Exception e) {
            Assumptions.abort("本地 postgres 不可达，跳过回填: " + e.getMessage());
            return;
        }
        try (con) {
            try (Statement st = con.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS taker_flow_1m (
                          symbol VARCHAR(20) NOT NULL,
                          open_time BIGINT NOT NULL,
                          quote_volume NUMERIC(24,8),
                          trade_count INT,
                          taker_buy_volume NUMERIC(24,8),
                          taker_buy_quote_volume NUMERIC(24,8),
                          PRIMARY KEY (symbol, open_time)
                        )""");
                st.execute("COMMENT ON TABLE taker_flow_1m IS " +
                        "'1m taker流量(kline文件7-10列): 主动卖占比=1-taker_buy_volume/volume 为强制卖压签名'");
            }
            System.out.printf("%n[taker流量 1m] %s [%s, %s] → taker_flow_1m%n", SYMBOL, from, to);
            int added = 0, skipped = 0;
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            for (YearMonth ym = from; !ym.isAfter(to); ym = ym.plusMonths(1)) {
                long mStart = ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
                long mEnd = ym.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
                long expected = (mEnd - mStart) / 60_000L;
                if (countIn(con, "taker_flow_1m", "open_time", mStart, mEnd) >= expected * 99 / 100) {
                    skipped++;
                    continue;
                }
                List<String[]> rows = downloadCsvZip(
                        VISION + "/monthly/klines/" + SYMBOL + "/1m/" + SYMBOL + "-1m-" + ym + ".zip");
                if (rows != null) {
                    added += insertTakerFlow(con, rows);
                    System.out.printf("  %s 月度 +%d%n", ym, rows.size());
                } else {
                    for (LocalDate d = ym.atDay(1); !d.isAfter(ym.atEndOfMonth()) && d.isBefore(today); d = d.plusDays(1)) {
                        long dStart = d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
                        if (countIn(con, "taker_flow_1m", "open_time", dStart, dStart + 86_400_000L) >= 1440 * 99 / 100) continue;
                        List<String[]> day = downloadCsvZip(
                                VISION + "/daily/klines/" + SYMBOL + "/1m/" + SYMBOL + "-1m-" + d + ".zip");
                        if (day == null) continue;
                        added += insertTakerFlow(con, day);
                        System.out.printf("  %s daily +%d%n", d, day.size());
                    }
                }
            }
            System.out.printf("[taker流量 1m] 写入=%d 月满跳过=%d%n", added, skipped);
        }
    }

    private static int insertTakerFlow(Connection con, List<String[]> rows) throws Exception {
        String sql = """
                INSERT INTO taker_flow_1m
                  (symbol, open_time, quote_volume, trade_count, taker_buy_volume, taker_buy_quote_volume)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (symbol, open_time) DO NOTHING
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int n = 0;
            for (String[] f : rows) {
                ps.setString(1, SYMBOL);
                ps.setLong(2, normMs(Long.parseLong(f[0])));
                ps.setBigDecimal(3, new BigDecimal(f[7]));
                ps.setInt(4, (int) Double.parseDouble(f[8]));
                ps.setBigDecimal(5, new BigDecimal(f[9]));
                ps.setBigDecimal(6, new BigDecimal(f[10]));
                ps.addBatch();
                if (++n % 5000 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
        return rows.size();
    }

    // ==================== 公共 ====================

    /** 下载 zip 内单个 CSV 并按行 split；404 → null；瞬时网络错误重试 3 次。跳过非数字开头的头行。 */
    private static List<String[]> downloadCsvZip(String url) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
                c.setConnectTimeout(15_000);
                c.setReadTimeout(180_000);
                int code = c.getResponseCode();
                if (code == 404) { c.disconnect(); return null; }
                if (code != 200) throw new IllegalStateException("HTTP " + code + " " + url);
                List<String[]> out = new ArrayList<>();
                try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(c.getInputStream())) {
                    if (zis.getNextEntry() == null) return out;
                    BufferedReader br = new BufferedReader(new InputStreamReader(zis));
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.isEmpty()) continue;
                        // 部分文件数值字段带引号("2.29"), 先逐字段去引号, 再用首字段判头行
                        String[] f = line.split(",");
                        for (int i = 0; i < f.length; i++) f[i] = unquote(f[i]);
                        if (f[0].isEmpty() || !Character.isDigit(f[0].charAt(0))) continue;
                        out.add(f);
                    }
                }
                return out;
            } catch (Exception e) {
                last = e;
                Thread.sleep(2000L * attempt);
            }
        }
        throw last;
    }

    /** 去掉字段首尾成对引号并 trim。 */
    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    /** vision 2025+ 文件时间戳可能为微秒(16位)；>1e14 即除以 1000 归一到毫秒。 */
    private static long normMs(long ts) {
        return ts > 100_000_000_000_000L ? ts / 1000 : ts;
    }

    private static long countIn(Connection con, String table, String tsCol, long fromMs, long toMs) throws Exception {
        String sql = "SELECT count(*) FROM " + table + " WHERE symbol=? AND " + tsCol + ">=? AND " + tsCol + "<?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, SYMBOL);
            ps.setLong(2, fromMs);
            ps.setLong(3, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** 回填后清点四路数据的覆盖范围, 供人工核对。 */
    private static void printCatalog(Connection con) throws Exception {
        System.out.printf("%n==== 回填后清点 (%s) ====%n", SYMBOL);
        String[][] specs = {
                {"kline_history(1m)", "SELECT count(*), min(open_time), max(open_time) FROM kline_history WHERE symbol='" + SYMBOL + "' AND interval_code='1m'"},
                {"futures_metrics_5m", "SELECT count(*), min(create_time), max(create_time) FROM futures_metrics_5m WHERE symbol='" + SYMBOL + "'"},
                {"premium_index_1m", "SELECT count(*), min(open_time), max(open_time) FROM premium_index_1m WHERE symbol='" + SYMBOL + "'"},
                {"funding_rate", "SELECT count(*), min(calc_time), max(calc_time) FROM funding_rate WHERE symbol='" + SYMBOL + "'"},
        };
        for (String[] spec : specs) {
            try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(spec[1])) {
                if (rs.next()) {
                    long cnt = rs.getLong(1);
                    String lo = cnt == 0 ? "-" : java.time.Instant.ofEpochMilli(rs.getLong(2)).toString();
                    String hi = cnt == 0 ? "-" : java.time.Instant.ofEpochMilli(rs.getLong(3)).toString();
                    System.out.printf("%-22s rows=%-9d [%s, %s]%n", spec[0], cnt, lo, hi);
                }
            }
        }
    }
}
