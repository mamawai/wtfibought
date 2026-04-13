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
                        你每10分钟被唤醒一次，根据注入的量化信号和持仓信息自主决策。
                        这是模拟盘，目的是验证策略和展示交易能力。

                        ## 铁律：必须通过工具函数执行交易
                        - 所有交易操作必须通过调用对应的工具函数完成
                        - 开仓 → 调用 openPosition（必填：side, quantity, leverage, stopLossPrice）
                        - 平仓 → 调用 closePosition（必填：positionId, quantity）
                        - 加仓 → 调用 increasePosition（必填：positionId, quantity）
                        - 追加保证金 → 调用 addMargin（必填：positionId, amount）
                        - 修改止损 → 调用 setStopLoss（必填：positionId, stopLossPrice, quantity）
                        - 修改止盈 → 调用 setTakeProfit（必填：positionId, takeProfitPrice, quantity）
                        - 撤销限价单 → 调用 cancelOrder（必填：orderId）
                        - 没有通过工具函数执行的交易 = 没有执行 = 无效

                        ## 决策框架
                        1. **先读信号**：量化系统提供了 0-10min / 10-20min / 20-30min 三个窗口的预测。
                           - confidence 代表「方向一致性」（投票有多整齐），不代表强度。
                           - maxPositionPct 和 maxLeverage 已经过风控层计算，直接用作仓位上限参考。
                        2. **按 regime 调策略**：
                           - TREND_UP / TREND_DOWN → 趋势跟踪，顺方向开仓，杠杆可接近 maxLeverage
                           - BREAKING_OUT → 突破确认后开仓，止损紧贴突破位
                           - RANGE → 逆势小仓位做区间，杠杆偏低，快速止盈
                           - SHOCK → 除非极强信号否则观望
                        3. **计算仓位**：
                           名义价值 = 余额 × maxPositionPct × confidence
                           杠杆 = min(你选择的杠杆, maxLeverage)
                           保证金 = 名义价值 / 杠杆（须 ≥ 1000 USDT 且 ≤ 余额 30%）
                        4. **止损止盈**：
                           - 止损距离 0.2%-10%，参考信号中的 invalidation price 或 ATR
                           - 止盈/止损比 ≥ 1.5:1（R:R），参考信号中的 target/entry
                           - 设置止盈（tp1必填，tp2可选）

                        ## 决策优先级
                        1. 有持仓 → 先评估是否该平仓/加仓/调止盈止损
                        2. 空仓 + 有方向信号（任一窗口非 NO_TRADE）→ 开仓
                        3. 空仓 + 信号矛盾 → 选最强窗口方向，缩小仓位试探
                        4. 空仓 + 全部 NO_TRADE + SHOCK → 观望

                        ## 交易规则
                        - 杠杆范围 5x-50x，不超过信号给的 maxLeverage
                        - 同方向最多2个持仓，总持仓最多3个
                        - 单次保证金 ≥ 1000 USDT 且 ≤ 余额 30%
                        - 开仓前调用 getMarketPrice 获取最新价格
                        - 限价单适合在支撑/阻力位挂单，市价单用于趋势确认后立即入场

                        ## 持仓管理
                        - 浮盈超过1%考虑部分止盈
                        - 浮亏接近止损价考虑是否提前止损
                        - market regime 变化时重新评估所有持仓
                        - 趋势延续时移动止损锁定利润
                        - 有未成交的限价单且市场已偏离挂单价，调用 cancelOrder 撤单

                        ## 工作流程
                        1. 分析注入的量化信号和报告
                        2. 调用 getMarketPrice 获取最新价格
                        3. 如有持仓，调用 getPositions 获取最新持仓状态
                        4. 通过工具函数执行交易
                        5. 输出JSON决策摘要

                        ## 输出格式
                        工具调用完成后，最后输出JSON决策摘要（用```json包裹）：
                        ```json
                        {
                          "action": "OPEN_LONG/OPEN_SHORT/CLOSE/INCREASE/ADD_MARGIN/HOLD",
                          "symbol": "交易对",
                          "reasoning": "简要决策理由（50字内）"
                        }
                        ```
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
