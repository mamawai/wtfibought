package com.mawai.wiibservice.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.entity.AiModelAssignment;
import com.mawai.wiibcommon.entity.AiRuntimeConfig;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibservice.agent.quant.memory.VerificationService;
import com.mawai.wiibservice.agent.quant.node.DebateJudgeNode;
import com.mawai.wiibservice.agent.quant.service.FactorWeightOverrideService;
import com.mawai.wiibservice.agent.trading.DeterministicTradingExecutor;
import com.mawai.wiibservice.agent.trading.PositionDrawdownSentinel;
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
        return Result.ok(assignmentMapper.selectAll());
    }

    @PostMapping("/assignments")
    @Operation(summary = "保存模型分配并刷新运行时")
    public Result<Void> saveAssignments(@RequestBody List<AssignmentRequest> assignments) {
        checkAdmin();
        for (AssignmentRequest req : assignments) {
            if (req.getFunctionName() == null || req.getConfigId() == null
                    || req.getModel() == null || req.getModel().isBlank()) {
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
        if (symbol == null || symbol.isBlank()) {
            symbols = QuantConstants.WATCH_SYMBOLS;
        } else {
            try {
                symbols = List.of(QuantConstants.normalizeSymbol(symbol));
            } catch (IllegalArgumentException e) {
                return Result.fail("symbol格式错误: " + e.getMessage());
            }
        }
        int cycleNo = aiTradingScheduler.triggerTradingCycle(symbols);
        log.info("[Admin] 手动唤醒AI Trader决策 cycleNo={} symbols={}", cycleNo, symbols);
        return Result.ok("AI Trader决策已触发: cycleNo=" + cycleNo + " symbols=" + symbols);
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
        if (req.getLowVolTradingEnabled() != null) {
            DeterministicTradingExecutor.LOW_VOL_TRADING_ENABLED = req.getLowVolTradingEnabled();
            log.info("[Admin] 低波动交易开关更新为: {}", req.getLowVolTradingEnabled());
        }
        if (req.getLegacyThreshold5of7Enabled() != null) {
            DeterministicTradingExecutor.LEGACY_THRESHOLD_5OF7_ENABLED = req.getLegacyThreshold5of7Enabled();
            log.info("[Admin] LEGACY 5/7实盘开关更新为: {}", req.getLegacyThreshold5of7Enabled());
        }
        if (req.getLegacy5of7ShadowEnabled() != null) {
            DeterministicTradingExecutor.LEGACY_5OF7_SHADOW_ENABLED = req.getLegacy5of7ShadowEnabled();
            log.info("[Admin] LEGACY 5/7 shadow开关更新为: {}", req.getLegacy5of7ShadowEnabled());
        }
        if (req.getDrawdownSentinelEnabled() != null) {
            PositionDrawdownSentinel.ENABLED = req.getDrawdownSentinelEnabled();
            log.info("[Admin] 回撤哨兵开关更新为: {}", req.getDrawdownSentinelEnabled());
        }
        if (req.getDrawdownWindowMinutes() != null && req.getDrawdownWindowMinutes() > 0) {
            PositionDrawdownSentinel.WINDOW_MINUTES = req.getDrawdownWindowMinutes();
        }
        if (req.getDrawdownPnlPctDropThresholdPpt() != null && req.getDrawdownPnlPctDropThresholdPpt() >= 0) {
            PositionDrawdownSentinel.PNL_PCT_DROP_THRESHOLD_PPT = req.getDrawdownPnlPctDropThresholdPpt();
        }
        if (req.getDrawdownProfitDrawdownThresholdPct() != null && req.getDrawdownProfitDrawdownThresholdPct() >= 0) {
            PositionDrawdownSentinel.PROFIT_DRAWDOWN_THRESHOLD_PCT = req.getDrawdownProfitDrawdownThresholdPct();
        }
        if (req.getDrawdownProfitDrawdownMinBase() != null && req.getDrawdownProfitDrawdownMinBase() >= 0) {
            PositionDrawdownSentinel.PROFIT_DRAWDOWN_MIN_BASE = req.getDrawdownProfitDrawdownMinBase();
        }
        if (req.getDrawdownCooldownMinutes() != null && req.getDrawdownCooldownMinutes() > 0) {
            PositionDrawdownSentinel.COOLDOWN_MINUTES = req.getDrawdownCooldownMinutes();
        }
        return Result.ok(buildTradingConfigResponse());
    }

    private TradingConfigResponse buildTradingConfigResponse() {
        TradingConfigResponse resp = new TradingConfigResponse();
        resp.setLowVolTradingEnabled(DeterministicTradingExecutor.LOW_VOL_TRADING_ENABLED);
        resp.setLegacyThreshold5of7Enabled(DeterministicTradingExecutor.LEGACY_THRESHOLD_5OF7_ENABLED);
        resp.setLegacy5of7ShadowEnabled(DeterministicTradingExecutor.LEGACY_5OF7_SHADOW_ENABLED);
        resp.setDrawdownSentinelEnabled(PositionDrawdownSentinel.ENABLED);
        resp.setDrawdownWindowMinutes(PositionDrawdownSentinel.WINDOW_MINUTES);
        resp.setDrawdownPnlPctDropThresholdPpt(PositionDrawdownSentinel.PNL_PCT_DROP_THRESHOLD_PPT);
        resp.setDrawdownProfitDrawdownThresholdPct(PositionDrawdownSentinel.PROFIT_DRAWDOWN_THRESHOLD_PCT);
        resp.setDrawdownProfitDrawdownMinBase(PositionDrawdownSentinel.PROFIT_DRAWDOWN_MIN_BASE);
        resp.setDrawdownCooldownMinutes(PositionDrawdownSentinel.COOLDOWN_MINUTES);
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
        if (req.getDebateJudgeEnabled() != null) {
            DebateJudgeNode.ENABLED = req.getDebateJudgeEnabled();
            log.info("[Admin] 辩论裁决开关更新为: {}", req.getDebateJudgeEnabled());
        }
        if (req.getFactorWeightOverrideEnabled() != null) {
            FactorWeightOverrideService.FACTOR_WEIGHT_OVERRIDE_ENABLED = req.getFactorWeightOverrideEnabled();
            log.info("[Admin] 静态调权开关更新为: {}", req.getFactorWeightOverrideEnabled());
        }
        return Result.ok(buildQuantConfigResponse());
    }

    private QuantConfigResponse buildQuantConfigResponse() {
        QuantConfigResponse resp = new QuantConfigResponse();
        resp.setDebateJudgeEnabled(DebateJudgeNode.ENABLED);
        resp.setFactorWeightOverrideEnabled(FactorWeightOverrideService.FACTOR_WEIGHT_OVERRIDE_ENABLED);
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
        private Boolean legacyThreshold5of7Enabled;
        private Boolean legacy5of7ShadowEnabled;
        private Boolean drawdownSentinelEnabled;
        private Integer drawdownWindowMinutes;
        private Double drawdownPnlPctDropThresholdPpt;
        private Double drawdownProfitDrawdownThresholdPct;
        private Double drawdownProfitDrawdownMinBase;
        private Integer drawdownCooldownMinutes;
    }

    @Data
    public static class TradingConfigResponse {
        private Boolean lowVolTradingEnabled;
        private Boolean legacyThreshold5of7Enabled;
        private Boolean legacy5of7ShadowEnabled;
        private Boolean drawdownSentinelEnabled;
        private Integer drawdownWindowMinutes;
        private Double drawdownPnlPctDropThresholdPpt;
        private Double drawdownProfitDrawdownThresholdPct;
        private Double drawdownProfitDrawdownMinBase;
        private Integer drawdownCooldownMinutes;
    }

    @Data
    public static class QuantConfigRequest {
        private Boolean debateJudgeEnabled;
        private Boolean factorWeightOverrideEnabled;
    }

    @Data
    public static class QuantConfigResponse {
        private Boolean debateJudgeEnabled;
        private Boolean factorWeightOverrideEnabled;
    }

}
