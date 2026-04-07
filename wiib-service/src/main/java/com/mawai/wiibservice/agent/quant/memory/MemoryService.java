package com.mawai.wiibservice.agent.quant.memory;

import com.mawai.wiibcommon.entity.QuantForecastVerification;
import com.mawai.wiibcommon.entity.QuantReflectionMemory;
import com.mawai.wiibservice.mapper.QuantForecastVerificationMapper;
import com.mawai.wiibservice.mapper.QuantReflectionMemoryMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final QuantForecastVerificationMapper verificationMapper;
    private final QuantReflectionMemoryMapper reflectionMapper;

    /**
     * 组装完整的记忆上下文，供LLM prompt注入
     */
    public String buildMemoryContext(String symbol, String regime) {
        StringBuilder sb = new StringBuilder();

        // 1. 同regime下的反思教训
        List<QuantReflectionMemory> regimeMemories = reflectionMapper.selectByRegime(symbol, regime, 3);
        if (!regimeMemories.isEmpty()) {
            sb.append("最近").append(regimeMemories.size()).append("次 ").append(regime).append(" 下的反思:\n");
            for (QuantReflectionMemory m : regimeMemories) {
                sb.append("- ").append(m.getPredictedDirection());
                if (m.getActualPriceChangeBps() != null) {
                    sb.append(" → 实际").append(m.getActualPriceChangeBps() >= 0 ? "+" : "")
                            .append(m.getActualPriceChangeBps()).append("bps");
                }
                sb.append(m.getPredictionCorrect() != null && m.getPredictionCorrect() ? " ✓" : " ✗");
                sb.append("\n");
            }
            // 最近一条的反思结论
            String lesson = regimeMemories.get(0).getReflectionText();
            if (lesson != null && !lesson.isBlank()) {
                sb.append("反思教训: ").append(truncate(lesson, 200)).append("\n");
            }
        }

        // 2. 最近24h命中率（单次查询同时拿correct/total）
        List<QuantForecastVerification> recent = verificationMapper.selectRecent(symbol, 30);
        if (!recent.isEmpty()) {
            long correct = recent.stream().filter(v -> v.getPredictionCorrect() != null && v.getPredictionCorrect()).count();
            sb.append("最近命中率: ").append(correct).append("/").append(recent.size())
                    .append(" (").append(recent.size() > 0 ? correct * 100 / recent.size() : 0).append("%)\n");
        }

        // 3. 最近的通用反思教训
        List<QuantReflectionMemory> recentLessons = reflectionMapper.selectRecent(symbol, 2);
        if (!recentLessons.isEmpty()) {
            for (QuantReflectionMemory m : recentLessons) {
                if (m.getLessonTags() != null && !m.getLessonTags().isBlank()) {
                    sb.append("标签: ").append(m.getLessonTags()).append("\n");
                }
            }
        }

        String result = sb.toString().trim();
        if (result.isEmpty()) return "暂无历史记忆";
        return result;
    }

    /**
     * 查询准确率统计（供报告用）
     */
    public String buildAccuracySummary(String symbol) {
        Double acc24h = verificationMapper.selectAccuracyRate(symbol, 24);
        if (acc24h == null) return "";
        return String.format("最近24h预测命中率: %.0f%%", acc24h * 100);
    }

    /**
     * 从最近的反思记忆中提取agent准确率评估。
     * 返回 agent → horizon → accuracy(0-1)，无数据时返回空Map。
     */
    public Map<String, Map<String, Double>> getAgentAccuracy(String symbol) {
        List<QuantReflectionMemory> recent = reflectionMapper.selectRecent(symbol, 5);
        for (QuantReflectionMemory m : recent) {
            String text = m.getReflectionText();
            if (text == null || !text.contains("[AGENT_ACCURACY]")) continue;
            int idx = text.indexOf("[AGENT_ACCURACY]");
            String jsonPart = text.substring(idx + "[AGENT_ACCURACY]".length()).trim();
            try {
                JSONObject obj = JSON.parseObject(jsonPart);
                Map<String, Map<String, Double>> result = new HashMap<>();
                for (String agent : obj.keySet()) {
                    JSONObject horizons = obj.getJSONObject(agent);
                    if (horizons == null) continue;
                    Map<String, Double> hMap = new HashMap<>();
                    for (String h : horizons.keySet()) {
                        hMap.put(h, horizons.getDoubleValue(h));
                    }
                    result.put(agent, hMap);
                }
                return result;
            } catch (Exception e) {
                log.warn("[Memory] agentAccuracy解析失败: {}", e.getMessage());
            }
        }
        return Map.of();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
