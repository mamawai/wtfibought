package com.mawai.wiibquant.controller;

import com.mawai.wiibcommon.annotation.RequireAdmin;
import com.mawai.wiibcommon.annotation.Symbol;
import com.mawai.wiibcommon.constant.AiProtocols;
import com.mawai.wiibcommon.entity.AiModelAssignment;
import com.mawai.wiibcommon.entity.AiRuntimeConfig;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibcommon.mapper.AiModelAssignmentMapper;
import com.mawai.wiibcommon.mapper.AiRuntimeConfigMapper;
import com.mawai.wiibquant.agent.analysis.VolVerificationService;
import com.mawai.wiibquant.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibquant.agent.research.ForecastHorizon;
import com.mawai.wiibquant.task.QuantSnapshotScheduler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Slf4j
@Tag(name = "AI Agent管理")
@RestController
@RequestMapping("/api/admin/ai-agent")
@RequiredArgsConstructor
@RequireAdmin // 整个 AI Agent 管理控制器仅管理员(userId=1)可访问
public class AiAgentAdminController {

    /** Responses API 的思考档位合法值（none=完全关思考，仅部分模型支持如 grok-4.3） */
    private static final Set<String> EFFORT_LEVELS = Set.of("none", "low", "medium", "high");

    private final AiAgentRuntimeManager aiAgentRuntimeManager;
    private final QuantSnapshotScheduler quantSnapshotScheduler;
    private final VolVerificationService volVerificationService;
    private final AiRuntimeConfigMapper configMapper;
    private final AiModelAssignmentMapper assignmentMapper;

    // ========== API Key 管理 ==========

    @GetMapping("/keys")
    @Operation(summary = "获取所有API Key配置")
    public Result<List<AiRuntimeConfig>> listKeys() {
        return Result.ok(configMapper.selectAllConfigs());
    }

