package com.mawai.wiibservice.agent.behavior;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.fastjson2.JSON;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.agent.config.AiAgentRuntimeManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class BehaviorAnalysisService {

    private final AiAgentRuntimeManager aiAgentRuntimeManager;

    private final Semaphore behaviorSemaphore = new Semaphore(10);
    private final Cache<Long, BehaviorAnalysisReport> reportCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    public Result<BehaviorAnalysisReport> analyze(long userId) {
        BehaviorAnalysisReport cached = reportCache.getIfPresent(userId);
        if (cached != null) {
            return Result.ok(cached);
        }

        if (!behaviorSemaphore.tryAcquire()) {
            return Result.fail("当前分析人数已满，请稍后再试");
        }
        try {
            Result<BehaviorAnalysisReport> result = doAnalyze(userId);
            if (result.getCode() == 0 && result.getData() != null) {
                reportCache.put(userId, result.getData());
            }
            return result;
        } finally {
            behaviorSemaphore.release();
        }
    }

    private Result<BehaviorAnalysisReport> doAnalyze(long userId) {
        log.info("用户{}请求行为分析", userId);

        ReactAgent agent = aiAgentRuntimeManager.createBehaviorAgent(step -> log.info("用户{} 工具调用: {}", userId, step));

        String text;
        try {
            text = collectResponse(agent, userId);
        } catch (Exception e) {
            log.error("行为分析执行失败 userId={}", userId, e);
            return Result.fail("分析执行失败: " + e.getMessage());
        }

        BehaviorAnalysisReport report;
        try {
            report = JSON.parseObject(JsonUtils.extractJson(text), BehaviorAnalysisReport.class);
        } catch (Exception e) {
            log.error("行为分析报告解析失败 userId={}", userId, e);
            return Result.fail("分析结果解析失败");
        }

        if (!report.isValid()) {
            log.error("行为分析关键字段缺失 userId={}", userId);
            return Result.fail("分析结果不完整，请重试");
        }

        log.info("用户{} 行为分析完成", userId);
        return Result.ok(report);
    }

    private String collectResponse(ReactAgent agent, long userId) {
        String prompt = "分析用户#" + userId + "的全部行为数据，用户ID为" + userId;
        StringBuilder assistantChunks = new StringBuilder();

        try {
            agent.streamMessages(prompt, RunnableConfig.builder().threadId("behavior-" + userId).build())
                    .doOnNext(message -> appendResponse(message, assistantChunks))
                    .blockLast();
        } catch (Exception e) {
            throw new RuntimeException("行为分析流式执行失败: " + e.getMessage(), e);
        }

        String finalText = assistantChunks.toString();
        if (finalText.isBlank()) {
            throw new IllegalStateException("行为分析未返回有效内容");
        }
        log.info("用户{} 行为分析完成, responseLength={}", userId, finalText.length());
        return finalText;
    }

    private void appendResponse(Message message, StringBuilder assistantChunks) {
        if (!(message instanceof AssistantMessage assistant)) {
            return;
        }
        String text = assistant.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        assistantChunks.append(text);
    }
}
