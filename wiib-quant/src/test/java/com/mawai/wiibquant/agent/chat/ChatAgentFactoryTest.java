package com.mawai.wiibquant.agent.chat;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.mawai.wiibquant.agent.config.AiAgentRuntime;
import com.mawai.wiibquant.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import com.mawai.wiibquant.agent.toolkit.NewsToolkit;
import com.mawai.wiibquant.agent.toolkit.QuantForecastToolkit;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAgentFactoryTest {

    private final AiAgentRuntimeManager runtimeManager = mock(AiAgentRuntimeManager.class);

    private ChatAgentFactory factory() {
        when(runtimeManager.current()).thenReturn(new AiAgentRuntime(
                mock(ChatModel.class), mock(ChatModel.class), mock(ChatModel.class), mock(ChatModel.class)));
        return new ChatAgentFactory(runtimeManager,
                mock(MarketToolkit.class), mock(QuantForecastToolkit.class), mock(NewsToolkit.class),
                mock(BaseCheckpointSaver.class), 12);
    }

    @Test
    void buildsSupervisorGraphWithThreeExperts() throws Exception {
        CompiledGraph graph = factory().chatGraph();

        assertThat(graph).isNotNull();
        // Supervisor 编排图里三个专家 agent 都是节点（Mermaid 出图顺带验证——P8 复用同 API 进 README）
        String mermaid = graph.stateGraph.getGraph(
                com.alibaba.cloud.ai.graph.GraphRepresentation.Type.MERMAID, "workbench").content();
        assertThat(mermaid).contains("market_agent").contains("quant_agent").contains("news_agent");
    }

    @Test
    void cachesGraphAndRebuildsOnRuntimeRefresh() throws Exception {
        ChatAgentFactory factory = factory();

        CompiledGraph first = factory.chatGraph();
        CompiledGraph second = factory.chatGraph();
        assertThat(second).isSameAs(first); // 单例缓存

        factory.onRuntimeRefreshed(); // 模型热更事件 → 缓存失效
        CompiledGraph third = factory.chatGraph();
        assertThat(third).isNotSameAs(first);
    }
}
