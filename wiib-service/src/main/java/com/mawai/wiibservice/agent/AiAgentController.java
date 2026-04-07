package com.mawai.wiibservice.agent;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.mawai.wiibcommon.annotation.RateLimiter;
import com.mawai.wiibcommon.constant.RateLimiterType;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibservice.agent.behavior.BehaviorAnalysisReport;
import com.mawai.wiibservice.agent.config.AiAgentConfig;
import com.mawai.wiibservice.agent.quant.CryptoAnalysisReport;
import com.mawai.wiibservice.agent.quant.QuantForecastPersistService;
import com.mawai.wiibservice.agent.quant.domain.ForecastResult;
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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.context.request.async.DeferredResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Tag(name = "AI Agent接口")
@RestController
@RequestMapping("/api/ai")
public class AiAgentController {

    private final AiAgentConfig aiAgentConfig;
    private final CompiledGraph cryptoAnalysisGraph;
    private final QuantForecastPersistService persistService;
    private final QuantForecastCycleMapper cycleMapper;
    private final QuantSignalDecisionMapper decisionMapper;
    private final ChatClient chatClient;
    private final ExecutorService behaviorAnalysisExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public AiAgentController(AiAgentConfig aiAgentConfig, CompiledGraph cryptoAnalysisGraph,
                             QuantForecastPersistService persistService,
                             QuantForecastCycleMapper cycleMapper,
                             QuantSignalDecisionMapper decisionMapper,
                             ChatModel chatModel) {
        this.aiAgentConfig = aiAgentConfig;
        this.cryptoAnalysisGraph = cryptoAnalysisGraph;
        this.persistService = persistService;
        this.cycleMapper = cycleMapper;
        this.decisionMapper = decisionMapper;
        this.chatClient = ChatClient.builder(chatModel)
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
    @RateLimiter(type = RateLimiterType.AI_BEHAVIOR, permitsPerSecond = 1.0 / 300, bucketCapacity = 1, message = "行为分析每5分钟限1次")
    public DeferredResult<Result<BehaviorAnalysisReport>> analyzeBehavior() {
        long userId = StpUtil.getLoginIdAsLong();
        DeferredResult<Result<BehaviorAnalysisReport>> deferred = new DeferredResult<>(300_000L);

        deferred.onTimeout(() -> {
            log.warn("行为分析超时 userId={}", userId);
            if (!deferred.isSetOrExpired()) {
                deferred.setResult(Result.fail("分析超时，请稍后重试"));
            }
        });
        deferred.onError(e -> {
            log.error("行为分析异步请求异常 userId={}", userId, e);
            if (!deferred.isSetOrExpired()) {
                deferred.setResult(Result.fail("分析失败，请稍后重试"));
            }
        });

        behaviorAnalysisExecutor.submit(() -> {
            try {
                Result<BehaviorAnalysisReport> result = doAnalyzeBehavior(userId);
                if (!deferred.isSetOrExpired()) {
                    deferred.setResult(result);
                }
            } catch (Exception e) {
                log.error("行为分析失败 userId={}", userId, e);
                if (!deferred.isSetOrExpired()) {
                    deferred.setResult(Result.fail("分析失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误")));
                }
            }
        });

        return deferred;
    }

