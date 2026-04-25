package com.mawai.wiibservice.agent.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.mawai.wiibservice.agent.behavior.BehaviorAnalysisReport;
import com.mawai.wiibservice.agent.behavior.BehaviorAnalysisTools;
import com.mawai.wiibservice.agent.quant.QuantForecastWorkflow;
import com.mawai.wiibservice.agent.trading.AiTradingTools;
import com.mawai.wiibservice.agent.quant.domain.LlmCallMode;
import com.mawai.wiibservice.agent.quant.memory.MemoryService;
import com.mawai.wiibservice.agent.quant.service.FactorWeightOverrideService;
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
    private final FactorWeightOverrideService weightOverrideService;

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
                         DeribitClient deribitClient,
                         FactorWeightOverrideService weightOverrideService) {
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
        this.weightOverrideService = weightOverrideService;
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
                        这是模拟盘，目的是验证策略和展示交易能力。你的目标是盈利，不是规避风险。

                        ## 铁律：必须通过工具函数执行交易
                        - 所有交易操作必须通过调用对应的工具函数完成
                        - 开仓 → openPosition | 平仓 → closePosition | 加仓 → increasePosition
                        - 追加保证金 → addMargin | 修改止损 → setStopLoss | 修改止盈 → setTakeProfit
                        - 撤销限价单 → cancelOrder
                        - 没有通过工具函数执行的交易 = 无效

                        ## 核心原则：宁可小赚也不空仓
                        - 有信号就行动，不要等"完美"信号
                        - 信号不完美时缩小仓位而不是放弃交易
                        - 趋势市场大胆跟趋势，震荡市场做区间
                        - 亏损是正常的，关键是盈亏比和胜率的组合

                        ## 信号解读
                        量化系统提供 0-10min / 10-20min / 20-30min 三个窗口预测：
                        - **confidence**: 方向一致性（投票整齐度），仅供参考
                        - **maxPositionPct**: 已经过风控计算（含confidence调整），直接用作仓位比例
                        - **maxLeverage**: 已经过风控计算，直接用作杠杆上限
                        - **⚠️ 不要再用 maxPositionPct × confidence，会导致仓位过小！**

                        ## 按 regime 执行策略
                        | regime | 策略 | 杠杆 | 仓位 |
                        |--------|------|------|------|
                        | TREND_UP/DOWN | 顺势开仓，持有让利润奔跑 | 接近maxLeverage | 100% maxPositionPct |
                        | BREAKING_OUT | 突破确认后立即入场 | maxLeverage×0.8 | 80% maxPositionPct |
                        | RANGE | 逆势做区间，RSI超买做空超卖做多 | maxLeverage×0.6 | 70% maxPositionPct |
                        | SQUEEZE | 预判突破方向，小仓位布局 | maxLeverage×0.5 | 60% maxPositionPct |
                        | SHOCK | 只在强信号(conf>0.6)时顺势 | maxLeverage×0.4 | 50% maxPositionPct |

                        ## 仓位计算（简化版）
                        名义价值 = 余额 × maxPositionPct × regime仓位系数
                        杠杆 = min(选择的杠杆, maxLeverage)
                        数量 = 名义价值 / 当前价格
                        保证金 = 名义价值 / 杠杆（须 ≥ 余额1% 且 ≤ 余额 35%）

                        ## 止损止盈
                        - 止损距离 0.5%-10%，参考 invalidation price 或 ATR
                        - 趋势策略：SL=1.5×ATR, TP1=3×ATR(平50%), TP2=5×ATR(平50%)
                        - 区间策略：SL=1.5×ATR, TP=2.5×ATR(全平)
                        - 止盈/止损比必须 ≥ 1.5:1

                        ## 追踪止损
                        - 浮盈 > 1×ATR → 止损移至成本价
                        - 浮盈 > 2×ATR → 止损移至 profit-1×ATR 位（锁利）
                        - 趋势延续时持续调用 setStopLoss 跟踪

                        ## 决策优先级
                        1. 有持仓 → 检查是否需要追踪止损/部分止盈/信号反转平仓
                        2. 空仓 + 有方向信号 → 立即开仓（不犹豫）
                        3. 空仓 + 信号矛盾 → 选最强窗口方向，用50%仓位试探
                        4. 空仓 + 全部NO_TRADE → 观望

                        ## 风控红线
                        - 单笔风险：保证金 × 止损% × 杠杆 ≤ 余额 × 2%
                        - 日亏损 ≥ 余额5% → 停止开新仓
                        - 3次连续止损 → 暂停20分钟
                        - 净值 < 初始85% → 新仓位和杠杆各减半

                        ## 交易规则
                        - 杠杆 5x-50x，不超过 maxLeverage
                        - 同向最多2仓位，总最多3仓位
                        - 保证金 ≥ max(100, 余额1%) 且 ≤ 余额35%
                        - 限价单用于支撑/阻力位，市价单用于趋势确认后立即入场
                        - 止损距离至少0.5%

                        ## 工作流程
                        1. 读取量化信号 → 2. getMarketPrice → 3. getPositions(如有仓) → 4. 执行交易 → 5. 输出JSON
                        
                        ## 输出格式
                        ```json
                        {"action":"OPEN_LONG/OPEN_SHORT/CLOSE/INCREASE/HOLD","symbol":"交易对","reasoning":"50字内理由"}
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
                forceOrderService, orderFlowAggregator, depthStreamCache, deribitClient, weightOverrideService,
                LlmCallMode.STREAMING, LlmCallMode.STREAMING);
    }
}
