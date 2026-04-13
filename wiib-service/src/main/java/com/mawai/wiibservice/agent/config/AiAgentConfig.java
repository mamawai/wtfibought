package com.mawai.wiibservice.agent.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.mawai.wiibservice.agent.behavior.BehaviorAnalysisReport;
import com.mawai.wiibservice.agent.behavior.BehaviorAnalysisTools;
import com.mawai.wiibservice.agent.quant.QuantForecastWorkflow;
import com.mawai.wiibservice.agent.trading.AiTradingTools;
import com.mawai.wiibservice.agent.quant.domain.LlmCallMode;
import com.mawai.wiibservice.agent.quant.memory.MemoryService;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.config.DeribitClient;
import com.mawai.wiibservice.mapper.*;
import com.mawai.wiibservice.service.CryptoPositionService;
import com.mawai.wiibservice.service.DepthStreamCache;
import com.mawai.wiibservice.service.ForceOrderService;
import com.mawai.wiibservice.service.OrderFlowAggregator;
import com.mawai.wiibservice.service.UserService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
public class AiAgentConfig {

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
    private final UserService userService;
    private final BinanceRestClient binanceRestClient;
    private final MemoryService memoryService;
    private final ForceOrderService forceOrderService;
    private final OrderFlowAggregator orderFlowAggregator;
    private final DepthStreamCache depthStreamCache;
    private final DeribitClient deribitClient;

    public AiAgentConfig(UserMapper userMapper,
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
                         UserService userService,
                         BinanceRestClient binanceRestClient,
                         MemoryService memoryService,
                         ForceOrderService forceOrderService,
                         OrderFlowAggregator orderFlowAggregator,
                         DepthStreamCache depthStreamCache,
                         DeribitClient deribitClient) {
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
        this.userService = userService;
        this.binanceRestClient = binanceRestClient;
        this.memoryService = memoryService;
        this.forceOrderService = forceOrderService;
        this.orderFlowAggregator = orderFlowAggregator;
        this.depthStreamCache = depthStreamCache;
        this.deribitClient = deribitClient;
    }

    public ReactAgent createBehaviorAgent(ChatModel chatModel, Consumer<String> onProgress) {
        BehaviorAnalysisTools tools = new BehaviorAnalysisTools(
                userMapper, snapshotMapper, positionMapper, orderMapper,
                cryptoOrderMapper, cryptoPositionService, futuresOrderMapper,
                futuresPositionMapper, optionOrderMapper, predictionBetMapper,
                blackjackAccountMapper, minesGameMapper, videoPokerGameMapper,
                userService, onProgress);

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

    public ReactAgent createTradingAgent(ChatModel chatModel, AiTradingTools tools) {
        return ReactAgent.builder()
                .name("ai_trader")
                .model(chatModel)
                .instruction("""
                        你是一名永续合约自主交易员，管理一个独立的模拟账户（初始100000 USDT）。
                        你每10分钟被唤醒一次，根据注入的市场数据和持仓信息自主决策。
                        这是模拟盘，目的是验证策略和展示交易能力，不要害怕亏损。

                        ## 决策原则
                        - 有交易方案（方向不是NO_TRADE）且有入场/止损价位时，就应该考虑执行
                        - 置信度低不代表不能交易，用小仓位+低杠杆控制风险即可
                        - 空仓本身也是成本（错过行情），连续3次以上HOLD应主动降低门槛
                        - 风控铁律：必须设止损，杠杆不超过20倍，单次保证金不超过余额30%，最多3个仓位
                        - 已有持仓时关注是否需要止盈/止损/加仓

                        ## 仓位
                        - 根据置信度和市场微观数据自行决定仓位大小，杠杆最低10x，每次开仓名义价值不低于10000 USDT
                        - 置信度高/信号一致时可以加大仓位，不确定时小仓位试探
                        - 只有所有窗口都是NO_TRADE时才完全观望

                        ## 工作流程
                        1. 分析注入的市场数据和量化信号（含交易方案的入场/止损/止盈价位）
                        2. 如需深入了解市场微观结构，调用 getMarketSnapshot 查看恐贪指数、资金费率、爆仓压力、大户持仓等
                        3. 做出决策并执行（开仓/平仓/加仓/观望）
                        4. 开仓时可选择市价(MARKET)或限价(LIMIT)，限价单适合在入场区间边缘挂单
                        5. 输出决策摘要

                        ## 输出要求
                        最后输出JSON决策摘要（用```json包裹）：
                        ```json
                        {
                          "action": "OPEN_LONG/OPEN_SHORT/CLOSE/INCREASE/ADD_MARGIN/HOLD",
                          "symbol": "交易对",
                          "reasoning": "简要决策理由（50字内）"
                        }
                        ```
                        观望则action=HOLD但必须说明具体原因。
                        注意：你每次只为一个交易对做决策，不可操作其他交易对。
                        """)
                .tools(ToolCallbacks.from(tools))
                .build();
    }

    public CompiledGraph createCryptoAnalysisGraph(ChatModel chatModel) throws Exception {
        // 当前深/浅指向同一模型，未来可配置不同模型
        ChatClient.Builder deepClient = ChatClient.builder(chatModel);
        ChatClient.Builder shallowClient = ChatClient.builder(chatModel);
        return QuantForecastWorkflow.build(deepClient, shallowClient, binanceRestClient, memoryService,
                forceOrderService, orderFlowAggregator, depthStreamCache, deribitClient,
                LlmCallMode.STREAMING, LlmCallMode.STREAMING);
    }
}
