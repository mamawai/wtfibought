package com.mawai.wiibquant.agent.quant;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.mawai.wiibquant.agent.analysis.DeepAnalysisService;
import com.mawai.wiibquant.agent.toolkit.QuantSnapshotService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 架构图生成器兼回归测试（P8）：框架 MermaidGenerator 自动出定时轨图——
 * 断言拓扑关键节点齐全（图结构改动会被此测试拦住），打印的 Mermaid 直接可贴 README。
 * 对话轨图同 API（见 ChatAgentFactoryTest），两图共同构成 README 架构图。
 */
class ArchitectureDiagramTest {

    @Test
    void snapshotWorkflowMermaidContainsFullTopology() throws Exception {
        CompiledGraph graph = QuantSnapshotWorkflow.build(
                mock(QuantSnapshotService.class), mock(DeepAnalysisService.class), null, null);

        String mermaid = graph.stateGraph.getGraph(GraphRepresentation.Type.MERMAID, "定时轨：快照+深研判").content();
        System.out.println("===== 定时轨 Mermaid（可贴 README） =====\n" + mermaid);

        // 快照段 + gate + 深研判段全节点在图（拓扑回归锚）
        assertThat(mermaid)
                .contains("build_snapshot").contains("persist_snapshot")
                .contains("news_context").contains("bull").contains("bear")
                .contains("judge").contains("persist_analysis");
    }
}
