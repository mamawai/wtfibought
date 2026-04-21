package com.mawai.wiibservice.agent;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.mawai.wiibcommon.annotation.RateLimiter;
import com.mawai.wiibcommon.constant.RateLimiterType;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibservice.agent.behavior.BehaviorAnalysisReport;
import com.mawai.wiibservice.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibservice.agent.quant.CryptoAnalysisReport;
import com.mawai.wiibservice.agent.quant.QuantForecastPersistService;
import com.mawai.wiibservice.agent.quant.domain.LlmCallMode;
import com.mawai.wiibservice.agent.quant.domain.ForecastResult;
import com.mawai.wiibservice.agent.quant.memory.VerificationService;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import com.mawai.wiibservice.mapper.QuantForecastCycleMapper;
import com.mawai.wiibservice.mapper.QuantSignalDecisionMapper;

import com.alibaba.fastjson2.JSON;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Tag(name = "AI Agent接口")
@RestController
@RequestMapping("/api/ai")
public class AiAgentController {

    private final AiAgentRuntimeManager aiAgentRuntimeManager;
    private final QuantForecastPersistService persistService;
    private final QuantForecastCycleMapper cycleMapper;
    private final QuantSignalDecisionMapper decisionMapper;
    private final VerificationService verificationService;
    private final ExecutorService chatStreamExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private static final Semaphore BEHAVIOR_SEMAPHORE = new Semaphore(10);
    private static final long BEHAVIOR_COOLDOWN_MS = 300_000;
    private final Map<Long, BehaviorAnalysisReport> behaviorReportCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, Long> behaviorLastTime = new java.util.concurrent.ConcurrentHashMap<>();

    public AiAgentController(AiAgentRuntimeManager aiAgentRuntimeManager,
                             QuantForecastPersistService persistService,
                             QuantForecastCycleMapper cycleMapper,
                             QuantSignalDecisionMapper decisionMapper,
                             VerificationService verificationService) {
        this.aiAgentRuntimeManager = aiAgentRuntimeManager;
        this.persistService = persistService;
        this.cycleMapper = cycleMapper;
        this.decisionMapper = decisionMapper;
        this.verificationService = verificationService;
    }

    @Data
    public static class AnalyzeCryptoRequest {
        private String symbol;
    }

    @Data
    public static class ChatRequest {
        private String message;
        private String context;
        private List<ChatMessage> history;
    }

    @Data
    public static class ChatMessage {
        private String role;
        private String content;
    }

    @PostMapping("/analyze-behavior")
    @Operation(summary = "用户行为分析")
    public Result<BehaviorAnalysisReport> analyzeBehavior() {
        long userId = StpUtil.getLoginIdAsLong();

        Long lastTime = behaviorLastTime.get(userId);
        if (lastTime != null && System.currentTimeMillis() - lastTime < BEHAVIOR_COOLDOWN_MS) {
            BehaviorAnalysisReport cached = behaviorReportCache.get(userId);
            if (cached != null) return Result.ok(cached);
        }

        if (!BEHAVIOR_SEMAPHORE.tryAcquire()) {
            BehaviorAnalysisReport cached = behaviorReportCache.get(userId);
            return cached != null ? Result.ok(cached) : Result.fail("当前分析人数已满，请稍后再试");
        }
        try {
            Result<BehaviorAnalysisReport> result = doAnalyzeBehavior(userId);
            if (result.getCode() == 0 && result.getData() != null) {
                behaviorReportCache.put(userId, result.getData());
                behaviorLastTime.put(userId, System.currentTimeMillis());
            }
            return result;
        } finally {
            BEHAVIOR_SEMAPHORE.release();
        }
    }

