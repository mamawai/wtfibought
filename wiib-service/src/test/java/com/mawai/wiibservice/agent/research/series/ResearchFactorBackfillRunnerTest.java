package com.mawai.wiibservice.agent.research.series;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.mawai.wiibservice.agent.external.etf.EtfFlowScraper;
import com.mawai.wiibservice.agent.quant.service.StablecoinFlowService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.jsoup.Jsoup;

import java.math.BigDecimal;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * research 外部因子本地回填 runner：不启动 Spring，不走 HTTP controller。
 * 显式传 -DresearchFactorBackfill=true 才会连外部源 + 本地 DB，避免普通测试误跑外部 I/O。
 */
class ResearchFactorBackfillRunnerTest {

    private static final String FUNDING_FACTOR = "FUNDING_RATE";
    private static final String FEAR_GREED_FACTOR = "FEAR_GREED";
    private static final String ETF_FACTOR = "BTC_ETF_FLOW";
    private static final String STABLECOIN_FACTOR = "STABLECOIN_SUPPLY_DELTA";
    private static final int FUNDING_PAGE = 1000;
    private static final int BATCH_SIZE = 1000;
    private static final int LOGIN_TIMEOUT_SECONDS = 15;
    private static final int QUERY_TIMEOUT_SECONDS = 60;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(90);
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36";

