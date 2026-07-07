package com.mawai.wiibquant.agent.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.mawai.wiibcommon.entity.AiModelAssignment;
import com.mawai.wiibcommon.entity.AiRuntimeConfig;
import com.mawai.wiibquant.agent.behavior.BehaviorAgentFactory;
import com.mawai.wiibquant.mapper.AiModelAssignmentMapper;
import com.mawai.wiibquant.mapper.AiRuntimeConfigMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AiAgentRuntimeManager {

    // P2a 删 reflection（方向反思链）；P4 增 quant-light（对话子 agent 浅模型，深浅分层省成本）
    private static final List<String> FUNCTIONS = List.of("behavior", "quant", "quant-light", "chat");

    private final BehaviorAgentFactory behaviorAgentFactory;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final AiRuntimeConfigMapper configMapper;
    private final AiModelAssignmentMapper assignmentMapper;
    private final OpenAiChatModel prototypeChatModel;
    private final String ymlApiKey;
    private final String ymlBaseUrl;
    private final String ymlModel;
    private final AtomicReference<AiAgentRuntime> runtimeRef = new AtomicReference<>();
    private final Object graphLock = new Object();

    public AiAgentRuntimeManager(BehaviorAgentFactory behaviorAgentFactory,
                                 AiRuntimeConfigMapper configMapper,
                                 AiModelAssignmentMapper assignmentMapper,
                                 ChatModel chatModel,
                                 org.springframework.context.ApplicationEventPublisher eventPublisher,
                                 @Value("${spring.ai.openai.api-key:}") String ymlApiKey,
                                 @Value("${spring.ai.openai.base-url:}") String ymlBaseUrl,
                                 @Value("${spring.ai.openai.chat.options.model:}") String ymlModel) {
        this.behaviorAgentFactory = behaviorAgentFactory;
        this.eventPublisher = eventPublisher;
        this.configMapper = configMapper;
        this.assignmentMapper = assignmentMapper;
        if (!(chatModel instanceof OpenAiChatModel openAiChatModel)) {
            throw new IllegalStateException("AI Agent动态配置当前仅支持OpenAiChatModel");
        }
        this.prototypeChatModel = openAiChatModel;
        this.ymlApiKey = ymlApiKey;
        this.ymlBaseUrl = ymlBaseUrl;
        this.ymlModel = ymlModel;
    }

    @PostConstruct
    public void init() {
        ensureApiKeyExists();
        ensureAssignmentsExist();
        refresh();
    }

    public AiAgentRuntime current() {
        AiAgentRuntime runtime = runtimeRef.get();
        if (runtime == null) {
            throw new IllegalStateException("AI Agent运行时尚未初始化");
        }
        return runtime;
    }

    public static boolean isManagedFunction(String functionName) {
        return FUNCTIONS.contains(functionName);
    }

    /**
     * 从DB读取所有配置和分配关系，重建4个独立ChatModel
     */
    public void refresh() {
        synchronized (graphLock) {
            Map<Long, AiRuntimeConfig> configMap = configMapper.selectAllConfigs().stream()
                    .collect(Collectors.toMap(AiRuntimeConfig::getId, c -> c));
            List<AiModelAssignment> assignments = assignmentMapper.selectAll();

            runtimeRef.set(new AiAgentRuntime(
                    buildFromAssignment(assignments, "behavior", configMap),
                    buildFromAssignment(assignments, "quant", configMap),
                    buildFromAssignment(assignments, "quant-light", configMap),
                    buildFromAssignment(assignments, "chat", configMap)
            ));
            log.info("AI运行时已刷新，共{}个API Key配置，{}个模型分配", configMap.size(), assignments.size());
            // 通知对话图等模型消费方重建（构建期绑定 ChatModel 的组件靠此感知热更新）
            eventPublisher.publishEvent(new AiRuntimeRefreshedEvent(this));
        }
    }

    public ReactAgent createBehaviorAgent(Consumer<String> onProgress) {
        return behaviorAgentFactory.create(current().behaviorChatModel(), onProgress);
    }

    // 旧 quant graph 构建/fallback 整套已随旧管线删除（P2a）：
    // 新快照图零 LLM 走 QuantSnapshotGraphFactory；P2b 深研判的模型韧性由框架 interceptor 承担。

    /**
     * 检查指定API Key是否被模型分配引用
     */
    public boolean isConfigReferenced(Long configId) {
        return assignmentMapper.selectAll().stream()
                .filter(a -> isManagedFunction(a.getFunctionName()))
                .anyMatch(a -> configId.equals(a.getConfigId()));
    }

    private ChatModel buildFromAssignment(List<AiModelAssignment> assignments, String functionName,
                                          Map<Long, AiRuntimeConfig> configMap) {
        AiModelAssignment assignment = assignments.stream()
                .filter(a -> functionName.equals(a.getFunctionName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到" + functionName + "的模型分配"));

        AiRuntimeConfig config = configMap.get(assignment.getConfigId());
        if (config == null) {
            throw new IllegalStateException(functionName + "引用的API Key不存在(id=" + assignment.getConfigId() + ")");
        }

        return buildChatModel(config.getApiKey(), config.getBaseUrl(), assignment.getModel());
    }

    private void ensureApiKeyExists() {
        List<AiRuntimeConfig> configs = configMapper.selectAllConfigs();
        if (!configs.isEmpty()) {
            return;
        }

        // 从旧的default配置迁移
        AiRuntimeConfig old = configMapper.selectDefault();
        if (old != null) {
            log.info("从已有default配置迁移 id={}", old.getId());
            return;
        }

        // 从yml创建初始配置
        log.info("数据库无API Key配置，从yml导入");
        AiRuntimeConfig config = new AiRuntimeConfig();
        config.setConfigName("default");
        config.setApiKey(ymlApiKey);
        config.setBaseUrl(ymlBaseUrl);
        config.setModel(ymlModel);
        config.setEnabled(true);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        configMapper.insert(config);
    }

    private void ensureAssignmentsExist() {
        int removed = assignmentMapper.delete(new LambdaQueryWrapper<AiModelAssignment>()
                .eq(AiModelAssignment::getFunctionName, "trading"));
        if (removed > 0) {
            log.info("已删除旧AI Trader模型分配 {} 条", removed);
        }
        // P2a：reflection 随方向反思链删除，清理遗留分配（同 trading 先例）
        int removedReflection = assignmentMapper.delete(new LambdaQueryWrapper<AiModelAssignment>()
                .eq(AiModelAssignment::getFunctionName, "reflection"));
        if (removedReflection > 0) {
            log.info("已删除旧reflection模型分配 {} 条", removedReflection);
        }

        List<AiModelAssignment> existing = assignmentMapper.selectAll();
        java.util.Set<String> existingFunctions = existing.stream()
                .map(AiModelAssignment::getFunctionName).collect(Collectors.toSet());
        if (existingFunctions.containsAll(FUNCTIONS)) {
            return;
        }

        // 找第一个可用的API Key配置
        List<AiRuntimeConfig> configs = configMapper.selectAllConfigs();
        if (configs.isEmpty()) {
            throw new IllegalStateException("无可用的API Key配置");
        }
        AiRuntimeConfig first = configs.getFirst();
        String defaultModel = first.getModel() != null && !first.getModel().isBlank()
                ? first.getModel() : ymlModel;

        for (String fn : FUNCTIONS) {
            if (existingFunctions.contains(fn)) continue;
            AiModelAssignment a = new AiModelAssignment();
            a.setFunctionName(fn);
            a.setConfigId(first.getId());
            a.setModel(defaultModel);
            a.setUpdatedAt(LocalDateTime.now());
            assignmentMapper.insert(a);
            log.info("自动创建模型分配 function={} configId={} model={}", fn, first.getId(), defaultModel);
        }
    }

    private OpenAiChatModel buildChatModel(String apiKey, String baseUrl, String model) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        var currentOptions = prototypeChatModel.getDefaultOptions();
        OpenAiChatOptions options = currentOptions instanceof OpenAiChatOptions openAiChatOptions
                ? OpenAiChatOptions.fromOptions(openAiChatOptions)
                : new OpenAiChatOptions();
        options.setModel(model);

        return prototypeChatModel.mutate()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }
}
