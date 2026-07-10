package com.mawai.wiibquant.agent.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.mawai.wiibcommon.constant.AiFunctions;
import com.mawai.wiibcommon.constant.AiProtocols;
import com.mawai.wiibcommon.entity.AiModelAssignment;
import com.mawai.wiibcommon.entity.AiRuntimeConfig;
import com.mawai.wiibcommon.mapper.AiModelAssignmentMapper;
import com.mawai.wiibcommon.mapper.AiRuntimeConfigMapper;
import com.mawai.wiibquant.agent.behavior.BehaviorAgentFactory;
import com.mawai.wiibquant.agent.llm.ResponsesChatModel;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * AI 运行时管理：唯一配置源是 DB（ai_runtime_config + ai_model_assignment），启动/Admin 变更时重建各功能位模型。
 * ChatModel 自动装配已关（yml: spring.ai.model.*=none，不再要求 yml 预置 api-key），模型全部在此手建；
 * 空库不拖死进程（降级为"AI未就绪"），构建失败保留上一份可用模型——Admin 页配好后 refresh 即恢复，无需重启。
 */
@Slf4j
@Component
public class AiAgentRuntimeManager {

    // P2a 删 reflection（方向反思链）；P4 增 quant-light（对话子 agent 浅模型，深浅分层省成本）
    // 管理口径（种子/Admin白名单/配置删除保护）：前4个是本进程运行时功能位（refresh()按名建模型），
    // sim 是 wiib-sim 进程的功能位——它每次调用自读 DB，不在本进程建模型，只借 quant 统一种子和管理
    private static final List<String> MANAGED_FUNCTIONS = List.of(
            AiFunctions.BEHAVIOR, AiFunctions.QUANT, AiFunctions.QUANT_LIGHT, AiFunctions.CHAT, AiFunctions.SIM);

    private final BehaviorAgentFactory behaviorAgentFactory;
    private final ApplicationEventPublisher eventPublisher;
    private final AiRuntimeConfigMapper configMapper;
    private final AiModelAssignmentMapper assignmentMapper;
    private final ToolCallingManager toolCallingManager;
    private final RetryTemplate retryTemplate;
    private final ObservationRegistry observationRegistry;
    private final AtomicReference<AiAgentRuntime> runtimeRef = new AtomicReference<>();
    private final Object graphLock = new Object();

    public AiAgentRuntimeManager(BehaviorAgentFactory behaviorAgentFactory,
                                 AiRuntimeConfigMapper configMapper,
                                 AiModelAssignmentMapper assignmentMapper,
                                 ToolCallingManager toolCallingManager,
                                 RetryTemplate retryTemplate,
                                 ObjectProvider<ObservationRegistry> observationRegistry,
                                 ApplicationEventPublisher eventPublisher) {
        this.behaviorAgentFactory = behaviorAgentFactory;
        this.eventPublisher = eventPublisher;
        this.configMapper = configMapper;
        this.assignmentMapper = assignmentMapper;
        // 与关掉的自动装配同源：RetryTemplate(spring.ai.retry)/ToolCallingManager 仍是独立装配的 bean，手建模型能力等价
        this.toolCallingManager = toolCallingManager;
        this.retryTemplate = retryTemplate;
        this.observationRegistry = observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP);
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    public AiAgentRuntime current() {
        AiAgentRuntime runtime = runtimeRef.get();
        if (runtime == null) {
            throw new IllegalStateException("AI未配置或配置不完整，请在Admin页添加LLM配置并分配功能位");
        }
        return runtime;
    }

    public static boolean isManagedFunction(String functionName) {
        return MANAGED_FUNCTIONS.contains(functionName);
    }

