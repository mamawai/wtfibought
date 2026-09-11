package com.mawai.wiibagent.learning;

import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibcommon.enums.AgentLang;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LearningRunner 回路测试：合格判定（两个必需段）/降级安全（格式失守不动笔记）/2000字截断/
 * 三样注入齐全/失败与超时只留 ERROR 行不计连败。提示词按习惯配套断言。
 * <p>
 * 这些用例跑的是真 ReactLoop，只是模型第一轮就给终稿（不调工具）——工具循环那条路
 * 由 {@link LearningLoopTest} 覆盖。
 */
class LearningRunnerTest {

    private static final long BOUNDARY = 1785196800000L; // 2026-07-28 00:00:00 UTC

    @BeforeAll
    static void initTableInfoCache() {
        // LambdaUpdateWrapper 纯单测需要实体 TableInfo 缓存（正常由 MP 启动时注册）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
    }

    private final PeerInsightService peerInsightService = mock(PeerInsightService.class);
    private final TraderModelFactory modelFactory = mock(TraderModelFactory.class);
    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);

    private final UserLangResolver langResolver = mock(UserLangResolver.class);
    private final PromptCatalog prompts = new PromptCatalog();

    private final LearningRunner runner = new LearningRunner(peerInsightService, modelFactory,
            traderMapper, decisionMapper, prompts, new LocalizedToolCallbacks(prompts), langResolver);

    {
        when(langResolver.of(anyLong())).thenReturn(AgentLang.ZH);
    }

    private AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setName("我");
        t.setRoundNo(1);
        t.setSimUserId(70L);
        t.setSymbols("BTCUSDT");
        t.setIntervalCode("1h");
        t.setMemory("旧复盘：我的突破单老是追高被扫");
        t.setLearningNotes("上份学习：从[id=8]学到等回踩，本期继续验证");
        return t;
    }

    private void stubLeaderboard() {
        when(peerInsightService.leaderboard(anyLong(), any())).thenReturn("""
                【同侪排行榜】（本局快照，按收益率降序）
                1. [id=8] 赢家 ｜ 运行中 ｜ 本局收益率 +20.00% ｜ 已了结 12 笔 ｜ 最新复盘: 只做回踩不追高
                2. [id=7] 我 ｜ 运行中 ｜ 本局收益率 -5.00% ｜ 已了结 9 笔 ｜ 最新复盘: 追高又被扫（这是你）
                """);
    }

    /** 第一轮就给终稿的模型（不调工具）。getOptions 必须给真 options，否则 ResilientChatService 挂出去的工具列表就是空数组。 */
    private ChatModel modelReturning(String text) {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                        ChatResponseMetadata.builder().usage(new DefaultUsage(1200, 500)).build()));
        return model;
    }

    private static final String QUALIFIED_OUTPUT = """
            【本期学习】
            看了谁：[id=8] 赢家。他 12 笔样本里 BREAKOUT 占 8 笔，正好打在我最亏的那一类上。
            学到什么：
            1. 他的做法：突破后等回踩站稳再进 → 证据：BREAKOUT 12 笔 8 胜，最近一笔 100200 破位后
               回踩 99800 才进 → 我的差距：我 9 笔 2 胜，全是破位当根就进 → 我怎么改：破位那根不进，
               等下一根收在破位上方再进。

            【不学什么】
            1. 他 20 倍杠杆押 ETH 单笔赚 3000——样本 1 笔，不可复现，且超出我的杠杆区间。

            【前车之鉴】
            1. [id=9] 炸了：亏损后连续加仓摊平 → 反向行情继续 → 保证金归零。我在连亏两笔后
               也想过加倍下注，同一个坑。""";

    @Test
    void qualifiedOutputPersistsLearnRowAndOverwritesNotes() {
        stubLeaderboard();
        // 先建好再 stub：thenReturn 参数里嵌套 when() 是 UnfinishedStubbing
        ChatModel model = modelReturning(QUALIFIED_OUTPUT);
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.learn(trader(), BOUNDARY);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        AiTraderDecision d = dec.getValue();
        assertThat(d.getKind()).isEqualTo(AiTraderDecision.KIND_LEARN);
        assertThat(d.getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(d.getWakeTime()).isEqualTo(BOUNDARY);
        assertThat(d.getIntervalCode()).isEqualTo("1d");
        assertThat(d.getRoundNo()).isEqualTo(1);
        // LEARN 行存学习全文，不记 equity（净值曲线按 isNotNull 过滤，天然不受影响）
        assertThat(d.getReasoning()).contains("【本期学习】").contains("【不学什么】").contains("【前车之鉴】");
        assertThat(d.getEquity()).isNull();
        assertThat(d.getError()).isNull();
        // 用量照记：学习也烧用户的钱
        assertThat(d.getModelCalls()).isEqualTo(1);
        assertThat(d.getPromptTokens()).isEqualTo(1200L);
        assertThat(d.getCompletionTokens()).isEqualTo(500L);
        assertThat(d.getActionsJson()).isNotNull();
        assertThat(d.getLatencyMs()).isNotNull();

        // learning_notes 全文覆盖写（列级更新：并发唤醒回路正在改同一行其它列）
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> up =
                ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(traderMapper).update(any(), up.capture());
        assertThat(up.getValue().getSqlSet()).contains("learning_notes");
        assertThat(up.getValue().getParamNameValuePairs()).containsValue(QUALIFIED_OUTPUT);
    }

    /** 三样代码注入必须齐（排行榜/自己的复盘笔记/上一份学习笔记）+ 身份与输出契约 */
    @Test
    void promptCarriesLeaderboardOwnMemoryAndPriorNotes() {
        stubLeaderboard();
        ChatModel model = modelReturning(QUALIFIED_OUTPUT);
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.learn(trader(), BOUNDARY);

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).call(prompts.capture());
        String all = prompts.getAllValues().get(0).getInstructions().stream()
                .map(Message::getText).reduce("", String::concat);
        // 三样注入：省掉一次必然发生的工具调用 + 学的东西要对得上自己的问题 + 滚动继承
        assertThat(all).contains("【同侪排行榜】").contains("[id=8] 赢家");
        assertThat(all).contains("旧复盘：我的突破单老是追高被扫");
        assertThat(all).contains("上份学习：从[id=8]学到等回踩");
        // 身份先于指令 + 单问题框架（研究同行的交易员，不是评审）
        assertThat(all).contains("研究同行").contains("不给别人打分")
                .contains("别人做对了什么，其中哪些对我真的有用");
        // 甄别指引：收益率是硬事实、归因不是；幸存者偏差点名
        assertThat(all).contains("幸存者偏差").contains("运气和方法长得一模一样");
        // 反照抄三件套
        assertThat(all).contains("【不学什么】是必填段").contains("你就只是在抄");
        assertThat(all).contains("12 笔 8 胜").contains("风控意识值得学习");
        assertThat(all).contains("引用同侪战绩必须带笔数").contains("不可靠");
        // 输出契约三段 + 滚动继承 + 中文
        assertThat(all).contains("【本期学习】").contains("【不学什么】").contains("【前车之鉴】");
        assertThat(all).contains("看了谁").contains("学到什么").contains("我的差距").contains("我怎么改");
        assertThat(all).contains("整份覆盖").contains("只看得到这一份").contains("2000");
        assertThat(all).contains("全部输出使用中文");
        // 工具在场且说清双模式取 id 的地方
        assertThat(all).contains("peer_insights").contains("传排行榜里的 id");
    }

    /** 缺【不学什么】：没做否定判断＝在抄，判不合格；ERROR 行留痕、笔记一个字不动 */
    @Test
    void missingSkipSectionKeepsNotesAndErrorNamesIt() {
        stubLeaderboard();
        ChatModel model = modelReturning("""
                【本期学习】
                看了谁：[id=8] 赢家
                学到什么：他等回踩再进，我追高，改成等下一根确认。

                【前车之鉴】
                [id=9] 亏损加仓摊平炸号。""");
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.learn(trader(), BOUNDARY);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        AiTraderDecision d = dec.getValue();
        assertThat(d.getKind()).isEqualTo(AiTraderDecision.KIND_LEARN);
        assertThat(d.getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        assertThat(d.getError()).contains("【不学什么】").doesNotContain("【本期学习】");
        assertThat(d.getReasoning()).contains("他等回踩再进");    // 原文留痕，好排查是模型还是提示词的问题
        // 降级安全：一次格式失守不许污染笔记
        verify(traderMapper, never()).update(any(), any());
    }

    /** 缺【本期学习】：error 要指名道姓是缺这一段，两种失守分得开 */
    @Test
    void missingLearnSectionKeepsNotesAndErrorNamesIt() {
        stubLeaderboard();
        ChatModel model = modelReturning("""
                【不学什么】
                他的 20 倍杠杆，样本 1 笔不可复现。

                【前车之鉴】
                [id=9] 亏损加仓摊平炸号。""");
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.learn(trader(), BOUNDARY);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        assertThat(dec.getValue().getError()).contains("【本期学习】").doesNotContain("【不学什么】");
        verify(traderMapper, never()).update(any(), any());
    }

    /** 篇幅只由提示词那句"≤N"约束：模型写超了照样整段落库，代码不替它裁 */
    @Test
    void notesStoredInFullEvenOverBudget() {
        stubLeaderboard();
        String longOutput = QUALIFIED_OUTPUT + "\n" + "这句凑长度的话以句号收尾。".repeat(200);
        ChatModel model = modelReturning(longOutput);
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.learn(trader(), BOUNDARY);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> up =
                ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(traderMapper).update(any(), up.capture());
        String written = up.getValue().getParamNameValuePairs().values().stream()
                .filter(v -> v instanceof String s && s.startsWith("【本期学习】"))
                .map(String.class::cast).findFirst().orElseThrow();
        assertThat(written.length()).isGreaterThan(NoteBudget.maxChars(AgentLang.ZH));   // 确实超了预算
        assertThat(written).isEqualTo(longOutput);
        // 决策行同样是全文（笔记与公开时间线本就是同一份产出）
        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getReasoning()).hasSize(longOutput.length());
    }

    /** 模型炸了：只留 ERROR 行，trader 表一个字段都不碰（不计连败——学习失败没有资金风险） */
    @Test
    void failureWritesErrorRowWithoutNotesOrFailureCount() {
        stubLeaderboard();
        when(modelFactory.modelFor(any())).thenThrow(new IllegalStateException("上游401"));

        runner.learn(trader(), BOUNDARY);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getKind()).isEqualTo(AiTraderDecision.KIND_LEARN);
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        // 公开行只存归类文案，上游原文不落库
        assertThat(dec.getValue().getError()).contains("API key").doesNotContain("上游401");
        verify(traderMapper, never()).update(any(), any());
    }

    /** 超时：本轮作废记 ERROR，笔记不动；烧掉的 token 仍要入账（用量落 finally） */
    @Test
    void timeoutWritesErrorRowButStillRecordsUsage() {
        stubLeaderboard();
        ChatModel slow = mock(ChatModel.class);
        when(slow.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(slow.call(any(Prompt.class))).thenAnswer(inv -> {
            Thread.sleep(3_000);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("迟到的学习"))));
        });
        when(modelFactory.modelFor(any())).thenReturn(slow);
        runner.timeoutSeconds = 1;

        runner.learn(trader(), BOUNDARY);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        assertThat(dec.getValue().getError()).contains("超时");
        assertThat(dec.getValue().getModelCalls()).isNotNull();
        verify(traderMapper, never()).update(any(), any());
    }

    /**
     * LEARN 行已落库后写笔记才失败：不许把这行改写成 ERROR 再 insert 一次——
     * MP 已把自增 id 回填进对象，二次 insert 必撞主键，异常会直接逃出学习回路
     * （与 TraderWakeupRunner 的同款坑同款防护）。这一轮学习本身是成功的，只丢日志。
     */
    @Test
    void notesWriteFailureAfterInsertDoesNotReinsertRow() {
        stubLeaderboard();
        ChatModel model = modelReturning(QUALIFIED_OUTPUT);
        when(modelFactory.modelFor(any())).thenReturn(model);
        // 模拟 MP insert 回填自增主键
        when(decisionMapper.insert(any(AiTraderDecision.class))).thenAnswer(inv -> {
            inv.<AiTraderDecision>getArgument(0).setId(99L);
            return 1;
        });
        when(traderMapper.update(any(), any())).thenThrow(new IllegalStateException("连接抖了"));

        runner.learn(trader(), BOUNDARY);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper, org.mockito.Mockito.times(1)).insert(dec.capture());
        // 行状态保持 OK：学习产出是真实的，失败的只是笔记那一步
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
    }
}
