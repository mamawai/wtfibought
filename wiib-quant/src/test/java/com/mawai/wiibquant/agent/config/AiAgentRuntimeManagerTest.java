package com.mawai.wiibquant.agent.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.mawai.wiibquant.agent.behavior.BehaviorAgentFactory;
import com.mawai.wiibquant.agent.quant.QuantGraphFactory;
import com.mawai.wiibquant.mapper.AiModelAssignmentMapper;
import com.mawai.wiibquant.mapper.AiRuntimeConfigMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.openai.OpenAiChatModel;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAgentRuntimeManagerTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void initialStateUsesMethodSymbolOverExtraState() throws Exception {
        CompiledGraph primaryGraph = mock(CompiledGraph.class);
        when(primaryGraph.invoke(anyMap(), any(RunnableConfig.class)))
                .thenReturn(Optional.of(new OverAllState(Map.of())));
        AiAgentRuntimeManager manager = newManager();
        setField(manager, "quantGraph", primaryGraph);

        manager.invokeQuantWithFallback("BTCUSDT", "thread-1",
                Map.of("target_symbol", "ETHUSDT", "forecast_source", "test"));

        ArgumentCaptor<Map> stateCaptor = ArgumentCaptor.forClass(Map.class);
        verify(primaryGraph).invoke(stateCaptor.capture(), any(RunnableConfig.class));
        assertThat(stateCaptor.getValue())
                .containsEntry("target_symbol", "BTCUSDT")
                .containsEntry("forecast_source", "test");
    }

    private static AiAgentRuntimeManager newManager() {
        return new AiAgentRuntimeManager(
                mock(BehaviorAgentFactory.class),
                mock(QuantGraphFactory.class),
                mock(AiRuntimeConfigMapper.class),
                mock(AiModelAssignmentMapper.class),
                mock(OpenAiChatModel.class),
                "key",
                "base",
                "model");
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
