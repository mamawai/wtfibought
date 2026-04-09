package com.mawai.wiibservice.agent.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.mawai.wiibcommon.entity.AiModelAssignment;
import com.mawai.wiibcommon.entity.AiRuntimeConfig;
import com.mawai.wiibservice.mapper.AiModelAssignmentMapper;
import com.mawai.wiibservice.mapper.AiRuntimeConfigMapper;
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

    private static final List<String> FUNCTIONS = List.of("behavior", "quant", "chat", "reflection");

    private final AiAgentConfig aiAgentConfig;
    private final AiRuntimeConfigMapper configMapper;
    private final AiModelAssignmentMapper assignmentMapper;
    private final OpenAiChatModel prototypeChatModel;
    private final String ymlApiKey;
    private final String ymlBaseUrl;
    private final String ymlModel;
    private final AtomicReference<AiAgentRuntime> runtimeRef = new AtomicReference<>();

    public AiAgentRuntimeManager(AiAgentConfig aiAgentConfig,
                                 AiRuntimeConfigMapper configMapper,
                                 AiModelAssignmentMapper assignmentMapper,
                                 ChatModel chatModel,
                                 @Value("${spring.ai.openai.api-key:}") String ymlApiKey,
                                 @Value("${spring.ai.openai.base-url:}") String ymlBaseUrl,
                                 @Value("${spring.ai.openai.chat.options.model:}") String ymlModel) {
        this.aiAgentConfig = aiAgentConfig;
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

    /**
     * 从DB读取所有配置和分配关系，重建4个独立ChatModel
     */
    public synchronized void refresh() {
        Map<Long, AiRuntimeConfig> configMap = configMapper.selectAllConfigs().stream()
                .collect(Collectors.toMap(AiRuntimeConfig::getId, c -> c));
        List<AiModelAssignment> assignments = assignmentMapper.selectAll();

        runtimeRef.set(new AiAgentRuntime(
                buildFromAssignment(assignments, "behavior", configMap),
                buildFromAssignment(assignments, "quant", configMap),
                buildFromAssignment(assignments, "chat", configMap),
                buildFromAssignment(assignments, "reflection", configMap)
        ));
        log.info("AI运行时已刷新，共{}个API Key配置，{}个模型分配", configMap.size(), assignments.size());
    }

    public com.alibaba.cloud.ai.graph.agent.ReactAgent createBehaviorAgent(Consumer<String> onProgress) {
        return aiAgentConfig.createBehaviorAgent(current().behaviorChatModel(), onProgress);
    }

    public CompiledGraph createCryptoAnalysisGraph() throws Exception {
        return aiAgentConfig.createCryptoAnalysisGraph(current().quantChatModel());
    }

    /**
     * 量化调用统一入口
     */
    public Optional<OverAllState> invokeQuantWithFallback(String symbol, String threadId) throws Exception {
        return invokeQuantWithFallback(symbol, threadId, null);
    }

    /**
     * 量化调用统一入口：支持传入额外初始状态（如共享的FGI数据）；LLM异常时自动降级到default配置重试
     */
    public Optional<OverAllState> invokeQuantWithFallback(String symbol, String threadId, Map<String, Object> extraState) throws Exception {
        try {
            CompiledGraph graph = createCryptoAnalysisGraph();
            Map<String, Object> initialState = new HashMap<>(Map.of("target_symbol", symbol));
            if (extraState != null) initialState.putAll(extraState);
            return graph.invoke(initialState,
                    RunnableConfig.builder().threadId(threadId).build());
        } catch (Exception e) {
            AiRuntimeConfig defaultConfig = configMapper.selectDefault();
            AiModelAssignment quantAssignment = assignmentMapper.selectByFunction("quant");
            if (defaultConfig == null
                    || (quantAssignment != null && quantAssignment.getConfigId().equals(defaultConfig.getId()))) {
                throw e;
            }
            log.warn("量化LLM异常，降级到default配置重试 symbol={}", symbol, e);
            String model = defaultConfig.getModel();
            if (model == null || model.isBlank()) model = ymlModel;
            ChatModel fallbackModel = buildChatModel(defaultConfig.getApiKey(), defaultConfig.getBaseUrl(), model);
            CompiledGraph fallbackGraph = aiAgentConfig.createCryptoAnalysisGraph(fallbackModel);
            Map<String, Object> initialState = new HashMap<>(Map.of("target_symbol", symbol));
            if (extraState != null) initialState.putAll(extraState);
            return fallbackGraph.invoke(initialState,
                    RunnableConfig.builder().threadId(threadId).build());
        }
    }

    /**
     * 检查指定API Key是否被模型分配引用
     */
    public boolean isConfigReferenced(Long configId) {
        return assignmentMapper.selectAll().stream()
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
        List<AiModelAssignment> existing = assignmentMapper.selectAll();
        if (existing.size() >= FUNCTIONS.size()) {
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

        java.util.Set<String> existingFunctions = existing.stream()
                .map(AiModelAssignment::getFunctionName).collect(Collectors.toSet());

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
