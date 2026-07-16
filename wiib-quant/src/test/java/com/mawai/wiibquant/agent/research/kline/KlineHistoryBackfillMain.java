package com.mawai.wiibquant.agent.research.kline;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibcommon.market.KlineBar;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.BinanceRestClient;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 手动本地 K 线补齐入口。
 *
 * <p>默认补：ETHUSDT 合约 5m，北京时间 [2026-06-17 12:00:00, 2026-06-23 13:00:00)。</p>
 *
 * <p>IDEA 直接运行 main 即可；也可用 VM options 覆盖：</p>
 * <pre>
 * -Dkline.fill.symbols=ETHUSDT
 * -Dkline.fill.from="2026-06-17 12:00:00"
 * -Dkline.fill.to="2026-06-23 13:00:00"
 * </pre>
 */
public final class KlineHistoryBackfillMain {

    private static final String INTERVAL = "5m";
    private static final long BAR_MILLIS = 5 * 60_000L;
    private static final int PAGE_LIMIT = 1500;
    private static final int FLUSH_SIZE = 3000;
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter LOCAL_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter LOCAL_MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private KlineHistoryBackfillMain() {
    }

    public static void main(String[] args) throws Exception {
        Settings settings = Settings.from(args);
        BinanceRestClient client = new BinanceRestClient(binanceProperties(settings));

        System.out.printf("%n==== KlineHistoryBackfillMain ====%n");
        System.out.printf("symbols=%s interval=%s%n", settings.symbols(), INTERVAL);
        System.out.printf("range BJ [%s, %s)%n",
                formatLocal(settings.fromMs(), settings.zone()), formatLocal(settings.toMs(), settings.zone()));
        System.out.printf("range UTC [%s, %s)%n",
                Instant.ofEpochMilli(settings.fromMs()), Instant.ofEpochMilli(settings.toMs()));
        System.out.printf("db=%s user=%s futuresBaseUrl=%s%n",
                settings.dbUrl(), settings.dbUser(), settings.futuresBaseUrl());

        try (Connection con = DriverManager.getConnection(settings.dbUrl(), settings.dbUser(), settings.dbPassword())) {
            for (String symbol : settings.symbols()) {
                runOne(con, client, settings, symbol);
            }
        }
    }

    private static void runOne(Connection con, BinanceRestClient client, Settings settings, String symbol) throws Exception {
        long fromMs = resolveFromMs(con, symbol, settings);
        long toMs = settings.toMs();
        if (fromMs >= toMs) {
            throw new IllegalArgumentException("from 必须早于 to: " + fromMs + " >= " + toMs);
        }

        long expected = expectedCompleteBars(fromMs, toMs);
        long before = countBars(con, symbol, fromMs, toMs);
        Coverage beforeCoverage = coverage(con, symbol, fromMs, toMs);

        System.out.printf("%n---- %s ----%n", symbol);
        System.out.printf("before rows=%d expected=%d first=%s last=%s%n",
                before, expected, formatNullable(beforeCoverage.firstOpen(), settings.zone()),
                formatNullable(beforeCoverage.lastOpen(), settings.zone()));

        FillResult fill = backfillPages(con, client, settings, symbol, fromMs, toMs);

        long after = countBars(con, symbol, fromMs, toMs);
        Coverage afterCoverage = coverage(con, symbol, fromMs, toMs);
        String gap = firstGap(con, symbol, fromMs, toMs, settings.zone());

        System.out.printf("fetched=%d attempted=%d inserted=%d actualAdded=%d%n",
                fill.fetched(), fill.attempted(), fill.inserted(), after - before);
        System.out.printf("after  rows=%d expected=%d first=%s last=%s gap=%s%n",
                after, expected, formatNullable(afterCoverage.firstOpen(), settings.zone()),
                formatNullable(afterCoverage.lastOpen(), settings.zone()), gap == null ? "-" : gap);
    }

