package com.mawai.wiibservice.agent.quant.memory;

import com.mawai.wiibservice.mapper.QuantAgentVoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 程序统计版agent表现记忆：用真实验证结果调权，不再相信LLM主观打分。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentPerformanceMemoryService {

    private static final int LOOKBACK_DAYS = 30;
    private static final int MIN_SAMPLE_COUNT = 20;

    private final QuantAgentVoteMapper voteMapper;

    /** agent + horizon 维度的完整统计 */
    public record AgentStat(double accuracy, int sampleCount, double avgEdgeBps, double badRate) {}

    /** 只返回 accuracy（向后兼容，轻周期等只需 accuracy 的场景） */
    public Map<String, Map<String, Double>> loadAccuracy(String symbol, String regime) {
        Map<String, Map<String, AgentStat>> full = loadFullStats(symbol, regime);
        Map<String, Map<String, Double>> result = new HashMap<>();
        full.forEach((agent, horizons) -> {
            Map<String, Double> accMap = new HashMap<>();
            horizons.forEach((h, stat) -> accMap.put(h, stat.accuracy()));
            result.put(agent, Map.copyOf(accMap));
        });
        return Map.copyOf(result);
    }

    /** 返回含 sample_count 的完整统计，供 HorizonJudge 调权可见性使用 */
    public Map<String, Map<String, AgentStat>> loadFullStats(String symbol, String regime) {
        List<Map<String, Object>> rows =
                voteMapper.selectAgentPerformanceStats(symbol, normalizeRegime(regime), LOOKBACK_DAYS);
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }

        Map<String, Map<String, AgentStat>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String agent = stringValue(value(row, "agent"));
            String horizon = stringValue(value(row, "horizon"));
            int samples = intValue(value(row, "sample_count"));
            double accuracy = doubleValue(value(row, "accuracy"));
            double avgEdgeBps = doubleValue(value(row, "avg_edge_bps"));
            double badRate = doubleValue(value(row, "bad_rate"));

            if (agent.isBlank() || horizon.isBlank() || samples < MIN_SAMPLE_COUNT) {
                continue;
            }

            result.computeIfAbsent(agent, ignored -> new HashMap<>())
                    .put(horizon, new AgentStat(clamp01(accuracy), samples, avgEdgeBps, badRate));
            log.info("[Memory.agent] symbol={} regime={} agent={} horizon={} samples={} acc={} avgEdgeBps={} badRate={}",
                    symbol, normalizeRegime(regime), agent, horizon, samples,
                    String.format("%.3f", accuracy), String.format("%.1f", avgEdgeBps),
                    String.format("%.3f", badRate));
        }

        return freezeFull(result);
    }

    private Map<String, Map<String, AgentStat>> freezeFull(Map<String, Map<String, AgentStat>> source) {
        Map<String, Map<String, AgentStat>> frozen = new HashMap<>();
        source.forEach((agent, horizons) -> frozen.put(agent, Map.copyOf(horizons)));
        return Map.copyOf(frozen);
    }

    private String normalizeRegime(String regime) {
        return regime == null || regime.isBlank() ? null : regime.trim().toUpperCase();
    }

    private Object value(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value != null) return value;
        value = row.get(key.toUpperCase());
        if (value != null) return value;
        return row.get(toCamelCase(key));
    }

    private String toCamelCase(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upperNext = false;
        for (char ch : snake.toCharArray()) {
            if (ch == '_') {
                upperNext = true;
                continue;
            }
            sb.append(upperNext ? Character.toUpperCase(ch) : ch);
            upperNext = false;
        }
        return sb.toString();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int intValue(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private double doubleValue(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd.doubleValue();
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0.0;
    }

    private double clamp01(double value) {
        return Math.clamp(value, 0.0, 1.0);
    }
}
