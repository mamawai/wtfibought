package com.mawai.wiibservice.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.entity.AiModelAssignment;
import com.mawai.wiibcommon.entity.AiRuntimeConfig;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibservice.agent.config.RuntimeFeatureToggleService;
import com.mawai.wiibservice.agent.config.RuntimeToggleSnapshot;
import com.mawai.wiibservice.agent.quant.memory.VerificationService;
import com.mawai.wiibservice.agent.risk.CircuitBreakerService;
import com.mawai.wiibservice.agent.trading.submit.SubmitStatus;
import com.mawai.wiibservice.agent.trading.submit.SymbolSubmitResult;
import com.mawai.wiibservice.agent.trading.submit.TradingCycleSubmitResult;
import com.mawai.wiibservice.mapper.AiModelAssignmentMapper;
import com.mawai.wiibservice.mapper.AiRuntimeConfigMapper;
import com.mawai.wiibservice.task.AiTradingScheduler;
import com.mawai.wiibservice.task.QuantForecastScheduler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Tag(name = "AI Agent管理")
@RestController
@RequestMapping("/api/admin/ai-agent")
@RequiredArgsConstructor
public class AiAgentAdminController {

    private final AiAgentRuntimeManager aiAgentRuntimeManager;
    private final QuantForecastScheduler quantForecastScheduler;
    private final AiTradingScheduler aiTradingScheduler;
    private final VerificationService verificationService;
    private final RuntimeFeatureToggleService runtimeFeatureToggleService;
    private final CircuitBreakerService circuitBreakerService;
    private final AiRuntimeConfigMapper configMapper;
    private final AiModelAssignmentMapper assignmentMapper;

    private void checkAdmin() {
        long userId = StpUtil.getLoginIdAsLong();
        if (userId != 1L) {
            throw new RuntimeException("无权限");
        }
    }

    // ========== API Key 管理 ==========

    @GetMapping("/keys")
    @Operation(summary = "获取所有API Key配置")
    public Result<List<AiRuntimeConfig>> listKeys() {
        checkAdmin();
        return Result.ok(configMapper.selectAllConfigs());
    }

    @PostMapping("/keys")
    @Operation(summary = "新增/修改API Key配置")
    public Result<AiRuntimeConfig> saveKey(@RequestBody KeyRequest req) {
        checkAdmin();
        if (req.getApiKey() == null || req.getApiKey().isBlank()) {
            return Result.fail("apiKey不能为空");
        }
        if (req.getBaseUrl() == null || req.getBaseUrl().isBlank()) {
            return Result.fail("baseUrl不能为空");
        }
        if (req.getConfigName() == null || req.getConfigName().isBlank()) {
            return Result.fail("名称不能为空");
        }
        if (req.getModel() == null || req.getModel().isBlank()) {
            return Result.fail("model不能为空");
        }

        String baseUrl = req.getBaseUrl().trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        AiRuntimeConfig config;
        if (req.getId() != null) {
            config = configMapper.selectById(req.getId());
            if (config == null) {
                return Result.fail("配置不存在");
            }
        } else {
            config = new AiRuntimeConfig();
            config.setEnabled(true);
            config.setCreatedAt(LocalDateTime.now());
        }

        config.setConfigName(req.getConfigName().trim());
        config.setApiKey(req.getApiKey().trim());
        config.setBaseUrl(baseUrl);
        config.setModel(req.getModel().trim());
        config.setUpdatedAt(LocalDateTime.now());

        if (config.getId() == null) {
            configMapper.insert(config);
        } else {
            configMapper.updateById(config);
        }

        // 管理端配置修改频率很低，直接全量刷新最稳，确保主图和fallback图缓存都失效
        aiAgentRuntimeManager.refresh();
        return Result.ok(config);
    }

    @DeleteMapping("/keys/{id}")
    @Operation(summary = "删除API Key配置")
    public Result<Void> deleteKey(@PathVariable Long id) {
        checkAdmin();
        if (aiAgentRuntimeManager.isConfigReferenced(id)) {
            return Result.fail("该API Key正被模型分配引用，无法删除");
        }
        configMapper.deleteById(id);
        aiAgentRuntimeManager.refresh();
        return Result.ok(null);
    }

    // ========== 模型分配 ==========

    @GetMapping("/assignments")
    @Operation(summary = "获取模型分配")
    public Result<List<AiModelAssignment>> listAssignments() {
        checkAdmin();
        return Result.ok(assignmentMapper.selectAll().stream()
                .filter(a -> AiAgentRuntimeManager.isManagedFunction(a.getFunctionName()))
                .toList());
    }

