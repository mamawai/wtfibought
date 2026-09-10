package com.mawai.wiibagent.learning;

import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibquant.market.domain.KlineClosedEvent;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibagent.trader.TraderScheduler;
import com.mawai.wiibagent.trader.TraderWakeupRunner;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 日线交接整链回路（G6 验收的 mock 侧）：K线事件进 → 真调度三阶段 → 真 LearningRunner 跑
 * ReactLoop（mock 模型）→ 真 PeerInsightService 出工具数据 → 3 个 learner 并发学完各自落库。
 * 与分层测试的分工：TraderSchedulerTest 验时序/屏障/窗口，LearningLoopTest 验单人回路，
 * 这里验的是"整条链真对象手拉手 + 多 learner 并发"——装配错、并发共享 mock 模型出乱序，只有这里现形。
 */
class LearningHandoverLoopTest {

    private static final long DAY_BOUNDARY = 1785110400000L; // 2026-07-27 00:00:00 UTC

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
    }

    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);
    private final AiTraderPlanMapper planMapper = mock(AiTraderPlanMapper.class);
    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final ReviewMaterialAssembler assembler = mock(ReviewMaterialAssembler.class);
    private final TraderModelFactory modelFactory = mock(TraderModelFactory.class);
    private final TraderWakeupRunner wakeupRunner = mock(TraderWakeupRunner.class);
    private final ReviewRunner reviewRunner = mock(ReviewRunner.class);

    private static AiTrader trader(long id, String name) {
        AiTrader t = new AiTrader();
        t.setId(id);
        t.setUserId(id);
        t.setName(name);
        t.setStatus(AiTrader.STATUS_RUNNING);
        t.setIntervalCode("1h");
        t.setRoundNo(1);
        t.setSimUserId(id * 10);
        t.setSymbols("BTCUSDT");
        return t;
    }

    private static final String QUALIFIED = """
            【本期学习】
            看了谁：[id=8] 赢家，收益率榜首且 12 笔样本。
            学到什么：1. 他等回踩再进（12笔8胜）→ 我 9 笔 2 胜全是追价 → 改为破位次根确认再进。

            【不学什么】
            1. 他单笔 20 倍杠杆押对方向，样本 1 笔不可复现。

            【前车之鉴】
            1. [id=9] 亏损后加倍摊平 → 爆仓。连亏时我也有加倍冲动，同一个坑。""";

    /**
     * 共享一个 mock 模型给 3 个并发 learner：按"最后一条消息是否工具回执"分派返回，
     * 不能用 thenReturn(a,b) 的调用序——并发交错下序次全乱（这正是本测试要暴露的那类问题）。
     */
    private ChatModel sharedModel() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt p = inv.getArgument(0);
            boolean afterTool = p.getInstructions().get(p.getInstructions().size() - 1)
                    instanceof ToolResponseMessage;
            if (afterTool) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(QUALIFIED))));
            }
            return new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder().content("先深看榜首。")
                            .toolCalls(List.of(new AssistantMessage.ToolCall(
                                    "c1", "function", "peer_insights", "{\"traderId\": 8}")))
                            .build())));
        });
        return model;
    }

    @Test
    void fullHandoverChainLearnsAllTradersConcurrently() {
        AiTrader me = trader(7L, "我");
        AiTrader winner = trader(8L, "赢家");
        AiTrader loser = trader(9L, "输家");
        when(traderMapper.selectList(any())).thenReturn(List.of(me, winner, loser));
        when(traderMapper.selectById(8L)).thenReturn(winner);
        // 权益/复盘行都走空态：整链验证的是协作与并发，硬事实的口径在 PeerInsightServiceTest 已细验
        when(decisionMapper.selectOne(any())).thenReturn(null);
        when(assembler.lastReview(anyLong(), anyInt())).thenReturn(null);
        when(simTradeClient.getClosedPositions(anyLong(), anyInt())).thenReturn(List.of());
        // 三人都挂着仓 → 都在同侪池里，每人除自己外还有 2 个同侪，过门槛
        when(simTradeClient.getAllPositions(anyLong())).thenReturn(List.of(new FuturesPositionDTO()));
        when(planMapper.selectList(any())).thenReturn(List.of());
        // 先建好再 stub：thenReturn 参数里嵌套 when() 是 UnfinishedStubbing
        ChatModel model = sharedModel();
        when(modelFactory.modelFor(any())).thenReturn(model);

        PromptCatalog prompts = new PromptCatalog();
        UserLangResolver langResolver = mock(UserLangResolver.class);
        when(langResolver.of(anyLong())).thenReturn(AgentLang.ZH);
        PeerInsightService peers = new PeerInsightService(
                traderMapper, decisionMapper, planMapper, simTradeClient, assembler, prompts);
        LearningRunner learningRunner = new LearningRunner(peers, modelFactory, traderMapper,
                decisionMapper, prompts, new LocalizedToolCallbacks(prompts), langResolver);
        TraderScheduler scheduler = new TraderScheduler(traderMapper, wakeupRunner, reviewRunner, learningRunner, peers, new MessageCatalog());

        scheduler.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", DAY_BOUNDARY - 1));

        // 3 个 learner 并发各自落一条合格 LEARN 行
        ArgumentCaptor<AiTraderDecision> rows = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper, timeout(10_000).times(3)).insert(rows.capture());
        assertThat(rows.getAllValues())
                .allSatisfy(d -> {
                    assertThat(d.getKind()).isEqualTo(AiTraderDecision.KIND_LEARN);
                    assertThat(d.getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
                    assertThat(d.getWakeTime()).isEqualTo(DAY_BOUNDARY);
                    // 工具轨迹记下了"看了谁"——公开时间线的观赏点，整链断了这里就是空的
                    assertThat(d.getActionsJson()).contains("peer_insights");
                })
                .extracting(AiTraderDecision::getTraderId)
                .containsExactlyInAnyOrder(7L, 8L, 9L);
        // 3 份学习笔记各自覆盖写。必须带 timeout：LEARN 行 insert 在前、笔记 update 在后，
        // 上面的 verify 只等到了 insert，不等的话会撞见"行已落、笔记还没写完"的窗口（偶发红）
        verify(traderMapper, timeout(10_000).times(3)).update(any(), any());
    }
}
