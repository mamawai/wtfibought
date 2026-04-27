package com.mawai.wiibservice.agent.behavior;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.mawai.wiibservice.mapper.BlackjackAccountMapper;
import com.mawai.wiibservice.mapper.CryptoOrderMapper;
import com.mawai.wiibservice.mapper.FuturesOrderMapper;
import com.mawai.wiibservice.mapper.FuturesPositionMapper;
import com.mawai.wiibservice.mapper.MinesGameMapper;
import com.mawai.wiibservice.mapper.OptionOrderMapper;
import com.mawai.wiibservice.mapper.OrderMapper;
import com.mawai.wiibservice.mapper.PositionMapper;
import com.mawai.wiibservice.mapper.PredictionBetMapper;
import com.mawai.wiibservice.mapper.UserAssetSnapshotMapper;
import com.mawai.wiibservice.mapper.UserMapper;
import com.mawai.wiibservice.mapper.VideoPokerGameMapper;
import com.mawai.wiibservice.service.CryptoPositionService;
import com.mawai.wiibservice.service.UserService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
public class BehaviorAgentFactory {

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

    public BehaviorAgentFactory(UserMapper userMapper,
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
                                UserService userService) {
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
    }

    public ReactAgent create(ChatModel chatModel, Consumer<String> onProgress) {
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
}
