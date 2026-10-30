package com.mawai.wiibagent.controller;

import com.mawai.wiibagent.jev.JevPlatformConfig;
import com.mawai.wiibagent.jev.predictor.JevPredictionRuns;
import com.mawai.wiibagent.jev.predictor.JevPredictionSwitch;
import com.mawai.wiibagent.llm.ByokModelBuilder;
import com.mawai.wiibagent.news.NewsEventCollector;
import com.mawai.wiibagent.runtime.AiAgentRuntimeManager;
import com.mawai.wiibcommon.entity.AiRuntimeConfig;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.mapper.AiModelAssignmentMapper;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.mapper.AiRuntimeConfigMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台 LLM 配置的 key：接口只回尾 4 位；改已有配置 key 留空=沿用已存的，但 baseUrl 换了主机要重填
 */
class AiAgentAdminControllerTest {

    private final AiAgentRuntimeManager runtime = mock(AiAgentRuntimeManager.class);
    private final AiRuntimeConfigMapper configMapper = mock(AiRuntimeConfigMapper.class);
    private final ByokModelBuilder builder = mock(ByokModelBuilder.class);
    private final AiAgentAdminController controller = new AiAgentAdminController(runtime, configMapper,
            mock(AiModelAssignmentMapper.class), builder, mock(NewsEventCollector.class), mock(JevPlatformConfig.class),
            mock(JevPredictionSwitch.class), mock(JevPredictionRuns.class), new MessageCatalog(),
            mock(BinanceRestClient.class));

    private static AiRuntimeConfig saved() {
        AiRuntimeConfig c = new AiRuntimeConfig();
        c.setId(1L);
        c.setConfigName("cpa");
        c.setApiKey("sk-platform-1234");
        c.setBaseUrl("http://cliproxyapi:8317");
        c.setModel("m");
        c.setApiProtocol("openai");
        return c;
    }

    private static AiAgentAdminController.KeyRequest req(Long id, String baseUrl, String apiKey) {
        AiAgentAdminController.KeyRequest r = new AiAgentAdminController.KeyRequest();
        r.setId(id);
        r.setConfigName("cpa");
        r.setBaseUrl(baseUrl);
        r.setApiKey(apiKey);
        r.setModel("m2");
        r.setApiProtocol("openai");
        return r;
    }

    @Test
    void 列表只给尾4位() {
        when(configMapper.selectAllConfigs()).thenReturn(List.of(saved()));

        assertThat(controller.listKeys().getData()).singleElement()
                .satisfies(v -> assertThat(v.apiKeyTail()).isEqualTo("1234"));
    }

    @Test
    void 改配置key留空沿用已存的_回执只给尾4位() {
        when(configMapper.selectById(1L)).thenReturn(saved());
        when(runtime.refresh()).thenReturn(true);

        assertThat(controller.saveKey(req(1L, "http://cliproxyapi:8317/", "")).getData().apiKeyTail()).isEqualTo("1234");

        ArgumentCaptor<AiRuntimeConfig> cap = ArgumentCaptor.forClass(AiRuntimeConfig.class);
        verify(configMapper).updateById(cap.capture());
        assertThat(cap.getValue().getApiKey()).isEqualTo("sk-platform-1234");
        assertThat(cap.getValue().getModel()).isEqualTo("m2");
    }

    @Test
    void 换了主机或新建时key留空要求重填() {
        when(configMapper.selectById(1L)).thenReturn(saved());
        AiAgentAdminController.KeyRequest moved = req(1L, "https://evil.example", "");

        assertThat(controller.saveKey(moved).getMsg()).contains("重新填");
        assertThat(controller.testKey(moved).getMsg()).contains("重新填");
        assertThat(controller.listModels(moved).getMsg()).contains("重新填");
        assertThat(controller.saveKey(req(null, "http://cliproxyapi:8317", "")).getMsg()).isEqualTo("apiKey不能为空");
        verify(configMapper, never()).updateById(any(AiRuntimeConfig.class));
        verify(configMapper, never()).insert(any(AiRuntimeConfig.class));
        verify(builder, never()).listModels(any(), any(), any());
    }

    @Test
    void 测连通key留空用已存的() {
        when(configMapper.selectById(1L)).thenReturn(saved());
        when(builder.build(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(mock(ChatModel.class));

        assertThat(controller.testKey(req(1L, "http://cliproxyapi:8317", "")).getCode()).isZero();
        verify(builder).build(eq("openai"), eq("http://cliproxyapi:8317"), eq("sk-platform-1234"), eq("m2"), any(), eq(false));
    }
}
