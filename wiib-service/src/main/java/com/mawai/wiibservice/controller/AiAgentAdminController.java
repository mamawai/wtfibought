package com.mawai.wiibservice.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.entity.AiModelAssignment;
import com.mawai.wiibcommon.entity.AiRuntimeConfig;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibservice.agent.quant.memory.VerificationService;
import com.mawai.wiibservice.mapper.AiModelAssignmentMapper;
import com.mawai.wiibservice.mapper.AiRuntimeConfigMapper;
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

        // 如果该Key被引用，刷新运行时
        if (config.getId() != null && aiAgentRuntimeManager.isConfigReferenced(config.getId())) {
            aiAgentRuntimeManager.refresh();
        }

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
        String normalized = normalizeSymbol(symbol);
        Thread.startVirtualThread(() -> quantForecastScheduler.runForecast(normalized));
        return Result.ok("量化分析已触发: " + normalized);
    }

    @PostMapping("/quant/verify/trigger")
    @Operation(summary = "手动触发量化预测验证")
    public Result<String> triggerQuantVerification(@RequestParam(defaultValue = "BTCUSDT") String symbol) {
        checkAdmin();
        String normalized = normalizeSymbol(symbol);
        Thread.startVirtualThread(() -> {
            try {
                int verified = verificationService.verifyPendingCycles(normalized, 24);
                log.info("[Admin] 手动触发预测验证完成 symbol={} verified={}", normalized, verified);
            } catch (Exception e) {
                log.error("[Admin] 手动触发预测验证失败 symbol={}", normalized, e);
            }
        });
        return Result.ok("预测验证已触发: " + normalized);
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

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "BTCUSDT";
        }
        String normalized = symbol.trim().toUpperCase();
        if (normalized.endsWith("USDT")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        } else if (normalized.endsWith("USDC")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        if (normalized.isBlank() || !normalized.matches("^[A-Z0-9]{2,10}$")) {
            throw new IllegalArgumentException("symbol格式错误");
        }
        return normalized + "USDT";
    }
}
