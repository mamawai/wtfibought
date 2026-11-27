package com.mawai.wiibagent.runtime;

import com.mawai.wiibagent.llm.ByokModelBuilder;
import com.mawai.wiibcommon.constant.AiFunctions;
import com.mawai.wiibcommon.entity.AiModelAssignment;
import com.mawai.wiibcommon.entity.AiRuntimeConfig;
import com.mawai.wiibcommon.mapper.AiModelAssignmentMapper;
import com.mawai.wiibcommon.mapper.AiRuntimeConfigMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 快讯翻译主备位：每次取都现查库，配置没变复用模型、变了重建；「快讯翻译」没选 LLM 就不翻译；
 * 备用可空，选了就排在主位后面。
 */
class AiAgentRuntimeManagerTest {

    private final AiRuntimeConfigMapper configMapper = mock(AiRuntimeConfigMapper.class);
    private final AiModelAssignmentMapper assignmentMapper = mock(AiModelAssignmentMapper.class);
    private final ByokModelBuilder modelBuilder = mock(ByokModelBuilder.class);
    private final AiAgentRuntimeManager manager =
            new AiAgentRuntimeManager(configMapper, assignmentMapper, modelBuilder);

    private static AiRuntimeConfig config(long id, String model) {
        AiRuntimeConfig c = new AiRuntimeConfig();
        c.setId(id);
        c.setConfigName("cfg" + id);
        c.setApiKey("sk");
        c.setBaseUrl("http://x");
        c.setModel(model);
        return c;
    }

    private static AiModelAssignment assign(String fn, long configId) {
        AiModelAssignment a = new AiModelAssignment();
        a.setFunctionName(fn);
        a.setConfigId(configId);
        return a;
    }

    private void builderReturnsMocks() {
        when(modelBuilder.build(any(), anyString(), anyString(), anyString(), any(), anyBoolean()))
                .thenAnswer(inv -> mock(ChatModel.class));
    }

    /** 「快讯翻译」选了 configId 那条，库里有这些配置 */
    private void primaryIs(long configId, AiRuntimeConfig... configs) {
        when(assignmentMapper.selectByFunction(AiFunctions.NEWS_TRANSLATION))
                .thenReturn(assign(AiFunctions.NEWS_TRANSLATION, configId));
        for (AiRuntimeConfig c : configs) {
            when(configMapper.selectById(c.getId())).thenReturn(c);
        }
    }

    @Test
    void 只选主位时翻译串只有主位() {
        builderReturnsMocks();
        primaryIs(1, config(1, "grok"));

        assertThat(manager.current().newsTranslation())
                .extracting(AiAgentRuntime.NamedModel::name).containsExactly("grok");
    }

    @Test
    void 选了备用排在主位后面() {
        builderReturnsMocks();
        primaryIs(1, config(1, "grok"), config(2, "deepseek"));
        when(assignmentMapper.selectByFunction(AiFunctions.NEWS_TRANSLATION_FALLBACK))
                .thenReturn(assign(AiFunctions.NEWS_TRANSLATION_FALLBACK, 2));

        assertThat(manager.current().newsTranslation())
                .extracting(AiAgentRuntime.NamedModel::name).containsExactly("grok", "deepseek");
    }

    @Test
    void 配置没变复用同一个模型_改了就重建() {
        builderReturnsMocks();
        primaryIs(1, config(1, "grok"));
        ChatModel first = manager.current().newsTranslation().getFirst().chatModel();
        assertThat(manager.current().newsTranslation().getFirst().chatModel()).isSameAs(first);

        AiRuntimeConfig newKey = config(1, "grok");
        newKey.setApiKey("sk-new");
        when(configMapper.selectById(1L)).thenReturn(newKey);

        assertThat(manager.current().newsTranslation().getFirst().chatModel()).isNotSameAs(first);
        verify(modelBuilder, times(2)).build(any(), anyString(), anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void 快讯翻译没选LLM时不翻译_备用选了也不用_管理页校验算通过() {
        when(assignmentMapper.selectByFunction(AiFunctions.NEWS_TRANSLATION_FALLBACK))
                .thenReturn(assign(AiFunctions.NEWS_TRANSLATION_FALLBACK, 2));

        assertThat(manager.current().newsTranslation()).isEmpty();
        assertThat(manager.check()).isTrue();
        verify(modelBuilder, never()).build(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void 备用位选的配置缺模型名时取不到_管理页校验报失败() {
        builderReturnsMocks();
        primaryIs(1, config(1, "grok"), config(2, " "));
        when(assignmentMapper.selectByFunction(AiFunctions.NEWS_TRANSLATION_FALLBACK))
                .thenReturn(assign(AiFunctions.NEWS_TRANSLATION_FALLBACK, 2));

        assertThatThrownBy(manager::current).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺模型名");
        assertThat(manager.check()).isFalse();
    }

    @Test
    void 备用位引用的配置也不许删() {
        when(assignmentMapper.selectAll()).thenReturn(List.of(
                assign(AiFunctions.NEWS_TRANSLATION, 1), assign(AiFunctions.NEWS_TRANSLATION_FALLBACK, 2)));

        assertThat(manager.isConfigReferenced(2L)).isTrue();
        assertThat(manager.isConfigReferenced(3L)).isFalse();
    }

    @Test
    void 只有备用位可空() {
        assertThat(AiAgentRuntimeManager.isOptionalFunction(AiFunctions.NEWS_TRANSLATION_FALLBACK)).isTrue();
        assertThat(AiAgentRuntimeManager.isOptionalFunction(AiFunctions.NEWS_TRANSLATION)).isFalse();
    }
}
