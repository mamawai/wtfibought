package com.mawai.wiibagent.runtime;

import com.mawai.wiibcommon.constant.AiFunctions;
import com.mawai.wiibcommon.entity.AiModelAssignment;
import com.mawai.wiibcommon.entity.AiRuntimeConfig;
import com.mawai.wiibcommon.mapper.AiModelAssignmentMapper;
import com.mawai.wiibcommon.mapper.AiRuntimeConfigMapper;
import com.mawai.wiibagent.llm.ByokModelBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 快讯翻译的模型：平台出钱的两个功能位（主位、备用）。唯一配置源是 DB（ai_runtime_config + ai_model_assignment），
 * 每次取都现查库，库里是什么就用什么；「快讯翻译」没选 LLM 就不翻译。建好的模型按配置内容缓存，配置没变就复用。
 * 没有 ChatModel 自动装配（Spring AI 的模型自动配置没引，yml 不用预置 api-key），模型全部在此手建
 */
@Slf4j
@Component
public class AiAgentRuntimeManager {

    // 管理口径（Admin白名单/配置删除保护）：只列本进程要建模型的功能位。
    // ai_model_assignment 里别的名字（quant、quant-light、chat、behavior、sim 等）是没人用的残行，无害——
    // 白名单、删除保护都只认这个常量
    private static final List<String> MANAGED_FUNCTIONS =
            List.of(AiFunctions.NEWS_TRANSLATION, AiFunctions.NEWS_TRANSLATION_FALLBACK);

    /** 可空位：Admin 能清空 */
    private static final Set<String> OPTIONAL_FUNCTIONS = Set.of(AiFunctions.NEWS_TRANSLATION_FALLBACK);

    /** 建一个模型用到的全部字段，本身就是缓存键：哪个字段变了都对不上，就重建 */
    private record Spec(String protocol, String baseUrl, String apiKey, String model, String effort) {
        static Spec of(AiRuntimeConfig c) {
            return new Spec(c.getApiProtocol(), c.getBaseUrl(), c.getApiKey(), c.getModel(), c.getReasoningEffort());
        }
    }

    private final AiRuntimeConfigMapper configMapper;
    private final AiModelAssignmentMapper assignmentMapper;
    /** 建模与 BYOK 同一份实现，只是 key 来源不同 */
    private final ByokModelBuilder modelBuilder;
    /** 建好的模型，只留最近一次用到的 */
    private final Map<Spec, ChatModel> models = new ConcurrentHashMap<>();

    public AiAgentRuntimeManager(AiRuntimeConfigMapper configMapper,
                                 AiModelAssignmentMapper assignmentMapper,
                                 ByokModelBuilder modelBuilder) {
        this.configMapper = configMapper;
        this.assignmentMapper = assignmentMapper;
        this.modelBuilder = modelBuilder;
    }

    /**
     * 现查库组装快讯翻译要用的模型：主位在前，备用选了排在后面，主位那批抛错才轮到它。
     * 「快讯翻译」没选 LLM 就是空列表；选了但指向的配置没了、缺模型名时抛 IllegalStateException
     */
    public AiAgentRuntime current() {
        List<AiRuntimeConfig> used = new ArrayList<>();
        AiModelAssignment primary = assignmentMapper.selectByFunction(AiFunctions.NEWS_TRANSLATION);
        if (primary != null) {
            used.add(configOf(primary));
            AiModelAssignment fallback = assignmentMapper.selectByFunction(AiFunctions.NEWS_TRANSLATION_FALLBACK);
            if (fallback != null) {
                used.add(configOf(fallback));
            }
        }
        models.keySet().retainAll(used.stream().map(Spec::of).toList());
        return new AiAgentRuntime(used.stream()
                .map(c -> new AiAgentRuntime.NamedModel(c.getModel(), models.computeIfAbsent(Spec.of(c), this::build)))
                .toList());
    }

    /** 管理页改完配置后校验一遍组装得起来（没选 LLM 也算通过）；组装不起来返回 false，原因打日志 */
    public boolean check() {
        try {
            current();
            return true;
        } catch (Exception e) {
            log.error("快讯翻译的模型组装不起来", e);
            return false;
        }
    }

    public static boolean isManagedFunction(String functionName) {
        return MANAGED_FUNCTIONS.contains(functionName);
    }

    public static boolean isOptionalFunction(String functionName) {
        return OPTIONAL_FUNCTIONS.contains(functionName);
    }

    /**
     * 检查指定LLM配置是否被功能位分配引用
     */
    public boolean isConfigReferenced(Long configId) {
        return assignmentMapper.selectAll().stream()
                .filter(a -> isManagedFunction(a.getFunctionName()))
                .anyMatch(a -> configId.equals(a.getConfigId()));
    }

    /** 功能位指向的配置行（指针悬空、缺模型名都在这儿拦） */
    private AiRuntimeConfig configOf(AiModelAssignment assignment) {
        AiRuntimeConfig config = configMapper.selectById(assignment.getConfigId());
        if (config == null) {
            throw new IllegalStateException(assignment.getFunctionName() + "引用的LLM配置不存在(id=" + assignment.getConfigId() + ")");
        }
        // 模型名归属配置本身（一条配置=一个具体LLM），功能位只是指针
        if (config.getModel() == null || config.getModel().isBlank()) {
            throw new IllegalStateException(assignment.getFunctionName() + "所选LLM配置'" + config.getConfigName() + "'缺模型名，请在Admin页完善");
        }
        return config;
    }

    /** 按配置手建模型，建法与 BYOK 同一份。平台轨没有搜索配置位：webSearch 恒关（服务端搜索是对话侧的能力） */
    private ChatModel build(Spec spec) {
        return modelBuilder.build(spec.protocol(), spec.baseUrl(), spec.apiKey(), spec.model(), spec.effort(), false);
    }
}
