package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.llm.SseChatModel;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.toolkit.IndicatorToolkit;
import com.mawai.wiibagent.toolkit.NewsToolkit;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibquant.mapper.NewsEventMapper;
import com.mawai.wiibquant.market.service.NewsCache;
import com.mawai.wiibquant.market.service.NewsFlashLocalizer;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单笔复盘本体：带工具跑一圈落 DEBRIEF 行（仓位编号、工具调用、轨迹、用量）；没有【归类】段、输出为空、
 * 模型报错、超时都是 ERROR 行；哪一笔到期、失败后隔多久重试。
 */
class DebriefRunnerTest {

    private static final long H = 3_600_000L;
    private static final long NOW = 1785110400000L + 100 * H;

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderPlan.class);
    }

    private final PromptCatalog prompts = new PromptCatalog();
    private final DebriefMaterialAssembler assembler = mock(DebriefMaterialAssembler.class);
    private final TraderModelFactory modelFactory = mock(TraderModelFactory.class);
    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);
    private final AiTraderPlanMapper planMapper = mock(AiTraderPlanMapper.class);
    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final UserLangResolver langResolver = mock(UserLangResolver.class);

    private final DebriefRunner runner = new DebriefRunner(assembler,
            new DebriefToolkit(decisionMapper, planMapper, simTradeClient,
                    new CaseFileBuilder(mock(KlineHistoryStore.class)), new CaseFileRenderer(prompts)),
            mock(IndicatorToolkit.class),
            new NewsToolkit(mock(NewsCache.class), new NewsFlashLocalizer(mock(NewsEventMapper.class))),
            new LocalizedToolCallbacks(prompts),
            modelFactory, decisionMapper, planMapper, simTradeClient, prompts, langResolver, new ExperienceOpParser(prompts));

    {
        when(langResolver.of(anyLong())).thenReturn(AgentLang.ZH);
        when(assembler.assemble(any(), any(), any(), any())).thenReturn(new DebriefMaterialAssembler.DebriefMaterial(
                "【这笔单子的事实表】\n- [42] BTCUSDT LONG …\n", "【各轮决策】\n- #901 …\n", "【经验账本】\n#75 规律 …\n", 2));
    }

    private static final String OUTPUT = """
            【单笔复盘】
            路径：13:10 限价成交，14:00 止损带走。
            方向错了还是被洗掉：被洗掉。
            问题出在哪：止损宽度。
            管理动作：没动过。
            有没有写下要做却没照做的：无
            【归类】
            #75 名下 42 ｜ 条件对得上
            #61 无""";

    private static AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(1);
        t.setSimUserId(99L);
        t.setIntervalCode("1h");
        t.setCustomPrompt("只做突破，不抄底。");
        return t;
    }

    private static FuturesPositionDTO pos(long id, long closeMs) {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId(id);
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setStatus("CLOSED");
        p.setClosedPnl(new BigDecimal("-50"));
        p.setCreatedAt(Instant.ofEpochMilli(closeMs - H).atZone(ZoneId.systemDefault()).toLocalDateTime());
        p.setUpdatedAt(Instant.ofEpochMilli(closeMs).atZone(ZoneId.systemDefault()).toLocalDateTime());
        return p;
    }

    private static AiTraderPlan plan(long positionId) {
        AiTraderPlan plan = new AiTraderPlan();
        plan.setPositionId(positionId);
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        return plan;
    }

    private static DebriefRunner.Due due() {
        return new DebriefRunner.Due(pos(42L, NOW - 25 * H), plan(42L));
    }

    /** 能挂工具的模型 */
    private static ChatModel toolModel() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        return model;
    }

    private static ChatResponse reply(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)),
                ChatResponseMetadata.builder().usage(new DefaultUsage(1000, 400)).build());
    }

    private AiTraderDecision inserted() {
        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        return dec.getValue();
    }

    /** 先调一次工具再给结论：落 OK 的 DEBRIEF 行，带仓位编号、工具调用、轨迹和两次模型调用的用量 */
    @Test
    void debriefRunsToolsThenWritesOkRowWithTrace() {
        ChatModel model = toolModel();
        when(model.call(any(Prompt.class))).thenReturn(
                reply(AssistantMessage.builder().content("先看看之前的单子").toolCalls(List.of(
                        new AssistantMessage.ToolCall("c1", "function", "trade_history", "{}"))).build()),
                reply(new AssistantMessage(OUTPUT)));
        when(modelFactory.modelFor(any())).thenReturn(model);
        long before = System.currentTimeMillis();

        runner.debrief(trader(), due());

        AiTraderDecision d = inserted();
        assertThat(d.getKind()).isEqualTo(AiTraderDecision.KIND_DEBRIEF);
        assertThat(d.getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(d.getPositionId()).isEqualTo(42L);
        assertThat(d.getWakeTime()).isBetween(before, System.currentTimeMillis());
        assertThat(d.getIntervalCode()).isEqualTo("1h");
        assertThat(d.getEquity()).isNull();
        assertThat(d.getError()).isNull();
        assertThat(d.getReasoning()).startsWith("【单笔复盘】").contains("#75 名下 42");
        assertThat(d.getToolCalls()).isEqualTo(1);
        assertThat(d.getActionsJson()).contains("\"tool\":\"trade_history\"");
        assertThat(d.getModelCalls()).isEqualTo(2);
        assertThat(d.getPromptTokens()).isEqualTo(2000L);
        // 轨迹：提示词、两次调用、工具回执、收尾
        assertThat(d.getTraceJson()).contains("\"kind\":\"DEBRIEF\"").contains("先看看之前的单子")
                .contains("\"name\":\"trade_history\"").contains("\"status\":\"OK\"");

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompt.capture());
        String all = prompt.getAllValues().getFirst().getInstructions().stream().map(Message::getText).reduce("", String::concat);
        assertThat(all).contains("只判这一笔").contains("之后才发生的").contains("#<编号> 名下 <仓位编号>")
                .contains("复盘对象：仓位 [42] BTCUSDT LONG").contains("【主人的交易指令】").contains("只做突破，不抄底。")
                .contains("【这笔单子的事实表】").contains("【各轮决策】").contains("#75 规律");
        assertThat(all.stripTrailing()).endsWith("输出语言：中文。");
    }

    /** 没有【归类】段：记成 ERROR，正文照存 */
    @Test
    void outputWithoutClassifySectionIsAnErrorRowKeepingTheBody() {
        ChatModel model = toolModel();
        when(model.call(any(Prompt.class))).thenReturn(reply(new AssistantMessage("【单笔复盘】\n路径：……")));
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.debrief(trader(), due());

        AiTraderDecision d = inserted();
        assertThat(d.getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        assertThat(d.getReasoning()).contains("路径：……");
        assertThat(d.getError()).contains("没有【归类】段");
        assertThat(d.getPositionId()).isEqualTo(42L);
    }

    /** 账本有现役条目，【归类】下面却一行都认不出：记成 ERROR，过后重试；账本为空时什么都不写是对的 */
    @Test
    void sortingSectionWithNoReadableRowIsAnErrorUnlessTheLedgerIsEmpty() {
        String garbled = "【单笔复盘】\n路径：……\n【归类】\n75：名下\n61：破";
        ChatModel model = toolModel();
        when(model.call(any(Prompt.class))).thenReturn(reply(new AssistantMessage(garbled)));
        when(modelFactory.modelFor(any())).thenReturn(model);

        AiTraderDecision d = runner.debriefRow(trader(), due());

        assertThat(d.getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        assertThat(d.getError()).contains("一行都认不出");
        assertThat(d.getReasoning()).contains("75：名下");

        when(assembler.assemble(any(), any(), any(), any())).thenReturn(new DebriefMaterialAssembler.DebriefMaterial(
                "【这笔单子的事实表】\n", "【各轮决策】\n", "【经验账本】\n（账本为空）\n", 0));
        when(model.call(any(Prompt.class))).thenReturn(reply(new AssistantMessage("【单笔复盘】\n路径：……\n【归类】")));

        assertThat(runner.debriefRow(trader(), due()).getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
    }

    @Test
    void emptyOutputIsAnErrorRow() {
        ChatModel model = toolModel();
        when(model.call(any(Prompt.class))).thenReturn(reply(new AssistantMessage("")));
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.debrief(trader(), due());

        AiTraderDecision d = inserted();
        assertThat(d.getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        assertThat(d.getError()).contains("输出为空");
    }

    /** 模型报错：ERROR 行只存归类文案，上游原文不落库 */
    @Test
    void modelFailureIsAnErrorRowWithClassifiedMessage() {
        when(modelFactory.modelFor(any())).thenThrow(new SseChatModel.UpstreamRejected(401, "上游401"));

        runner.debrief(trader(), due());

        AiTraderDecision d = inserted();
        assertThat(d.getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        assertThat(d.getError()).contains("API key").doesNotContain("上游401");
        assertThat(d.getTraceJson()).contains("\"status\":\"ERROR\"");
    }

    @Test
    void timeoutIsAnErrorRow() {
        ChatModel slow = toolModel();
        when(slow.call(any(Prompt.class))).thenAnswer(inv -> {
            Thread.sleep(3_000);
            return reply(new AssistantMessage(OUTPUT));
        });
        when(modelFactory.modelFor(any())).thenReturn(slow);
        runner.timeoutSeconds = 1;

        runner.debrief(trader(), due());

        AiTraderDecision d = inserted();
        assertThat(d.getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        assertThat(d.getError()).contains("超时");
    }

    // ==================== 哪一笔到期 ====================

    private static AiTraderDecision attempt(long positionId, String status, long wakeTime) {
        AiTraderDecision d = new AiTraderDecision();
        d.setPositionId(positionId);
        d.setStatus(status);
        d.setWakeTime(wakeTime);
        return d;
    }

    /** 平仓满 24 小时、没复盘成功过的里面取平仓最早的；没满 24 小时的、复盘成功过的不取 */
    @Test
    void nextPicksTheEarliestClosedTradeThatIsDue() {
        when(planMapper.selectList(any())).thenReturn(List.of(plan(1L), plan(2L), plan(3L), plan(4L)));
        when(decisionMapper.selectList(any())).thenReturn(List.of(attempt(1L, AiTraderDecision.STATUS_OK, NOW - H)));
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(
                pos(1L, NOW - 40 * H), pos(2L, NOW - 23 * H), pos(3L, NOW - 26 * H), pos(4L, NOW - 30 * H)));

        DebriefRunner.Due due = runner.next(trader(), NOW);

        assertThat(due.position().getId()).isEqualTo(4L);
        assertThat(due.plan().getPositionId()).isEqualTo(4L);
    }

    /** 平仓超过 7 天的不再补做：只剩这种单子时没有到期的 */
    @Test
    void tradesClosedMoreThanSevenDaysAgoAreNotDebriefed() {
        when(planMapper.selectList(any())).thenReturn(List.of(plan(1L), plan(2L)));
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(
                pos(1L, NOW - 8 * 24 * H), pos(2L, NOW - 7 * 24 * H + H)));

        assertThat(runner.next(trader(), NOW).position().getId()).isEqualTo(2L);

        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(pos(1L, NOW - 8 * 24 * H)));
        assertThat(runner.next(trader(), NOW)).isNull();
    }

    /**
     * 平仓所在那根 5m 开盘起满 24 小时、再过一根 5m 才算到期：14:03 平的单子，第二天 14:05 那根收盘才扫得到，
     * 贴着边界 14:00:00.2 平的也是 14:05，不会赶在 14:00 那根收盘事件上
     */
    @Test
    void tradeIsReadyOneBarAfterTwentyFourHoursFromItsClosingBar() {
        long bar = 1785110400000L;          // 某根 5m 的开盘时刻
        long day = 24 * H;
        long m5 = 300_000L;
        assertThat(DebriefRunner.ready(bar + 180_000L, bar + day + m5 - 1)).isFalse();
        assertThat(DebriefRunner.ready(bar + 180_000L, bar + day + m5)).isTrue();
        assertThat(DebriefRunner.ready(bar + 200L, bar + day + 500L)).isFalse();
        assertThat(DebriefRunner.ready(bar + 200L, bar + day + m5)).isTrue();
    }

    /** 没有候选计划行、候选都不能再试：不去问 sim */
    @Test
    void nextAsksSimOnlyWhenACandidateRemains() {
        when(planMapper.selectList(any())).thenReturn(List.of());
        assertThat(runner.next(trader(), NOW)).isNull();

        when(planMapper.selectList(any())).thenReturn(List.of(plan(1L)));
        when(decisionMapper.selectList(any())).thenReturn(List.of(attempt(1L, AiTraderDecision.STATUS_OK, NOW - H)));
        assertThat(runner.next(trader(), NOW)).isNull();

        verify(simTradeClient, never()).getClosedPositions(anyLong(), anyInt());
    }

    /** 失败一次等 1 小时再试，失败两次等 24 小时，失败三次不再试；成功过就不再跑 */
    @Test
    void failedDebriefIsRetriedAfterOneHourThenADayThenGivenUp() {
        String error = AiTraderDecision.STATUS_ERROR;
        assertThat(DebriefRunner.attemptable(List.of(), NOW)).isTrue();
        assertThat(DebriefRunner.attemptable(List.of(attempt(1L, error, NOW - H + 1)), NOW)).isFalse();
        assertThat(DebriefRunner.attemptable(List.of(attempt(1L, error, NOW - H)), NOW)).isTrue();
        List<AiTraderDecision> twice = List.of(attempt(1L, error, NOW - 30 * H), attempt(1L, error, NOW - 24 * H + 1));
        assertThat(DebriefRunner.attemptable(twice, NOW)).isFalse();
        assertThat(DebriefRunner.attemptable(twice, NOW + 1)).isTrue();
        assertThat(DebriefRunner.attemptable(List.of(attempt(1L, error, NOW - 90 * H), attempt(1L, error, NOW - 80 * H),
                attempt(1L, error, NOW - 50 * H)), NOW)).isFalse();
        assertThat(DebriefRunner.attemptable(List.of(attempt(1L, error, NOW - 30 * H),
                attempt(1L, AiTraderDecision.STATUS_OK, NOW - 29 * H)), NOW)).isFalse();
    }
}
