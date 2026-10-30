package com.mawai.wiibagent.controller;

import com.mawai.wiibcommon.annotation.RequireAdmin;
import com.mawai.wiibcommon.constant.AiProtocols;
import com.mawai.wiibcommon.entity.AiModelAssignment;
import com.mawai.wiibcommon.entity.AiRuntimeConfig;
import com.mawai.wiibcommon.entity.JevPredictionRun;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.mapper.AiModelAssignmentMapper;
import com.mawai.wiibcommon.mapper.AiRuntimeConfigMapper;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.BinanceWeightMeter;
import com.mawai.wiibagent.jev.JevPlatformConfig;
import com.mawai.wiibagent.jev.predictor.JevPredictionRuns;
import com.mawai.wiibagent.jev.predictor.JevPredictionSwitch;
import com.mawai.wiibagent.llm.BaseUrlGuard;
import com.mawai.wiibagent.llm.ByokModelBuilder;
import com.mawai.wiibagent.news.NewsEventCollector;
import com.mawai.wiibagent.runtime.AiAgentRuntimeManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
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

    /** 手动补拉最多补最近几条：每 100 条一次额度，防手滑 */
    private static final int MAX_BACKFILL_COUNT = 500;

    private final AiAgentRuntimeManager aiAgentRuntimeManager;
    private final AiRuntimeConfigMapper configMapper;
    private final AiModelAssignmentMapper assignmentMapper;
    private final ByokModelBuilder modelBuilder;
    private final NewsEventCollector newsEventCollector;
    private final JevPlatformConfig jevPlatform;
    private final JevPredictionSwitch jevSwitch;
    private final JevPredictionRuns jevRuns;
    /** 管理页的校验与回执跟界面语言 */
    private final MessageCatalog messages;
    private final BinanceRestClient binanceRestClient;

    // ========== Binance 权重 ==========

    @GetMapping("/binance-weight")
    @Operation(summary = "Binance 合约站（fapi）当前已用权重（按 IP 算、每分钟归零；60 秒没新回包按 0）")
    public Result<BinanceWeightMeter.Reading> binanceWeight() {
        return Result.ok(binanceRestClient.weight().reading());
    }

    // ========== API Key 管理 ==========

    @GetMapping("/keys")
    @Operation(summary = "获取所有API Key配置（key 只回尾 4 位）")
    public Result<List<KeyView>> listKeys() {
        return Result.ok(configMapper.selectAllConfigs().stream().map(KeyView::of).toList());
    }

    @PostMapping("/keys")
    @Operation(summary = "新增/修改API Key配置（改的时候 apiKey 留空=不换）")
    public Result<KeyView> saveKey(@RequestBody KeyRequest req) {
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
        String effort = effortOf(req);
        if (effort != null && effort.length() > MAX_EFFORT_LEN) {
            return Result.fail(messages.get("agent.admin.effortTooLong", Map.of("max", MAX_EFFORT_LEN)));
        }
        // 协议留空=openai（存量兼容）
        String protocol = AiProtocols.normalize(req.getApiProtocol());
        if (!AiProtocols.isValid(protocol)) {
            return Result.fail(messages.get("agent.admin.protocolUnsupported"));
        }

        String baseUrl = baseUrlOf(req);

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
        AiRuntimeConfig saved = req.getId() == null ? null : config;
        String apiKey = keyOf(req, saved);
        if (apiKey == null) {
            return Result.fail(missingKey(saved));
        }

        config.setConfigName(req.getConfigName().trim());
        config.setApiKey(apiKey);
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
        return Result.ok(KeyView.of(config));
    }

    /** 探测用表单里的 key，不用先保存；改已有配置时 key 留空=用它已存的。平台位要连内网网关（CPA），不走 BYOK 那道内网地址拦截 */
    @PostMapping("/keys/models")
    @Operation(summary = "拉取LLM配置可用模型清单")
    public Result<List<String>> listModels(@RequestBody KeyRequest req) {
        if (req.getBaseUrl() == null || req.getBaseUrl().isBlank()) {
            return Result.fail(messages.get("agent.admin.baseUrlRequired"));
        }
        AiRuntimeConfig saved = savedOf(req);
        String apiKey = keyOf(req, saved);
        if (apiKey == null) {
            return Result.fail(missingKey(saved));
        }
        try {
            return Result.ok(modelBuilder.listModels(req.getApiProtocol(), baseUrlOf(req), apiKey));
        } catch (Exception e) {
            return Result.fail(reason(e));
        }
    }

    /** 走运行时同一条建模路径发一句 ping：测的就是接下来真跑的 */
    @PostMapping("/keys/test")
    @Operation(summary = "测试LLM配置连通性")
    public Result<Void> testKey(@RequestBody KeyRequest req) {
        if (req.getBaseUrl() == null || req.getBaseUrl().isBlank()) {
            return Result.fail(messages.get("agent.admin.baseUrlRequired"));
        }
        AiRuntimeConfig saved = savedOf(req);
        String apiKey = keyOf(req, saved);
        if (apiKey == null) {
            return Result.fail(missingKey(saved));
        }
        if (req.getModel() == null || req.getModel().isBlank()) {
            return Result.fail(messages.get("agent.admin.modelRequired"));
        }
        String protocol = AiProtocols.normalize(req.getApiProtocol());
        if (!AiProtocols.isValid(protocol)) {
            return Result.fail(messages.get("agent.admin.protocolUnsupported"));
        }
        try {
            modelBuilder.build(protocol, baseUrlOf(req), apiKey, req.getModel().trim(),
                    effortOf(req), false).call(new Prompt(new UserMessage("ping")));
            return Result.ok(null);
        } catch (Exception e) {
            return Result.fail(messages.get("agent.admin.connectFailed", Map.of("reason", reason(e))));
        }
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
                if (!AiAgentRuntimeManager.isOptionalFunction(req.getFunctionName())) {
                    return Result.fail(messages.get("agent.admin.paramsIncomplete", Map.of("what", req.getFunctionName())));
                }
                // 可空位传空＝不用了，删掉分配行
                AiModelAssignment existing = assignmentMapper.selectByFunction(req.getFunctionName());
                if (existing != null) {
                    assignmentMapper.deleteById(existing.getId());
                }
                continue;
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

    // ========== 快讯补拉 ==========

    /** 断档超出定时拉取窗口时手动补：补最近 count 条只存中文，译文由定时轮补上 */
    @PostMapping("/news/backfill")
    @Operation(summary = "手动补拉最近 N 条 BlockBeats 重要快讯（每 100 条烧一次额度）")
    public Result<NewsEventCollector.BackfillResult> backfillNews(@RequestBody NewsBackfillRequest req) {
        if (req.getCount() == null || req.getCount() < 1 || req.getCount() > MAX_BACKFILL_COUNT) {
            return Result.fail(messages.get("agent.admin.backfillCountInvalid", Map.of("max", MAX_BACKFILL_COUNT)));
        }
        NewsEventCollector.BackfillResult r = newsEventCollector.backfill(req.getCount());
        log.info("[NewsCollect] 管理员补拉最近 {} 条：拉到 {} 条，新存 {} 条", req.getCount(), r.fetched(), r.inserted());
        if (r.failed()) {
            return Result.fail(messages.get("agent.admin.backfillFailed",
                    Map.of("fetched", r.fetched(), "inserted", r.inserted())));
        }
        return Result.ok(r);
    }

    // ========== Jev 预测员开关 ==========

    /**
     * configured=平台 JEV_API_KEY 配了；enabled=开关开着，两个都真、且有在跑的局才真跑；
     * runs 是在跑的局，按 v5-1、v5-2、v5-3 排，还没开过带组的局是空的
     */
    public record JevPredictionState(boolean configured, boolean enabled, List<JevPredictionRun> runs) {
    }

    @GetMapping("/jev-prediction")
    @Operation(summary = "Jev 预测员开关状态与在跑的三局")
    public Result<JevPredictionState> jevPrediction() {
        return Result.ok(jevState());
    }

    @PostMapping("/jev-prediction/new-run")
    @Operation(summary = "Jev 预测员重新开局：三组各建一局一个新账户注资，旧局留档；开关开着不让开")
    public Result<JevPredictionState> newJevRun(@RequestBody JevNewRunRequest req) {
        if (jevSwitch.isOn()) {
            return Result.fail(messages.get("agent.admin.jevRunWhileOn"));
        }
        for (JevPredictionRun run : jevRuns.startNew(req.getLabel())) {
            log.info("[JevPred] 管理员重新开局 R{} arm={} label={}", run.getRunNo(), run.getArm(), run.getLabel());
        }
        return Result.ok(jevState());
    }

    private JevPredictionState jevState() {
        return new JevPredictionState(jevPlatform.enabled(), jevSwitch.isOn(), jevRuns.active());
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
        return Result.ok(jevState());
    }

    // 量化触发端点（快照/vol验证）已随预测管线下线（2026-08：生产验证无前瞻信息）。
    // quant-config 开关端点已删：开关框架自 v1 调权清理后空转（无注册开关），随死表清理一并拆除。

    /** 档位留空=null（不传走模型默认），统一小写 */
    private static String effortOf(KeyRequest req) {
        String effort = req.getReasoningEffort() == null ? null : req.getReasoningEffort().trim().toLowerCase();
        return effort == null || effort.isEmpty() ? null : effort;
    }

    private static String baseUrlOf(KeyRequest req) {
        String baseUrl = req.getBaseUrl().trim();
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** 带 id 是在改已有配置；查不到当新建 */
    private AiRuntimeConfig savedOf(KeyRequest req) {
        return req.getId() == null ? null : configMapper.selectById(req.getId());
    }

    /** 表单里的 key；留空就沿用已存的，但地址的 scheme、主机、端口得和存它时一样。拿不到 key 返回 null */
    private static String keyOf(KeyRequest req, AiRuntimeConfig saved) {
        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            return req.getApiKey().trim();
        }
        return saved != null && BaseUrlGuard.sameOrigin(saved.getBaseUrl(), req.getBaseUrl()) ? saved.getApiKey() : null;
    }

    /** 新建没填 key 是必填；改已有配置拿不到 key 只能是地址换了 */
    private String missingKey(AiRuntimeConfig saved) {
        return messages.get(saved == null ? "agent.admin.apiKeyRequired" : "agent.admin.apiKeyRequiredNewHost");
    }

    /** 上游报错原文截短回给管理员看 */
    private static String reason(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return msg.length() > 300 ? msg.substring(0, 300) : msg;
    }

    // ========== DTO ==========

    /** 下发给页面的配置：key 只给尾 4 位，完整 key 不出服务端 */
    public record KeyView(Long id, String configName, String apiKeyTail, String baseUrl, String model,
                          String reasoningEffort, String apiProtocol) {

        static KeyView of(AiRuntimeConfig c) {
            String key = c.getApiKey();
            String tail = key.length() > 4 ? key.substring(key.length() - 4) : "****";
            return new KeyView(c.getId(), c.getConfigName(), tail, c.getBaseUrl(), c.getModel(),
                    c.getReasoningEffort(), c.getApiProtocol());
        }
    }

    @Data
    public static class KeyRequest {
        private Long id;
        private String configName;
        /** 改已有配置时留空=不换 */
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
        /** 可空位传 null＝清空 */
        private Long configId;
    }

    @Data
    public static class NewsBackfillRequest {
        /** 补最近几条 */
        private Integer count;
    }

    @Data
    public static class JevPredictionRequest {
        private Boolean enabled;
    }

    @Data
    public static class JevNewRunRequest {
        /** 版本说明，可空 */
        private String label;
    }

}
