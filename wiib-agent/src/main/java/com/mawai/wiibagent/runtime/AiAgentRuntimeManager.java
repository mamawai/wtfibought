package com.mawai.wiibagent.runtime;

import com.mawai.wiibcommon.constant.AiFunctions;
import com.mawai.wiibcommon.entity.AiModelAssignment;
import com.mawai.wiibcommon.entity.AiRuntimeConfig;
import com.mawai.wiibcommon.mapper.AiModelAssignmentMapper;
import com.mawai.wiibcommon.mapper.AiRuntimeConfigMapper;
import com.mawai.wiibagent.llm.ByokModelBuilder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * AI 运行时管理：唯一配置源是 DB（ai_runtime_config + ai_model_assignment），启动/Admin 变更时重建各功能位模型。
 * ChatModel 自动装配已关（yml: spring.ai.model.*=none，不再要求 yml 预置 api-key），模型全部在此手建；
 * 空库不拖死进程（降级为"AI未就绪"），构建失败保留上一份可用模型——Admin 页配好后 refresh 即恢复，无需重启。
 */
@Slf4j
@Component
public class AiAgentRuntimeManager {

    // 管理口径（种子/Admin白名单/配置删除保护）：只列本进程要建模型的功能位，refresh()按名建。
    // quant/quant-light/chat 随对话轨 BYOK 化删除、behavior 随行为分析进对话轨删除，
    // sim 是 wiib-sim 自读 DB 的位；这些名字在 ai_model_assignment 里的残行是孤儿，无害——
    // 种子、白名单、删除保护都只认这个常量
    private static final List<String> MANAGED_FUNCTIONS = List.of(AiFunctions.NEWS_TRANSLATION);

    private final AiRuntimeConfigMapper configMapper;
    private final AiModelAssignmentMapper assignmentMapper;
    /** 建模与 BYOK 同一份实现，只是 key 来源不同 */
    private final ByokModelBuilder modelBuilder;
    private final AtomicReference<AiAgentRuntime> runtimeRef = new AtomicReference<>();
    private final Object graphLock = new Object();

    public AiAgentRuntimeManager(AiRuntimeConfigMapper configMapper,
                                 AiModelAssignmentMapper assignmentMapper,
                                 ByokModelBuilder modelBuilder) {
        this.configMapper = configMapper;
        this.assignmentMapper = assignmentMapper;
        this.modelBuilder = modelBuilder;
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
     * 从DB读取所有配置和分配关系，重建 news-translation 功能位的 ChatModel（面向用户的功能位已全量 BYOK 化）；
     * 返回是否刷新成功（Admin据此报错）。
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
                } else {
                    seedMissingAssignments(configs);
                    Map<Long, AiRuntimeConfig> configMap = configs.stream()
                            .collect(Collectors.toMap(AiRuntimeConfig::getId, c -> c));
                    List<AiModelAssignment> assignments = assignmentMapper.selectAll();
                    // 译文模型名随行落库（news_event.translated_model 追责用），所以这一位要留住配置行
                    AiRuntimeConfig newsTranslation = configFor(assignments, AiFunctions.NEWS_TRANSLATION, configMap);
                    runtimeRef.set(new AiAgentRuntime(buildChatModel(newsTranslation), newsTranslation.getModel()));
                    log.info("AI运行时已刷新，共{}个LLM配置，{}个功能位分配", configMap.size(), assignments.size());
                }
                ok = true;
            } catch (Exception e) {
                log.error("AI运行时构建失败，沿用变更前模型运行", e);
                ok = false;
            }
            return ok;
        }
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

    /** 功能位 → 校验过的配置行（分配缺失/指针悬空/缺模型名都在这儿拦） */
    private AiRuntimeConfig configFor(List<AiModelAssignment> assignments, String functionName,
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
        return config;
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

    /** 从 DB 配置手建模型，建法与 BYOK 同一份。平台轨没有搜索配置位：webSearch 恒关（服务端搜索是对话侧的能力） */
    private ChatModel buildChatModel(AiRuntimeConfig config) {
        return modelBuilder.build(config.getApiProtocol(), config.getBaseUrl(), config.getApiKey(),
                config.getModel(), config.getReasoningEffort(), false);
    }
}