    /**
     * 从DB读取所有配置和分配关系，重建4个独立ChatModel；返回是否刷新成功（Admin据此报错）。
     * 空库→runtime置空（合法的"未配置"态）；构建失败→保留上一份可用runtime——坏切换/瞬时DB错误不打死在跑的AI。
     */
    public boolean refresh() {
        synchronized (graphLock) {
            boolean ok;
            try {
                List<AiRuntimeConfig> configs = configMapper.selectAllConfigs();
                if (configs.isEmpty()) {
                    runtimeRef.set(null);
                    log.warn("AI未配置：ai_runtime_config为空，AI功能暂不可用——在Admin页添加LLM配置后自动生效，无需重启");
                    ok = true;
                } else {
                    seedMissingAssignments(configs);
                    Map<Long, AiRuntimeConfig> configMap = configs.stream()
                            .collect(Collectors.toMap(AiRuntimeConfig::getId, c -> c));
                    List<AiModelAssignment> assignments = assignmentMapper.selectAll();
                    runtimeRef.set(new AiAgentRuntime(
                            buildFromAssignment(assignments, AiFunctions.BEHAVIOR, configMap),
                            buildFromAssignment(assignments, AiFunctions.QUANT, configMap),
                            buildFromAssignment(assignments, AiFunctions.QUANT_LIGHT, configMap),
                            buildFromAssignment(assignments, AiFunctions.CHAT, configMap)
                    ));
                    log.info("AI运行时已刷新，共{}个LLM配置，{}个功能位分配", configMap.size(), assignments.size());
                    ok = true;
                }
            } catch (Exception e) {
                log.error("AI运行时构建失败，沿用变更前模型运行", e);
                ok = false;
            }
            // 成败都广播：让对话图等构建期绑定模型的缓存失效重建，与当前 runtime 保持一致
            eventPublisher.publishEvent(new AiRuntimeRefreshedEvent(this));
            return ok;
        }
    }

    public ReactAgent createBehaviorAgent(Consumer<String> onProgress) {
        return behaviorAgentFactory.create(current().behaviorChatModel(), onProgress);
    }

    // 旧 quant graph 构建/fallback 整套已随旧管线删除（P2a）：
    // 新快照图零 LLM 走 QuantSnapshotGraphFactory；P2b 深研判的模型韧性由框架 interceptor 承担。

    /**
     * 检查指定LLM配置是否被功能位分配引用
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
                .orElseThrow(() -> new IllegalStateException("未找到" + functionName + "的功能位分配"));

        AiRuntimeConfig config = configMap.get(assignment.getConfigId());
        if (config == null) {
            throw new IllegalStateException(functionName + "引用的LLM配置不存在(id=" + assignment.getConfigId() + ")");
        }
        // 模型名归属配置本身（一条配置=一个具体LLM），功能位只是指针
        if (config.getModel() == null || config.getModel().isBlank()) {
            throw new IllegalStateException(functionName + "所选LLM配置'" + config.getConfigName() + "'缺模型名，请在Admin页完善");
        }

        return buildChatModel(config);
    }

    /**
     * 功能位缺行时用第一个配置补齐——放在refresh里，Admin加第一条配置即自动完成种子，无需重启。
     * 种子失败不阻断后续建模：已有分配的功能位照常工作，只有缺失位不可用（如存量库尚未删model列时的NOT NULL违约）。
     */
    private void seedMissingAssignments(List<AiRuntimeConfig> configs) {
        try {
            List<AiModelAssignment> existing = assignmentMapper.selectAll();
            Set<String> existingFunctions = existing.stream()
                    .map(AiModelAssignment::getFunctionName).collect(Collectors.toSet());
            if (existingFunctions.containsAll(MANAGED_FUNCTIONS)) {
                return;
            }

            AiRuntimeConfig first = configs.getFirst();
            for (String fn : MANAGED_FUNCTIONS) {
                if (existingFunctions.contains(fn)) continue;
                AiModelAssignment a = new AiModelAssignment();
                a.setFunctionName(fn);
                a.setConfigId(first.getId());
                a.setUpdatedAt(LocalDateTime.now());
                assignmentMapper.insert(a);
                log.info("自动创建功能位分配 function={} → 配置'{}'(model={})", fn, first.getConfigName(), first.getModel());
            }
        } catch (Exception e) {
            log.error("功能位种子补齐失败，缺失的功能位暂不可用", e);
        }
    }

    /**
     * 从 DB 配置手建模型，按配置行的协议分叉：
     * responses → 自研 ResponsesChatModel（/v1/responses，思考模型原生协议）；
     * openai → Spring AI OpenAiChatModel（/v1/chat/completions，DeepSeek 等通用）。
     * 思考档位两条路线都注入：responses 走 reasoning.effort，openai 走 reasoning_effort 字段。
     */
    private ChatModel buildChatModel(AiRuntimeConfig config) {
        // 不设置 temperature：走各模型默认值，思考模型（多数拒收或忽略温度）也安全
        if (AiProtocols.isResponses(config.getApiProtocol())) {
            return new ResponsesChatModel(config.getApiKey(), config.getBaseUrl(), config.getModel(),
                    null, config.getReasoningEffort(), toolCallingManager, retryTemplate);
        }

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .build();

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(config.getModel());
        if (config.getReasoningEffort() != null) {
            options.reasoningEffort(config.getReasoningEffort());
        }

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options.build())
                .toolCallingManager(toolCallingManager)
                .retryTemplate(retryTemplate)
                .observationRegistry(observationRegistry)
                .build();
    }
}
