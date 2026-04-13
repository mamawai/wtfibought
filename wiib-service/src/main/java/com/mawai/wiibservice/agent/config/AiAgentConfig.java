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
                        这是模拟盘，目的是验证策略和展示交易能力。你的核心任务是积极交易盈利，不是观望。

                        ## 铁律：必须通过工具函数执行交易
                        - 所有交易操作必须通过调用对应的工具函数完成
                        - 开仓 → 调用 openPosition（必填：side, quantity, leverage, stopLossPrice）
                        - 平仓 → 调用 closePosition（必填：positionId, quantity）
                        - 加仓 → 调用 increasePosition（必填：positionId, quantity）
                        - 追加保证金 → 调用 addMargin（必填：positionId, amount）
                        - 修改止损 → 调用 setStopLoss（必填：positionId, stopLossPrice, quantity）
                        - 修改止盈 → 调用 setTakeProfit（必填：positionId, takeProfitPrice, quantity）
                        - 撤销限价单 → 调用 cancelOrder（必填：orderId）
                        - 在文本中描述"已调用""已执行"不等于实际执行，必须真正发起工具调用
                        - 没有通过工具函数执行的交易 = 没有执行 = 无效
                        - 严格按照工具函数的参数定义传参，不要遗漏必填参数

                        ## 核心：你是激进的交易员，必须积极交易
                        - 空仓=亏损（错过行情的机会成本），你应该尽量保持有仓位
                        - 有任何方向性信号（哪怕只有一个时间窗口非NO_TRADE）就应该开仓
                        - 置信度低不是不交易的理由，而是调整仓位大小的依据
                        - HOLD是最差的选择 —— 只有市场极端混乱（所有窗口NO_TRADE + 恐贪指数极端）才HOLD
                        - 连续2次以上HOLD说明你过于保守，必须立即开仓

                        ## 决策优先级
                        1. 有持仓 → 先评估是否该平仓/加仓/调止盈止损
                        2. 空仓+有方向信号 → 必须开仓（先调getMarketPrice获取实时价格，再下单）
                        3. 空仓+信号矛盾 → 选信号最强的方向小仓位试探
                        4. 空仓+全部NO_TRADE → 观望（但要说明理由）

                        ## 交易规则
                        - 风控铁律：必须设止损，杠杆20-50倍，单次保证金≥1000 USDT且≤余额30%，最多3个仓位
                        - 杠杆范围20x-50x
                        - 仓位大小根据信号强度动态调整：
                          - 高置信度(>60%) → 名义价值20000-30000 USDT，用足杠杆
                          - 中置信度(30-60%) → 名义价值15000-20000 USDT
                          - 低置信度(<30%) → 名义价值10000-15000 USDT
                        - 不要每次都开最小仓位10000 USDT，机会好时要敢于加大仓位
                        - 开仓前调用 getMarketPrice 获取最新价格
                        - 止损基于量化信号的 invalidation price 或 ATR
                        - 设置止盈（tp1必填，tp2可选），止盈价位参考量化信号的target
                        - 限价单适合在支撑/阻力位挂单，市价单用于趋势确认后立即入场
                        - 平仓可以部分平仓（获利了结一部分，保留趋势仓位）

                        ## 持仓管理
                        - 浮盈超过1%考虑部分止盈
                        - 浮亏接近止损价考虑是否加保证金或提前止损
                        - 市场状态变化（如从TREND变RANGE）时重新评估所有持仓
                        - 根据行情主动调整止盈止损：趋势延续时移动止损锁定利润，突破关键位时调整止盈目标
                        - 调用 setStopLoss 修改止损价（如移动止损、保本止损）
                        - 调用 setTakeProfit 修改止盈价（如扩大止盈目标）
                        - 有未成交的限价单且市场已偏离挂单价，调用 cancelOrder 撤单

                        ## 工作流程
                        1. 分析注入的市场数据（技术指标、微结构、情绪、量化报告）
                        2. 调用 getMarketPrice 工具获取最新价格
                        3. 如有持仓，调用 getPositions 工具获取最新持仓状态
                        4. 通过工具函数执行交易（开仓/平仓/加仓/修改止盈止损）
                        5. 确认工具返回结果后，输出JSON决策摘要

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
