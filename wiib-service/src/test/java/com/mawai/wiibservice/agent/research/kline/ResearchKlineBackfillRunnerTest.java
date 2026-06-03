package com.mawai.wiibservice.agent.research.kline;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * research K线本地回填 runner：不启动 Spring，不走 HTTP controller。
 * 显式传 -DresearchKlineBackfill=true 才会连 Binance + 本地 DB，避免普通测试误跑外部 I/O。
 */
class ResearchKlineBackfillRunnerTest {

    private static final String DEFAULT_INTERVAL = "1m";
    private static final int PAGE_LIMIT = 1500;
    private static final int FLUSH_SIZE = 3000;
    private static final int LOGIN_TIMEOUT_SECONDS = 15;
    private static final int QUERY_TIMEOUT_SECONDS = 60;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    @Test
    void runResearchKlineBackfill() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("researchKlineBackfill"),
                "skip: add -DresearchKlineBackfill=true to backfill kline_history");

        BackfillConfig cfg = loadConfig();
        System.out.println("[research.klineBackfill] config=" + JSON.toJSONString(cfg.masked(), JSONWriter.Feature.PrettyFormat));

        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();

        List<SymbolResult> results = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(cfg.jdbcUrl(), cfg.dbUser(), cfg.dbPassword())) {
            connection.setAutoCommit(false);
            configureSession(connection);
            for (String symbol : cfg.symbols()) {
                SymbolResult result = backfillSymbol(connection, httpClient, cfg, symbol);
                results.add(result);
                connection.commit();
            }
        }

        System.out.println();
        System.out.println("========== Research Kline Backfill ==========");
        System.out.println(JSON.toJSONString(results, JSONWriter.Feature.PrettyFormat));
        System.out.println("=============================================");
        System.out.println();
    }

    private SymbolResult backfillSymbol(Connection connection, HttpClient httpClient,
                                        BackfillConfig cfg, String symbol) throws Exception {
        long cursor = cfg.toMs();
        int pages = 0;
        int parsedRows = 0;
        int insertedRows = 0;
        List<KlineBar> buffer = new ArrayList<>(FLUSH_SIZE);

        while (cursor > cfg.fromMs()) {
            List<KlineBar> page = fetchPage(httpClient, cfg.futuresBaseUrl(), symbol, cfg.interval(), cursor);
            if (page.isEmpty()) {
                break;
            }
            pages++;
            long oldest = page.get(0).openTime();
            for (KlineBar bar : page) {
                if (bar.openTime() >= cfg.fromMs() && bar.openTime() < cfg.toMs()) {
                    buffer.add(bar);
                    parsedRows++;
                }
            }
            if (buffer.size() >= FLUSH_SIZE) {
                insertedRows += flush(connection, symbol, cfg.interval(), buffer);
                buffer.clear();
                connection.commit();
                printProgress(symbol, pages, parsedRows, insertedRows, oldest);
            }
            if (oldest <= cfg.fromMs()) {
                break;
            }
            long nextCursor = oldest - 1;
            if (nextCursor >= cursor) {
                throw new IllegalStateException("Binance pagination cursor did not move: " + cursor);
            }
            cursor = nextCursor;
            if (cfg.pauseMs() > 0) {
                Thread.sleep(cfg.pauseMs());
            }
        }

        if (!buffer.isEmpty()) {
            insertedRows += flush(connection, symbol, cfg.interval(), buffer);
            buffer.clear();
        }

        Coverage coverage = loadCoverage(connection, symbol, cfg.interval());
        printProgress(symbol, pages, parsedRows, insertedRows, cfg.fromMs());
        return new SymbolResult(symbol, pages, parsedRows, insertedRows, coverage);
    }

    private List<KlineBar> fetchPage(HttpClient httpClient, String baseUrl, String symbol, String interval, long endTime) throws Exception {
        URI uri = URI.create(stripTrailingSlash(baseUrl)
                + "/fapi/v1/klines?symbol=" + symbol
                + "&interval=" + interval
                + "&limit=" + PAGE_LIMIT
                + "&endTime=" + endTime);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Binance klines HTTP " + response.statusCode()
                    + " symbol=" + symbol + " body=" + abbreviate(response.body()));
        }
        return KlineHistoryStore.parseRawFuturesKlines(response.body());
    }

    private int flush(Connection connection, String symbol, String interval, List<KlineBar> bars) throws Exception {
        String sql = """
                INSERT INTO kline_history
                  (symbol, interval_code, open_time, close_time, open, high, low, close, volume)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (symbol, interval_code, open_time) DO NOTHING
                """;
        int inserted = 0;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            for (KlineBar b : bars) {
                ps.setString(1, symbol);
                ps.setString(2, interval);
                ps.setLong(3, b.openTime());
                ps.setLong(4, b.closeTime());
                ps.setBigDecimal(5, b.open());
                ps.setBigDecimal(6, b.high());
                ps.setBigDecimal(7, b.low());
                ps.setBigDecimal(8, b.close());
                ps.setBigDecimal(9, b.volume());
                ps.addBatch();
            }
            for (int count : ps.executeBatch()) {
                if (count > 0) {
                    inserted += count;
                } else if (count == Statement.SUCCESS_NO_INFO) {
                    inserted++;
                }
            }
        }
        return inserted;
    }

    private Coverage loadCoverage(Connection connection, String symbol, String interval) throws Exception {
        String sql = """
                SELECT COUNT(*) AS samples,
                       MIN(open_time) AS first_open,
                       MAX(open_time) AS latest_open
                FROM kline_history
                WHERE symbol = ? AND interval_code = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            ps.setString(1, symbol);
            ps.setString(2, interval);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new Coverage(0, null, null);
                }
                long samples = rs.getLong("samples");
                Long first = nullableLong(rs, "first_open");
                Long latest = nullableLong(rs, "latest_open");
                return new Coverage(samples, toIso(first), toIso(latest));
            }
        }
    }

    private void configureSession(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("SET statement_timeout = '60s'")) {
            ps.execute();
        }
    }

    private BackfillConfig loadConfig() {
        Instant to = parseInstant(propertyOrEnv("kline.to", "KLINE_TO", null), Instant.now());
        Instant from = parseInstant(propertyOrEnv("kline.from", "KLINE_FROM", null),
                to.minus(Duration.ofDays(Integer.getInteger("kline.days", 365))));
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("kline.from must be before kline.to");
        }
        return new BackfillConfig(
                splitSymbols(propertyOrEnv("kline.symbols", "KLINE_SYMBOLS", "BTCUSDT,ETHUSDT")),
                propertyOrEnv("kline.interval", "KLINE_INTERVAL", DEFAULT_INTERVAL),
                from.toEpochMilli(),
                to.toEpochMilli(),
                stripTrailingSlash(propertyOrEnv("kline.binance.futuresBaseUrl", "KLINE_BINANCE_FUTURES_BASE_URL",
                        "https://fapi.binance.com")),
                propertyOrEnv("kline.db.url", "WIIB_DB_URL",
                        "jdbc:postgresql://localhost:5432/wiib?reWriteBatchedInserts=true"),
                propertyOrEnv("kline.db.user", "WIIB_DB_USER", "mawai"),
                propertyOrEnv("kline.db.password", "WIIB_DB_PASSWORD", ""),
                Integer.getInteger("kline.pauseMs", 80)
        );
    }

    private List<String> splitSymbols(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private Instant parseInstant(String raw, Instant defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String value = raw.trim();
        if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        if (value.contains(" ")) {
            value = value.replace(' ', 'T');
        }
        if (value.length() == 16) {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        }
        return Instant.parse(value);
    }

    private String propertyOrEnv(String property, String env, String defaultValue) {
        String propertyValue = System.getProperty(property);
        if (propertyValue != null) {
            return propertyValue;
        }
        String envValue = System.getenv(env);
        return envValue != null ? envValue : defaultValue;
    }

    private Long nullableLong(ResultSet rs, String column) throws Exception {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String toIso(Long epochMs) {
        return epochMs == null ? null : Instant.ofEpochMilli(epochMs).toString();
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String out = value.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }

    private void printProgress(String symbol, int pages, int parsedRows, int insertedRows, long cursorMs) {
        System.out.println("[research.klineBackfill] symbol=" + symbol
                + " pages=" + pages
                + " parsedRows=" + parsedRows
                + " insertedRows=" + insertedRows
                + " cursor=" + Instant.ofEpochMilli(cursorMs));
    }

    private record BackfillConfig(
            List<String> symbols,
            String interval,
            long fromMs,
            long toMs,
            String futuresBaseUrl,
            String jdbcUrl,
            String dbUser,
            String dbPassword,
            int pauseMs
    ) {
        BackfillConfig masked() {
            return new BackfillConfig(symbols, interval, fromMs, toMs, futuresBaseUrl, maskJdbcUrl(jdbcUrl), dbUser, "***", pauseMs);
        }

        private static String maskJdbcUrl(String jdbcUrl) {
            int queryIndex = jdbcUrl.indexOf('?');
            return queryIndex >= 0 ? jdbcUrl.substring(0, queryIndex) + "?..." : jdbcUrl;
        }
    }

    private record SymbolResult(String symbol, int pages, int parsedRows, int insertedRows, Coverage coverage) {}

    private record Coverage(long samples, String firstOpen, String latestOpen) {}
}