    @PostMapping("/assignments")
    @Operation(summary = "保存模型分配并刷新运行时")
    public Result<Void> saveAssignments(@RequestBody List<AssignmentRequest> assignments) {
        checkAdmin();
        for (AssignmentRequest req : assignments) {
            if (req.getFunctionName() == null) {
                return Result.fail("参数不完整: " + null);
            }
            if (!AiAgentRuntimeManager.isManagedFunction(req.getFunctionName())) {
                continue;
            }
            if (req.getConfigId() == null || req.getModel() == null || req.getModel().isBlank()) {
                return Result.fail("参数不完整: " + req.getFunctionName());
            }
            // 校验configId存在
            if (configMapper.selectById(req.getConfigId()) == null) {
                return Result.fail("API Key不存在(id=" + req.getConfigId() + ")");
            }

            AiModelAssignment existing = assignmentMapper.selectByFunction(req.getFunctionName());
            if (existing != null) {
                existing.setConfigId(req.getConfigId());
                existing.setModel(req.getModel().trim());
                existing.setUpdatedAt(LocalDateTime.now());
                assignmentMapper.updateById(existing);
            } else {
                AiModelAssignment a = new AiModelAssignment();
                a.setFunctionName(req.getFunctionName());
                a.setConfigId(req.getConfigId());
                a.setModel(req.getModel().trim());
                a.setUpdatedAt(LocalDateTime.now());
                assignmentMapper.insert(a);
            }
        }
        aiAgentRuntimeManager.refresh();
        return Result.ok(null);
    }

    // ========== 量化触发 ==========

    @PostMapping("/quant/trigger")
    @Operation(summary = "手动触发量化分析")
    public Result<String> triggerQuant(@RequestParam(defaultValue = "BTCUSDT") String symbol) {
        checkAdmin();
        String normalized;
        try {
            normalized = QuantConstants.normalizeSymbol(symbol);
        } catch (IllegalArgumentException e) {
            return Result.fail("symbol格式错误: " + e.getMessage());
        }
        Thread.startVirtualThread(() -> quantForecastScheduler.runForecast(normalized));
        return Result.ok("量化分析已触发: " + normalized);
    }

    @PostMapping("/quant/verify/trigger")
    @Operation(summary = "手动触发量化预测验证")
    public Result<String> triggerQuantVerification(@RequestParam(required = false) String symbol) {
        checkAdmin();
        List<String> symbols;
        if (symbol == null || symbol.isBlank()) {
            symbols = QuantConstants.WATCH_SYMBOLS;
        } else {
            try {
                symbols = List.of(QuantConstants.normalizeSymbol(symbol));
            } catch (IllegalArgumentException e) {
                return Result.fail("symbol格式错误: " + e.getMessage());
            }
        }
        for (String s : symbols) {
            Thread.startVirtualThread(() -> {
                try {
                    int verified = verificationService.verifyPendingCycles(s, 24);
                    log.info("[Admin] 手动触发预测验证完成 symbol={} verified={}", s, verified);
                } catch (Exception e) {
                    log.error("[Admin] 手动触发预测验证失败 symbol={}", s, e);
                }
            });
        }
        return Result.ok("预测验证已触发: " + symbols);
    }

    @PostMapping("/trading/trigger")
    @Operation(summary = "手动唤醒AI Trader决策")
    public Result<String> triggerTrading(@RequestParam(required = false) String symbol) {
        checkAdmin();
        List<String> symbols;
        try {
            symbols = resolveTradingSymbols(symbol);
        } catch (IllegalArgumentException e) {
            return Result.fail("symbol格式错误: " + e.getMessage());
        }
        TradingCycleSubmitResult result = aiTradingScheduler.submitTradingCycle(symbols);
        log.info("[Admin] 手动提交AI Trader决策 result={}", result);
        return Result.ok(formatTradingSubmitMessage(result));
    }

    @PostMapping("/trading/trigger-details")
    @Operation(summary = "手动唤醒AI Trader决策并返回提交详情")
    public Result<TradingCycleSubmitResult> triggerTradingDetails(@RequestParam(required = false) String symbol) {
        checkAdmin();
        List<String> symbols;
        try {
            symbols = resolveTradingSymbols(symbol);
        } catch (IllegalArgumentException e) {
            return Result.fail("symbol格式错误: " + e.getMessage());
        }
        TradingCycleSubmitResult result = aiTradingScheduler.submitTradingCycle(symbols);
        log.info("[Admin] 手动提交AI Trader决策详情 result={}", result);
        return Result.ok(result);
    }