    private Result<BehaviorAnalysisReport> doAnalyzeBehavior(long userId) {
        log.info("用户{}请求行为分析", userId);

        ReactAgent agent = aiAgentConfig.createBehaviorAgent(step -> {
            log.info("用户{} 工具调用: {}", userId, step);
        });

        AssistantMessage aiResponse;
        try {
            aiResponse = agent.call("分析用户#" + userId + "的全部行为数据，用户ID为" + userId);
        } catch (Exception e) {
            log.error("行为分析执行失败 userId={}", userId, e);
            return Result.fail("分析执行失败: " + e.getMessage());
        }

        log.info("用户{} 行为分析完成, responseLength={}", userId, aiResponse.getText().length());

        String text = aiResponse.getText();
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

    @PreDestroy
    public void shutdownBehaviorAnalysisExecutor() {
        behaviorAnalysisExecutor.shutdown();
    }

    @PostMapping("/analyze-crypto")
    @Operation(summary = "加密货币量化分析")
    public Result<CryptoAnalysisReport> analyzeCrypto(@RequestBody(required = false) AnalyzeCryptoRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        String symbol = request != null ? request.getSymbol() : null;

        String normalized;
        try {
            normalized = normalizeSymbol(symbol);
        } catch (IllegalArgumentException e) {
            return Result.fail(ErrorCode.PARAM_ERROR.getCode(), "symbol格式错误");
        }

        // 通过代理调用，触发 @RateLimiter AOP
        return SpringUtils.getAopProxy(this).doAnalyzeCryptoInternal(userId, normalized);
    }

    @RateLimiter(type = RateLimiterType.AI_CRYPTO, permitsPerSecond = 1.0 / 60, bucketCapacity = 1,
            message = "量化分析每分钟限1次")
    public Result<CryptoAnalysisReport> doAnalyzeCryptoInternal(long userId, String symbol) {
        long startMs = System.currentTimeMillis();
        log.info("[Quant] 工作流开始 userId={} symbol={}", userId, symbol);

        try {
            Optional<OverAllState> result = cryptoAnalysisGraph.invoke(
                    Map.of("target_symbol", symbol),
                    RunnableConfig.builder().threadId("crypto-" + userId).build()
            );

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
                // 落库（forecast_result 以 JSON 字符串存入 state，避免框架深拷贝破坏 record 类型）
                Object fr = state.value("forecast_result").orElse(null);
                ForecastResult forecastResult = null;
                if (fr instanceof String frJson) {
                    forecastResult = JSON.parseObject(frJson, ForecastResult.class);
                } else if (fr instanceof ForecastResult frObj) {
                    forecastResult = frObj;
                }
                if (forecastResult != null) {
                    String debateSummary = (String) state.value("debate_summary").orElse(null);
                    persistService.persist(forecastResult, debateSummary);
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

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "BTCUSDT";
        }
        symbol = symbol.trim().toUpperCase();
        if (symbol.endsWith("USDT")) {
            symbol = symbol.substring(0, symbol.length() - 4);
        } else if (symbol.endsWith("USDC")) {
            symbol = symbol.substring(0, symbol.length() - 4);
        }
        if (symbol.isBlank()) {
            return "BTCUSDT";
        }
        if (!symbol.matches("^[A-Z0-9]{2,10}$")) {
            throw new IllegalArgumentException("symbol格式错误");
        }
        return symbol + "USDT";
    }

    @PostMapping("/chat")
    @Operation(summary = "追问对话")
    public Result<String> chat(@RequestBody ChatRequest request) {
        StpUtil.checkLogin();
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return Result.fail("消息不能为空");
        }

        StringBuilder prompt = new StringBuilder();
        if (request.getContext() != null && !request.getContext().isBlank()) {
            String ctx = request.getContext();
            if (ctx.length() > 2000) {
                ctx = ctx.substring(0, 2000) + "...(已截断)";
            }
            prompt.append("【当前分析报告摘要】\n").append(ctx).append("\n\n");
        }
        if (request.getHistory() != null) {
            List<ChatMessage> recentHistory = request.getHistory().size() > 3
                    ? request.getHistory().subList(request.getHistory().size() - 3, request.getHistory().size())
                    : request.getHistory();
            for (ChatMessage msg : recentHistory) {
                prompt.append("user".equals(msg.getRole()) ? "用户: " : "分析师: ")
                        .append(msg.getContent()).append("\n");
            }
        }
        prompt.append("用户: ").append(request.getMessage());

        // 通过代理调用，触发 @RateLimiter AOP
        return SpringUtils.getAopProxy(this).doChatInternal(prompt.toString());
    }

    @RateLimiter(type = RateLimiterType.AI_CHAT, permitsPerSecond = 1.0, bucketCapacity = 10, message = "追问每秒限1次")
    public Result<String> doChatInternal(String prompt) {
        try {
            String answer = chatClient.prompt().user(prompt).call().content();
            return Result.ok(answer);
        } catch (Exception e) {
            log.error("对话失败", e);
            return Result.fail("回答失败: " + e.getMessage());
        }
    }

    @GetMapping("/quant/signals/latest")
    @Operation(summary = "查最新量化信号")
    public Result<Map<String, Object>> latestSignals(@RequestParam(defaultValue = "BTCUSDT") String symbol) {
        StpUtil.checkLogin();
        String normalized;
        try {
            normalized = normalizeSymbol(symbol);
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
            normalized = normalizeSymbol(symbol);
        } catch (IllegalArgumentException e) {
            return Result.fail(ErrorCode.PARAM_ERROR.getCode(), "symbol格式错误");
        }

        limit = Math.min(limit, 100);
        List<QuantForecastCycle> list = cycleMapper.selectRecent(normalized, limit);
        return Result.ok(list);
    }

}