    @Test
    void runResearchFactorBackfill() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("researchFactorBackfill"),
                "skip: add -DresearchFactorBackfill=true to backfill factor_history");

        BackfillConfig cfg = loadConfig();
        System.out.println("[research.factorBackfill] config=" + JSON.toJSONString(cfg.masked(), JSONWriter.Feature.PrettyFormat));

        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();

        List<BackfillResult> results = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(cfg.jdbcUrl(), cfg.dbUser(), cfg.dbPassword())) {
            connection.setAutoCommit(false);
            configureSession(connection);

            for (String symbol : cfg.symbols()) {
                results.add(backfillFunding(connection, httpClient, cfg, symbol));
                connection.commit();
            }
            results.add(backfillFearGreed(connection, httpClient, cfg));
            connection.commit();
            results.add(backfillEtf(connection, httpClient, cfg));
            connection.commit();
            results.add(backfillStablecoin(connection, httpClient, cfg));
            connection.commit();

            List<Coverage> coverage = loadCoverage(connection);
            System.out.println();
            System.out.println("========== Research Factor Coverage ==========");
            System.out.println(JSON.toJSONString(coverage, JSONWriter.Feature.PrettyFormat));
            System.out.println("==============================================");
        }

        System.out.println();
        System.out.println("========== Research Factor Backfill ==========");
        System.out.println(JSON.toJSONString(results, JSONWriter.Feature.PrettyFormat));
        System.out.println("==============================================");
        System.out.println();
    }

    private BackfillResult backfillFunding(Connection connection, HttpClient httpClient,
                                          BackfillConfig cfg, String symbol) throws Exception {
        long cursor = cfg.fromMs();
        int pages = 0;
        int parsedRows = 0;
        int affectedRows = 0;
        List<FactorRow> buffer = new ArrayList<>(BATCH_SIZE);
        while (cursor < cfg.toMs()) {
            String json = get(httpClient, fundingUri(cfg.futuresBaseUrl(), symbol, cursor, cfg.toMs()), false);
            List<MarketSeriesPoint> points = MarketSeriesStore.parseFundingRateHistory(json);
            if (points.isEmpty()) {
                break;
            }
            pages++;
            long newest = Long.MIN_VALUE;
            for (MarketSeriesPoint p : points) {
                if (p.ts() >= cfg.fromMs() && p.ts() < cfg.toMs()) {
                    buffer.add(new FactorRow(symbol, FUNDING_FACTOR, p.value(), toLdt(p.ts()),
                            JSON.toJSONString(Map.of("source", "binance/fundingRate"))));
                    parsedRows++;
                }
                newest = Math.max(newest, p.ts());
            }
            if (buffer.size() >= BATCH_SIZE) {
                affectedRows += flush(connection, buffer);
                buffer.clear();
            }
            if (newest < cursor || points.size() < FUNDING_PAGE) {
                break;
            }
            cursor = newest + 1;
            if (cfg.pauseMs() > 0) {
                Thread.sleep(cfg.pauseMs());
            }
        }
        if (!buffer.isEmpty()) {
            affectedRows += flush(connection, buffer);
        }
        return new BackfillResult("funding", symbol, pages, parsedRows, affectedRows);
    }

    private BackfillResult backfillFearGreed(Connection connection, HttpClient httpClient,
                                            BackfillConfig cfg) throws Exception {
        String json = get(httpClient, URI.create(stripTrailingSlash(cfg.fearGreedUrl())
                + "/?limit=0&format=json"), false);
        List<MarketSeriesPoint> points = MarketSeriesStore.parseFearGreed(json);
        List<FactorRow> rows = new ArrayList<>(points.size());
        for (MarketSeriesPoint p : points) {
            rows.add(new FactorRow(MarketSeriesStore.GLOBAL, FEAR_GREED_FACTOR, p.value(), toLdt(p.ts()),
                    JSON.toJSONString(Map.of("source", "alternative.me/fng"))));
        }
        return new BackfillResult("fearGreed", MarketSeriesStore.GLOBAL, 1, points.size(), flush(connection, rows));
    }

    private BackfillResult backfillEtf(Connection connection, HttpClient httpClient,
                                       BackfillConfig cfg) throws Exception {
        String html = getFarsideHtml(cfg.farsideUrl());
        List<EtfFlowScraper.EtfFlowPoint> points = new EtfFlowScraper(null).parseAll(html);
        List<FactorRow> rows = new ArrayList<>(points.size());
        for (EtfFlowScraper.EtfFlowPoint p : points) {
            rows.add(new FactorRow("BTCUSDT", ETF_FACTOR, p.totalFlowUsdMillion(), p.date().atStartOfDay(),
                    JSON.toJSONString(Map.of(
                            "source", "Farside",
                            "url", cfg.farsideUrl(),
                            "unit", "USD_million",
                            "flowDate", p.date().toString()
                    ))));
        }
        return new BackfillResult("etfFlow", "BTCUSDT", 1, points.size(), flush(connection, rows));
    }

    private BackfillResult backfillStablecoin(Connection connection, HttpClient httpClient,
                                             BackfillConfig cfg) throws Exception {
        String json = get(httpClient, URI.create(cfg.stablecoinUrl()), false);
        List<StablecoinFlowService.StablecoinPoint> points = StablecoinFlowService.parseSupplyDeltas(json);
        List<FactorRow> rows = new ArrayList<>(points.size() * cfg.symbols().size());
        for (StablecoinFlowService.StablecoinPoint p : points) {
            for (String symbol : cfg.symbols()) {
                rows.add(new FactorRow(symbol, STABLECOIN_FACTOR, p.delta(), toLdt(p.dateSec() * 1000L),
                        JSON.toJSONString(Map.of(
                                "source", "DeFiLlama",
                                "endpoint", cfg.stablecoinUrl(),
                                "rawMetric", "totalCirculatingUSD.peggedUSD_delta",
                                "latestSupplyUsd", p.supplyUsd(),
                                "previousSupplyUsd", p.previousSupplyUsd(),
                                "previousDate", p.previousDateSec()
                        ))));
            }
        }
        return new BackfillResult("stablecoinDelta", String.join(",", cfg.symbols()), 1, rows.size(), flush(connection, rows));
    }

    private String getFarsideHtml(String url) throws Exception {
        try {
            return Jsoup.connect(url)
                    .userAgent(BROWSER_UA)
                    .referrer("https://farside.co.uk/")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout((int) HTTP_TIMEOUT.toMillis())
                    .get()
                    .outerHtml();
        } catch (Exception e) {
            System.out.println("[research.factorBackfill] Farside Jsoup failed, fallback to curl.exe: " + e.getMessage());
            return getFarsideHtmlByCurl(url);
        }
    }

    private String getFarsideHtmlByCurl(String url) throws Exception {
        Process process = new ProcessBuilder(
                "curl.exe",
                "-L",
                "-s",
                "-m", String.valueOf(HTTP_TIMEOUT.toSeconds()),
                "-A", BROWSER_UA,
                "-H", "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "-H", "Accept-Language: en-US,en;q=0.9",
                url)
                .redirectErrorStream(true)
                .start();
        String body = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0 || body.isBlank()) {
            throw new IllegalStateException("curl.exe Farside failed exit=" + exit + " body=" + abbreviate(body));
        }
        return body;
    }

    private String get(HttpClient httpClient, URI uri, boolean browserHeaders) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(HTTP_TIMEOUT)
                .GET();
        if (browserHeaders) {
            builder.header("User-Agent", BROWSER_UA)
                    .header("Referer", "https://farside.co.uk/")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode()
                    + " uri=" + uri + " body=" + abbreviate(response.body()));
        }
        return response.body();
    }

    private URI fundingUri(String baseUrl, String symbol, long startTime, long endTime) {
        return URI.create(stripTrailingSlash(baseUrl)
                + "/fapi/v1/fundingRate?symbol=" + symbol
                + "&startTime=" + startTime
                + "&endTime=" + endTime
                + "&limit=" + FUNDING_PAGE);
    }

    private int flush(Connection connection, List<FactorRow> rows) throws Exception {
        if (rows.isEmpty()) {
            return 0;
        }
        String sql = """
                INSERT INTO factor_history(symbol, factor_name, factor_value, observed_at, metadata_json)
                VALUES (?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (symbol, factor_name, observed_at) DO UPDATE
                SET factor_value = EXCLUDED.factor_value,
                    metadata_json = EXCLUDED.metadata_json
                """;
        int affected = 0;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            int pending = 0;
            for (FactorRow row : rows) {
                ps.setString(1, row.symbol());
                ps.setString(2, row.factorName());
                ps.setBigDecimal(3, row.factorValue());
                ps.setTimestamp(4, Timestamp.valueOf(row.observedAt()));
                ps.setString(5, row.metadataJson());
                ps.addBatch();
                pending++;
                if (pending >= BATCH_SIZE) {
                    affected += sum(ps.executeBatch());
                    pending = 0;
                }
            }
            if (pending > 0) {
                affected += sum(ps.executeBatch());
            }
        }
        return affected;
    }

    private int sum(int[] counts) {
        int out = 0;
        for (int count : counts) {
            if (count > 0) {
                out += count;
            } else if (count == Statement.SUCCESS_NO_INFO) {
                out++;
            }
        }
        return out;
    }

    private List<Coverage> loadCoverage(Connection connection) throws Exception {
        String sql = """
                SELECT symbol,
                       factor_name,
                       COUNT(*) AS samples,
                       MIN(observed_at) AS first_observed_at,
                       MAX(observed_at) AS latest_observed_at
                FROM factor_history
                WHERE factor_name IN (?, ?, ?, ?)
                  AND symbol IN (?, ?, ?)
                GROUP BY symbol, factor_name
                ORDER BY factor_name, symbol
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            ps.setString(1, FUNDING_FACTOR);
            ps.setString(2, FEAR_GREED_FACTOR);
            ps.setString(3, ETF_FACTOR);
            ps.setString(4, STABLECOIN_FACTOR);
            ps.setString(5, "BTCUSDT");
            ps.setString(6, "ETHUSDT");
            ps.setString(7, MarketSeriesStore.GLOBAL);
            try (ResultSet rs = ps.executeQuery()) {
                List<Coverage> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new Coverage(
                            rs.getString("symbol"),
                            rs.getString("factor_name"),
                            rs.getLong("samples"),
                            rs.getTimestamp("first_observed_at").toLocalDateTime().toString(),
                            rs.getTimestamp("latest_observed_at").toLocalDateTime().toString()
                    ));
                }
                return rows;
            }
        }
    }

    private void configureSession(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("SET statement_timeout = '60s'")) {
            ps.execute();
        }
    }

    private BackfillConfig loadConfig() {
        Instant to = parseInstant(propertyOrEnv("factor.to", "FACTOR_TO", null), Instant.now());
        Instant from = parseInstant(propertyOrEnv("factor.from", "FACTOR_FROM", null),
                to.minus(Duration.ofDays(Integer.getInteger("factor.days", 365))));
        return new BackfillConfig(
                splitSymbols(propertyOrEnv("factor.symbols", "FACTOR_SYMBOLS", "BTCUSDT,ETHUSDT")),
                from.toEpochMilli(),
                to.toEpochMilli(),
                stripTrailingSlash(propertyOrEnv("factor.binance.futuresBaseUrl", "FACTOR_BINANCE_FUTURES_BASE_URL",
                        "https://fapi.binance.com")),
                propertyOrEnv("factor.fearGreedUrl", "FACTOR_FEAR_GREED_URL", "https://api.alternative.me/fng"),
                propertyOrEnv("factor.farsideUrl", "FACTOR_FARSIDE_URL",
                        "https://farside.co.uk/bitcoin-etf-flow-all-data/"),
                propertyOrEnv("factor.stablecoinUrl", "FACTOR_STABLECOIN_URL",
                        "https://stablecoins.llama.fi/stablecoincharts/all"),
                propertyOrEnv("factor.db.url", "WIIB_DB_URL",
                        "jdbc:postgresql://localhost:5432/wiib?reWriteBatchedInserts=true"),
                propertyOrEnv("factor.db.user", "WIIB_DB_USER", "mawai"),
                propertyOrEnv("factor.db.password", "WIIB_DB_PASSWORD", ""),
                Integer.getInteger("factor.pauseMs", 80)
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

    private LocalDateTime toLdt(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
    }

    private String stripTrailingSlash(String value) {
        String out = value == null ? "" : value.trim();
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

    private record BackfillConfig(
            List<String> symbols,
            long fromMs,
            long toMs,
            String futuresBaseUrl,
            String fearGreedUrl,
            String farsideUrl,
            String stablecoinUrl,
            String jdbcUrl,
            String dbUser,
            String dbPassword,
            int pauseMs
    ) {
        BackfillConfig masked() {
            return new BackfillConfig(symbols, fromMs, toMs, futuresBaseUrl, fearGreedUrl, farsideUrl, stablecoinUrl,
                    maskJdbcUrl(jdbcUrl), dbUser, "***", pauseMs);
        }

        private static String maskJdbcUrl(String jdbcUrl) {
            int queryIndex = jdbcUrl.indexOf('?');
            return queryIndex >= 0 ? jdbcUrl.substring(0, queryIndex) + "?..." : jdbcUrl;
        }
    }

    private record FactorRow(String symbol, String factorName, BigDecimal factorValue,
                             LocalDateTime observedAt, String metadataJson) {}

    private record BackfillResult(String source, String symbol, int pages, int parsedRows, int affectedRows) {}

    private record Coverage(String symbol, String factorName, long samples,
                            String firstObservedAt, String latestObservedAt) {}
}
