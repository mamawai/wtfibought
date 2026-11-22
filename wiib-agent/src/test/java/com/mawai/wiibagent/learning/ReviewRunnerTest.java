package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.llm.SseChatModel;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.enums.AgentLang;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.entity.AiTraderExperienceTrade.MARK_UNDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReviewRunner 回路：解析落账 / 代码结账 / 账本渲染写回 memory / 只写「本期无更新」算成功 / 一行解析不出账本不动 /
 * 无素材跳过 / 失败只留 ERROR 行不计连败 / 结账结果和没记进账本的新增追加在正文末尾。
 */
class ReviewRunnerTest {

    private static final long BOUNDARY = 1785110400000L + 86_400_000L;
    /** 本期一笔单子：仓位 42 */
    private static final Map<Long, ExperienceLedger.PeriodTrade> TRADES = Map.of(
            42L, new ExperienceLedger.PeriodTrade(new BigDecimal("-300.00"), LocalDate.of(2026, 7, 27)));

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
    }

    private final PromptCatalog prompts = new PromptCatalog();
    private final ReviewMaterialAssembler assembler = mock(ReviewMaterialAssembler.class);
    private final TraderModelFactory modelFactory = mock(TraderModelFactory.class);
    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);
    private final ExperienceLedger ledger = mock(ExperienceLedger.class);
    private final UserLangResolver langResolver = mock(UserLangResolver.class);
    private final TraderPlanStore planStore = mock(TraderPlanStore.class);

    private final ReviewRunner runner = new ReviewRunner(assembler, modelFactory,
            decisionMapper, prompts, langResolver, ledger, new ExperienceOpParser(prompts), planStore);

    /** 单笔复盘合并出来的归类：42 号在 #1 名下 */
    private static final List<ExperienceLedger.Classify> CLASSIFIES = List.of(
            new ExperienceLedger.Classify(1L, List.of(new ExperienceLedger.Mark(MARK_UNDER, 42L))));

    {
        when(langResolver.of(anyLong())).thenReturn(AgentLang.ZH);
        // insert 回填 id：名下单子要挂在这篇复盘上
        when(decisionMapper.insert(any(AiTraderDecision.class))).thenAnswer(inv -> {
            ((AiTraderDecision) inv.getArgument(0)).setId(900L);
            return 1;
        });
        when(ledger.apply(anyLong(), anyInt(), any(), any(), any(), any()))
                .thenReturn(new ExperienceLedger.Applied(2, 0, List.of()));
        when(ledger.writeMemory(any(), any())).thenReturn("【经验账本】\n#1 规律·执行型 ｜ 止损不小于 1 个 1h ATR");
    }

    private AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(1);
        t.setSimUserId(99L);
        t.setSymbols("BTCUSDT");
        t.setIntervalCode("1h");
        t.setMemory("【经验账本】\n#1 规律·执行型 ｜ 止损不小于 1 个 1h ATR");
        return t;
    }

    private void stubMaterial() {
        stubMaterial(TRADES);
    }

    /** trades：本批单子；有单子时带上单笔复盘合并出来的归类和计划行 id，没有时都是空的 */
    private void stubMaterial(Map<Long, ExperienceLedger.PeriodTrade> trades) {
        when(assembler.lastReview(7L, 1)).thenReturn(null);
        when(assembler.hasNewMaterial(any(), anyLong(), anyLong())).thenReturn(true);
        when(assembler.assemble(any(), anyLong(), anyLong(), any())).thenReturn(
                new ReviewMaterialAssembler.ReviewMaterial(
                        "【战绩表】起始权益 10000.00 → 期末权益 9800.00，期间收益率 -2.00%\n",
                        "————— 本局论点战绩 —————\nPULLBACK：6笔 4胜2负 盈亏合计+7946 USDT\n",
                        "【本批单子】\nPULLBACK（2 笔，1 胜，净 -300）\n- [42] BTCUSDT LONG …\n",
                        "【本期行情】\n- BTCUSDT 81144 → 86579，低到高 8.1%\n",
                        "【盯盘布防】\n- 期间布防 0 条（—）；命中 0 条；复盘时仍在盯 0 条\n",
                        "【经验账本】（现役 1 条）\n#1 规律 风险限制 执行型 PULLBACK ｜ 止损不小于 1 个 1h ATR ｜ 名下 5 笔 ｜ 证据 x\n",
                        trades, trades.isEmpty() ? List.of() : CLASSIFIES, trades.isEmpty() ? List.of() : List.of(101L)));
    }

    private ChatModel modelReturning(String... texts) {
        ChatModel model = mock(ChatModel.class);
        var stub = when(model.call(any(Prompt.class)));
        for (String text : texts) {
            stub = stub.thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(1000, 400)).build()));
        }
        return model;
    }

    /** 输出里那行编号归类是模型多写的：不认，#9 也不是素材里合并出来的那条 */
    private static final String OUTPUT = """
            【本期复盘】
            战绩：起始权益 10000.00 → 期末权益 9800.00，期间收益率 -2.00%
            PULLBACK（2 笔，1 胜，净 -300）
            分界线：赢的那笔是限价等回踩，亏的那笔是市价追
            机会：没错过
            【经验更新】
            #9 名下 42 ｜ 止损放在 1.2 个 ATR
            新增 PULLBACK 入场条件 机会型 ｜ 回踩 1h EMA20 不破 → 挂限价多 ｜ ETH 09:32、SOL 23:33""";

    private static String allText(Prompt p) {
        return p.getInstructions().stream().map(Message::getText).reduce("", String::concat);
    }

    @Test
    void successfulReviewAppliesOpsSettlesAndWritesMemory() {
        stubMaterial();
        ChatModel model = modelReturning(OUTPUT);
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.review(trader(), BOUNDARY);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        AiTraderDecision d = dec.getValue();
        assertThat(d.getKind()).isEqualTo(AiTraderDecision.KIND_REVIEW);
        assertThat(d.getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(d.getWakeTime()).isEqualTo(BOUNDARY);
        assertThat(d.getIntervalCode()).isEqualTo("1d");
        // 正文 + 更新表整篇存进 REVIEW 行，公开上时间线
        assertThat(d.getReasoning()).contains("分界线").contains("新增 PULLBACK");
        assertThat(d.getEquity()).isNull();
        assertThat(d.getError()).isNull();
        assertThat(d.getModelCalls()).isEqualTo(1);
        assertThat(d.getPromptTokens()).isEqualTo(1000L);

        // 落账的是：单笔复盘合并出来的归类在前，本篇的新增在后；输出里那行 #9 归类不认
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExperienceLedger.Op>> ops = ArgumentCaptor.forClass((Class) List.class);
        verify(ledger).apply(eq(7L), eq(1), eq(900L), ops.capture(), eq(AgentLang.ZH), eq(TRADES));
        assertThat(ops.getValue()).hasSize(2);
        assertThat(ops.getValue().getFirst()).isEqualTo(CLASSIFIES.getFirst());
        assertThat(ops.getValue().getLast()).isInstanceOf(ExperienceLedger.Add.class);
        // 落账后给本批单子盖上这篇复盘的编号，再按这篇复盘结账
        verify(planStore).markReviewed(List.of(101L), 900L);
        verify(ledger).settle(7L, 1, 900L, true, AgentLang.ZH);

        // 账本自己把渲染文本写回 ai_trader.memory；复盘行的快照按列写 memory_after
        assertThat(d.getMemoryAfter()).startsWith("【经验账本】");
        verify(ledger).writeMemory(any(), eq(AgentLang.ZH));
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<AiTraderDecision>> up = ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(decisionMapper).update(isNull(), up.capture());
        assertThat(up.getValue().getSqlSet()).contains("memory_after");
        assertThat(up.getValue().getParamNameValuePairs()).containsValue("【经验账本】\n#1 规律·执行型 ｜ 止损不小于 1 个 1h ATR");
        verify(decisionMapper, never()).updateById(any(AiTraderDecision.class));
        verify(model, times(1)).call(any(Prompt.class));
    }

    /** 转正、降级、退役和名额满了没记进账本的新增，都追加在复盘正文末尾，一行一条，结账的在前 */
    @Test
    void settledChangesAndOverCapAddsAreListedAtTheEndOfTheReviewText() {
        stubMaterial();
        ChatModel model = modelReturning(OUTPUT);
        when(modelFactory.modelFor(any())).thenReturn(model);
        when(ledger.apply(anyLong(), anyInt(), any(), any(), any(), any())).thenReturn(new ExperienceLedger.Applied(1, 1,
                List.of(new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_ENTRY, AiTraderExperience.DIR_OPPORTUNITY,
                        "回踩 1h EMA20 不破 → 挂限价多", "ETH 09:32、SOL 23:33", List.of(), null))));
        ExperienceLedger.Tally tally = new ExperienceLedger.Tally(6, 4, 2, new BigDecimal("40.00"), 4,
                5, new BigDecimal("31.00"), 3, 0, 0, 0);
        when(ledger.settle(anyLong(), anyInt(), anyLong(), anyBoolean(), any())).thenReturn(List.of(
                new ExperienceLedger.Change(ExperienceLedger.ChangeKind.PROMOTED, entry(5L, "破位后挂限价等反抽"), tally),
                new ExperienceLedger.Change(ExperienceLedger.ChangeKind.RETIRED, entry(6L, "量比大于 2 才追"), tally)));

        runner.review(trader(), BOUNDARY);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<AiTraderDecision>> up = ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        // 先是账本快照那次，追加段排在最后
        verify(decisionMapper, times(2)).update(isNull(), up.capture());
        assertThat(up.getAllValues().getFirst().getSqlSet()).contains("memory_after");
        assertThat(up.getAllValues().getLast().getSqlSet()).contains("reasoning");
        assertThat(up.getAllValues().getLast().getParamNameValuePairs().values()).anySatisfy(v -> assertThat(v.toString())
                .contains("分界线")
                .endsWith("""
                        【代码记账】下面这些是代码按名下单子的账做的：
                        - #5 转为规律（当前段名下 5 笔，3 个平仓日，合计 +31.00）：破位后挂限价等反抽
                        - #6 退役（连续 10 篇复盘名下没有新单子）：量比大于 2 才追

                        【没记进账本】下面几条新增因为账本名额已满，没有记进账本：
                        - 回踩 1h EMA20 不破 → 挂限价多"""));
    }

    @Test
    void promptCarriesInstructionsMaterialLedgerAndContract() {
        stubMaterial();
        ChatModel model = modelReturning(OUTPUT);
        when(modelFactory.modelFor(any())).thenReturn(model);
        AiTrader t = trader();
        t.setCustomPrompt("只做突破，不抄底。");

        runner.review(t, BOUNDARY);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String all = allText(prompt.getValue());
        // 身份：跨单子，不逐笔重做
        assertThat(all).contains("写给明天").contains("你不再逐笔分析").contains("找分界线").contains("不要逐笔重做");
        // 事实纪律：数字只许引用、不复述指令、不继承旧教训；归类不归它写；新增只收三个格子
        assertThat(all).contains("只许原样复述")
                .contains("主人指令里的规定不能当假设写进账本").contains("上期的教训不许重抄")
                .contains("你不写编号归类行，写了也不算数")
                .contains("由你决定要不要立成执行缺口")
                .contains("新增的格子只有入场条件、风险限制、执行缺口三种").contains("已经转正的限制型不算在内")
                .contains("但要等之后的复盘又有新单子记进它名下才会转正");
        assertThat(prompts.get(AgentLang.EN, "reviewer.system"))
                .contains("Do not write #<id> sorting rows")
                .contains("it is only promoted once a later review puts new trades under it");
        // 输出契约：没有编号归类行
        assertThat(all).contains("【本期复盘】").contains("【经验更新】").contains("分界线：…")
                .contains("新增 <单子类型代码或 通用>").doesNotContain("#<编号> 名下");
        // 窗口行、五块素材、主人指令在场，指令排在素材前
        assertThat(all).contains("复盘窗口").contains("现在是 ");
        assertThat(all).contains("【战绩表】").contains("本局论点战绩").contains("【本批单子】")
                .contains("【本期行情】").contains("#1 规律 风险限制");
        assertThat(all).contains("【主人的交易指令】").contains("只做突破，不抄底。");
        assertThat(all.indexOf("只做突破，不抄底。")).isLessThan(all.indexOf("【战绩表】"));
        assertThat(all.stripTrailing()).endsWith("输出语言：中文。");
    }

    /** 行情、布防的窗口整体晚 24 小时：右端是复盘时刻往前 24 小时，左端接上一篇的右端；本局首篇从头 */
    @Test
    void windowEndsADayBeforeTheReviewAndStartsWhereTheLastOneEnded() {
        stubMaterial();
        ChatModel model = modelReturning(OUTPUT);
        when(modelFactory.modelFor(any())).thenReturn(model);
        long day = 86_400_000L;

        runner.review(trader(), BOUNDARY);
        verify(assembler).assemble(any(), eq(0L), eq(BOUNDARY - day), eq(AgentLang.ZH));

        AiTraderDecision last = new AiTraderDecision();
        last.setWakeTime(BOUNDARY);
        when(assembler.lastReview(7L, 1)).thenReturn(last);
        runner.review(trader(), BOUNDARY + day);
        verify(assembler).hasNewMaterial(any(), eq(BOUNDARY - day), eq(BOUNDARY));
        verify(assembler).assemble(any(), eq(BOUNDARY - day), eq(BOUNDARY), eq(AgentLang.ZH));
    }

    /**
     * 本批一笔单子都没有（只有开仓动作），只写了「本期无更新」：算成功（素材窗口往前推），正文照存，
     * 落账那步没有操作、没有单子要盖编号；结账时告诉账本本批没有单子，照常渲染记忆
     */
    @Test
    void noUpdatesLineIsAnOkReviewWithNoOps() {
        stubMaterial(Map.of());
        ChatModel model = modelReturning("【本期复盘】\n战绩：……\n下次改什么 / 可复制条件：无\n【经验更新】\n本期无更新");
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.review(trader(), BOUNDARY);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(dec.getValue().getError()).isNull();
        assertThat(dec.getValue().getReasoning()).contains("可复制条件：无").endsWith("本期无更新");
        verify(ledger).apply(eq(7L), eq(1), eq(900L), eq(List.of()), eq(AgentLang.ZH), eq(Map.of()));
        verify(planStore).markReviewed(List.of(), 900L);
        verify(ledger).settle(7L, 1, 900L, false, AgentLang.ZH);
        verify(ledger).writeMemory(any(), eq(AgentLang.ZH));
        assertThat(dec.getValue().getMemoryAfter()).startsWith("【经验账本】");
    }

    /**
     * 一行都认不出、也没写「本期无更新」：记成 ERROR（素材窗口不往前推），正文照存，账本不动，
     * 本批单子不盖编号、下次还在。只写了编号归类行也是这样：那种行不认
     */
    @Test
    void noParsableOpsWritesErrorRowWithBodyAndLeavesLedgerAlone() {
        stubMaterial();
        ChatModel model = modelReturning("【本期复盘】\n战绩：……\n【经验更新】\n写了一段散文，没有一行是表里的\n#1 名下 42");
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.review(trader(), BOUNDARY);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        assertThat(dec.getValue().getReasoning()).contains("散文");
        assertThat(dec.getValue().getError()).contains("账本未更新");
        assertThat(dec.getValue().getMemoryAfter()).isNull();
        verify(ledger, never()).apply(anyLong(), anyInt(), any(), any(), any(), any());
        verify(planStore, never()).markReviewed(any(), anyLong());
        verify(ledger, never()).settle(anyLong(), anyInt(), anyLong(), anyBoolean(), any());
        verify(ledger, never()).writeMemory(any(), any());
    }

    @Test
    void noMaterialSkipsWithoutModelCallOrRow() {
        when(assembler.lastReview(7L, 1)).thenReturn(null);
        when(assembler.hasNewMaterial(any(), anyLong(), anyLong())).thenReturn(false);

        runner.review(trader(), BOUNDARY);

        verify(modelFactory, never()).modelFor(any());
        verify(decisionMapper, never()).insert(any(AiTraderDecision.class));
    }

    @Test
    void failureWritesErrorRowWithoutMemoryOrFailureCount() {
        stubMaterial();
        when(modelFactory.modelFor(any())).thenThrow(new SseChatModel.UpstreamRejected(401, "上游401"));

        runner.review(trader(), BOUNDARY);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        // 公开行只存归类文案，上游原文不落库
        assertThat(dec.getValue().getError()).contains("API key").doesNotContain("上游401");
        verify(ledger, never()).writeMemory(any(), any());
        verify(ledger, never()).apply(anyLong(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void emptyOutputWritesErrorRow() {
        stubMaterial();
        ChatModel empty = modelReturning("");
        when(modelFactory.modelFor(any())).thenReturn(empty);

        runner.review(trader(), BOUNDARY);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        assertThat(dec.getValue().getError()).contains("输出为空");
    }

    @Test
    void timeoutWritesErrorRow() {
        stubMaterial();
        ChatModel slow = mock(ChatModel.class);
        when(slow.call(any(Prompt.class))).thenAnswer(inv -> {
            Thread.sleep(3_000);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("迟到的复盘"))));
        });
        when(modelFactory.modelFor(any())).thenReturn(slow);
        runner.timeoutSeconds = 1;

        runner.review(trader(), BOUNDARY);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        assertThat(dec.getValue().getError()).contains("超时");
        verify(ledger, never()).writeMemory(any(), any());
    }

    private static AiTraderExperience entry(long id, String text) {
        AiTraderExperience e = new AiTraderExperience();
        e.setId(id);
        e.setText(text);
        return e;
    }
}
