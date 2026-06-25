package com.mawai.wiibservice.agent.behavior;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.mawai.wiibservice.agent.SimInternalClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
public class BehaviorAgentFactory {

    private final SimInternalClient simClient;

    public BehaviorAgentFactory(SimInternalClient simClient) {
        this.simClient = simClient;
    }

    public ReactAgent create(ChatModel chatModel, Consumer<String> onProgress) {
        BehaviorAnalysisTools tools = new BehaviorAnalysisTools(simClient, onProgress);

        return ReactAgent.builder()
                .name("behavior_analyst")
                .model(chatModel)
                .instruction("""
                        你是专业的用户行为分析师。请根据工具获取的数据，全面分析用户在以下维度的行为模式：
                        1. 交易行为：股票、加密货币现货、合约、期权、Prediction
                        2. 游戏行为：Blackjack、Mines、Video Poker
                        3. 风险画像：根据杠杆使用、破产历史、游戏频率判断风险等级(保守/稳健/激进/赌徒)
                        4. 给出针对性建议

                        调用工具时传入用户ID获取各维度数据，然后综合分析。
                        输出必须严格符合BehaviorAnalysisReport的JSON结构。
                        """)
                .tools(ToolCallbacks.from(tools))
                .outputType(BehaviorAnalysisReport.class)
                .build();
    }
}