    @PostMapping("/keys")
    @Operation(summary = "新增/修改API Key配置")
    public Result<AiRuntimeConfig> saveKey(@RequestBody KeyRequest req) {
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
        // 档位留空=不传（走模型默认）；有值必须是合法档位，别让手误值静默传到上游被拒
        String effort = req.getReasoningEffort() == null ? null : req.getReasoningEffort().trim().toLowerCase();
        if (effort != null && effort.isEmpty()) {
            effort = null;
        }
        if (effort != null && !EFFORT_LEVELS.contains(effort)) {
            return Result.fail("思考档位仅支持 none/low/medium/high 或留空");
        }
        // 协议留空=openai（存量兼容）；responses 需上游支持 /v1/responses（CPA/OpenAI官方/xAI）
        String protocol = req.getApiProtocol() == null || req.getApiProtocol().isBlank()
                ? AiProtocols.OPENAI : req.getApiProtocol().trim().toLowerCase();
        if (!AiProtocols.isValid(protocol)) {
            return Result.fail("协议仅支持 openai / responses");
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
        config.setReasoningEffort(effort);
        config.setApiProtocol(protocol);
        config.setUpdatedAt(LocalDateTime.now());

        if (config.getId() == null) {
            configMapper.insert(config);
        } else {
            configMapper.updateById(config);
        }

        // 管理端配置修改频率很低，直接全量刷新最稳，确保主图和fallback图缓存都失效
        if (!aiAgentRuntimeManager.refresh()) {
            return Result.fail("配置已保存，但AI运行时刷新失败（详见服务日志），当前沿用变更前模型运行");
        }
        return Result.ok(config);
    }

    @DeleteMapping("/keys/{id}")
    @Operation(summary = "删除LLM配置")
    public Result<Void> deleteKey(@PathVariable Long id) {
        if (aiAgentRuntimeManager.isConfigReferenced(id)) {
            return Result.fail("该LLM配置正被功能位引用，无法删除");
        }
        configMapper.deleteById(id);
        if (!aiAgentRuntimeManager.refresh()) {
            return Result.fail("已删除，但AI运行时刷新失败（详见服务日志），当前沿用变更前模型运行");
        }
        return Result.ok(null);
    }

    // ========== 模型分配 ==========

    @GetMapping("/assignments")
    @Operation(summary = "获取模型分配")
    public Result<List<AiModelAssignment>> listAssignments() {
        return Result.ok(assignmentMapper.selectAll().stream()
                .filter(a -> AiAgentRuntimeManager.isManagedFunction(a.getFunctionName()))
                .toList());
    }

    @PostMapping("/assignments")
    @Operation(summary = "更换功能位LLM（只改指针，模型名随所选配置）并刷新运行时")
    public Result<Void> saveAssignments(@RequestBody List<AssignmentRequest> assignments) {
        for (AssignmentRequest req : assignments) {
            if (req.getFunctionName() == null) {
                return Result.fail("参数不完整: " + null);
            }
            if (!AiAgentRuntimeManager.isManagedFunction(req.getFunctionName())) {
                continue;
            }
            if (req.getConfigId() == null) {
                return Result.fail("参数不完整: " + req.getFunctionName());
            }
            AiRuntimeConfig target = configMapper.selectById(req.getConfigId());
            if (target == null) {
                return Result.fail("LLM配置不存在(id=" + req.getConfigId() + ")");
            }
            // 空model的配置建不出模型（历史遗留行可能缺model），提前拦截别等refresh才炸
            if (target.getModel() == null || target.getModel().isBlank()) {
                return Result.fail("LLM配置'" + target.getConfigName() + "'缺模型名，请先在「配置LLM」里补全");
            }

            AiModelAssignment existing = assignmentMapper.selectByFunction(req.getFunctionName());
            if (existing != null) {
                existing.setConfigId(req.getConfigId());
                existing.setUpdatedAt(LocalDateTime.now());
                assignmentMapper.updateById(existing);
            } else {
                AiModelAssignment a = new AiModelAssignment();
                a.setFunctionName(req.getFunctionName());
                a.setConfigId(req.getConfigId());
                a.setUpdatedAt(LocalDateTime.now());
                assignmentMapper.insert(a);
            }
        }
        if (!aiAgentRuntimeManager.refresh()) {
            return Result.fail("分配已保存，但AI运行时刷新失败（详见服务日志），当前沿用变更前模型运行");
        }
        return Result.ok(null);
    }

    // ========== 量化触发 ==========

    @PostMapping("/quant/trigger")
    @Operation(summary = "手动触发量化分析")
    public Result<String> triggerQuant(@Symbol String symbol) {
        Thread.startVirtualThread(() -> quantSnapshotScheduler.runSnapshot(symbol));
        return Result.ok("量化分析已触发: " + symbol);
    }

    @PostMapping("/quant/verify/trigger")
    @Operation(summary = "手动触发量化预测验证")
    public Result<String> triggerQuantVerification(@RequestParam(required = false) String symbol) {
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
        // P3 起验证对象=vol 预测点（quant_vol_verification），旧方向验证已随旧管线删除
        for (String s : symbols) {
            Thread.startVirtualThread(() -> {
                try {
                    int verified = 0;
                    for (ForecastHorizon horizon : ForecastHorizon.values()) {
                        verified += volVerificationService.verifyDue(s, horizon);
                    }
                    log.info("[Admin] 手动触发 vol 验证完成 symbol={} verified={}", s, verified);
                } catch (Exception e) {
                    log.error("[Admin] 手动触发 vol 验证失败 symbol={}", s, e);
                }
            });
        }
        return Result.ok("vol 预测验证已触发: " + symbols);
    }

    // quant-config 开关端点已删：开关框架自 v1 调权清理后空转（无注册开关），随死表清理一并拆除。

    // ========== DTO ==========

    @Data
    public static class KeyRequest {
        private Long id;
        private String configName;
        private String apiKey;
        private String baseUrl;
        private String model;
        /** 思考档位 none/low/medium/high；空=不传走模型默认 */
        private String reasoningEffort;
        /** 上游协议 openai/responses；空=openai */
        private String apiProtocol;
    }

    @Data
    public static class AssignmentRequest {
        private String functionName;
        private Long configId;
    }

}