    private Result<BehaviorAnalysisReport> doAnalyzeBehavior(long userId) {
        log.info("用户{}请求行为分析", userId);

        ReactAgent agent = aiAgentRuntimeManager.createBehaviorAgent(step -> log.info("用户{} 工具调用: {}", userId, step));

        String text;
        try {
            text = collectBehaviorAnalysisResponse(agent, userId);
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

    private String collectBehaviorAnalysisResponse(ReactAgent agent, long userId) {
        String prompt = "分析用户#" + userId + "的全部行为数据，用户ID为" + userId;
        StringBuilder assistantChunks = new StringBuilder();

        try {
            agent.streamMessages(prompt, RunnableConfig.builder().threadId("behavior-" + userId).build())
                    .doOnNext(message -> appendBehaviorResponse(message, assistantChunks))
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

    private void appendBehaviorResponse(Message message, StringBuilder assistantChunks) {
        if (!(message instanceof AssistantMessage assistant)) {
            return;
        }
        String text = assistant.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        assistantChunks.append(text);
    }

    @PreDestroy
    public void shutdownExecutors() {
        chatStreamExecutor.shutdown();
    }

    @GetMapping("/analyze-crypto/latest")
    @Operation(summary = "获取最新量化分析报告（不触发分析）")
    public Result<Map<String, Object>> latestCryptoReport(@RequestParam(defaultValue = "BTCUSDT") String symbol) {
        StpUtil.checkLogin();
        String normalized;
        try {
            normalized = QuantConstants.normalizeSymbol(symbol);
        } catch (IllegalArgumentException e) {
            return Result.fail(ErrorCode.PARAM_ERROR.getCode(), "symbol格式错误");
        }

        QuantForecastCycle cycle = cycleMapper.selectLatest(normalized);
        if (cycle == null) {
            return Result.ok(Map.of("status", "pending", "message", "暂无分析报告，等待系统生成"));
        }

        // 35分钟内的报告视为有效（30分钟周期 + 5分钟容差）
        boolean recent = cycle.getForecastTime() != null
                && cycle.getForecastTime().isAfter(java.time.LocalDateTime.now().minusMinutes(35));
        if (!recent) {
            return Result.ok(Map.of("status", "pending", "message", "报告已过期，等待下一轮分析",
                    "lastForecastTime", Objects.requireNonNull(cycle.getForecastTime()).toString()));
        }

        if (cycle.getReportJson() == null || cycle.getReportJson().isBlank()) {
            return Result.ok(Map.of("status", "pending", "message", "分析进行中，请稍候",
                    "forecastTime", cycle.getForecastTime().toString()));
        }

        try {
            CryptoAnalysisReport report = JSON.parseObject(cycle.getReportJson(), CryptoAnalysisReport.class);
            return Result.ok(Map.of(
                    "status", "ready",
                    "forecastTime", cycle.getForecastTime().toString(),
                    "cycleId", cycle.getCycleId(),
                    "overallDecision", cycle.getOverallDecision(),
                    "riskStatus", cycle.getRiskStatus(),
                    "report", report));
        } catch (Exception e) {
            log.error("报告JSON解析失败 cycleId={}", cycle.getCycleId(), e);
            return Result.fail("报告数据异常");
        }
    }

    @PostMapping("/analyze-crypto")
    @Operation(summary = "加密货币量化分析")
    public Result<CryptoAnalysisReport> analyzeCrypto(@RequestBody(required = false) AnalyzeCryptoRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        String symbol = request != null ? request.getSymbol() : null;

        String normalized;
        try {
            normalized = QuantConstants.normalizeSymbol(symbol);
        } catch (IllegalArgumentException e) {
            return Result.fail(ErrorCode.PARAM_ERROR.getCode(), "symbol格式错误");
        }

        // 通过代理调用，触发 @RateLimiter AOP
        return SpringUtils.getAopProxy(this).doAnalyzeCryptoInternal(userId, normalized);
    }

    @RateLimiter(type = RateLimiterType.AI_CRYPTO, message = "量化分析每分钟限1次")
    public Result<CryptoAnalysisReport> doAnalyzeCryptoInternal(long userId, String symbol) {
        long startMs = System.currentTimeMillis();
        log.info("[Quant] 工作流开始 userId={} symbol={}", userId, symbol);

        try {
            Optional<OverAllState> result = aiAgentRuntimeManager.invokeQuantWithFallback(
                    symbol, "crypto-" + userId);

            if (result.isEmpty()) {
                log.warn("[Quant] 工作流无结果 userId={} symbol={} 耗时{}ms", userId, symbol, System.currentTimeMillis() - startMs);
                return Result.fail("分析无结果");
            }

            OverAllState state = result.get();
            boolean dataAvailable = (boolean) state.value("data_available").orElse(true);
            if (!dataAvailable) {
                log.warn("[Quant] 行情采集失败 userId={} symbol={} 耗时{}ms", userId, symbol, System.currentTimeMillis() - startMs);
                return Result.fail("行情数据采集失败，请稍后重试");
            }

            Object report = state.value("report").orElse(null);
            if (report instanceof CryptoAnalysisReport r) {
                if (!r.isValid()) {
                    log.error("[Quant] 报告关键字段缺失 userId={} symbol={} 耗时{}ms", userId, symbol, System.currentTimeMillis() - startMs);
                    return Result.fail("分析结果不完整，请重试");
                }
                Object fr = state.value("forecast_result").orElse(null);
                ForecastResult forecastResult = null;
                if (fr instanceof String frJson) {
                    forecastResult = JSON.parseObject(frJson, ForecastResult.class);
                } else if (fr instanceof ForecastResult frObj) {
                    forecastResult = frObj;
                }
                if (forecastResult != null) {
                    String debateSummary = (String) state.value("debate_summary").orElse(null);
                    String rawSnapshotJson = (String) state.value("raw_snapshot_json").orElse(null);
                    String rawReportJson = (String) state.value("raw_report_json").orElse(null);
                    persistService.persist(forecastResult, debateSummary, rawSnapshotJson, rawReportJson);
                } else {
                    log.warn("[Quant] forecast_result为空或类型异常 type={}", fr != null ? fr.getClass().getName() : "null");
                }
                log.info("[Quant] 工作流完成 userId={} symbol={} confidence={} summary={} 总耗时{}ms",
                        userId, symbol, r.getConfidence(), r.getSummary(), System.currentTimeMillis() - startMs);
                return Result.ok(r);
            }
            log.error("[Quant] 报告格式错误 userId={} symbol={} 耗时{}ms", userId, symbol, System.currentTimeMillis() - startMs);
            return Result.fail("分析结果格式错误");
        } catch (Exception e) {
            log.error("[Quant] 工作流异常 userId={} symbol={} 耗时{}ms", userId, symbol, System.currentTimeMillis() - startMs, e);
            return Result.fail("分析失败，请稍后重试");
        }
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "追问对话")
    public SseEmitter chat(@RequestBody ChatRequest request) {
        StpUtil.checkLogin();
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }

        return SpringUtils.getAopProxy(this).doChatInternal(buildChatPrompt(request));
    }

    private String buildChatPrompt(ChatRequest request) {
        StringBuilder prompt = new StringBuilder();
        if (request.getContext() != null && !request.getContext().isBlank()) {
            String ctx = request.getContext();
            if (ctx.length() > 2000) {
                ctx = ctx.substring(0, 2000) + "...(已截断)";
            }
            prompt.append("【当前分析报告摘要】\n").append(ctx).append("\n\n");
        }
        if (request.getHistory() != null) {
            for (ChatMessage msg : recentCompleteRounds(request.getHistory(), 3)) {
                prompt.append("user".equals(msg.getRole()) ? "用户: " : "分析师: ")
                        .append(msg.getContent()).append("\n");
            }
        }
        prompt.append("用户: ").append(request.getMessage());
        return prompt.toString();
    }

    private List<ChatMessage> recentCompleteRounds(List<ChatMessage> history, int roundLimit) {
        List<ChatMessage> completeRounds = new ArrayList<>();
        ChatMessage pendingUser = null;

        for (ChatMessage msg : history) {
            if ("user".equals(msg.getRole())) {
                pendingUser = msg;
                continue;
            }
            if ("assistant".equals(msg.getRole()) && pendingUser != null) {
                completeRounds.add(pendingUser);
                completeRounds.add(msg);
                pendingUser = null;
            }
        }

        int messageLimit = roundLimit * 2;
        if (completeRounds.size() <= messageLimit) {
            return completeRounds;
        }
        return completeRounds.subList(completeRounds.size() - messageLimit, completeRounds.size());
    }

    @RateLimiter(type = RateLimiterType.AI_CHAT, permitsPerSecond = 6.0 / 60.0, bucketCapacity = 6, message = "追问每10秒限1次")
    public SseEmitter doChatInternal(String prompt) {
        SseEmitter emitter = new SseEmitter(120_000L);
        AtomicBoolean closed = new AtomicBoolean(false);

        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(ex -> closed.set(true));

        chatStreamExecutor.submit(() -> {
            try {
                ChatClient chatClient = ChatClient.builder(aiAgentRuntimeManager.current().chatChatModel())
                        .defaultSystem("""
                                你是专业的加密货币量化分析师，基于已有分析报告回答用户追问。
                                回答规则：
                                1. 控制在300字以内，精炼直接
                                2. 先给结论，再给理由
                                3. 涉及价格时给出具体数字
                                4. 术语首次出现带简短注释
                                5. 不要重复报告里已有的内容，针对问题回答
                                """)
                        .build();

                LlmCallMode.STREAMING.stream(chatClient, prompt, chunk -> {
                    if (closed.get()) {
                        return;
                    }
                    try {
                        emitter.send(SseEmitter.event().name("chunk").data(chunk));
                    } catch (Exception sendError) {
                        throw new RuntimeException(sendError);
                    }
                });

                if (!closed.get()) {
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                    emitter.complete();
                }
            } catch (Exception e) {
                log.error("对话失败", e);
                if (!closed.get()) {
                    try {
                        emitter.send(SseEmitter.event().name("error")
                                .data("回答失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误")));
                    } catch (Exception ignored) {
                    }
                    emitter.completeWithError(e);
                }
            }
        });

        return emitter;
    }

    @GetMapping("/quant/signals/latest")
    @Operation(summary = "查最新量化信号")
    public Result<Map<String, Object>> latestSignals(@RequestParam(defaultValue = "BTCUSDT") String symbol) {
        StpUtil.checkLogin();
        String normalized;
        try {
            normalized = QuantConstants.normalizeSymbol(symbol);
        } catch (IllegalArgumentException e) {
            return Result.fail(ErrorCode.PARAM_ERROR.getCode(), "symbol格式错误");
        }

        QuantForecastCycle cycle = cycleMapper.selectLatest(normalized);
        if (cycle == null) {
            return Result.fail("暂无预测数据");
        }

        List<QuantSignalDecision> signals = decisionMapper.selectLatestBySymbol(normalized);
        Map<String, Object> data = Map.of(
                "cycleId", cycle.getCycleId(),
                "symbol", cycle.getSymbol(),
                "forecastTime", cycle.getForecastTime(),
                "overallDecision", cycle.getOverallDecision(),
                "riskStatus", cycle.getRiskStatus(),
                "signals", signals
        );
        return Result.ok(data);
    }

    @GetMapping("/quant/forecasts")
    @Operation(summary = "查历史量化预测")
    public Result<List<QuantForecastCycle>> forecasts(
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam(defaultValue = "20") int limit) {
        StpUtil.checkLogin();
        String normalized;
        try {
            normalized = QuantConstants.normalizeSymbol(symbol);
        } catch (IllegalArgumentException e) {
            return Result.fail(ErrorCode.PARAM_ERROR.getCode(), "symbol格式错误");
        }

        limit = Math.min(limit, 100);
        List<QuantForecastCycle> list = cycleMapper.selectRecent(normalized, limit);
        return Result.ok(list);
    }

    @GetMapping("/quant/verifications")
    @Operation(summary = "查历史量化预测验证")
    public Result<VerificationService.VerificationSummary> verifications(
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam(defaultValue = "10") int limit) {
        StpUtil.checkLogin();
        String normalized;
        try {
            normalized = QuantConstants.normalizeSymbol(symbol);
        } catch (IllegalArgumentException e) {
            return Result.fail(ErrorCode.PARAM_ERROR.getCode(), "symbol格式错误");
        }

        limit = Math.min(limit, 20);
        return Result.ok(verificationService.recentVerificationResults(normalized, limit));
    }

    @GetMapping("/quant/verifications/grouped")
    @Operation(summary = "分组查询量化预测验证（重周期为主，轻周期挂载）")
    public Result<VerificationService.GroupedVerificationSummary> groupedVerifications(
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam(defaultValue = "10") int limit) {
        StpUtil.checkLogin();
        String normalized;
        try {
            normalized = QuantConstants.normalizeSymbol(symbol);
        } catch (IllegalArgumentException e) {
            return Result.fail(ErrorCode.PARAM_ERROR.getCode(), "symbol格式错误");
        }
        limit = Math.min(limit, 20);
        return Result.ok(verificationService.groupedVerificationResults(normalized, limit));
    }


}
