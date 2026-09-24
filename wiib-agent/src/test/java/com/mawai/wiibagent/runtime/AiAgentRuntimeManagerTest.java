package com.mawai.wiibagent.runtime;

import com.mawai.wiibagent.llm.ByokModelBuilder;
import com.mawai.wiibcommon.constant.AiFunctions;
import com.mawai.wiibcommon.entity.AiModelAssignment;
import com.mawai.wiibcommon.entity.AiRuntimeConfig;
import com.mawai.wiibcommon.mapper.AiModelAssignmentMapper;
import com.mawai.wiibcommon.mapper.AiRuntimeConfigMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 快讯翻译主备位：主位必配、缺了自动种子；备用可空、不种子，配了就排在主位后面。
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

    @Test
    void 只配主位时翻译串只有主位() {
        builderReturnsMocks();
        when(configMapper.selectAllConfigs()).thenReturn(List.of(config(1, "grok")));
        when(assignmentMapper.selectAll()).thenReturn(List.of(assign(AiFunctions.NEWS_TRANSLATION, 1)));

        assertThat(manager.refresh()).isTrue();

        assertThat(manager.current().newsTranslation())
                .extracting(AiAgentRuntime.NamedModel::name).containsExactly("grok");
    }

    @Test
    void 配了备用排在主位后面() {
        builderReturnsMocks();
        when(configMapper.selectAllConfigs()).thenReturn(List.of(config(1, "grok"), config(2, "deepseek")));
        when(assignmentMapper.selectAll()).thenReturn(List.of(
                assign(AiFunctions.NEWS_TRANSLATION_FALLBACK, 2), assign(AiFunctions.NEWS_TRANSLATION, 1)));

        assertThat(manager.refresh()).isTrue();

        assertThat(manager.current().newsTranslation())
                .extracting(AiAgentRuntime.NamedModel::name).containsExactly("grok", "deepseek");
    }

    @Test
    void 种子只补主位不补备用() {
        builderReturnsMocks();
        when(configMapper.selectAllConfigs()).thenReturn(List.of(config(1, "grok")));
        // 第一次是种子前查，第二次是建模时查
        when(assignmentMapper.selectAll()).thenReturn(List.of(), List.of(assign(AiFunctions.NEWS_TRANSLATION, 1)));

        manager.refresh();

        ArgumentCaptor<AiModelAssignment> seeded = ArgumentCaptor.forClass(AiModelAssignment.class);
        verify(assignmentMapper, times(1)).insert(seeded.capture());
        assertThat(seeded.getValue().getFunctionName()).isEqualTo(AiFunctions.NEWS_TRANSLATION);
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

    @Test
    void 备用位配的配置缺模型名时刷新失败沿用旧运行时() {
        builderReturnsMocks();
        when(configMapper.selectAllConfigs()).thenReturn(List.of(config(1, "grok")));
        when(assignmentMapper.selectAll()).thenReturn(List.of(assign(AiFunctions.NEWS_TRANSLATION, 1)));
        manager.refresh();

        when(configMapper.selectAllConfigs()).thenReturn(List.of(config(1, "grok"), config(2, " ")));
        when(assignmentMapper.selectAll()).thenReturn(List.of(
                assign(AiFunctions.NEWS_TRANSLATION, 1), assign(AiFunctions.NEWS_TRANSLATION_FALLBACK, 2)));

        assertThat(manager.refresh()).isFalse();
        assertThat(manager.current().newsTranslation())
                .extracting(AiAgentRuntime.NamedModel::name).containsExactly("grok");
    }
}