    /**
     * Binance endTime 向前翻页；只收完整闭合 bar，避免把 to 边界后的半截 K 线写进库。
     */
    private static FillResult backfillPages(Connection con, BinanceRestClient client, Settings settings,
                                            String symbol, long fromMs, long toMs) throws Exception {
        long cursor = toMs;
        int fetched = 0;
        int attempted = 0;
        int inserted = 0;
        List<KlineBar> buffer = new ArrayList<>(FLUSH_SIZE);

        while (cursor > fromMs) {
            String json = client.getFuturesKlines(symbol, INTERVAL, PAGE_LIMIT, cursor);
            List<KlineBar> bars = KlineHistoryStore.parseRawFuturesKlines(json);
            if (bars.isEmpty()) {
                break;
            }
            fetched += bars.size();
            long oldest = bars.get(0).openTime();

            for (KlineBar bar : bars) {
                if (bar.openTime() < fromMs || bar.closeTime() >= toMs) {
                    continue;
                }
                buffer.add(bar);
            }
            if (buffer.size() >= FLUSH_SIZE) {
                InsertResult r = insertIgnore(con, symbol, buffer);
                attempted += r.attempted();
                inserted += r.inserted();
                buffer.clear();
            }

            if (oldest <= fromMs) {
                break;
            }
            cursor = oldest - 1;
            if (settings.sleepMs() > 0) {
                Thread.sleep(settings.sleepMs());
            }
        }

        if (!buffer.isEmpty()) {
            InsertResult r = insertIgnore(con, symbol, buffer);
            attempted += r.attempted();
            inserted += r.inserted();
        }
        return new FillResult(fetched, attempted, inserted);
    }

    /** 幂等补齐：已有 K 线跳过，不覆盖历史数据。 */
    private static InsertResult insertIgnore(Connection con, String symbol, List<KlineBar> bars) throws SQLException {
        String sql = """
                INSERT INTO kline_history
                  (symbol, interval_code, open_time, close_time, open, high, low, close, volume)
                VALUES (?, '5m', ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (symbol, interval_code, open_time) DO NOTHING
                """;
        int inserted = 0;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (KlineBar bar : bars) {
                ps.setString(1, symbol);
                ps.setLong(2, bar.openTime());
                ps.setLong(3, bar.closeTime());
                ps.setBigDecimal(4, bar.open());
                ps.setBigDecimal(5, bar.high());
                ps.setBigDecimal(6, bar.low());
                ps.setBigDecimal(7, bar.close());
                ps.setBigDecimal(8, bar.volume());
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            for (int count : counts) {
                if (count > 0 || count == PreparedStatement.SUCCESS_NO_INFO) {
                    inserted++;
                }
            }
        }
        return new InsertResult(bars.size(), inserted);
    }

    private static long resolveFromMs(Connection con, String symbol, Settings settings) throws Exception {
        if (!"latest".equalsIgnoreCase(settings.rawFrom())) {
            return settings.fromMs();
        }
        Long latestClose = latestCloseTime(con, symbol);
        if (latestClose == null) {
            throw new IllegalStateException(symbol + " 本地没有历史 K 线，from=latest 无法推断起点");
        }
        return latestClose + 1;
    }

