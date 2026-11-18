package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderExperienceMapper;
import com.mawai.wiibagent.mapper.AiTraderExperienceTradeMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TradeRecordService;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.entity.AiTraderExperienceTrade;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复盘回路贯通：真 assembler + 真事实表 + 真账本 + 真解析器，只 mock 库与模型——
 * 组装出的事实真进了提示词，模型按仓位编号的归类真记成了名下单子，结账和渲染进 memory 都走真代码。
 */
class ReviewLoopTest {

    private static final long DAY_BOUNDARY = 1785196800000L;
    private static final long FROM = DAY_BOUNDARY - 86_400_000L;

    @BeforeAll
    static void initTableInfoCache() {
        for (Class<?> c : List.of(AiTrader.class, AiTraderDecision.class, AiTraderPlan.class,
                AiTraderExperience.class, AiTraderExperienceTrade.class)) {
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
    private final AiTraderExperienceTradeMapper tradeMapper = mock(AiTraderExperienceTradeMapper.class);
    private final UserLangResolver langResolver = mock(UserLangResolver.class);
    private final ExperienceLedger ledger = new ExperienceLedger(expMapper, tradeMapper, traderMapper,
            mock(TraderPlanStore.class), prompts);

    private final ReviewRunner runner = new ReviewRunner(
            new ReviewMaterialAssembler(decisionMapper, planMapper, simTradeClient, prompts,
                    new CaseFileBuilder(historyStore), ledger,
                    new PlayStatsAssembler(simTradeClient, planMapper, prompts, new CaseFileBuilder(historyStore)),
                    mock(TraderWatchStore.class), new DecisionText(prompts),
                    new TradeRecordService(simTradeClient, planMapper, decisionMapper)),
            modelFactory, decisionMapper, prompts, langResolver, ledger, new ExperienceOpParser(prompts));

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

    private static AiTraderExperience entry(long id, String kind, String slot, String playType, String text, String evidence) {
        AiTraderExperience e = new AiTraderExperience();
        e.setId(id);
        e.setTraderId(7L);
        e.setRoundNo(1);
        e.setPlayType(playType);
        e.setKind(kind);
        e.setSlot(slot);
        e.setDirection(AiTraderExperience.DIR_EXECUTION);
        e.setText(text);
        e.setEvidence(evidence);
        e.setCitedOpen(0);
        e.setCitedHold(0);
        e.setStatus(AiTraderExperience.STATUS_ACTIVE);
        e.setSegmentNo(0);
        e.setIdleReviews(0);
        e.setPinned(false);
        return e;
    }

    @Test
    void fullLoopAssemblesFactsCallsModelRecordsTradesAndRendersMemory() {
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
        plan.setTakeProfitsJson("[{\"price\":102000,\"percent\":100}]");
        plan.setEntryCtxJson("{\"adx1h\":31.2,\"bollBw1h\":2.8,\"atr1h\":320,\"stopAtr\":3.1,\"stopPct\":1.0,\"hourBj\":9,\"orderType\":\"MARKET\",\"leverage\":20}");
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

        // 账本：一条规律、一条待攒单子的假设；新增的那条进来拿到 6 号
        AiTraderExperience rule = entry(4L, AiTraderExperience.KIND_RULE, AiTraderExperience.SLOT_RISK, null,
                "止损距离不小于 1 个 1h ATR", "10-01 三笔");
        AiTraderExperience hyp = entry(5L, AiTraderExperience.KIND_HYPOTHESIS, AiTraderExperience.SLOT_ENTRY, "BREAKOUT",
                "突破前高 → 等 1h 收稳再进", "ETH 09:32");
        List<AiTraderExperience> active = new ArrayList<>(List.of(rule, hyp));
        when(expMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(active));
        when(expMapper.selectById(4L)).thenReturn(rule);
        when(expMapper.selectById(5L)).thenReturn(hyp);
        when(expMapper.insert(any(AiTraderExperience.class))).thenAnswer(inv -> {
            AiTraderExperience e = inv.getArgument(0);
            e.setId(6L);
            active.add(e);
            return 1;
        });
        // 名下单子表：插进去的行，结账和渲染时查得到
        List<AiTraderExperienceTrade> trades = new ArrayList<>();
        when(tradeMapper.insertIgnore(any())).thenAnswer(inv -> {
            trades.add(inv.getArgument(0));
            return 1;
        });
        when(tradeMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(trades));

        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("""
                        【本期复盘】
                        战绩：起始权益 10000.00 → 期末权益 9989.00，期间收益率 -0.11%
                        案例一：BREAKOUT（1 笔，净 -11）
                        路径：10 分钟到目标 45%，第 3 根收回入场价，第 4 根止损
                        机会：BTC 窗口低到高 1.0%，没有可做的单边
                        【经验更新】
                        #4 无
                        #5 名下 42 ｜ 等 1h 收稳后才进
                        新增 BREAKOUT 入场条件 限制型 ｜ 突破前高后不等回踩 → 不市价追 ｜ BTC 100000 市价追多 -11 ｜ 名下 42""")))));
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.review(trader(), DAY_BOUNDARY);

        // 提示词里是代码算的事实原文，每笔前面带仓位编号，账本行带代码记的账
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String all = prompt.getValue().getInstructions().stream().map(Message::getText).reduce("", String::concat);
        assertThat(all).contains("起始权益 10000.00").contains("-0.11%");                       // 战绩表
        assertThat(all).contains("★ BREAKOUT（1 笔，0 胜，净 -11）").contains("- [42] BTCUSDT LONG"); // 案例组
        assertThat(all).contains("最大浮盈 100900").contains("+0.90R")                            // 路径
                .contains("最大逆向 98850（-1.15R）")
                .contains("出场后 4h / 24h 最有利 — / —，出场后数据不足");
        assertThat(all).contains("1h ADX 31.2 ｜ 1h 布林带宽 2.8").contains("止损 3.1 个 ATR");   // 入场上下文
        assertThat(all).contains("论点 突破前高100200").contains("失效 1h收盘跌回99500下方");       // 计划
        assertThat(all).contains("【本期行情】").contains("- BTCUSDT 100000 → 99300");             // 行情
        assertThat(all).contains("#5 假设 入场条件 执行型 BREAKOUT ｜ 突破前高 → 等 1h 收稳再进 ｜ "
                + "名下 0 笔，赚 0 亏 0，合计 +0.00，0 天；当前段 0 笔，合计 +0.00，0 天 ｜ 证据 ETH 09:32");

        // 名下单子按仓位记：净盈亏取案例行那个数，平仓日是北京日期；#5 和新增的 #6 各一行
        assertThat(trades).extracting(AiTraderExperienceTrade::getExperienceId, AiTraderExperienceTrade::getPositionId,
                AiTraderExperienceTrade::getReviewId, AiTraderExperienceTrade::getMark)
                .containsExactly(tuple(5L, 42L, 900L, AiTraderExperienceTrade.MARK_UNDER),
                        tuple(6L, 42L, 900L, AiTraderExperienceTrade.MARK_UNDER));
        assertThat(trades.getFirst().getNet()).isEqualByComparingTo("-11");
        assertThat(trades.getFirst().getClosedDay()).isEqualTo(LocalDate.of(2026, 7, 27));
        ArgumentCaptor<AiTraderExperience> added = ArgumentCaptor.forClass(AiTraderExperience.class);
        verify(expMapper).insert(added.capture());
        assertThat(added.getValue().getSlot()).isEqualTo(AiTraderExperience.SLOT_ENTRY);
        assertThat(added.getValue().getDirection()).isEqualTo(AiTraderExperience.DIR_RESTRICTION);
        assertThat(added.getValue().getCreatedReviewId()).isEqualTo(900L);
        // 结账：#5 有新单子不加闲置，没单子的规律 #4 加一，本篇新增的 #6 不数
        assertThat(hyp.getIdleReviews()).isZero();
        assertThat(rule.getIdleReviews()).isEqualTo(1);
        assertThat(hyp.getKind()).isEqualTo(AiTraderExperience.KIND_HYPOTHESIS);

        // REVIEW 行存全文；memory_after 与 ai_trader.memory 是渲染后的账本
        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getReasoning()).contains("案例一").contains("#5 名下 42");
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<AiTraderDecision>> snap = ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(decisionMapper).update(isNull(), snap.capture());
        assertThat(snap.getValue().getSqlSet()).contains("memory_after");
        // 假设（#5、新增的 #6）不注入，只有规律 #4
        assertThat(dec.getValue().getMemoryAfter()).startsWith("【经验账本】")
                .contains("#4 规律·执行型 ｜ 止损距离不小于 1 个 1h ATR ｜ 照做 0 笔，赚 0 亏 0，合计 +0.00 USDT")
                .doesNotContain("#5 ").doesNotContain("#6 ");
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> up = ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(traderMapper).update(any(), up.capture());
        assertThat(up.getValue().getParamNameValuePairs().values().stream()
                .anyMatch(v -> v instanceof String s && s.startsWith("【经验账本】"))).isTrue();
    }
}
