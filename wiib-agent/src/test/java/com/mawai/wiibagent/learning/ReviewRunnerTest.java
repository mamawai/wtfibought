package com.mawai.wiibagent.learning;

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
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReviewRunner 回路测试：两段解析/降级安全（缺分隔符不动 memory）/超预算照存全文/
 * 无素材跳过/失败只留 ERROR 行不计连败。提示词按习惯配套断言。
 */
class ReviewRunnerTest {

    private static final long BOUNDARY = 1785110400000L + 86_400_000L;

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
    }

    private final ReviewMaterialAssembler assembler = mock(ReviewMaterialAssembler.class);
    private final TraderModelFactory modelFactory = mock(TraderModelFactory.class);
    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);

    /** 语言解析钉在中文：本类钉的是复盘回路行为，双语文案由 PromptI18nTest 单管 */
    private final UserLangResolver langResolver = mock(UserLangResolver.class);

    private final ReviewRunner runner = new ReviewRunner(assembler, modelFactory, traderMapper,
            decisionMapper, new PromptCatalog(), langResolver);

    {
        when(langResolver.of(anyLong())).thenReturn(AgentLang.ZH);
    }

    private AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(1);
        t.setSimUserId(99L);
        t.setSymbols("BTCUSDT");
        t.setIntervalCode("1h");
        t.setMemory("旧笔记：追高是我的老毛病");
        return t;
    }

    /** 上一期复盘行：窗口起点 + 本期要承接检验的全文 */
    private AiTraderDecision priorReview() {
        AiTraderDecision d = new AiTraderDecision();
        d.setWakeTime(BOUNDARY - 86_400_000L);
        d.setReasoning("""
                【本期复盘】
                战绩：起始权益 10200.00 → 期末权益 10000.00
                下期纪律：1. 突破必须等回踩确认 2. 单日最多开两笔""");
        return d;
    }

    private void stubMaterial() {
        when(assembler.lastReview(7L, 1)).thenReturn(priorReview());
        when(assembler.hasNewMaterial(eq(7L), eq(1), anyLong(), anyLong())).thenReturn(true);
        when(assembler.assemble(any(), anyLong(), anyLong(), any())).thenReturn(
                new ReviewMaterialAssembler.ReviewMaterial(
                        "【战绩表】起始权益 10000.00 → 期末权益 9800.00，期间收益率 -2.00%\n",
                        "【已了结交易配对表】1. BTCUSDT LONG [BREAKOUT] …止损带走\n",
                        "【决策时间线摘编】- 08-07 16:00 …等待：跌破100500减仓\n",
                        "【各币1h价格路径】- BTCUSDT: 开 61000 → 收 60000（-1.64%）\n", 1));
    }

    private ChatModel modelReturning(String text) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                        ChatResponseMetadata.builder().usage(new DefaultUsage(1000, 400)).build()));
        return model;
    }

    private static final String TWO_PART_OUTPUT = """
            【本期复盘】
            战绩：起始权益 10000.00 → 期末权益 9800.00，期间收益率 -2.00%
            逐笔教训：1. BTCUSDT 多单追高被止损带走，入场即峰值
            观望对账：等待跌破100500减仓 → 未命中（最低100800）→ 该等没等，对
            下期纪律：突破回踩确认再进
            【记忆更新】
            旧毛病仍在：追高。下期只做回踩确认。""";

    @Test
    void successfulReviewPersistsReviewRowAndOverwritesMemory() {
        stubMaterial();
        // 先建好再 stub：thenReturn 参数里嵌套 when() 是 UnfinishedStubbing
        ChatModel model = modelReturning(TWO_PART_OUTPUT);
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.review(trader(), BOUNDARY);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        AiTraderDecision d = dec.getValue();
        assertThat(d.getKind()).isEqualTo(AiTraderDecision.KIND_REVIEW);
        assertThat(d.getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(d.getWakeTime()).isEqualTo(BOUNDARY);
        assertThat(d.getIntervalCode()).isEqualTo("1d");
        // REVIEW 行存【本期复盘】段（不含记忆段），不记 equity（净值曲线 isNotNull 过滤天然不受影响）
        assertThat(d.getReasoning()).contains("逐笔教训").contains("观望对账").doesNotContain("旧毛病仍在");
        assertThat(d.getEquity()).isNull();
        // 学习快照随行存档：memory 是滚动覆盖的，历史版本只活在这一列
        assertThat(d.getMemoryAfter()).isEqualTo("旧毛病仍在：追高。下期只做回踩确认。");
        // 用量照记：复盘也烧用户的钱
        assertThat(d.getModelCalls()).isEqualTo(1);
        assertThat(d.getPromptTokens()).isEqualTo(1000L);
        assertThat(d.getCompletionTokens()).isEqualTo(400L);

        // memory 全文覆盖写（列级更新）
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> up =
                ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(traderMapper).update(any(), up.capture());
        assertThat(up.getValue().getSqlSet()).contains("memory");
        assertThat(up.getValue().getParamNameValuePairs())
                .containsValue("旧毛病仍在：追高。下期只做回踩确认。");
    }

    @Test
    void promptCarriesIdentityMaterialOldMemoryAndContract() {
        stubMaterial();
        ChatModel model = modelReturning(TWO_PART_OUTPUT);
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.review(trader(), BOUNDARY);

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompts.capture());
        String all = prompts.getValue().getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .reduce("", String::concat);
        // 身份：给自己写日志的交易员而非评价者；单问题框架；防自夸三件套
        assertThat(all).contains("写给明天").contains("哪里错了");
        assertThat(all).contains("只许原样复述").contains("先找错误");
        // 两段输出契约 + 条数上限
        assertThat(all).contains("【本期复盘】").contains("【记忆更新】").contains("2000");
        // 四块素材 + 旧笔记全文在场
        assertThat(all).contains("【战绩表】").contains("止损带走")
                .contains("等待：跌破100500减仓").contains("1h价格路径");
        assertThat(all).contains("追高是我的老毛病");
        // 观望对账的对照物有边界：范围外（尤其开局前）的价格不算证据，否则首篇复盘会拿开局前行情自证
        assertThat(all).contains("覆盖范围内的价格").contains("开局前");
        // 闭环：上一期复盘全文在场 + 本期必须给上期纪律逐条结账（只回注上一期，不堆全部历史）
        assertThat(all).contains("【上一期复盘】").contains("突破必须等回踩确认");
        assertThat(all).contains("上期纪律先结账").contains("逐条对照");
        // 滚动继承：下一期只看得到这一篇，所以这一篇必须自带全部有效认知，
        // 否则"只回注上一期"就会把更早的教训丢掉
        assertThat(all).contains("这篇复盘是滚动的").contains("只会看到这一篇")
                .contains("仍然成立的教训与纪律要继承");
        assertThat(all).contains("独立看懂");
        // 学习宗旨四件套：教训二分类（复盘过程不复盘运气，防"亏一次就不敢开仓"）、
        // 该做没做与做错同罪+保守度自检（对称记账）、纪律可证伪淘汰（防只进不出）、
        // 记忆两栏带样本数（防单次样本被当铁律盲信）
        assertThat(all).contains("【决策错】").contains("【运气差】").contains("同样条件下次照做");
        assertThat(all).contains("该做没做与做错同罪").contains("保守度自检");
        // 对错的尺子是主人的交易指令：段头在场，下期纪律不许与它相抵触
        assertThat(all).contains("【主人的交易指令】").contains("不得与主人的交易指令相抵触");
        assertThat(all).contains("削弱").contains("不许只进不出");
        assertThat(all).contains("【已验证纪律】").contains("【待验证假设】").contains("样本数");
    }

    /** 主人的交易指令原文进复盘用户消息，排在硬事实之前；没写就是占位句 */
    @Test
    void ownerInstructionsInjectedBeforeMaterial() {
        AiTrader t = trader();
        t.setCustomPrompt("只做突破，不抄底。");
        ReviewMaterialAssembler.ReviewMaterial m = new ReviewMaterialAssembler.ReviewMaterial(
                "统计块", "配对块", "时间线块", "路径块", 1);

        String user = runner.userPrompt(t, m, 0, BOUNDARY, null, AgentLang.ZH);

        assertThat(user).contains("【主人的交易指令】").contains("只做突破，不抄底。").doesNotContain("主人没写指令");
        assertThat(user.indexOf("只做突破，不抄底。")).isLessThan(user.indexOf("统计块"));

        t.setCustomPrompt(" ");
        assertThat(runner.userPrompt(t, m, 0, BOUNDARY, null, AgentLang.ZH)).contains("主人没写指令");
    }

    @Test
    void missingSeparatorStoresReviewButKeepsMemory() {
        stubMaterial();
        ChatModel model = modelReturning("【本期复盘】\n战绩：……格式失守没写记忆段");
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.review(trader(), BOUNDARY);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(dec.getValue().getReasoning()).contains("格式失守");
        assertThat(dec.getValue().getMemoryAfter()).isNull();   // 没产出记忆段，快照列同样空着
        // 降级安全：一次格式失守不许污染记忆
        verify(traderMapper, never()).update(any(), any());
    }

    /** 篇幅只由提示词那句"≤N"约束：模型写超了照样整段落库，代码不替它裁 */
    @Test
    void memoryStoredInFullEvenOverBudget() {
        stubMaterial();
        String longMemory = "记忆里每一句都以句号收尾。".repeat(160);
        ChatModel model = modelReturning("【本期复盘】\n战绩：……\n【记忆更新】\n" + longMemory);
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.review(trader(), BOUNDARY);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> up =
                ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(traderMapper).update(any(), up.capture());
        String written = up.getValue().getParamNameValuePairs().values().stream()
                .filter(v -> v instanceof String s && s.startsWith("记"))
                .map(String.class::cast).findFirst().orElseThrow();
        assertThat(written.length()).isGreaterThan(NoteBudget.maxChars(AgentLang.ZH));   // 确实超了预算
        assertThat(written).isEqualTo(longMemory);
    }

    @Test
    void noMaterialSkipsWithoutModelCallOrRow() {
        when(assembler.lastReview(7L, 1)).thenReturn(null);
        when(assembler.hasNewMaterial(eq(7L), eq(1), anyLong(), anyLong())).thenReturn(false);

        runner.review(trader(), BOUNDARY);

        verify(modelFactory, never()).modelFor(any());
        verify(decisionMapper, never()).insert(any(AiTraderDecision.class));
    }

    /** 门控自己抖了（sim/K线故障）也得落 ERROR 行——review.started 承诺"失败也会留记录，不会没有下文" */
    @Test
    void quietHoldWindowFailureStillWritesErrorRow() {
        when(assembler.lastReview(7L, 1)).thenReturn(priorReview());
        when(assembler.hasNewMaterial(eq(7L), eq(1), anyLong(), anyLong())).thenReturn(true);
        when(assembler.quietHoldWindow(any(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("sim down"));

        runner.review(trader(), BOUNDARY);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
    }

    /** 观望门控（口径8）：纯观望且各币平静 → 留 SKIPPED 行注明缘由，不烧模型调用 */
    @Test
    void quietHoldWindowLeavesSkippedRowWithoutModelCall() {
        when(assembler.lastReview(7L, 1)).thenReturn(priorReview());
        when(assembler.hasNewMaterial(eq(7L), eq(1), anyLong(), anyLong())).thenReturn(true);
        when(assembler.quietHoldWindow(any(), anyLong(), anyLong())).thenReturn(true);

        runner.review(trader(), BOUNDARY);

        verify(modelFactory, never()).modelFor(any());
        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getKind()).isEqualTo(AiTraderDecision.KIND_REVIEW);
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_SKIPPED);
        assertThat(dec.getValue().getError()).contains("平静").contains("2%");
    }

    @Test
    void failureWritesErrorRowWithoutMemoryOrFailureCount() {
        stubMaterial();
        when(modelFactory.modelFor(any())).thenThrow(new IllegalStateException("上游401"));

        runner.review(trader(), BOUNDARY);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getKind()).isEqualTo(AiTraderDecision.KIND_REVIEW);
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        // 公开行只存归类文案，上游原文不落库
        assertThat(dec.getValue().getError()).contains("API key").doesNotContain("上游401");
        // 不动 memory、不计连败（复盘失败没有资金风险，trader 行一个字段都不碰）
        verify(traderMapper, never()).update(any(), any());
    }

    /** 模型空输出：ERROR 行写"输出为空"这句给用户看的话，不走异常归类 */
    @Test
    void emptyOutputWritesErrorRow() {
        stubMaterial();
        ChatModel model = modelReturning("");
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.review(trader(), BOUNDARY);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        assertThat(dec.getValue().getError()).contains("输出为空");
        verify(traderMapper, never()).update(any(), any());
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
        verify(traderMapper, never()).update(any(), any());
    }
}