    private static long countBars(Connection con, String symbol, long fromMs, long toMs) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM kline_history
                WHERE symbol=? AND interval_code='5m' AND open_time>=? AND close_time<?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setLong(2, fromMs);
            ps.setLong(3, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static Coverage coverage(Connection con, String symbol, long fromMs, long toMs) throws SQLException {
        String sql = """
                SELECT MIN(open_time) AS first_open, MAX(open_time) AS last_open
                FROM kline_history
                WHERE symbol=? AND interval_code='5m' AND open_time>=? AND close_time<?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setLong(2, fromMs);
            ps.setLong(3, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new Coverage(null, null);
                }
                return new Coverage(longObject(rs, "first_open"), longObject(rs, "last_open"));
            }
        }
    }

    private static Long latestCloseTime(Connection con, String symbol) throws SQLException {
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

    private static String firstGap(Connection con, String symbol, long fromMs, long toMs, ZoneId zone) throws SQLException {
        long expected = firstExpectedOpen(fromMs);
        long last = lastExpectedOpen(toMs);
        if (last < expected) {
            return null;
        }
        String sql = """
                SELECT open_time
                FROM kline_history
                WHERE symbol=? AND interval_code='5m' AND open_time>=? AND open_time<=?
                ORDER BY open_time
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setLong(2, expected);
            ps.setLong(3, last);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long actual = rs.getLong(1);
                    if (actual < expected) {
                        continue;
                    }
                    if (actual != expected) {
                        return formatLocal(expected, zone) + " -> " + formatLocal(actual - BAR_MILLIS, zone);
                    }
                    expected += BAR_MILLIS;
                }
            }
        }
        return expected <= last
                ? formatLocal(expected, zone) + " -> " + formatLocal(last, zone)
                : null;
    }

    private static long expectedCompleteBars(long fromMs, long toMs) {
        long first = firstExpectedOpen(fromMs);
        long last = lastExpectedOpen(toMs);
        return last < first ? 0L : ((last - first) / BAR_MILLIS) + 1;
    }

    private static long firstExpectedOpen(long fromMs) {
        long mod = Math.floorMod(fromMs, BAR_MILLIS);
        return mod == 0 ? fromMs : fromMs + BAR_MILLIS - mod;
    }

    private static long lastExpectedOpen(long toMs) {
        long latestCloseExclusive = toMs - BAR_MILLIS;
        return latestCloseExclusive - Math.floorMod(latestCloseExclusive, BAR_MILLIS);
    }

    private static BinanceProperties binanceProperties(Settings settings) {
        BinanceProperties props = new BinanceProperties();
        props.setRestBaseUrl(settings.spotBaseUrl());
        props.setFuturesRestBaseUrl(settings.futuresBaseUrl());
        return props;
    }

    private static long parseTimeMs(String raw, ZoneId zone) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank() || "now".equalsIgnoreCase(value)) {
            return System.currentTimeMillis();
        }
        TimeParser[] parsers = new TimeParser[] {
                s -> Instant.parse(s).toEpochMilli(),
                s -> OffsetDateTime.parse(s).toInstant().toEpochMilli(),
                s -> LocalDateTime.parse(s, LOCAL_SECONDS).atZone(zone).toInstant().toEpochMilli(),
                s -> LocalDateTime.parse(s, LOCAL_MINUTES).atZone(zone).toInstant().toEpochMilli(),
                s -> LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(zone).toInstant().toEpochMilli(),
                s -> LocalDate.parse(s).atStartOfDay(zone).toInstant().toEpochMilli()
        };
        for (TimeParser parser : parsers) {
            try {
                return parser.parse(value);
            } catch (DateTimeParseException ignored) {
                // 尝试下一个格式。
            }
        }
        throw new IllegalArgumentException("无法解析时间: " + raw);
    }

    private static List<String> parseSymbols(String raw) {
        List<String> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            String symbol = token.trim().toUpperCase(Locale.ROOT);
            if (!symbol.isBlank()) {
                out.add(symbol);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("kline.fill.symbols 不能为空");
        }
        return List.copyOf(out);
    }

    private static String argOrProperty(String[] args, int index, String property, String fallback) {
        return args.length > index && args[index] != null && !args[index].isBlank()
                ? args[index]
                : System.getProperty(property, fallback);
    }

    private static Long longObject(ResultSet rs, String column) throws SQLException {
        Number n = (Number) rs.getObject(column);
        return n == null ? null : n.longValue();
    }

    private static String formatNullable(Long ms, ZoneId zone) {
        return ms == null ? "-" : formatLocal(ms, zone);
    }

    private static String formatLocal(long ms, ZoneId zone) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone).format(LOCAL_SECONDS);
    }

    private interface TimeParser {
        long parse(String value);
    }

    private record FillResult(int fetched, int attempted, int inserted) {
    }

    private record InsertResult(int attempted, int inserted) {
    }

    private record Coverage(Long firstOpen, Long lastOpen) {
    }

    private record Settings(List<String> symbols,
                            String rawFrom,
                            long fromMs,
                            long toMs,
                            ZoneId zone,
                            String dbUrl,
                            String dbUser,
                            String dbPassword,
                            String spotBaseUrl,
                            String futuresBaseUrl,
                            long sleepMs) {

        static Settings from(String[] args) {
            ZoneId zone = ZoneId.of(System.getProperty("kline.fill.zone", DEFAULT_ZONE.getId()));
            String rawSymbols = argOrProperty(args, 0, "kline.fill.symbols", "SOLUSDT");
            String rawFrom = argOrProperty(args, 1, "kline.fill.from", "2020-01-01 00:00:00");
            String rawTo = argOrProperty(args, 2, "kline.fill.to", "2026-06-23 13:00:00");
            long fromMs = "latest".equalsIgnoreCase(rawFrom.trim()) ? -1L : parseTimeMs(rawFrom, zone);
            long toMs = parseTimeMs(rawTo, zone);
            return new Settings(
                    parseSymbols(rawSymbols),
                    rawFrom,
                    fromMs,
                    toMs,
                    zone,
                    System.getProperty("kline.fill.dbUrl", "jdbc:postgresql://localhost:5432/wiib"),
                    System.getProperty("kline.fill.dbUser", "mawai"),
                    System.getProperty("kline.fill.dbPassword", com.mawai.wiibquant.LocalEnv.dbPassword()),
                    System.getProperty("kline.fill.spotBaseUrl", "https://api.binance.com"),
                    System.getProperty("kline.fill.futuresBaseUrl", "https://fapi.binance.com"),
                    Long.getLong("kline.fill.sleepMs", 200L));
        }
    }
}
