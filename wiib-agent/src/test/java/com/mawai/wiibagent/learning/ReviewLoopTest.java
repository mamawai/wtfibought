package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderExperienceMapper;
import com.mawai.wiibagent.mapper.AiTraderExperienceVerdictMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.entity.AiTraderExperienceVerdict;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复盘回路贯通：真 assembler + 真事实表 + 真账本 + 真解析器，只 mock 库与模型——
 * 组装出的事实真进了提示词，模型的更新表真落了账、真渲染进了 memory。
 */
class ReviewLoopTest {

    private static final long DAY_BOUNDARY = 1785196800000L;
    private static final long FROM = DAY_BOUNDARY - 86_400_000L;

    @BeforeAll
    static void initTableInfoCache() {
        for (Class<?> c : List.of(AiTrader.class, AiTraderDecision.class, AiTraderPlan.class,
                AiTraderExperience.class, AiTraderExperienceVerdict.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), c);
        }
    }

    private final PromptCatalog prompts = new PromptCatalog();
    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);
    private final AiTraderPlanMapper planMapper = mock(AiTraderPlanMapper.class);
    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final KlineHistoryStore historyStore = mock(KlineHistoryStore.class);
    private final TraderModelFactory modelFactory = mock(TraderModelFactory.class);
    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final AiTraderExperienceMapper expMapper = mock(AiTraderExperienceMapper.class);
    private final AiTraderExperienceVerdictMapper verdictMapper = mock(AiTraderExperienceVerdictMapper.class);
    private final UserLangResolver langResolver = mock(UserLangResolver.class);
    private final ExperienceLedger ledger = new ExperienceLedger(expMapper, verdictMapper, traderMapper, prompts);

    private final ReviewRunner runner = new ReviewRunner(
            new ReviewMaterialAssembler(decisionMapper, planMapper, simTradeClient, prompts,
                    new CaseFileBuilder(historyStore), ledger, new PlayStatsAssembler(simTradeClient, planMapper, prompts)),
            modelFactory, decisionMapper, prompts, langResolver, ledger, new ExperienceOpParser(prompts), simTradeClient);

    {
        when(langResolver.of(anyLong())).thenReturn(AgentLang.ZH);
        when(decisionMapper.insert(any(AiTraderDecision.class))).thenAnswer(inv -> {
            ((AiTraderDecision) inv.getArgument(0)).setId(900L);
            return 1;
        });
    }

    private AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(1);
        t.setSimUserId(99L);
        t.setSymbols("BTCUSDT");
        t.setIntervalCode("1h");
        t.setMemory(null);
        return t;
    }

    private static AiTraderDecision equityRow(long wakeTime, String equity) {
        AiTraderDecision d = new AiTraderDecision();
        d.setWakeTime(wakeTime);
        d.setKind(AiTraderDecision.KIND_TRADE);
        d.setStatus(AiTraderDecision.STATUS_OK);
        d.setEquity(new BigDecimal(equity));
        return d;
    }

    private static KlineBar bar(long openTime, String o, String h, String l, String c) {
        return new KlineBar(openTime, openTime + 299_999L, new BigDecimal(o), new BigDecimal(h),
                new BigDecimal(l), new BigDecimal(c), BigDecimal.ZERO);
    }

    @Test
    void fullLoopAssemblesFactsCallsModelAppliesOpsAndRendersMemory() {
        long openMs = FROM + 3_600_000L;
        long closeMs = FROM + 3_600_000L + 55 * 60_000L;
        FuturesPositionDTO pos = new FuturesPositionDTO();
        pos.setId(42L);
        pos.setSymbol("BTCUSDT");
        pos.setSide("LONG");
        pos.setStatus("CLOSED");
        pos.setEntryPrice(new BigDecimal("100000"));
        pos.setClosedPrice(new BigDecimal("98900"));
        pos.setClosedPnl(new BigDecimal("-11"));
        pos.setFundingFeeTotal(BigDecimal.ZERO);
        pos.setQuantity(new BigDecimal("0.01"));
        pos.setStopLosses(List.of(new FuturesStopLoss("s1", new BigDecimal("99000"), new BigDecimal("0.01"))));
        pos.setCreatedAt(Instant.ofEpochMilli(openMs).atZone(ZoneId.systemDefault()).toLocalDateTime());
        pos.setUpdatedAt(Instant.ofEpochMilli(closeMs).atZone(ZoneId.systemDefault()).toLocalDateTime());
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(pos));

        AiTraderPlan plan = new AiTraderPlan();
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        plan.setPlayType("BREAKOUT");
        plan.setSignalsUsed("突破前高100200");
        plan.setInvalidationCondition("1h收盘跌回99500下方");
        plan.setStopLossPrice(new BigDecimal("99000"));
        plan.setTakeProfitPrice(new BigDecimal("102000"));
        plan.setEntryCtxJson("{\"regime\":\"TREND_UP\",\"atr1h\":320,\"stopAtr\":3.1,\"stopPct\":1.0,\"hourBj\":9,\"orderType\":\"MARKET\",\"leverage\":20}");
        plan.setStatus(AiTraderPlan.STATUS_CLOSED);
        plan.setOpenedWakeTime(openMs);
        plan.setClosedWakeTime(closeMs + 300_000L);
        plan.setPositionId(42L);
        when(planMapper.selectList(any())).thenReturn(List.of(plan));

        // decisionMapper：lastReview(selectOne#1)→战绩前值(selectOne#2)→权益序列(selectList)
        when(decisionMapper.selectOne(any())).thenReturn(null, null);
        when(decisionMapper.selectList(any())).thenReturn(List.of(
                equityRow(openMs, "10000"), equityRow(closeMs, "9989")));
        // 5m 路径：第二根冲到 100900（目标距离的 45%），第三根收回入场价下方，之后被打到 98900
        List<KlineBar> bars = new ArrayList<>();
        long t = openMs;
        bars.add(bar(t, "100000", "100300", "99900", "100100"));
        bars.add(bar(t + 300_000L, "100100", "100900", "100000", "100800"));
        bars.add(bar(t + 600_000L, "100800", "100850", "99700", "99800"));
        bars.add(bar(t + 900_000L, "99800", "99900", "98850", "98900"));
        bars.add(bar(closeMs + 1_800_000L, "98900", "99400", "98800", "99300"));
        when(historyStore.load(eq("BTCUSDT"), eq("5m"), anyLong(), anyLong())).thenReturn(bars);

        // 账本：现役一条待核对的假设
        AiTraderExperience hyp = new AiTraderExperience();
        hyp.setId(5L);
        hyp.setTraderId(7L);
        hyp.setRoundNo(1);
        hyp.setPlayType("BREAKOUT");
        hyp.setKind(AiTraderExperience.KIND_HYPOTHESIS);
        hyp.setSlot(AiTraderExperience.SLOT_FAIL_DEF);
        hyp.setDirection(AiTraderExperience.DIR_EXECUTION);
        hyp.setText("突破单两根 1h 收不出新高即判失败");
        hyp.setEvidence("ETH 09:32");
        hyp.setSupports(1);
        hyp.setOpposes(0);
        hyp.setUntested(0);
        hyp.setStatus(AiTraderExperience.STATUS_ACTIVE);
        hyp.setFlagged(false);
        hyp.setPinned(false);
        hyp.setVerdictsSinceAudit(1);
        List<AiTraderExperience> active = new ArrayList<>(List.of(hyp));
        when(expMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(active));
        when(expMapper.selectById(5L)).thenReturn(hyp);
        when(expMapper.insert(any(AiTraderExperience.class))).thenAnswer(inv -> {
            AiTraderExperience e = inv.getArgument(0);
            e.setId(6L);
            active.add(e);
            return 1;
        });
        when(verdictMapper.selectList(any())).thenReturn(List.of());

        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("""
                        【本期复盘】
                        战绩：起始权益 10000.00 → 期末权益 9989.00，期间收益率 -0.11%
                        案例一：BREAKOUT（1 笔，净 -11）
                        路径：10 分钟到目标 45%，第 3 根收回入场价，第 4 根止损
                        失败怎么界定：浮盈过目标 40% 又收回入场价
                        失败后怎么处理：当轮市价平
                        下次改什么：→ 新增
                        机会：BTC 窗口低到高 1.0%，没有可做的单边
                        【经验更新】
                        #5 支持 ｜ 本笔第 3 根 1h 内已收回入场价，两根内未创新高
                        新增 BREAKOUT 失败处理 执行型 ｜ 浮盈过目标 40% 又收回入场价即当轮市价平 ｜ BTC 100000 多 -11""")))));
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.review(trader(), DAY_BOUNDARY);

        // 提示词里是代码算的事实原文
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String all = prompt.getValue().getInstructions().stream().map(Message::getText).reduce("", String::concat);
        assertThat(all).contains("起始权益 10000.00").contains("-0.11%");                       // 战绩表
        assertThat(all).contains("★ BREAKOUT（1 笔，0 胜，净 -11）");                             // 案例组
        assertThat(all).contains("最大浮盈 100900").contains("目标的 45%")                        // 路径
                .contains("最大逆向 98850").contains("止损的 115%")
                .contains("出场后 4h / 24h 最有利 — / —，出场后数据不足");
        assertThat(all).contains("regime TREND_UP").contains("止损 3.1 个 ATR");                  // 入场上下文
        assertThat(all).contains("论点 突破前高100200").contains("失效 1h收盘跌回99500下方");       // 计划
        assertThat(all).contains("【本期行情】").contains("- BTCUSDT 100000 → 99300");             // 行情
        assertThat(all).contains("#5 假设 失败判定 执行型 BREAKOUT ｜ 突破单两根 1h 收不出新高即判失败 ｜ 支持 1 反对 0 未测试 0");

        // 结论流水挂在复盘行上，计数 +1；新增入账
        ArgumentCaptor<AiTraderExperienceVerdict> verdict = ArgumentCaptor.forClass(AiTraderExperienceVerdict.class);
        verify(verdictMapper).insert(verdict.capture());
        assertThat(verdict.getValue().getExperienceId()).isEqualTo(5L);
        assertThat(verdict.getValue().getReviewId()).isEqualTo(900L);
        assertThat(verdict.getValue().getPositionIds()).isEqualTo("42");
        assertThat(hyp.getSupports()).isEqualTo(2);
        ArgumentCaptor<AiTraderExperience> added = ArgumentCaptor.forClass(AiTraderExperience.class);
        verify(expMapper).insert(added.capture());
        assertThat(added.getValue().getSlot()).isEqualTo(AiTraderExperience.SLOT_FAIL_HANDLE);
        assertThat(added.getValue().getCreatedReviewId()).isEqualTo(900L);

        // REVIEW 行存全文；memory_after 与 ai_trader.memory 是渲染后的账本
        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getReasoning()).contains("案例一").contains("#5 支持");
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<AiTraderDecision>> snap = ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(decisionMapper).update(isNull(), snap.capture());
        assertThat(snap.getValue().getSqlSet()).contains("memory_after");
        assertThat(dec.getValue().getMemoryAfter()).startsWith("【经验账本】")
                .contains("#5 假设·执行型 ｜ 突破单两根 1h 收不出新高即判失败 ｜ 支持 2 反对 0")
                .contains("#6 假设·执行型 ｜ 浮盈过目标 40% 又收回入场价即当轮市价平");
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> up = ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(traderMapper).update(any(), up.capture());
        assertThat(up.getValue().getParamNameValuePairs().values().stream()
                .anyMatch(v -> v instanceof String s && s.startsWith("【经验账本】"))).isTrue();
    }
}
