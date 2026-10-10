package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.entity.AiTraderExperienceVerdict;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibquant.external.sim.SimTradeClient;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
 * ReviewRunner 回路：解析落账 / 账本渲染写回 memory / 一行解析不出账本不动 / 无素材跳过 /
 * 失败只留 ERROR 行不计连败 / 有旗再调一次复审 / 旧记忆先迁移。
 */
class ReviewRunnerTest {

    private static final long BOUNDARY = 1785110400000L + 86_400_000L;

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

    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);

    private final ReviewRunner runner = new ReviewRunner(assembler, modelFactory,
            decisionMapper, prompts, langResolver, ledger, new ExperienceOpParser(prompts), simTradeClient);

    {
        when(langResolver.of(anyLong())).thenReturn(AgentLang.ZH);
        // insert 回填 id：结论流水要挂在这篇复盘上
        when(decisionMapper.insert(any(AiTraderDecision.class))).thenAnswer(inv -> {
            ((AiTraderDecision) inv.getArgument(0)).setId(900L);
            return 1;
        });
        when(ledger.isRendered(any())).thenAnswer(inv -> {
            String m = inv.getArgument(0);
            return m != null && m.startsWith("【经验账本】");
        });
        when(ledger.apply(anyLong(), anyInt(), any(), any(), any(), any())).thenReturn(new ExperienceLedger.Applied(2, 0));
        when(ledger.writeMemory(any(), any())).thenReturn("【经验账本】\n#1 规律 执行型 ｜ 出场只认目标位");
    }

    private AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(1);
        t.setSimUserId(99L);
        t.setSymbols("BTCUSDT");
        t.setIntervalCode("1h");
        t.setMemory("【经验账本】\n#1 规律 执行型 ｜ 出场只认目标位");
        return t;
    }

    private void stubMaterial() {
        when(assembler.lastReview(7L, 1)).thenReturn(null);
        when(assembler.hasNewMaterial(any(), anyLong(), anyLong())).thenReturn(true);
        when(assembler.assemble(any(), anyLong(), anyLong(), any())).thenReturn(
                new ReviewMaterialAssembler.ReviewMaterial(
                        "【战绩表】起始权益 10000.00 → 期末权益 9800.00，期间收益率 -2.00%\n",
                        "————— 本局论点战绩 —————\nPULLBACK：6笔 4胜2负 盈亏合计+7946 USDT\n",
                        "【案例素材】\n★ PULLBACK（2 笔，1 胜，净 -300）\n- BTCUSDT LONG …\n",
                        "【本期行情】\n- BTCUSDT 81144 → 86579，低到高 8.1%\n",
                        "【经验账本】（现役 1 条）\n#1 规律 盈利处理 执行型 PULLBACK ｜ 出场只认目标位 ｜ 支持 5 反对 1 未测试 0 ｜ 证据 x\n", 2,
                        Map.of("PULLBACK", List.of(42L))));
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

    private static final String OUTPUT = """
            【本期复盘】
            战绩：起始权益 10000.00 → 期末权益 9800.00，期间收益率 -2.00%
            案例一：PULLBACK（2 笔，净 -300）
            路径：…
            机会：没错过
            【经验更新】
            #1 支持 ｜ 09-21 BTC 平在目标位
            新增 PULLBACK 失败判定 执行型 ｜ 90 分钟没到目标 25% 即判失败 ｜ ETH 09:32、SOL 23:33""";

    private static String allText(Prompt p) {
        return p.getInstructions().stream().map(Message::getText).reduce("", String::concat);
    }

    @Test
    void successfulReviewAppliesOpsRendersLedgerAndWritesMemory() {
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
        assertThat(d.getReasoning()).contains("案例一").contains("#1 支持");
        assertThat(d.getEquity()).isNull();
        assertThat(d.getError()).isNull();
        assertThat(d.getModelCalls()).isEqualTo(1);
        assertThat(d.getPromptTokens()).isEqualTo(1000L);

        // 两行操作带着复盘行 id 与本期仓位分组落账
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExperienceLedger.Op>> ops = ArgumentCaptor.forClass((Class) List.class);
        verify(ledger).apply(eq(7L), eq(1), eq(900L), ops.capture(), eq(AgentLang.ZH), eq(Map.of("PULLBACK", List.of(42L))));
        assertThat(ops.getValue()).hasSize(2);
        assertThat(ops.getValue().get(0)).isEqualTo(new ExperienceLedger.Verdict(1L, AiTraderExperienceVerdict.SUPPORT, "09-21 BTC 平在目标位"));

        // 账本自己把渲染文本写回 ai_trader.memory；复盘行的快照按列写 memory_after
        assertThat(d.getMemoryAfter()).startsWith("【经验账本】");
        verify(ledger).writeMemory(any(), eq(AgentLang.ZH));
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<AiTraderDecision>> up = ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(decisionMapper).update(isNull(), up.capture());
        assertThat(up.getValue().getSqlSet()).contains("memory_after");
        assertThat(up.getValue().getParamNameValuePairs()).containsValue("【经验账本】\n#1 规律 执行型 ｜ 出场只认目标位");
        verify(decisionMapper, never()).updateById(any(AiTraderDecision.class));
        // 没有旗就不复审
        verify(model, times(1)).call(any(Prompt.class));
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
        // 身份与五问
        assertThat(all).contains("写给明天").contains("失败怎么界定").contains("赢在判断还是运气").contains("少赚了多少");
        // 事实纪律：数字只许引用、界定要可观察、不复述指令、不继承旧教训
        assertThat(all).contains("只许原样复述").contains("可观察的价格、根数或时间")
                .contains("主人指令里已经有的不写进账本").contains("上期的教训不许重抄");
        // 输出契约
        assertThat(all).contains("【本期复盘】").contains("【经验更新】").contains("新增 <单子类型代码或 通用>");
        // 五块素材 + 主人指令在场，指令排在素材前
        assertThat(all).contains("【战绩表】").contains("本局论点战绩").contains("★ PULLBACK")
                .contains("【本期行情】").contains("#1 规律 盈利处理");
        assertThat(all).contains("【主人的交易指令】").contains("只做突破，不抄底。");
        assertThat(all.indexOf("只做突破，不抄底。")).isLessThan(all.indexOf("【战绩表】"));
        assertThat(all.stripTrailing()).endsWith("输出语言：中文。");
    }

    @Test
    void noParsableOpsKeepsReviewRowButLeavesLedgerAlone() {
        stubMaterial();
        ChatModel model = modelReturning("【本期复盘】\n战绩：……\n【经验更新】\n写了一段散文，没有一行是表里的");
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.review(trader(), BOUNDARY);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(dec.getValue().getReasoning()).contains("散文");
        assertThat(dec.getValue().getError()).contains("账本未更新");
        assertThat(dec.getValue().getMemoryAfter()).isNull();
        verify(ledger, never()).apply(anyLong(), anyInt(), any(), any(), any(), any());
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
        when(modelFactory.modelFor(any())).thenThrow(new IllegalStateException("上游401"));

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

    /** 有旗：第二次调用复审，结论落账，正文末尾追加复审段，记忆重渲染 */
    @Test
    void flaggedEntriesTriggerAuditCall() {
        stubMaterial();
        AiTraderExperience flagged = new AiTraderExperience();
        flagged.setId(5L);
        flagged.setPlayType("PULLBACK");
        flagged.setText("动能变小即落袋");
        flagged.setSupports(1);
        flagged.setOpposes(3);
        flagged.setUntested(0);
        when(ledger.flagged(7L, 1)).thenReturn(List.of(flagged));
        when(assembler.auditBlock(any(), eq(flagged), any(), any())).thenReturn("#5 PULLBACK ｜ 动能变小即落袋 ｜ 支持 1 反对 3 未测试 0 ｜ 支持率 25% ｜ 历史最长连续反对 3 次\n- 反对 ｜ 出场后 4h 再走 1.1%\n");
        when(ledger.applyAudit(eq(7L), eq(1), eq(900L), any(), eq(AgentLang.ZH))).thenReturn(true);
        ChatModel model = modelReturning(OUTPUT, "#5 退役 ｜ 近 4 次 1 支持 3 反对，出场后均续涨");
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.review(trader(), BOUNDARY);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompt.capture());
        String audit = allText(prompt.getAllValues().get(1));
        assertThat(audit).contains("复审").contains("保留").contains("修订").contains("拆分").contains("退役")
                .contains("动能变小即落袋").contains("历史最长连续反对 3 次");
        verify(ledger).applyAudit(eq(7L), eq(1), eq(900L),
                eq(new ExperienceLedger.Audit(5L, ExperienceLedger.AUDIT_RETIRE, null, "近 4 次 1 支持 3 反对，出场后均续涨")), eq(AgentLang.ZH));
        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).updateById(dec.capture());
        assertThat(dec.getValue().getReasoning()).contains("【复审结论】").contains("#5 退役");
        // 两次调用的用量累加
        assertThat(dec.getValue().getModelCalls()).isEqualTo(2);
        // 复审改了账本 → 复盘后、复审后各渲染一次
        verify(ledger, times(2)).writeMemory(any(), eq(AgentLang.ZH));
    }

    /** 拆分：来源对上的子条一起交给账本，账本没收下（如子条不够）就不重渲染，旗留到下期 */
    @Test
    void splitRejectedByLedgerKeepsFlagAndMemory() {
        stubMaterial();
        AiTraderExperience flagged = flaggedEntry();
        when(ledger.flagged(7L, 1)).thenReturn(List.of(flagged));
        when(assembler.auditBlock(any(), eq(flagged), any(), any())).thenReturn("#5 PULLBACK ｜ 动能变小即落袋\n");
        ChatModel model = modelReturning(OUTPUT,
                "#5 拆分 ｜ ADX 高低结局相反\n新增 PULLBACK 入场条件 机会型 ｜ ADX>40 时回踩做多 ｜ 6 笔 5 胜 ｜ 来源 #5");
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.review(trader(), BOUNDARY);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExperienceLedger.Add>> children = ArgumentCaptor.forClass((Class) List.class);
        verify(ledger).split(eq(7L), eq(1), eq(900L),
                eq(new ExperienceLedger.Audit(5L, ExperienceLedger.AUDIT_SPLIT, null, "ADX 高低结局相反")), children.capture(), eq(AgentLang.ZH));
        assertThat(children.getValue()).hasSize(1);
        verify(ledger, never()).applyAudit(anyLong(), anyInt(), any(), any(), any());
        // 子条不走 apply；拆分没生效就只有复盘后那一次渲染
        verify(ledger, times(1)).apply(anyLong(), anyInt(), any(), any(), any(), any());
        verify(ledger, times(1)).writeMemory(any(), any());
    }

    /** 拆分：只有来源对上的子条交给账本，账本收下就重渲染 */
    @Test
    void splitPassesMatchingChildrenAndRerendersOnSuccess() {
        stubMaterial();
        AiTraderExperience flagged = flaggedEntry();
        when(ledger.flagged(7L, 1)).thenReturn(List.of(flagged));
        when(assembler.auditBlock(any(), eq(flagged), any(), any())).thenReturn("#5 PULLBACK ｜ 动能变小即落袋\n");
        when(ledger.split(eq(7L), eq(1), eq(900L), any(), any(), eq(AgentLang.ZH))).thenReturn(true);
        ChatModel model = modelReturning(OUTPUT, """
                #5 拆分 ｜ ADX 高低结局相反
                新增 PULLBACK 入场条件 机会型 ｜ ADX>40 时回踩做多 ｜ 6 笔 5 胜 ｜ 来源 #5
                新增 PULLBACK 入场条件 限制型 ｜ ADX<30 不做回踩 ｜ 4 笔 0 胜 ｜ 来源 #5
                新增 BREAKOUT 入场条件 机会型 ｜ 没人拆分它 ｜ x ｜ 来源 #8""");
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.review(trader(), BOUNDARY);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExperienceLedger.Add>> children = ArgumentCaptor.forClass((Class) List.class);
        verify(ledger).split(eq(7L), eq(1), eq(900L),
                eq(new ExperienceLedger.Audit(5L, ExperienceLedger.AUDIT_SPLIT, null, "ADX 高低结局相反")), children.capture(), eq(AgentLang.ZH));
        assertThat(children.getValue()).hasSize(2).allMatch(a -> a.sourceId() == 5L);
        verify(ledger, times(1)).apply(anyLong(), anyInt(), any(), any(), any(), any());
        verify(ledger, times(2)).writeMemory(any(), eq(AgentLang.ZH));
    }

    /** 旧记忆一条都没压出来：memory 不动、复盘照常跑，memory 只在复盘落账后渲染一次，下次复盘再迁 */
    @Test
    void migrationWithNoRowsLeavesMemoryAndReviewGoesOn() {
        stubMaterial();
        AiTrader t = trader();
        t.setMemory("【已验证纪律】\n全是主人指令的复述");
        when(ledger.active(7L, 1)).thenReturn(List.of());
        when(ledger.retired(7L, 1)).thenReturn(List.of());
        when(ledger.apply(anyLong(), anyInt(), isNull(), any(), any(), any())).thenReturn(new ExperienceLedger.Applied(0, 2));
        ChatModel model = modelReturning("新增 BREAKOUT 入场条件 机会型 ｜ 指令里已有的 ｜ 没证据", OUTPUT);
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.review(t, BOUNDARY);

        verify(model, times(2)).call(any(Prompt.class));
        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        verify(ledger, times(1)).writeMemory(any(), eq(AgentLang.ZH));
    }

    private static AiTraderExperience flaggedEntry() {
        AiTraderExperience flagged = new AiTraderExperience();
        flagged.setId(5L);
        flagged.setPlayType("PULLBACK");
        flagged.setText("动能变小即落袋");
        flagged.setSupports(1);
        flagged.setOpposes(3);
        flagged.setUntested(0);
        return flagged;
    }

    /** 旧的自由文本记忆、账本还空着：先迁移一次，再复盘；迁移条目不挂复盘行 */
    @Test
    void legacyMemoryIsMigratedBeforeFirstLedgerReview() {
        stubMaterial();
        AiTrader t = trader();
        t.setMemory("【已验证纪律】\n1. 突破回踩单：浮盈过目标一半又收回入场价即判失败（样本 3）");
        when(ledger.active(7L, 1)).thenReturn(List.of());
        when(ledger.retired(7L, 1)).thenReturn(List.of());
        ChatModel model = modelReturning(
                "新增 BREAKOUT 失败判定 执行型 ｜ 浮盈过目标一半又收回入场价即判失败 ｜ 样本 3",
                OUTPUT);
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.review(t, BOUNDARY);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompt.capture());
        String migrate = allText(prompt.getAllValues().get(0));
        assertThat(migrate).contains("旧复盘笔记").contains("浮盈过目标一半").contains("最多 10 条")
                .contains("主人指令里已经有的");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExperienceLedger.Op>> ops = ArgumentCaptor.forClass((Class) List.class);
        verify(ledger).apply(eq(7L), eq(1), isNull(), ops.capture(), eq(AgentLang.ZH), eq(Map.of()));
        assertThat(ops.getValue()).containsExactly(new ExperienceLedger.Add("BREAKOUT", AiTraderExperience.SLOT_FAIL_DEF,
                AiTraderExperience.DIR_EXECUTION, "浮盈过目标一半又收回入场价即判失败", "样本 3", null));
        // 复盘本身照常落账；迁移成功后先渲染一次 memory，复盘后再一次
        verify(ledger).apply(eq(7L), eq(1), eq(900L), any(), eq(AgentLang.ZH), any());
        verify(ledger, times(2)).writeMemory(any(), eq(AgentLang.ZH));
    }

    /** 记忆已经是账本渲染的文本：不再迁移 */
    @Test
    void renderedMemoryIsNotMigratedAgain() {
        stubMaterial();
        ChatModel model = modelReturning(OUTPUT);
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.review(trader(), BOUNDARY);

        verify(model, times(1)).call(any(Prompt.class));
        verify(ledger, never()).apply(anyLong(), anyInt(), isNull(), any(), any(), any());
    }
}
