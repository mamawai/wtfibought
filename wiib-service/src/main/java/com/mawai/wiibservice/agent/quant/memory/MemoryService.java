package com.mawai.wiibservice.agent.quant.memory;

import com.mawai.wiibcommon.entity.QuantForecastVerification;
import com.mawai.wiibcommon.entity.QuantReflectionMemory;
import com.mawai.wiibservice.mapper.QuantForecastVerificationMapper;
import com.mawai.wiibservice.mapper.QuantReflectionMemoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MemoryService {

    private final QuantForecastVerificationMapper verificationMapper;
    private final QuantReflectionMemoryMapper reflectionMapper;
    private final AgentPerformanceMemoryService agentPerformanceMemoryService;

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
            String lesson = regimeMemories.getFirst().getReflectionText();
            if (lesson != null && !lesson.isBlank()) {
                int markerIdx = lesson.indexOf("[AGENT_ACCURACY]");
                if (markerIdx > 0) lesson = lesson.substring(0, markerIdx);
                else if (markerIdx == 0) lesson = null;
            }
            if (lesson != null && !lesson.isBlank()) {
                sb.append("反思教训: ").append(truncate(lesson, 200)).append("\n");
            }
        }

        // 2. 最近24h命中率（单次查询同时拿correct/total）
        List<QuantForecastVerification> recent = verificationMapper.selectRecent(symbol, 30);
        if (!recent.isEmpty()) {
            long correct = recent.stream().filter(v -> v.getPredictionCorrect() != null && v.getPredictionCorrect()).count();
            sb.append("最近命中率: ").append(correct).append("/").append(recent.size())
                    .append(" (").append(!recent.isEmpty() ? correct * 100 / recent.size() : 0).append("%)\n");
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

    public Map<String, Map<String, Double>> getAgentAccuracy(String symbol) {
        return getAgentAccuracy(symbol, null);
    }

    /**
     * 返回 agent → horizon → accuracy(0-1)。
     * 现在只用真实验证样本统计，不再使用LLM反思里的主观agentAccuracy。
     */
    public Map<String, Map<String, Double>> getAgentAccuracy(String symbol, String regime) {
        return agentPerformanceMemoryService.loadAccuracy(symbol, regime);
    }

    /** 返回含 sample_count 的完整统计，供 HorizonJudge 调权可见性使用 */
    public Map<String, Map<String, AgentPerformanceMemoryService.AgentStat>> getAgentFullStats(
            String symbol, String regime) {
        return agentPerformanceMemoryService.loadFullStats(symbol, regime);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
