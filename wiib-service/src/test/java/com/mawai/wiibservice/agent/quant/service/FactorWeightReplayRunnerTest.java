package com.mawai.wiibservice.agent.quant.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONWriter;
import com.mawai.wiibservice.agent.quant.domain.AgentVote;
import com.mawai.wiibservice.agent.quant.domain.Direction;
import com.mawai.wiibservice.agent.quant.domain.HorizonForecast;
import com.mawai.wiibservice.agent.quant.domain.MarketRegime;
import com.mawai.wiibservice.agent.quant.judge.HorizonJudge;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * B1-3 本地只读 runner：不启动 Spring，不走 HTTP/SaToken。
 * 显式传 -DfactorReplay=true 才会连库执行，避免普通测试误连本地库。
 */
class FactorWeightReplayRunnerTest {

    private static final String[] HORIZONS = {"0_10", "10_20", "20_30"};
    private static final int NO_TRADE_THRESHOLD_BPS = 10;
    private static final int QUERY_CHUNK_SIZE = 300;
    private static final int LOGIN_TIMEOUT_SECONDS = 15;
    private static final int QUERY_TIMEOUT_SECONDS = 60;

    /** 命令行入口：显式 -DfactorReplay=true 后连接外部库执行只读调权回放。 */
    @Test
    void runFactorWeightReplay() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("factorReplay"),
                "skip: add -DfactorReplay=true to run local replay");

        String symbol = System.getProperty("replay.symbol", "BTCUSDT");
        LocalDateTime to = parseDateTime(System.getProperty("replay.to",
                LocalDate.now().toString()));
        LocalDateTime from = parseDateTime(System.getProperty("replay.from",
                to.toLocalDate().minusDays(30).toString()));
        int limitCycles = Integer.getInteger("replay.limitCycles", 0);
        String jdbcUrl = propertyOrEnv("replay.db.url", "WIIB_DB_URL",
                "jdbc:postgresql://localhost:5432/wiib?reWriteBatchedInserts=true");
        String dbUser = propertyOrEnv("replay.db.user", "WIIB_DB_USER", "mawai");
        String dbPassword = propertyOrEnv("replay.db.password", "WIIB_DB_PASSWORD", "");

        FactorWeightOverrideService weightOverrideService = new FactorWeightOverrideService(
                false, new ClassPathResource("factor_weight_override.json"));
        weightOverrideService.load();

        ReplayReport report;
        System.out.println("[B1-3.localReplay] connect db url=" + maskJdbcUrl(jdbcUrl)
                + " user=" + dbUser + " symbol=" + symbol + " from=" + from + " to=" + to
                + " limitCycles=" + limitCycles);
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        try (Connection connection = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)) {
            System.out.println("[B1-3.localReplay] db connected");
            configureSession(connection);
            report = replay(connection, symbol, from, to, limitCycles, weightOverrideService);
        }

        System.out.println();
        System.out.println("========== B1-3 Factor Weight Replay ==========");
        System.out.println(JSON.toJSONString(report, JSONWriter.Feature.PrettyFormat));
        System.out.println("================================================");
        System.out.println();
    }

    /** 回放主流程：加载 cycle/vote/verification 后逐 horizon 比较 baseline 和 override。 */
    private ReplayReport replay(Connection connection, String symbol, LocalDateTime from, LocalDateTime to,
                                int limitCycles,
                                FactorWeightOverrideService weightOverrideService) throws Exception {
        System.out.println("[B1-3.localReplay] load cycles...");
        List<CycleRow> cycles = loadCycles(connection, symbol, from, to, limitCycles);
        if (cycles.isEmpty()) {
            throw new IllegalStateException("该时间段内无历史cycle记录: " + symbol);
        }

        List<String> cycleIds = cycles.stream().map(CycleRow::cycleId).toList();
        System.out.println("[B1-3.localReplay] load votes by cycle chunks...");
        Map<String, List<AgentVote>> votesByCycle = loadVotes(connection, cycleIds);
        System.out.println("[B1-3.localReplay] votes=" + votesByCycle.values().stream().mapToInt(List::size).sum());
        System.out.println("[B1-3.localReplay] load verifications by cycle chunks...");
        Map<String, Integer> actualByCycleHorizon = loadVerifications(connection, symbol, cycleIds);
        System.out.println("[B1-3.localReplay] verifications=" + actualByCycleHorizon.size());

        ReplayAccumulator all = new ReplayAccumulator("ALL");
        Map<String, ReplayAccumulator> byHorizon = new LinkedHashMap<>();
        for (String horizon : HORIZONS) {
            byHorizon.put(horizon, new ReplayAccumulator(horizon));
        }

        int evaluatedCycles = 0;
        int skippedNoVote = 0;
        int skippedNoVerification = 0;

        for (CycleRow cycle : cycles) {
            List<AgentVote> votes = votesByCycle.get(cycle.cycleId());
            if (votes == null || votes.isEmpty()) {
                skippedNoVote++;
                continue;
            }

            CycleContext ctx = parseCycleContext(cycle);
            boolean cycleUsed = false;
            for (String horizon : HORIZONS) {
                Integer actualChangeBps = actualByCycleHorizon.get(key(cycle.cycleId(), horizon));
                if (actualChangeBps == null) {
                    skippedNoVerification++;
                    continue;
                }

                HorizonForecast baseline = new HorizonJudge(horizon, Map.of(),
                        weightOverrideService, false, false)
                        .judge(votes, ctx.lastPrice(), ctx.qualityFlags(), ctx.regime());
                HorizonForecast override = new HorizonJudge(horizon, Map.of(),
                        weightOverrideService, true, false)
                        .judge(votes, ctx.lastPrice(), ctx.qualityFlags(), ctx.regime());

                // 同一份历史投票分别跑 baseline/override，保证差异只来自调权规则。
                SampleResult sample = evaluateSample(baseline.direction(), override.direction(), actualChangeBps);
                byHorizon.get(horizon).add(sample);
                all.add(sample);
                cycleUsed = true;
            }

            if (cycleUsed) {
                evaluatedCycles++;
            }
        }

        Map<String, ReplayMetrics> horizonMetrics = new LinkedHashMap<>();
        for (Map.Entry<String, ReplayAccumulator> entry : byHorizon.entrySet()) {
            horizonMetrics.put(entry.getKey(), entry.getValue().toMetrics());
        }

        int loadedVotes = votesByCycle.values().stream().mapToInt(List::size).sum();
        return new ReplayReport(symbol, from, to, cycles.size(), evaluatedCycles,
                loadedVotes, actualByCycleHorizon.size(), skippedNoVote, skippedNoVerification,
                all.toMetrics(), horizonMetrics);
    }

    /** 加载 cycle 基础列表，再按 cycle_id 分块补 snapshot 上下文。 */
    private List<CycleRow> loadCycles(Connection connection, String symbol,
                                      LocalDateTime from, LocalDateTime to,
                                      int limitCycles) throws Exception {
        List<CycleRef> refs = loadCycleRefs(connection, symbol, from, to, limitCycles);
        System.out.println("[B1-3.localReplay] cycle refs=" + refs.size());
        if (refs.isEmpty()) {
            return List.of();
        }

        System.out.println("[B1-3.localReplay] load cycle snapshots by cycle chunks...");
        Map<String, CycleContextRow> contextByCycle = loadCycleContexts(connection,
                refs.stream().map(CycleRef::cycleId).toList());

        List<CycleRow> result = new ArrayList<>();
        for (CycleRef ref : refs) {
            CycleContextRow context = contextByCycle.get(ref.cycleId());
            result.add(new CycleRow(ref.cycleId(),
                    context == null ? null : context.lastPrice(),
                    context == null ? null : context.regime(),
                    context == null ? null : context.qualityFlagsJson()));
        }
        System.out.println("[B1-3.localReplay] cycles=" + result.size());
        return result;
    }

    /** 只取目标 symbol/window 内的 cycle 引用；limitCycles>0 时取最近 N 个后再按时间升序回放。 */
    private List<CycleRef> loadCycleRefs(Connection connection, String symbol,
                                         LocalDateTime from, LocalDateTime to,
                                         int limitCycles) throws Exception {
        String sql = limitCycles > 0
                ? """
                SELECT cycle_id, forecast_time
                FROM (
                    SELECT cycle_id, forecast_time
                    FROM quant_forecast_cycle
                    WHERE symbol = ? AND forecast_time >= ? AND forecast_time < ?
                    ORDER BY forecast_time DESC
                    LIMIT ?
                ) t
                ORDER BY forecast_time ASC
                """
                : """
                SELECT cycle_id, forecast_time
                FROM quant_forecast_cycle
                WHERE symbol = ? AND forecast_time >= ? AND forecast_time < ?
                ORDER BY forecast_time ASC
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            bindWindow(ps, symbol, from, to);
            if (limitCycles > 0) {
                ps.setInt(4, limitCycles);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<CycleRef> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new CycleRef(rs.getString("cycle_id"), rs.getTimestamp("forecast_time").toLocalDateTime()));
                }
                return rows;
            }
        }
    }

    /** 分块加载 cycle snapshot，避免直接按时间范围扫大 JSON 表。 */
    private Map<String, CycleContextRow> loadCycleContexts(Connection connection, List<String> cycleIds) throws Exception {
        Map<String, CycleContextRow> result = new LinkedHashMap<>();
        int loaded = 0;
        for (List<String> chunk : chunks(cycleIds)) {
            String sql = """
                    SELECT cycle_id,
                           NULLIF(snapshot_json::jsonb ->> 'lastPrice', '')::numeric AS last_price,
                           snapshot_json::jsonb ->> 'regime' AS regime,
                           (snapshot_json::jsonb -> 'qualityFlags')::text AS quality_flags
                    FROM quant_forecast_cycle
                    WHERE cycle_id IN (%s)
                    """.formatted(placeholders(chunk.size()));
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                bindCycleIds(ps, chunk, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    int chunkRows = 0;
                    while (rs.next()) {
                        result.put(rs.getString("cycle_id"), new CycleContextRow(
                                rs.getBigDecimal("last_price"),
                                rs.getString("regime"),
                                rs.getString("quality_flags")));
                        chunkRows++;
                    }
                    loaded += chunkRows;
                    System.out.println("[B1-3.localReplay] cycle snapshots chunk loaded="
                            + loaded + "/" + cycleIds.size());
                }
            }
        }
        return result;
    }

    /** 分块加载历史 AgentVote，按 cycle_id 聚合成 HorizonJudge 输入。 */
    private Map<String, List<AgentVote>> loadVotes(Connection connection, List<String> cycleIds) throws Exception {
        Map<String, List<AgentVote>> result = new LinkedHashMap<>();
        int loaded = 0;
        for (List<String> chunk : chunks(cycleIds)) {
            String sql = """
                    SELECT cycle_id, agent, horizon, direction, score, confidence,
                           expected_move_bps, volatility_bps, reason_codes, risk_flags
                    FROM quant_agent_vote
                    WHERE cycle_id IN (%s)
                    ORDER BY cycle_id, horizon, agent
                    """.formatted(placeholders(chunk.size()));
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                bindCycleIds(ps, chunk, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    int chunkRows = 0;
                    while (rs.next()) {
                        result.computeIfAbsent(rs.getString("cycle_id"), k -> new ArrayList<>())
                                .add(toAgentVote(rs));
                        chunkRows++;
                    }
                    loaded += chunkRows;
                    System.out.println("[B1-3.localReplay] votes chunk loaded=" + loaded + "/" + cycleIds.size());
                }
            }
        }
        return result;
    }

    /** 分块加载后验验证，结果用 cycle_id+horizon 匹配回放样本。 */
    private Map<String, Integer> loadVerifications(Connection connection, String symbol,
                                                   List<String> cycleIds) throws Exception {
        Map<String, Integer> result = new LinkedHashMap<>();
        int loaded = 0;
        for (List<String> chunk : chunks(cycleIds)) {
            String sql = """
                    SELECT cycle_id, horizon, actual_change_bps
                    FROM quant_forecast_verification
                    WHERE symbol = ? AND cycle_id IN (%s) AND actual_change_bps IS NOT NULL
                    ORDER BY cycle_id, horizon
                    """.formatted(placeholders(chunk.size()));
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                ps.setString(1, symbol);
                bindCycleIds(ps, chunk, 2);
                try (ResultSet rs = ps.executeQuery()) {
                    int chunkRows = 0;
                    while (rs.next()) {
                        result.put(key(rs.getString("cycle_id"), rs.getString("horizon")),
                                rs.getInt("actual_change_bps"));
                        chunkRows++;
                    }
                    loaded += chunkRows;
                    System.out.println("[B1-3.localReplay] verifications chunk loaded="
                            + loaded + "/" + (cycleIds.size() * HORIZONS.length));
                }
            }
        }
        return result;
    }

    /** 把 cycleId 切成固定大小，控制 SQL IN 参数数量和查询耗时。 */
    private List<List<String>> chunks(List<String> values) {
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < values.size(); i += QUERY_CHUNK_SIZE) {
            chunks.add(values.subList(i, Math.min(values.size(), i + QUERY_CHUNK_SIZE)));
        }
        return chunks;
    }

    /** 生成 IN 查询占位符。 */
    private String placeholders(int size) {
        return java.util.stream.IntStream.range(0, size)
                .mapToObj(i -> "?")
                .collect(Collectors.joining(","));
    }

    /** 按起始下标绑定 cycle_id 列表。 */
    private void bindCycleIds(PreparedStatement ps, List<String> cycleIds, int startIndex) throws Exception {
        for (int i = 0; i < cycleIds.size(); i++) {
            ps.setString(startIndex + i, cycleIds.get(i));
        }
    }

    /** 把 JDBC 行转成 AgentVote 领域对象。 */
    private AgentVote toAgentVote(ResultSet rs) throws Exception {
        return new AgentVote(rs.getString("agent"), rs.getString("horizon"),
                parseDirection(rs.getString("direction")),
                decimal(rs.getBigDecimal("score")),
                decimal(rs.getBigDecimal("confidence")),
                nullableInt(rs, "expected_move_bps"),
                nullableInt(rs, "volatility_bps"),
                splitCodes(rs.getString("reason_codes")),
                splitCodes(rs.getString("risk_flags")));
    }

    /** 从 snapshot JSON 取 lastPrice/regime/qualityFlags，解析失败时回退为空上下文。 */
    private CycleContext parseCycleContext(CycleRow cycle) {
        try {
            MarketRegime regime = parseRegime(cycle.regime());
            JSONArray rawFlags = cycle.qualityFlagsJson() == null
                    ? null
                    : JSON.parseArray(cycle.qualityFlagsJson());
            List<String> qualityFlags = rawFlags == null
                    ? List.of()
                    : rawFlags.stream().map(String::valueOf).toList();
            return new CycleContext(cycle.lastPrice(), qualityFlags, regime);
        } catch (Exception e) {
            System.out.println("[B1-3.localReplay] snapshot解析失败 cycleId="
                    + cycle.cycleId() + ": " + e.getMessage());
            return new CycleContext(null, List.of(), null);
        }
    }

    /** 单样本比较：记录 baseline/override 是否交易、是否正确、方向是否变化。 */
    private SampleResult evaluateSample(Direction baseline, Direction override, int actualChangeBps) {
        boolean baselineTrade = baseline == Direction.LONG || baseline == Direction.SHORT;
        boolean overrideTrade = override == Direction.LONG || override == Direction.SHORT;
        boolean baselineCorrect = isCorrect(baseline, actualChangeBps);
        boolean overrideCorrect = isCorrect(override, actualChangeBps);
        return new SampleResult(baselineTrade, overrideTrade,
                baselineCorrect, overrideCorrect, baseline != override);
    }

    /** 判断方向是否命中；NO_TRADE 只有实际变化小于阈值才算正确。 */
    private boolean isCorrect(Direction direction, int actualChangeBps) {
        return (direction == Direction.LONG && actualChangeBps > 0)
                || (direction == Direction.SHORT && actualChangeBps < 0)
                || (direction == Direction.NO_TRADE && Math.abs(actualChangeBps) < NO_TRADE_THRESHOLD_BPS);
    }

    /** 绑定回放窗口参数。 */
    private void bindWindow(PreparedStatement ps, String symbol, LocalDateTime from, LocalDateTime to) throws Exception {
        ps.setString(1, symbol);
        ps.setTimestamp(2, Timestamp.valueOf(from));
        ps.setTimestamp(3, Timestamp.valueOf(to));
    }

    /** 给远程回放查询设置 statement_timeout，避免长查询拖死连接。 */
    private void configureSession(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("SET statement_timeout = '60s'")) {
            ps.execute();
        }
    }

    /** 支持 yyyy-MM-dd、yyyy-MM-ddTHH:mm 和 yyyy-MM-dd HH:mm 三种命令行时间格式。 */
    private LocalDateTime parseDateTime(String raw) {
        raw = raw.trim();
        if (raw.contains("T")) {
            return LocalDateTime.parse(raw);
        }
        if (raw.contains(" ")) {
            return LocalDateTime.parse(raw.replace(' ', 'T'));
        }
        return LocalDate.parse(raw).atStartOfDay();
    }

    /** 先读 JVM property，再读环境变量，最后使用默认值。 */
    private String propertyOrEnv(String property, String env, String defaultValue) {
        String propertyValue = System.getProperty(property);
        if (propertyValue != null) {
            return propertyValue;
        }
        String envValue = System.getenv(env);
        return envValue != null ? envValue : defaultValue;
    }

    /** 日志里隐藏 JDBC query 参数，避免密码或连接参数被完整打印。 */
    private String maskJdbcUrl(String jdbcUrl) {
        int queryIndex = jdbcUrl.indexOf('?');
        return queryIndex >= 0 ? jdbcUrl.substring(0, queryIndex) + "?..." : jdbcUrl;
    }

    /** 字符串方向转枚举，未知值按 NO_TRADE 处理。 */
    private Direction parseDirection(String raw) {
        if ("LONG".equals(raw)) return Direction.LONG;
        if ("SHORT".equals(raw)) return Direction.SHORT;
        return Direction.NO_TRADE;
    }

    /** snapshot 中的 regime 字符串转枚举，空值保持 null。 */
    private MarketRegime parseRegime(String raw) {
        return raw == null || raw.isBlank() ? null : MarketRegime.valueOf(raw);
    }

    /** BigDecimal 转 double，空值按 0 进入回放。 */
    private double decimal(BigDecimal value) {
        return value != null ? value.doubleValue() : 0;
    }

    /** ResultSet int 空值转 0，保持 AgentVote 构造兼容。 */
    private int nullableInt(ResultSet rs, String column) throws Exception {
        int value = rs.getInt(column);
        return rs.wasNull() ? 0 : value;
    }

    /** reason/risk 逗号串转去空格列表。 */
    private List<String> splitCodes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** cycle_id+horizon 组合 key。 */
    private String key(String cycleId, String horizon) {
        return cycleId + "|" + horizon;
    }

    private record CycleRef(String cycleId, LocalDateTime forecastTime) {}

    private record CycleRow(String cycleId, BigDecimal lastPrice, String regime, String qualityFlagsJson) {}

    private record CycleContextRow(BigDecimal lastPrice, String regime, String qualityFlagsJson) {}

    private record CycleContext(BigDecimal lastPrice, List<String> qualityFlags, MarketRegime regime) {}

    private record SampleResult(boolean baselineTrade, boolean overrideTrade,
                                boolean baselineCorrect, boolean overrideCorrect, boolean changed) {}

    private record ReplayReport(
            String symbol,
            LocalDateTime from,
            LocalDateTime to,
            int loadedCycles,
            int evaluatedCycles,
            int loadedVotes,
            int loadedVerifications,
            int skippedNoVoteCycles,
            int skippedNoVerificationSlots,
            ReplayMetrics overall,
            Map<String, ReplayMetrics> byHorizon
    ) {}

    private record ReplayMetrics(
            String bucket,
            int samples,
            int baselineTrades,
            int overrideTrades,
            int baselineCorrect,
            int overrideCorrect,
            double baselineHitRate,
            double overrideHitRate,
            double hitRateDelta,
            int changedDirections,
            int improvedSamples,
            int worsenedSamples
    ) {}

    private static class ReplayAccumulator {
        private final String bucket;
        private int samples;
        private int baselineTrades;
        private int overrideTrades;
        private int baselineCorrect;
        private int overrideCorrect;
        private int changedDirections;
        private int improvedSamples;
        private int worsenedSamples;

        private ReplayAccumulator(String bucket) {
            this.bucket = bucket;
        }

        /** 累加单个样本的 baseline/override 对比结果。 */
        private void add(SampleResult sample) {
            samples++;
            if (sample.baselineTrade()) baselineTrades++;
            if (sample.overrideTrade()) overrideTrades++;
            if (sample.baselineCorrect()) baselineCorrect++;
            if (sample.overrideCorrect()) overrideCorrect++;
            if (sample.changed()) changedDirections++;
            if (!sample.baselineCorrect() && sample.overrideCorrect()) improvedSamples++;
            if (sample.baselineCorrect() && !sample.overrideCorrect()) worsenedSamples++;
        }

        /** 输出当前 bucket 的命中率、差值和方向变化统计。 */
        private ReplayMetrics toMetrics() {
            double baselineHitRate = rate(baselineCorrect, samples);
            double overrideHitRate = rate(overrideCorrect, samples);
            return new ReplayMetrics(bucket, samples, baselineTrades, overrideTrades,
                    baselineCorrect, overrideCorrect, baselineHitRate, overrideHitRate,
                    round4(overrideHitRate - baselineHitRate),
                    changedDirections, improvedSamples, worsenedSamples);
        }

        /** 计算命中率并保留 4 位小数。 */
        private static double rate(int numerator, int denominator) {
            return denominator == 0 ? 0 : round4((double) numerator / denominator);
        }

        /** 回放报告统一 4 位小数，便于人工比较。 */
        private static double round4(double value) {
            return Math.round(value * 10000.0) / 10000.0;
        }
    }
}
