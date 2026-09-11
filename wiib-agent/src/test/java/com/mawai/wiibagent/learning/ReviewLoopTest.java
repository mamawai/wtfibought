package com.mawai.wiibagent.learning;

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
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复盘回路 mock 测试：真 ReviewMaterialAssembler + 真 ReviewRunner 贯通跑一遍——
 * 组装出的硬事实真进了提示词、模型两段产出真落了 REVIEW 行与 memory。
 * （L2/L3 各自的单测 mock 掉了对方，这条缝必须真接一次才算数。）
 */
class ReviewLoopTest {

    private static final long DAY_BOUNDARY = 1785196800000L; // 2026-07-28 00:00:00 UTC
    private static final long FROM = DAY_BOUNDARY - 86_400_000L;

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderPlan.class);
    }

    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);
    private final AiTraderPlanMapper planMapper = mock(AiTraderPlanMapper.class);
    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final KlineHistoryStore historyStore = mock(KlineHistoryStore.class);
    private final TraderModelFactory modelFactory = mock(TraderModelFactory.class);
    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);

    private final UserLangResolver langResolver = mock(UserLangResolver.class);

    private final ReviewRunner runner = new ReviewRunner(
            new ReviewMaterialAssembler(decisionMapper, planMapper, simTradeClient, historyStore,
                    new PromptCatalog(), new DecisionText(new PromptCatalog())),
            modelFactory, traderMapper, decisionMapper, new PromptCatalog(), langResolver);

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
        t.setMemory("旧笔记：止损设得太紧被扫过两次");
        return t;
    }

    private static AiTraderDecision okTradeRow(long wakeTime, String equity, String reasoning) {
        AiTraderDecision d = new AiTraderDecision();
        d.setWakeTime(wakeTime);
        d.setKind(AiTraderDecision.KIND_TRADE);
        d.setStatus(AiTraderDecision.STATUS_OK);
        d.setEquity(equity == null ? null : new BigDecimal(equity));
        d.setReasoning(reasoning);
        d.setActionsJson("[]");
        return d;
    }

    @Test
    void fullLoopAssemblesMaterialCallsModelAndPersistsBothOutputs() {
        // 素材：一笔止损带走的多单 + 带等待条件的 HOLD 行 + 1h 价格路径
        FuturesPositionDTO pos = new FuturesPositionDTO();
        pos.setSymbol("BTCUSDT");
        pos.setSide("LONG");
        pos.setStatus("CLOSED");
        pos.setEntryPrice(new BigDecimal("100000"));
        pos.setClosedPrice(new BigDecimal("98900"));
        pos.setClosedPnl(new BigDecimal("-11"));
        pos.setQuantity(new BigDecimal("0.01"));
        pos.setStopLosses(List.of(new FuturesStopLoss("s1", new BigDecimal("99000"), new BigDecimal("0.01"))));
        pos.setCreatedAt(Instant.ofEpochMilli(FROM + 3600_000).atZone(ZoneId.systemDefault()).toLocalDateTime());
        pos.setUpdatedAt(Instant.ofEpochMilli(FROM + 18_000_000).atZone(ZoneId.systemDefault()).toLocalDateTime());
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(pos));

        AiTraderPlan plan = new AiTraderPlan();
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        plan.setPlayType("BREAKOUT");
        plan.setSignalsUsed("突破前高100200");
        plan.setInvalidationCondition("1h收盘跌回99500下方");
        plan.setStatus(AiTraderPlan.STATUS_CLOSED);
        plan.setOpenedWakeTime(FROM + 3600_000);
        plan.setClosedWakeTime(FROM + 21_600_000);
        when(planMapper.selectList(any())).thenReturn(List.of(plan));

        // decisionMapper 调用顺序：lastReview(selectOne#1)→statsBlock前值(selectOne#2)
        // →权益序列(selectList#1)→时间线(selectList#2)；hasNewMaterial 走 selectCount
        when(decisionMapper.selectOne(any())).thenReturn(null, null);
        when(decisionMapper.selectCount(any())).thenReturn(2L);
        when(decisionMapper.selectList(any())).thenReturn(
                List.of(okTradeRow(FROM + 3600_000, "10000", null),
                        okTradeRow(FROM + 18_000_000, "9989", null)),
                List.of(okTradeRow(FROM + 3600_000, "10000",
                                "[本轮结论]\n判断：突破确认\n动作：开多BTCUSDT\n等待：无"),
                        okTradeRow(FROM + 18_000_000, "9989",
                                "[本轮结论]\n判断：止损离场\n动作：HOLD\n等待：99000上方站稳再进"),
                        // 同条件再等 6h：真观望升格对账块，等待条件全文才进素材（短观望只进汇总行）
                        okTradeRow(FROM + 39_600_000, "9989",
                                "[本轮结论]\n判断：仍在等\n动作：HOLD\n等待：99000上方站稳再进")));
        // 真本地库路径：5m bar 进去，组装器现聚合成 1h
        long h = FROM + 3600_000;
        when(historyStore.load(eq("BTCUSDT"), eq("5m"), anyLong(), anyLong())).thenReturn(List.of(
                new KlineBar(h, h + 299_999L, new BigDecimal("100000"), new BigDecimal("100300"),
                        new BigDecimal("99900"), new BigDecimal("100100"), BigDecimal.ZERO),
                new KlineBar(h + 300_000L, h + 599_999L, new BigDecimal("100100"), new BigDecimal("100200"),
                        new BigDecimal("98800"), new BigDecimal("99100"), BigDecimal.ZERO)));

        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("""
                        【本期复盘】
                        战绩：起始权益 10000.00 → 期末权益 9989.00，期间收益率 -0.11%
                        逐笔教训：1. BTCUSDT 多单 100000 进 98900 出，止损带走 -11
                        观望对账：等待99000上方站稳 → 未命中（收99100贴着）→ 该等没等，对
                        下期纪律：突破单止损放结构位下方
                        【记忆更新】
                        止损太紧的老毛病又犯了一次：这次 99000 结构位上方 1000 点就被扫。下期止损只放结构位下。""")))));
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.review(trader(), DAY_BOUNDARY);

        // 提示词里是组装器产出的硬事实原文 + 旧笔记
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompts.capture());
        String all = prompts.getValue().getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .reduce("", String::concat);
        assertThat(all).contains("起始权益 10000.00").contains("-0.11%");          // 战绩表代码算好
        assertThat(all).contains("BREAKOUT").contains("止损带走").contains("-11"); // 配对表论点→结局
        assertThat(all).contains("99000上方站稳再进");                              // 摘编保住等待条件
        assertThat(all).contains("98800");                                         // 价格路径（最低价）
        assertThat(all).contains("止损设得太紧被扫过两次");                          // 旧笔记在场

        // REVIEW 行落库 + memory 覆盖写
        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getKind()).isEqualTo(AiTraderDecision.KIND_REVIEW);
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(dec.getValue().getReasoning()).contains("逐笔教训").doesNotContain("老毛病又犯");

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> up =
                ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(traderMapper).update(any(), up.capture());
        assertThat(up.getValue().getParamNameValuePairs().values().stream()
                .anyMatch(v -> v instanceof String s && s.contains("老毛病又犯"))).isTrue();
    }
}
