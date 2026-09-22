package com.mawai.wiibagent.controller;

import com.mawai.wiibcommon.annotation.RequireAdmin;
import com.mawai.wiibcommon.constant.AiProtocols;
import com.mawai.wiibcommon.entity.AiModelAssignment;
import com.mawai.wiibcommon.entity.AiRuntimeConfig;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.mapper.AiModelAssignmentMapper;
import com.mawai.wiibcommon.mapper.AiRuntimeConfigMapper;
import com.mawai.wiibagent.llm.jev.JevPlatformConfig;
import com.mawai.wiibagent.prediction.JevPredictionSwitch;
import com.mawai.wiibagent.runtime.AiAgentRuntimeManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Tag(name = "AI Agent管理")
@RestController
@RequestMapping("/api/admin/ai-agent")
@RequiredArgsConstructor
@RequireAdmin // 整个 AI Agent 管理控制器仅管理员(userId=1)可访问
public class AiAgentAdminController {

    /** 档位列宽 VARCHAR(16)，超了留给 SQL 报错不如这里说人话 */
    private static final int MAX_EFFORT_LEN = 16;

    private final AiAgentRuntimeManager aiAgentRuntimeManager;
    private final AiRuntimeConfigMapper configMapper;
    private final AiModelAssignmentMapper assignmentMapper;
    private final JevPlatformConfig jevPlatform;
    private final JevPredictionSwitch jevSwitch;
    /** 管理页的校验与回执跟界面语言 */
    private final MessageCatalog messages;

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
            return Result.fail(messages.get("agent.admin.apiKeyRequired"));
        }
        if (req.getBaseUrl() == null || req.getBaseUrl().isBlank()) {
            return Result.fail(messages.get("agent.admin.baseUrlRequired"));
        }
        if (req.getConfigName() == null || req.getConfigName().isBlank()) {
            return Result.fail(messages.get("agent.admin.nameRequired"));
        }
        if (req.getModel() == null || req.getModel().isBlank()) {
            return Result.fail(messages.get("agent.admin.modelRequired"));
        }
        // 档位留空=不传（走模型默认）。不限白名单：各家档位名字自己定（xhigh/minimal…），
        // 认不认只有上游知道；只挡列宽 VARCHAR(16) 免得存的时候炸 SQL
        String effort = req.getReasoningEffort() == null ? null : req.getReasoningEffort().trim().toLowerCase();
        if (effort != null && effort.isEmpty()) {
            effort = null;
        }
        if (effort != null && effort.length() > MAX_EFFORT_LEN) {
            return Result.fail(messages.get("agent.admin.effortTooLong", Map.of("max", MAX_EFFORT_LEN)));
        }
        // 协议留空=openai（存量兼容）
        String protocol = AiProtocols.normalize(req.getApiProtocol());
        if (!AiProtocols.isValid(protocol)) {
            return Result.fail(messages.get("agent.admin.protocolUnsupported"));
        }

        String baseUrl = req.getBaseUrl().trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        AiRuntimeConfig config;
        if (req.getId() != null) {
            config = configMapper.selectById(req.getId());
            if (config == null) {
                return Result.fail(messages.get("agent.admin.configNotFound"));
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
            return Result.fail(messages.get("agent.admin.savedButRefreshFailed"));
        }
        return Result.ok(config);
    }

    @DeleteMapping("/keys/{id}")
    @Operation(summary = "删除LLM配置")
    public Result<Void> deleteKey(@PathVariable Long id) {
        if (aiAgentRuntimeManager.isConfigReferenced(id)) {
            return Result.fail(messages.get("agent.admin.configInUse"));
        }
        configMapper.deleteById(id);
        if (!aiAgentRuntimeManager.refresh()) {
            return Result.fail(messages.get("agent.admin.deletedButRefreshFailed"));
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
                return Result.fail(messages.get("agent.admin.paramsIncomplete", Map.of("what", "functionName")));
            }
            if (!AiAgentRuntimeManager.isManagedFunction(req.getFunctionName())) {
                continue;
            }
            if (req.getConfigId() == null) {
                return Result.fail(messages.get("agent.admin.paramsIncomplete", Map.of("what", req.getFunctionName())));
            }
            AiRuntimeConfig target = configMapper.selectById(req.getConfigId());
            if (target == null) {
                return Result.fail(messages.get("agent.admin.llmConfigNotFound", Map.of("id", req.getConfigId())));
            }
            // 空model的配置建不出模型，提前拦截别等refresh才炸
            if (target.getModel() == null || target.getModel().isBlank()) {
                return Result.fail(messages.get("agent.admin.llmConfigNoModel", Map.of("name", String.valueOf(target.getConfigName()))));
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
            return Result.fail(messages.get("agent.admin.assignedButRefreshFailed"));
        }
        return Result.ok(null);
    }

    // ========== Jev 预测员开关 ==========

    /** configured=平台 JEV_API_KEY 配了；enabled=开关开着。两个都真才真跑 */
    public record JevPredictionState(boolean configured, boolean enabled) {
    }

    @GetMapping("/jev-prediction")
    @Operation(summary = "Jev 预测员开关状态")
    public Result<JevPredictionState> jevPrediction() {
        return Result.ok(new JevPredictionState(jevPlatform.enabled(), jevSwitch.isOn()));
    }

    @PostMapping("/jev-prediction")
    @Operation(summary = "开/关 Jev 预测员（存 Redis，重启不丢）")
    public Result<JevPredictionState> setJevPrediction(@RequestBody JevPredictionRequest req) {
        if (req.getEnabled() == null) {
            return Result.fail(messages.get("agent.admin.paramsIncomplete", Map.of("what", "enabled")));
        }
        if (req.getEnabled() && !jevPlatform.enabled()) {
            return Result.fail(messages.get("agent.admin.jevKeyMissing"));
        }
        jevSwitch.set(req.getEnabled());
        log.info("[JevPred] 管理员{}预测员", req.getEnabled() ? "打开" : "关闭");
        return Result.ok(new JevPredictionState(jevPlatform.enabled(), jevSwitch.isOn()));
    }

    // 量化触发端点（快照/vol验证）已随预测管线下线（2026-08：生产验证无前瞻信息）。
    // quant-config 开关端点已删：开关框架自 v1 调权清理后空转（无注册开关），随死表清理一并拆除。

    // ========== DTO ==========

    @Data
    public static class KeyRequest {
        private Long id;
        private String configName;
        private String apiKey;
        private String baseUrl;
        private String model;
        /** 思考档位，任意上游认的值（none/low/medium/high/xhigh…）；空=不传走模型默认 */
        private String reasoningEffort;
        /** 上游协议 openai/responses；空=openai */
        private String apiProtocol;
    }

    @Data
    public static class AssignmentRequest {
        private String functionName;
        private Long configId;
    }

    @Data
    public static class JevPredictionRequest {
        private Boolean enabled;
    }

}