    private List<String> resolveTradingSymbols(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return QuantConstants.WATCH_SYMBOLS;
        }
        return List.of(QuantConstants.normalizeSymbol(symbol));
    }

    private String formatTradingSubmitMessage(TradingCycleSubmitResult result) {
        List<String> submitted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (SymbolSubmitResult item : result.items()) {
            if (item.status() == SubmitStatus.SUBMITTED) {
                submitted.add(item.symbol());
            } else {
                skipped.add(item.symbol() + "(" + item.reason() + ")");
            }
        }
        StringBuilder message = new StringBuilder("AI Trader提交完成: cycleNo=")
                .append(result.cycleNo());
        if (!submitted.isEmpty()) {
            message.append(" submitted=").append(submitted);
        }
        if (!skipped.isEmpty()) {
            message.append(" skipped=").append(skipped);
        }
        return message.toString();
    }

    // ========== 交易运行时开关 ==========

    @GetMapping("/trading-config")
    @Operation(summary = "获取交易运行时开关")
    public Result<TradingConfigResponse> getTradingConfig() {
        checkAdmin();
        return Result.ok(buildTradingConfigResponse());
    }

    @PostMapping("/trading-config")
    @Operation(summary = "设置交易运行时开关")
    public Result<TradingConfigResponse> setTradingConfig(@RequestBody TradingConfigRequest req) {
        checkAdmin();
        Result<TradingConfigResponse> invalid = validateTradingConfigRequest(req);
        if (invalid != null) {
            return invalid;
        }
        long operator = StpUtil.getLoginIdAsLong();
        if (req.getLowVolTradingEnabled() != null) {
            runtimeFeatureToggleService.set(RuntimeFeatureToggleService.TRADING_LOW_VOL_ENABLED,
                    req.getLowVolTradingEnabled(), operator, "admin trading-config");
        }
        if (req.getPlaybookExitEnabled() != null) {
            runtimeFeatureToggleService.set(RuntimeFeatureToggleService.TRADING_PLAYBOOK_EXIT_ENABLED,
                    req.getPlaybookExitEnabled(), operator, "admin trading-config");
        }
        if (req.getCircuitBreakerEnabled() != null) {
            runtimeFeatureToggleService.set(RuntimeFeatureToggleService.CIRCUIT_BREAKER_ENABLED,
                    req.getCircuitBreakerEnabled(), operator, "admin trading-config");
        }
        if (req.getCircuitBreakerL1DailyNetLossPct() != null) {
            runtimeFeatureToggleService.set(RuntimeFeatureToggleService.CIRCUIT_BREAKER_L1_DAILY_NET_LOSS_PCT,
                    req.getCircuitBreakerL1DailyNetLossPct(), operator, "admin trading-config");
        }
        if (req.getCircuitBreakerL2LossStreak() != null) {
            runtimeFeatureToggleService.set(RuntimeFeatureToggleService.CIRCUIT_BREAKER_L2_LOSS_STREAK,
                    req.getCircuitBreakerL2LossStreak(), operator, "admin trading-config");
        }
        if (req.getCircuitBreakerL2CooldownHours() != null) {
            runtimeFeatureToggleService.set(RuntimeFeatureToggleService.CIRCUIT_BREAKER_L2_COOLDOWN_HOURS,
                    req.getCircuitBreakerL2CooldownHours(), operator, "admin trading-config");
        }
        if (req.getCircuitBreakerL3DrawdownPct() != null) {
            runtimeFeatureToggleService.set(RuntimeFeatureToggleService.CIRCUIT_BREAKER_L3_DRAWDOWN_PCT,
                    req.getCircuitBreakerL3DrawdownPct(), operator, "admin trading-config");
        }
        return Result.ok(buildTradingConfigResponse());
    }

    private Result<TradingConfigResponse> validateTradingConfigRequest(TradingConfigRequest req) {
        if (req.getCircuitBreakerL1DailyNetLossPct() != null
                && !isPercentInRange(req.getCircuitBreakerL1DailyNetLossPct())) {
            return Result.fail("circuitBreakerL1DailyNetLossPct必须在0到100之间");
        }
        if (req.getCircuitBreakerL2LossStreak() != null && req.getCircuitBreakerL2LossStreak() <= 0) {
            return Result.fail("circuitBreakerL2LossStreak必须为正整数");
        }
        if (req.getCircuitBreakerL2CooldownHours() != null && req.getCircuitBreakerL2CooldownHours() <= 0) {
            return Result.fail("circuitBreakerL2CooldownHours必须为正整数");
        }
        if (req.getCircuitBreakerL3DrawdownPct() != null
                && !isPercentInRange(req.getCircuitBreakerL3DrawdownPct())) {
            return Result.fail("circuitBreakerL3DrawdownPct必须在0到100之间");
        }
        return null;
    }

    private boolean isPercentInRange(Double value) {
        return value != null && value > 0 && value <= 100;
    }

    private TradingConfigResponse buildTradingConfigResponse() {
        RuntimeToggleSnapshot snapshot = runtimeFeatureToggleService.snapshot();
        RuntimeToggleSnapshot.TradingToggles trading = snapshot.trading();
        RuntimeToggleSnapshot.CircuitBreakerToggles breaker = snapshot.circuitBreaker();
        TradingConfigResponse resp = new TradingConfigResponse();
        resp.setLowVolTradingEnabled(trading.lowVolTradingEnabled());
        resp.setPlaybookExitEnabled(trading.playbookExitEnabled());
        resp.setCircuitBreakerEnabled(circuitBreakerService.isEffectiveEnabled());
        resp.setCircuitBreakerRuntimeEnabled(breaker.enabled());
        resp.setCircuitBreakerPropertyEnabled(circuitBreakerService.isPropertyEnabled());
        resp.setCircuitBreakerL1DailyNetLossPct(breaker.l1DailyNetLossPct());
        resp.setCircuitBreakerL2LossStreak(breaker.l2LossStreak());
        resp.setCircuitBreakerL2CooldownHours(breaker.l2CooldownHours());
        resp.setCircuitBreakerL3DrawdownPct(breaker.l3DrawdownPct());
        return resp;
    }

    // ========== 量化运行时开关 ==========

    @GetMapping("/quant-config")
    @Operation(summary = "获取量化运行时开关")
    public Result<QuantConfigResponse> getQuantConfig() {
        checkAdmin();
        return Result.ok(buildQuantConfigResponse());
    }

    @PostMapping("/quant-config")
    @Operation(summary = "设置量化运行时开关")
    public Result<QuantConfigResponse> setQuantConfig(@RequestBody QuantConfigRequest req) {
        checkAdmin();
        long operator = StpUtil.getLoginIdAsLong();
        if (req.getDebateJudgeEnabled() != null) {
            runtimeFeatureToggleService.set(RuntimeFeatureToggleService.QUANT_DEBATE_JUDGE_ENABLED,
                    req.getDebateJudgeEnabled(), operator, "admin quant-config");
        }
        if (req.getDebateJudgeShadowEnabled() != null) {
            runtimeFeatureToggleService.set(RuntimeFeatureToggleService.QUANT_DEBATE_JUDGE_SHADOW_ENABLED,
                    req.getDebateJudgeShadowEnabled(), operator, "admin quant-config");
        }
        if (req.getFactorWeightOverrideEnabled() != null) {
            runtimeFeatureToggleService.set(RuntimeFeatureToggleService.QUANT_FACTOR_WEIGHT_OVERRIDE_ENABLED,
                    req.getFactorWeightOverrideEnabled(), operator, "admin quant-config");
        }
        return Result.ok(buildQuantConfigResponse());
    }

    private QuantConfigResponse buildQuantConfigResponse() {
        RuntimeToggleSnapshot snapshot = runtimeFeatureToggleService.snapshot();
        QuantConfigResponse resp = new QuantConfigResponse();
        resp.setDebateJudgeEnabled(snapshot.debateJudgeEnabled());
        resp.setDebateJudgeShadowEnabled(snapshot.debateJudgeShadowEnabled());
        resp.setFactorWeightOverrideEnabled(snapshot.factorWeightOverrideEnabled());
        return resp;
    }

    // ========== DTO ==========

    @Data
    public static class KeyRequest {
        private Long id;
        private String configName;
        private String apiKey;
        private String baseUrl;
        private String model;
    }

    @Data
    public static class AssignmentRequest {
        private String functionName;
        private Long configId;
        private String model;
    }

    @Data
    public static class TradingConfigRequest {
        private Boolean lowVolTradingEnabled;
        private Boolean playbookExitEnabled;
        private Boolean circuitBreakerEnabled;
        private Double circuitBreakerL1DailyNetLossPct;
        private Integer circuitBreakerL2LossStreak;
        private Integer circuitBreakerL2CooldownHours;
        private Double circuitBreakerL3DrawdownPct;
    }

    @Data
    public static class TradingConfigResponse {
        private Boolean lowVolTradingEnabled;
        private Boolean playbookExitEnabled;
        private Boolean circuitBreakerEnabled;
        private Boolean circuitBreakerRuntimeEnabled;
        private Boolean circuitBreakerPropertyEnabled;
        private Double circuitBreakerL1DailyNetLossPct;
        private Integer circuitBreakerL2LossStreak;
        private Integer circuitBreakerL2CooldownHours;
        private Double circuitBreakerL3DrawdownPct;
    }

    @Data
    public static class QuantConfigRequest {
        private Boolean debateJudgeEnabled;
        private Boolean debateJudgeShadowEnabled;
        private Boolean factorWeightOverrideEnabled;
    }

    @Data
    public static class QuantConfigResponse {
        private Boolean debateJudgeEnabled;
        private Boolean debateJudgeShadowEnabled;
        private Boolean factorWeightOverrideEnabled;
    }

}
