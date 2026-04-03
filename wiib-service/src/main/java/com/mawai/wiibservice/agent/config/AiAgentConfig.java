package com.mawai.wiibservice.agent.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.mawai.wiibservice.agent.behavior.BehaviorAnalysisReport;
import com.mawai.wiibservice.agent.behavior.BehaviorAnalysisTools;
import com.mawai.wiibservice.agent.quant.QuantForecastWorkflow;
import com.mawai.wiibservice.agent.quant.domain.LlmCallMode;
import com.mawai.wiibservice.agent.quant.memory.MemoryService;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.mapper.*;
import com.mawai.wiibservice.service.CryptoPositionService;
import com.mawai.wiibservice.service.ForceOrderService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

@Configuration
public class AiAgentConfig {

    private final ChatModel chatModel;
    private final UserMapper userMapper;
    private final UserAssetSnapshotMapper snapshotMapper;
    private final PositionMapper positionMapper;
    private final OrderMapper orderMapper;
    private final CryptoOrderMapper cryptoOrderMapper;
    private final CryptoPositionService cryptoPositionService;
    private final FuturesOrderMapper futuresOrderMapper;
    private final FuturesPositionMapper futuresPositionMapper;
    private final OptionOrderMapper optionOrderMapper;
    private final PredictionBetMapper predictionBetMapper;
    private final BlackjackAccountMapper blackjackAccountMapper;
    private final MinesGameMapper minesGameMapper;
    private final VideoPokerGameMapper videoPokerGameMapper;
    private final BinanceRestClient binanceRestClient;
    private final MemoryService memoryService;
    private final ForceOrderService forceOrderService;

    public AiAgentConfig(ChatModel chatModel,
                         UserMapper userMapper,
                         UserAssetSnapshotMapper snapshotMapper,
                         PositionMapper positionMapper,
                         OrderMapper orderMapper,
                         CryptoOrderMapper cryptoOrderMapper,
                         CryptoPositionService cryptoPositionService,
                         FuturesOrderMapper futuresOrderMapper,
                         FuturesPositionMapper futuresPositionMapper,
                         OptionOrderMapper optionOrderMapper,
                         PredictionBetMapper predictionBetMapper,
                         BlackjackAccountMapper blackjackAccountMapper,
                         MinesGameMapper minesGameMapper,
                         VideoPokerGameMapper videoPokerGameMapper,
                         BinanceRestClient binanceRestClient,
                         MemoryService memoryService,
                         ForceOrderService forceOrderService) {
        this.chatModel = chatModel;
        this.userMapper = userMapper;
        this.snapshotMapper = snapshotMapper;
        this.positionMapper = positionMapper;
        this.orderMapper = orderMapper;
        this.cryptoOrderMapper = cryptoOrderMapper;
        this.cryptoPositionService = cryptoPositionService;
        this.futuresOrderMapper = futuresOrderMapper;
        this.futuresPositionMapper = futuresPositionMapper;
        this.optionOrderMapper = optionOrderMapper;
        this.predictionBetMapper = predictionBetMapper;
        this.blackjackAccountMapper = blackjackAccountMapper;
        this.minesGameMapper = minesGameMapper;
        this.videoPokerGameMapper = videoPokerGameMapper;
        this.binanceRestClient = binanceRestClient;
        this.memoryService = memoryService;
        this.forceOrderService = forceOrderService;
    }

    public ReactAgent createBehaviorAgent(Consumer<String> onProgress) {
        BehaviorAnalysisTools tools = new BehaviorAnalysisTools(
                userMapper, snapshotMapper, positionMapper, orderMapper,
                cryptoOrderMapper, cryptoPositionService, futuresOrderMapper,
                futuresPositionMapper, optionOrderMapper, predictionBetMapper,
                blackjackAccountMapper, minesGameMapper, videoPokerGameMapper, onProgress);

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

    @Bean
    public CompiledGraph cryptoAnalysisGraph() throws Exception {
        // 当前深/浅指向同一模型，未来可配置不同模型
        ChatClient.Builder deepClient = ChatClient.builder(chatModel);
        ChatClient.Builder shallowClient = ChatClient.builder(chatModel);
        return QuantForecastWorkflow.build(deepClient, shallowClient, binanceRestClient, memoryService,
                forceOrderService, LlmCallMode.STREAMING, LlmCallMode.STREAMING);
    }
}
