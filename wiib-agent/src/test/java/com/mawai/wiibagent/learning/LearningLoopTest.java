package com.mawai.wiibagent.learning;

import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibcommon.enums.AgentLang;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 学习回路 mock 回路测试（纯 mock 无外部依赖，常规套件必跑）：真 PeerInsightService +
 * 真 PeerInsightToolkit + 真 ReactLoop 循环跑一遍——
 * 模型首轮调 peer_insights 深看同侪、拿到真实详情后次轮交终稿，
 * LEARN 行带"它看了谁"的轨迹落库、learning_notes 覆盖写。
 * （LearningRunnerTest 里模型第一轮就交稿，工具那条边没走过；这条缝必须真接一次才算数。）
 */
class LearningLoopTest {

    private static final long BOUNDARY = 1785196800000L; // 2026-07-28 00:00:00 UTC

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderPlan.class);
    }

    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);
    private final AiTraderPlanMapper planMapper = mock(AiTraderPlanMapper.class);
    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final ReviewMaterialAssembler assembler = mock(ReviewMaterialAssembler.class);
    private final TraderModelFactory modelFactory = mock(TraderModelFactory.class);

    private final UserLangResolver langResolver = mock(UserLangResolver.class);
    private final PromptCatalog prompts = new PromptCatalog();

    private final PeerInsightService peers = new PeerInsightService(
            traderMapper, decisionMapper, planMapper, simTradeClient, assembler, prompts);

    private final LearningRunner runner = new LearningRunner(peers,
            modelFactory, traderMapper, decisionMapper, prompts,
            new LocalizedToolCallbacks(prompts), langResolver);

    {
        when(langResolver.of(anyLong())).thenReturn(AgentLang.ZH);
        // 看榜时刻定在边界：赢家 BOUNDARY-1h 的那笔了结落在 24h 窗口内
        peers.nowMs = () -> BOUNDARY;
    }

    private static AiTrader trader(long id, String name) {
        AiTrader t = new AiTrader();
        t.setId(id);
        t.setUserId(id);
        t.setName(name);
        t.setStatus(AiTrader.STATUS_RUNNING);
        t.setRoundNo(1);
        t.setSimUserId(id * 10);
        t.setSymbols("BTCUSDT");
        return t;
    }

    private static LocalDateTime at(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private static AiTraderDecision reviewRow(String reasoning) {
        AiTraderDecision d = new AiTraderDecision();
        d.setWakeTime(BOUNDARY);
        d.setKind(AiTraderDecision.KIND_REVIEW);
        d.setStatus(AiTraderDecision.STATUS_OK);
        d.setReasoning(reasoning);
        return d;
    }

    private static final String QUALIFIED_OUTPUT = """
            【本期学习】
            看了谁：[id=8] 赢家——12 笔样本里 BREAKOUT 占多数，正打在我最亏的那一类上。
            学到什么：他的做法是破位后等回踩站稳再进 → 证据：他最近一笔 100000 进 101500 出，
            论点写明等回踩 → 我的差距：我破位当根就进，9 笔 2 胜 → 我怎么改：等下一根收在破位上方再进。

            【不学什么】
            他 20 倍杠杆押单边，样本 1 笔不可复现，也超出我的杠杆区间。

            【前车之鉴】
            亏损后加仓摊平 → 行情继续反向 → 保证金归零。我连亏两笔时也动过这个念头。""";

    /** 全链路：真排行榜注入 → 模型挑人调工具 → 真 detail 回流 → 终稿落库并覆盖笔记。 */
    @Test
    void fullLoopCallsPeerInsightsThenPersistsLearnRowAndNotes() {
        AiTrader me = trader(7L, "我");
        AiTrader winner = trader(8L, "赢家");
        winner.setLearningNotes("赢家的学习笔记：从别人那儿学会了只在日线趋势方向上做");
        me.setMemory("旧复盘：我的突破单老是追高被扫");
        when(traderMapper.selectList(any())).thenReturn(List.of(me, winner));
        when(traderMapper.selectById(8L)).thenReturn(winner);
        // 权益行：两人都给同一条即可，本用例断言的是详情内容不是收益率（那已由 PeerInsightServiceTest 覆盖）
        AiTraderDecision equity = new AiTraderDecision();
        equity.setWakeTime(BOUNDARY);
        equity.setEquity(new BigDecimal("12000"));
        when(decisionMapper.selectOne(any())).thenReturn(equity);
        when(assembler.lastReview(7L, 1)).thenReturn(reviewRow("【本期复盘】追高又被扫了一次"));
        when(assembler.lastReview(8L, 1)).thenReturn(
                reviewRow("【本期复盘】逐笔教训：破位当根不进，回踩站稳才进场，这是我全部收益的来源"));

        FuturesPositionDTO closed = new FuturesPositionDTO();
        closed.setSymbol("BTCUSDT");
        closed.setSide("LONG");
        closed.setStatus("CLOSED");
        closed.setEntryPrice(new BigDecimal("100000"));
        closed.setClosedPrice(new BigDecimal("101500"));
        closed.setClosedPnl(new BigDecimal("15"));
        closed.setQuantity(new BigDecimal("0.01"));
        closed.setCreatedAt(at(BOUNDARY - 7200_000L));
        closed.setUpdatedAt(at(BOUNDARY - 3600_000L));
        when(simTradeClient.getClosedPositions(eq(70L), anyInt())).thenReturn(List.of());
        when(simTradeClient.getClosedPositions(eq(80L), anyInt())).thenReturn(List.of(closed));

        AiTraderPlan live = new AiTraderPlan();
        live.setSymbol("BTCUSDT");
        live.setSide("LONG");
        live.setPlayType("BREAKOUT");
        live.setSignalsUsed("破位后回踩 99800 站稳");
        live.setInvalidationCondition("1h收盘跌回99500下方");
        live.setStatus(AiTraderPlan.STATUS_LIVE);
        live.setOpenedWakeTime(BOUNDARY - 7200_000L);
        when(planMapper.selectList(any())).thenReturn(List.of(live));

        // 先建好再 stub：thenReturn 参数里嵌套 when() 是 UnfinishedStubbing
        ChatModel model = mock(ChatModel.class);
        // getOptions 必须给真 options：返回 null/自造的，ResilientChatService 挂出去的工具列表就是空数组
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AssistantMessage lookup = AssistantMessage.builder().content("先看看榜首这位。")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "c1", "function", "peer_insights", "{\"traderId\":8}")))
                .build();
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(lookup))),
                new ChatResponse(List.of(new Generation(new AssistantMessage(QUALIFIED_OUTPUT)))));
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.learn(me, BOUNDARY);

        // 工具真被调用，且 PeerInsightService.detail 的四块内容真回流给了模型
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).call(prompts.capture());
        assertThat(prompts.getAllValues()).hasSize(2);
        String secondRound = allText(prompts.getAllValues().get(1));
        assertThat(secondRound).contains("【赢家】[id=8]");                       // 头部硬事实
        assertThat(secondRound).contains("回踩站稳才进场");                        // 复盘全文
        assertThat(secondRound).contains("只在日线趋势方向上做");                   // 他的学习笔记
        assertThat(secondRound).contains("1h收盘跌回99500下方");                   // 在场计划的失效条件
        assertThat(secondRound).contains("入场 100000").contains("出场 101500");   // 论点→结局配对

        // LEARN 行：轨迹记下"它看了谁"（公开时间线的观赏点）
        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        AiTraderDecision d = dec.getValue();
        assertThat(d.getKind()).isEqualTo(AiTraderDecision.KIND_LEARN);
        assertThat(d.getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(d.getActionsJson()).contains("peer_insights").contains("8");
        assertThat(d.getToolCalls()).isEqualTo(1);
        assertThat(d.getReasoning()).contains("【不学什么】");

        // learning_notes 覆盖写
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> up =
                ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(traderMapper).update(any(), up.capture());
        assertThat(up.getValue().getSqlSet()).contains("learning_notes");
        assertThat(up.getValue().getParamNameValuePairs()).containsValue(QUALIFIED_OUTPUT);
    }

    /** 工具回执的正文挂在 ToolResponseMessage.responses 上，不在 getText()——不摊开就断言不到工具结果 */
    private static String allText(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        for (Message m : prompt.getInstructions()) {
            if (m instanceof ToolResponseMessage trm) {
                trm.getResponses().forEach(r -> sb.append(r.responseData()).append('\n'));
            } else if (m.getText() != null) {
                sb.append(m.getText()).append('\n');
            }
        }
        return sb.toString();
    }
}
