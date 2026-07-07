package com.mawai.wiibquant.agent;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.annotation.RateLimiter;
import com.mawai.wiibcommon.constant.RateLimiterType;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibquant.agent.behavior.BehaviorAnalysisReport;
import com.mawai.wiibquant.agent.behavior.BehaviorAnalysisService;
import com.mawai.wiibquant.agent.config.AiAgentRuntimeManager;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibquant.agent.quant.CryptoAnalysisReport;
import com.mawai.wiibquant.agent.quant.domain.LlmCallMode;
import com.mawai.wiibquant.agent.quant.memory.VerificationService;
import com.mawai.wiibquant.task.QuantSnapshotScheduler;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import com.mawai.wiibcommon.entity.QuantSnapshot;
import com.mawai.wiibquant.mapper.QuantForecastCycleMapper;
import com.mawai.wiibquant.mapper.QuantSignalDecisionMapper;
import com.mawai.wiibquant.mapper.QuantSnapshotMapper;

import com.alibaba.fastjson2.JSON;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Tag(name = "AI Agent接口")
@RestController
@RequestMapping("/api/ai")
public class AiAgentController {

    private final AiAgentRuntimeManager aiAgentRuntimeManager;
    private final BehaviorAnalysisService behaviorAnalysisService;
    private final QuantSnapshotScheduler snapshotScheduler;
    private final QuantSnapshotMapper snapshotMapper;
    private final QuantForecastCycleMapper cycleMapper;
    private final QuantSignalDecisionMapper decisionMapper;
    private final VerificationService verificationService;
    private final ExecutorService chatStreamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public AiAgentController(AiAgentRuntimeManager aiAgentRuntimeManager,
                             BehaviorAnalysisService behaviorAnalysisService,
                             QuantSnapshotScheduler snapshotScheduler,
                             QuantSnapshotMapper snapshotMapper,
                             QuantForecastCycleMapper cycleMapper,
                             QuantSignalDecisionMapper decisionMapper,
                             VerificationService verificationService) {
        this.aiAgentRuntimeManager = aiAgentRuntimeManager;
        this.behaviorAnalysisService = behaviorAnalysisService;
        this.snapshotScheduler = snapshotScheduler;
        this.snapshotMapper = snapshotMapper;
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
        return behaviorAnalysisService.analyze(userId);
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

        // P2a 中间态：旧管线已下线，此端点转历史回放（读旧表最后一次研判，不再有时效检查）。
        QuantForecastCycle cycle = cycleMapper.selectLatestHeavy(normalized);
        if (cycle == null) {
            return Result.ok(Map.of("status", "pending", "message", "暂无历史报告"));
        }

        if (cycle.getReportJson() == null || cycle.getReportJson().isBlank()) {
            return Result.ok(Map.of("status", "pending", "message", "历史报告数据不完整"));
        }

        try {
            CryptoAnalysisReport report = JSON.parseObject(cycle.getReportJson(), CryptoAnalysisReport.class);
            // 简报产物（脆弱度/信号面板/弱lean）单独从 briefing_json 暴露，不污染 report 契约
            Object briefing = parseBriefing(cycle.getBriefingJson());
            Map<String, Object> data = new HashMap<>();
            data.put("status", "ready");
            data.put("schemaVersion", 2);
            data.put("forecastTime", cycle.getForecastTime().toString());
            data.put("cycleId", cycle.getCycleId());
            data.put("overallDecision", cycle.getOverallDecision());
            data.put("riskStatus", cycle.getRiskStatus());
            data.put("report", report);
            data.put("briefing", briefing); // 旧 cycle 无 briefing_json 时为 null，前端按存在性渲染
            data.put("advisory", "历史回放：旧研判管线最后一次输出（研判工作台升级中），非交易建议");
            return Result.ok(data);
        } catch (Exception e) {
            log.error("报告JSON解析失败 cycleId={}", cycle.getCycleId(), e);
            return Result.fail("报告数据异常");
        }
    }

    @GetMapping("/quant/snapshots/latest")
    @Operation(summary = "查最新数值快照（P2a 新链路：vol三腿/脆弱度/信号面板）")
    public Result<QuantSnapshot> latestSnapshot(@RequestParam(defaultValue = "BTCUSDT") String symbol) {
        StpUtil.checkLogin();
        String normalized;
        try {
            normalized = QuantConstants.normalizeSymbol(symbol);
        } catch (IllegalArgumentException e) {
            return Result.fail(ErrorCode.PARAM_ERROR.getCode(), "symbol格式错误");
        }
        QuantSnapshot snapshot = snapshotMapper.selectOne(new LambdaQueryWrapper<QuantSnapshot>()
                .eq(QuantSnapshot::getSymbol, normalized)
                .orderByDesc(QuantSnapshot::getCloseTime)
                .last("LIMIT 1"));
        return snapshot != null ? Result.ok(snapshot) : Result.fail("暂无快照数据");
    }

    private Object parseBriefing(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            // 透传为 JSONObject 给前端：嵌套 List<record>(signals/weakLeans) 做 typed 反序列化时
            // fastjson2 会丢元素泛型，透传无此问题且前端只认 JSON 结构。
            return JSON.parseObject(json);
        } catch (Exception e) {
            log.warn("briefing_json 解析失败: {}", e.getMessage());
            return null;
        }
    }

    @PostMapping("/analyze-crypto")
    @Operation(summary = "加密货币量化分析（P2a 中间态：触发数值快照，深研判 P2b 回归）")
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
        // P2a 中间态：旧 LLM 管线已拆除，手动触发改为跑一次数值快照；
        // 深度研判（debate 子图 + 门控）P2b 回归后此端点将由研判工作台取代。
        log.info("[Quant] 手动触发数值快照 userId={} symbol={}", userId, symbol);
        snapshotScheduler.runSnapshot(symbol);
        return Result.fail("研判管线升级中：已触发数值快照（/quant/snapshots/latest 可查），深度研判即将回归");
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

        QuantForecastCycle cycle = cycleMapper.selectLatestHeavy(normalized);
        if (cycle == null) {
            return Result.fail("暂无预测数据");
        }

        List<QuantSignalDecision> signals = decisionMapper.selectLatestHeavyBySymbol(normalized);
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
    @Operation(summary = "分组查询量化预测验证（research 主周期）")
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
