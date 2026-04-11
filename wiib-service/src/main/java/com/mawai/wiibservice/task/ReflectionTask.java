package com.mawai.wiibservice.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantForecastVerification;
import com.mawai.wiibcommon.entity.QuantHorizonForecast;
import com.mawai.wiibcommon.entity.QuantReflectionMemory;
import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibservice.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibservice.agent.quant.domain.LlmCallMode;
import com.mawai.wiibservice.agent.quant.memory.VerificationService;
import com.mawai.wiibservice.mapper.*;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class ReflectionTask {

    private final AiAgentRuntimeManager aiAgentRuntimeManager;
    private final QuantForecastCycleMapper cycleMapper;
    private final QuantHorizonForecastMapper horizonMapper;
    private final QuantForecastVerificationMapper verificationMapper;
    private final QuantReflectionMemoryMapper reflectionMapper;
    private final QuantAgentVoteMapper voteMapper;
    private final VerificationService verificationService;

    public ReflectionTask(AiAgentRuntimeManager aiAgentRuntimeManager,
                          QuantForecastCycleMapper cycleMapper,
                          QuantHorizonForecastMapper horizonMapper,
                          QuantForecastVerificationMapper verificationMapper,
                          QuantReflectionMemoryMapper reflectionMapper,
                          QuantAgentVoteMapper voteMapper,
                          VerificationService verificationService) {
        this.aiAgentRuntimeManager = aiAgentRuntimeManager;
        this.cycleMapper = cycleMapper;
        this.horizonMapper = horizonMapper;
        this.verificationMapper = verificationMapper;
        this.reflectionMapper = reflectionMapper;
        this.voteMapper = voteMapper;
        this.verificationService = verificationService;
    }

    private static final List<String> WATCH_LIST = QuantConstants.WATCH_SYMBOLS;

    /**
     * 每小时执行：批量验证 + LLM反思 + 写入记忆
     */
    @Scheduled(cron = "0 5 * * * *")
    public void reflectAndLearn() {
        for (String symbol : WATCH_LIST) {
            Thread.startVirtualThread(() -> {
                try {
                    runReflection(symbol);
                } catch (Exception e) {
                    log.error("[Reflect] 反思任务异常 symbol={}", symbol, e);
                }
            });
        }
    }

    private void runReflection(String symbol) {
        // 1. 验证未验证的周期（最多24个，约12小时的量）
        List<QuantForecastCycle> unverified = cycleMapper.selectUnverified(symbol, 24);
        if (unverified.isEmpty()) {
            log.info("[Reflect] 无待验证周期 symbol={}", symbol);
            return;
        }

        int verifiedCount = 0;
        for (QuantForecastCycle cycle : unverified) {
            List<QuantHorizonForecast> forecasts = horizonMapper.selectByCycleId(cycle.getCycleId());
            if (!forecasts.isEmpty()) {
                if (verificationService.verifyCycle(cycle, forecasts) > 0) verifiedCount++;
            }
        }
        log.info("[Reflect] 验证完成 symbol={} verified={}/{}", symbol, verifiedCount, unverified.size());

        // 2. 收集最近验证结果用于反思
        List<QuantForecastVerification> recentVerifications = verificationMapper.selectRecent(symbol, 36);
        if (recentVerifications.isEmpty()) {
            log.info("[Reflect] 无验证数据，跳过反思 symbol={}", symbol);
            return;
        }

        // 3. LLM反思（最多重试3次，间隔递增）
        String reflectionPrompt = buildReflectionPrompt(recentVerifications, symbol);
        String llmResponse = null;
        int maxRetries = 3;
        for (int i = 0; i <= maxRetries; i++) {
            try {
                ChatClient chatClient = ChatClient.builder(aiAgentRuntimeManager.current().reflectionChatModel()).build();
                llmResponse = LlmCallMode.STREAMING.call(chatClient, reflectionPrompt);
                break;
            } catch (Exception e) {
                if (i < maxRetries) {
                    log.warn("[Reflect] LLM反思调用失败，重试 {}/{} symbol={}: {}", i + 1, maxRetries, symbol, e.getMessage());
                    long[] delays = {3000L, 10000L, 30000L};
                    try { Thread.sleep(delays[i]); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                } else {
                    log.warn("[Reflect] LLM反思调用失败，已重试{}次放弃 symbol={}", maxRetries, symbol, e);
                    return;
                }
            }
        }

        // 4. 解析反思结果并写入记忆
        saveReflection(llmResponse, recentVerifications, symbol);

        // 5. 清理30天前的旧记忆
        reflectionMapper.cleanupOld();
    }

    private String buildReflectionPrompt(List<QuantForecastVerification> verifications, String symbol) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是量化系统复盘分析师。以下是").append(symbol).append("最近的预测验证结果。\n\n");
        sb.append("【预测验证】\n");

        int correct = 0, total = 0;
        int goodCount = 0, luckyCount = 0, badCount = 0;
        for (QuantForecastVerification v : verifications) {
            total++;
            if (v.getPredictionCorrect() != null && v.getPredictionCorrect()) correct++;
            String quality = v.getTradeQuality() != null ? v.getTradeQuality() : "?";
            switch (quality) {
                case "GOOD" -> goodCount++;
                case "LUCKY" -> luckyCount++;
                case "BAD" -> badCount++;
            }
            sb.append(total).append(". ")
                    .append(v.getHorizon()).append(" ")
                    .append("预测=").append(v.getPredictedDirection())
                    .append(" conf=").append(v.getPredictedConfidence())
                    .append(" 实际=").append(v.getActualChangeBps() >= 0 ? "+" : "").append(v.getActualChangeBps()).append("bps");
            if (v.getMaxFavorableBps() != null) {
                sb.append(" 最大有利=+").append(v.getMaxFavorableBps()).append("bps");
            }
            if (v.getMaxAdverseBps() != null) {
                sb.append(" 最大回撤=-").append(v.getMaxAdverseBps()).append("bps");
            }
            if (v.getTp1HitFirst() != null) {
                sb.append(v.getTp1HitFirst() ? " TP1先触" : " SL先触");
            }
            sb.append(" 评级=").append(quality)
                    .append("\n");
        }
        sb.append("\n命中率: ").append(correct).append("/").append(total)
                .append(" (").append(total > 0 ? correct * 100 / total : 0).append("%)");
        sb.append(" | GOOD=").append(goodCount).append(" LUCKY=").append(luckyCount)
                .append(" BAD=").append(badCount).append("\n\n");

        // 注入agent投票历史，供按agent维度分析
        appendAgentVoteHistory(sb, verifications);

        sb.append("""
                请分析：
                1. 哪些情况下预测准确/不准确？（按方向、区间维度分析）
                2. 是否存在系统性偏差？（如总是偏多/偏空）
                3. confidence高时准确率是否更高？
                4. 各个agent（microstructure/momentum/regime/volatility/news_event）在不同区间的贡献质量如何？
                5. 给出2-3条改进建议

                严格返回JSON（不要markdown包裹）：
                {
                  "overallAccuracy": 0.65,
                  "biases": ["偏差描述"],
                  "agentAccuracy": {
                    "microstructure": {"0_10": 0.7, "10_20": 0.5, "20_30": 0.4},
                    "momentum": {"0_10": 0.6, "10_20": 0.65, "20_30": 0.6},
                    "regime": {"0_10": 0.5, "10_20": 0.55, "20_30": 0.6},
                    "news_event": {"0_10": 0.5, "10_20": 0.5, "20_30": 0.5}
                  },
                  "lessons": [
                    {"tag": "标签如RANGE_BULLISH_BIAS", "lesson": "具体教训，50字内"}
                  ]
                }

                说明：agentAccuracy是你对每个agent在各区间预测贡献质量的评估(0-1)，
                基于验证结果和agent投票方向与实际走势的一致性来判断。
                如果数据不足以判断某个agent，用0.5（中性）。
                """);
        return sb.toString();
    }

    private void appendAgentVoteHistory(StringBuilder sb, List<QuantForecastVerification> verifications) {
        // 取最近几个cycle的agent投票，让LLM能分析单agent准确性
        java.util.Set<String> cycleIds = new java.util.LinkedHashSet<>();
        for (QuantForecastVerification v : verifications) {
            cycleIds.add(v.getCycleId());
            if (cycleIds.size() >= 6) break;
        }
        if (cycleIds.isEmpty()) return;

        sb.append("【Agent投票历史（最近").append(cycleIds.size()).append("个周期）】\n");
        for (String cycleId : cycleIds) {
            var votes = voteMapper.selectByCycleId(cycleId);
            if (votes == null || votes.isEmpty()) continue;
            sb.append("cycle=").append(cycleId.substring(Math.max(0, cycleId.length() - 15))).append(":\n");
            for (var vote : votes) {
                sb.append("  ").append(vote.getAgent()).append("[").append(vote.getHorizon()).append("]=")
                        .append(vote.getDirection()).append(" score=").append(vote.getScore())
                        .append(" conf=").append(vote.getConfidence()).append("\n");
            }
        }
        sb.append("\n");
    }

    private void saveReflection(String llmResponse, List<QuantForecastVerification> verifications, String symbol) {
        try {
            String json = JsonUtils.extractJson(llmResponse);
            JSONObject root = JSON.parseObject(json);
            JSONArray lessons = root.getJSONArray("lessons");
            if (lessons == null || lessons.isEmpty()) return;

            QuantForecastVerification latest = verifications.getFirst();
            QuantForecastCycle cycle = cycleMapper.selectOne(
                    new LambdaQueryWrapper<QuantForecastCycle>()
                            .eq(QuantForecastCycle::getCycleId, latest.getCycleId()));
            String regime = "UNKNOWN";
            if (cycle != null && cycle.getSnapshotJson() != null) {
                try {
                    JSONObject snap = JSON.parseObject(cycle.getSnapshotJson());
                    regime = snap.getString("regime") != null ? snap.getString("regime") : "UNKNOWN";
                } catch (Exception ignored) {}
            }

            StringBuilder allTags = new StringBuilder();
            StringBuilder allLessons = new StringBuilder();
            for (int i = 0; i < lessons.size(); i++) {
                JSONObject lesson = lessons.getJSONObject(i);
                String tag = lesson.getString("tag");
                String text = lesson.getString("lesson");
                if (tag != null) allTags.append(tag).append(",");
                if (text != null) allLessons.append(text).append(" ");
            }

            // agentAccuracy存入reflectionText，供MemoryService解析
            JSONObject agentAccuracy = root.getJSONObject("agentAccuracy");
            if (agentAccuracy != null) {
                allLessons.append("\n[AGENT_ACCURACY]").append(agentAccuracy.toJSONString());
            }

            QuantReflectionMemory memory = new QuantReflectionMemory();
            memory.setSymbol(symbol);
            memory.setCycleId(latest.getCycleId());
            memory.setRegime(regime);
            memory.setOverallDecision(cycle != null ? cycle.getOverallDecision() : null);
            memory.setPredictedDirection(latest.getPredictedDirection());
            memory.setActualPriceChangeBps(latest.getActualChangeBps());
            memory.setPredictionCorrect(latest.getPredictionCorrect());
            memory.setReflectionText(allLessons.toString().trim());
            memory.setLessonTags(!allTags.isEmpty() ? allTags.substring(0, allTags.length() - 1) : null);
            reflectionMapper.insert(memory);

            log.info("[Reflect] 反思记忆写入成功 symbol={} tags={} hasAgentAccuracy={}",
                    symbol, memory.getLessonTags(), agentAccuracy != null);
        } catch (Exception e) {
            log.warn("[Reflect] 反思结果解析失败: {}", e.getMessage());
        }
    }
}
