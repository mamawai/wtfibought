package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibagent.trader.TradeRecordService;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.entity.AiTraderExperienceTrade;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibagent.trader.trade.PlanEntry;
import com.mawai.wiibagent.trader.trade.TargetRung;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复盘素材组装单测：战绩、案例事实表、行情、账本全是代码算的，数字必须对——模型只许复述，代码错一位就是复盘造假。
 */
class ReviewMaterialAssemblerTest {

    /** 窗口起点：某个整日边界；窗口 = (FROM, TO]，一天 */
    private static final long FROM = 1785110400000L;
    private static final long TO = FROM + 86_400_000L;
    private static final long H = 3600_000L;
    private static final long M5 = 300_000L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(BeijingTime.ZONE);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(BeijingTime.ZONE);

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderPlan.class);
    }

    private final PromptCatalog prompts = new PromptCatalog();
    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);
    private final AiTraderPlanMapper planMapper = mock(AiTraderPlanMapper.class);
    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final KlineHistoryStore historyStore = mock(KlineHistoryStore.class);
    private final ExperienceLedger ledger = mock(ExperienceLedger.class);
    private final TraderWatchStore watchStore = mock(TraderWatchStore.class);

    private final CaseFileBuilder caseFiles = new CaseFileBuilder(historyStore);
    private final TradeRecordService tradeRecords = mock(TradeRecordService.class);

    private final ReviewMaterialAssembler assembler = new ReviewMaterialAssembler(
            decisionMapper, planMapper, simTradeClient, prompts, caseFiles, new CaseFileRenderer(prompts), ledger,
            new PlayStatsAssembler(simTradeClient, planMapper, prompts, caseFiles), watchStore, new DecisionText(prompts),
            new ExperienceOpParser(prompts), tradeRecords);

    /** 一笔单子的单笔复盘行：正文两行，加归类段；开跑时刻晚于这里各测试的平仓 */
    private static AiTraderDecision debrief(long positionId, String classifyLines) {
        AiTraderDecision d = new AiTraderDecision();
        d.setId(5000 + positionId);
        d.setPositionId(positionId);
        d.setWakeTime(TO + 3 * H);
        d.setReasoning("【单笔复盘】\n路径：第 " + positionId + " 笔的路径\n管理动作：没动过\n【归类】\n" + classifyLines);
        return d;
    }

    /** 查单笔复盘的那次，从 debriefs 里按查询带的仓位编号给；其余的 selectList（权益序列）给 equity */
    private void stubDecisionLists(List<AiTraderDecision> debriefs, List<AiTraderDecision> equity) {
        doAnswer(inv -> {
            LambdaQueryWrapper<AiTraderDecision> w = inv.getArgument(0);
            if (!String.valueOf(w.getSqlSelect()).contains("position_id")) {
                return equity;
            }
            // MP 条件值懒求值：先拼一次 SQL，参数才落进 paramNameValuePairs
            w.getTargetSql();
            var values = w.getParamNameValuePairs().values();
            return debriefs.stream().filter(d -> values.contains(d.getPositionId())).toList();
        }).when(decisionMapper).selectList(any());
    }

    /** 名下一笔都没有的账 */
    private static final ExperienceLedger.Tally NO_TRADES =
            new ExperienceLedger.Tally(0, 0, 0, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO, 0, 0, 0, 0);

    {
        // 没单独桩的条目，账都是零
        when(ledger.tallies(any())).thenAnswer(inv -> {
            Map<Long, ExperienceLedger.Tally> out = new HashMap<>();
            for (AiTraderExperience e : inv.<List<AiTraderExperience>>getArgument(0)) {
                out.put(e.getId(), NO_TRADES);
            }
            return out;
        });
    }

    private AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setRoundNo(1);
        t.setSimUserId(99L);
        t.setSymbols("BTCUSDT");
        t.setIntervalCode("1h");
        return t;
    }

    private static AiTraderDecision equityRow(long wakeTime, String equity) {
        AiTraderDecision d = new AiTraderDecision();
        d.setWakeTime(wakeTime);
        d.setEquity(new BigDecimal(equity));
        return d;
    }

    private static LocalDateTime at(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    /** 没单独指定仓位编号的单子从这里往后编，和用例里手写的编号岔开 */
    private static long nextPositionId = 9000;

    private static FuturesPositionDTO closedPos(String side, String entry, String closed, String pnl,
                                                long openMs, long closeMs) {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId(nextPositionId++);
        p.setSymbol("BTCUSDT");
        p.setSide(side);
        p.setStatus("CLOSED");
        // 默认主动平仓
        p.setClosedBy("FILLED");
        p.setEntryPrice(new BigDecimal(entry));
        p.setClosedPrice(new BigDecimal(closed));
        p.setClosedPnl(new BigDecimal(pnl));
        p.setQuantity(new BigDecimal("0.01"));
        p.setCreatedAt(at(openMs));
        p.setUpdatedAt(at(closeMs));
        return p;
    }

    private static AiTraderPlan planOf(String symbol, String playType, long openedWakeTime, boolean stale) {
        AiTraderPlan plan = new AiTraderPlan();
        plan.setSymbol(symbol);
        plan.setSide("LONG");
        plan.setPlayType(playType);
        plan.setSignalsUsed("信号-" + playType);
        plan.setInvalidationCondition("失效-" + playType);
        plan.setStatus(AiTraderPlan.STATUS_CLOSED);
        plan.setOpenedWakeTime(openedWakeTime);
        plan.setClosedWakeTime(openedWakeTime + H);
        plan.setStale(stale);
        return plan;
    }

    /** 绑了仓位、已归档的计划行 */
    private static AiTraderPlan boundPlan(long positionId, String playType, long openedWakeTime) {
        AiTraderPlan plan = planOf("BTCUSDT", playType, openedWakeTime, false);
        plan.setId(100 + positionId);
        plan.setPositionId(positionId);
        return plan;
    }

    /** 5m bar 造数：一根 5m 的 OHLC */
    private static KlineBar bar5m(long openTime, String o, String h, String l, String c) {
        return new KlineBar(openTime, openTime + M5 - 1,
                new BigDecimal(o), new BigDecimal(h), new BigDecimal(l), new BigDecimal(c), BigDecimal.ZERO);
    }

    @Test
    void ladderMaterialListsEntriesAddsAndCancelledLegsOfFilledPlan() {
        long opened = FROM + H;
        AiTraderPlan plan = planOf("BTCUSDT", "PULLBACK", opened, false);
        plan.setPositionId(1L); plan.setEntryPrice(new BigDecimal("105")); plan.setStopLossPrice(new BigDecimal("100"));
        plan.setTakeProfitsJson("[{\"price\":110,\"percent\":100}]");
        List<PlanEntry> entries = new ArrayList<>();
        String[] prices = {"100", "98", "97", "105", "103"}, quantities = {"3", "4", "3", "2", "1"};
        for (int i = 0; i < 5; i++) {
            var entry = new PlanEntry();
            entry.setLimitPrice(new BigDecimal(prices[i])); entry.setQuantity(new BigDecimal(quantities[i]));
            entry.setStop(new BigDecimal(i >= 3 ? "100" : "96"));
            entry.setTargets(List.of(new TargetRung(new BigDecimal(i >= 3 ? "110" : "104"), new BigDecimal("100"))));
            entry.setPlacedAt(opened); entry.setClosedAt(opened + i * M5); entry.setAdd(i >= 3);
            entry.setStatus(i == 0 || i == 3 ? "FILLED" : "CANCELLED");
            if ("FILLED".equals(entry.getStatus())) { entry.setFilledPrice(entry.getLimitPrice()); entry.setFee(new BigDecimal("0.06")); }
            else entry.setReason("cancel-reason-" + i);
            entries.add(entry);
        }
        PlanEntry.save(plan, entries);
        FuturesPositionDTO position = closedPos("LONG", "102", "104", "8", opened, opened + H); position.setId(1L);
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(position));
        stubDecisionLists(List.of(debrief(1L, "")), List.of());
        when(historyStore.load(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of(
                bar5m(opened, "100", "101", "99", "100"), bar5m(opened + 4 * M5, "100", "106", "97", "105")));
        String zh = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).casesBlock();
        assertThat(zh).contains("入场安排", "100 × 3", "98 × 4", "97 × 3", "105 × 2（加仓）",
                "计划合计 13，实际成交 5", "cancel-reason-1", "cancel-reason-2", "含加仓",
                "限价 98 止损 96 目标 104(100%)", "限价 97 止损 96 目标 104(100%)", "限价 103 止损 100 目标 110(100%)",
                // 98 那档第二根才穿过限价位，最低 97 没到止损 96，后面没 K线了；103 那档第一根就穿过，同一根最低 99 打掉止损 100
                "穿过限价位，从那一根起走到这段 K线末尾还没了结（止损没碰到），到过原目标 0/1 档",
                "穿过限价位，从那一根起走到止损被打掉为止，到过原目标 0/1 档");
        String en = assembler.assemble(trader(), FROM, TO, AgentLang.EN).casesBlock();
        assertThat(en).contains("Entry arrangement", "planned total 13, actually filled 5", "includes adds", "cancel-reason-2",
                "limit 103 stop 100 target 110(100%)",
                "from that candle on it ran until the stop was hit; reached 0/1 of the original targets");
        PlanEntry.save(plan, List.of(entries.getFirst()));
        // 只有一档且已成交：不出入场安排那一行（块头的说明里也有这四个字，认行首）
        assertThat(assembler.assemble(trader(), FROM, TO, AgentLang.ZH).casesBlock()).doesNotContain("  入场安排：");
    }

    // ==================== 战绩表 ====================

    /** 权益和回撤按窗口内的决策行算，起止和先后按落库时刻；笔数胜负合计数的是本批单子，不管它们哪天平的 */
    @Test
    @SuppressWarnings("unchecked")
    void statsComputedFromEquitySeriesAndBatchTrades() {
        // 窗口起点前最后一条权益 10000；窗口内 10200 → 9800 → 10500
        when(decisionMapper.selectOne(any())).thenReturn(equityRow(FROM - H, "10000"));
        stubDecisionLists(List.of(debrief(1L, ""), debrief(2L, "")), List.of(
                equityRow(FROM + H, "10200"),
                equityRow(FROM + 2 * H, "9800"),
                equityRow(FROM + 3 * H, "10500")));
        FuturesPositionDTO win = closedPos("LONG", "100000", "103000", "300", FROM - 30 * H, FROM - 29 * H);
        win.setId(1L);
        FuturesPositionDTO loss = closedPos("LONG", "100000", "99000", "-100", FROM + 2 * H, FROM + 3 * H);
        loss.setId(2L);
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(win, loss));
        when(planMapper.selectList(any())).thenReturn(List.of(
                boundPlan(1L, "PULLBACK", FROM - 30 * H), boundPlan(2L, "PULLBACK", FROM + 2 * H)));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        // 收益率 (10500-10000)/10000=+5%；回撤峰10200谷9800=3.92%；2笔1胜1负
        assertThat(m.statsBlock()).contains("10000.00").contains("10500.00").contains("+5.00%");
        assertThat(m.statsBlock()).contains("3.92%");
        assertThat(m.statsBlock()).contains("本批单子 2 笔：1 胜 1 负（胜率 50%），合计净盈亏 +200.00");
        // 几轮同时在跑时先开始的可能后结束：起始权益和序列都按落库时刻取、按落库时刻排
        ArgumentCaptor<LambdaQueryWrapper<AiTraderDecision>> prior = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(decisionMapper, atLeastOnce()).selectOne(prior.capture());
        assertThat(prior.getAllValues().getFirst().getSqlSegment()).contains("created_at <=").contains("ORDER BY created_at DESC");
        ArgumentCaptor<LambdaQueryWrapper<AiTraderDecision>> lists = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(decisionMapper, atLeastOnce()).selectList(lists.capture());
        assertThat(lists.getAllValues()).anySatisfy(q -> assertThat(q.getSqlSegment())
                .contains("created_at >").contains("created_at <=").contains("ORDER BY created_at ASC"));
    }

    @Test
    void startEquityFallsBackToInitialBalanceWhenNoPriorRow() {
        when(decisionMapper.selectOne(any())).thenReturn(null);
        when(decisionMapper.selectList(any())).thenReturn(List.of(equityRow(FROM + H, "10100")));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        // 无前值 → 起始按初始资金 10000：(10100-10000)/10000=+1%
        assertThat(m.statsBlock()).contains("+1.00%");
    }

    // ==================== 本批单子：一笔的事实表和单笔复盘正文 ====================

    /**
     * 一笔多单的完整事实表：交易行、路径指标、1h 收盘、计划（含修订）、入场上下文，下面跟着它的单笔复盘正文（归类段不带）。
     * 路径数字全从 5m 算：浮盈/逆向占比以计划原始目标/止损距离为分母。
     */
    @Test
    void caseRendersTradePathPlanEntryContextAndDebriefText() {
        long openMs = FROM + H;
        long closeMs = FROM + 6 * H;
        FuturesPositionDTO pos = closedPos("LONG", "100000", "95000", "-50", openMs, closeMs);
        pos.setId(42L);
        pos.setOpenedQuantity(new BigDecimal("0.01"));
        pos.setStopLosses(List.of(new FuturesStopLoss("s1", new BigDecimal("95500"), new BigDecimal("0.01"))));
        pos.setClosedBy("STOP_LOSS");
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(pos));

        AiTraderPlan plan = planOf("BTCUSDT", "BREAKOUT", openMs, false);
        plan.setPositionId(42L);
        plan.setSignalsUsed("突破前高+量比1.8");
        plan.setInvalidationCondition("1h收盘跌回98000下方");
        plan.setStopLossPrice(new BigDecimal("95500"));
        plan.setTakeProfitsJson("[{\"price\":110000,\"percent\":100}]");
        plan.setRevisionsJson("[{\"time\":\"x\",\"type\":\"stop_loss\",\"change\":\"95500→96000\",\"reason\":\"锁利\"}]");
        plan.setEntryCtxJson("{\"adx1h\":31.2,\"bollBw1h\":2.8,\"atr1h\":350.5,\"stopAtr\":12.8,\"stopPct\":4.5,\"hourBj\":9,"
                + "\"orderType\":\"MARKET\",\"leverage\":10,\"eventMin\":-90,\"eventTitle\":\"CPI\",\"funding\":0.0001}");
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        stubDecisionLists(List.of(debrief(42L, "#75 名下 42")), List.of());

        // 目标距离 10000、止损距离 4500
        when(historyStore.load(eq("BTCUSDT"), eq("5m"), anyLong(), anyLong())).thenReturn(List.of(
                bar5m(openMs, "100000", "101000", "99800", "100800"),
                bar5m(openMs + M5, "100800", "102500", "100500", "102000"),   // 浮盈 2500 = 目标 25%，第 5 分钟
                bar5m(FROM + 2 * H, "102000", "102200", "99500", "99700"),     // 首次收回入场价
                bar5m(FROM + 5 * H, "99700", "99900", "95000", "95200"),       // 逆向 5000 = 止损的 111%
                bar5m(closeMs + M5, "95200", "97000", "95000", "96500"),       // 出场后 4h 内最有利 97000
                bar5m(FROM + 20 * H, "96500", "99000", "96000", "98000"),      // 24h 内最有利 99000，没到 110000
                bar5m(FROM + 30 * H - M5, "98000", "98500", "97900", "98200"))); // 覆盖到出场后 24h

        String cases = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).casesBlock();

        assertThat(cases).startsWith("【本批单子】").contains("\nBREAKOUT（1 笔，0 胜，净 -50）\n");
        // 事实表下面是单笔复盘正文，段标记和归类行不带
        assertThat(cases).contains("  单笔复盘（平仓后 21小时 做的）：\n    路径：第 42 笔的路径\n    管理动作：没动过\n")
                .doesNotContain("【归类】").doesNotContain("#75 名下 42");
        assertThat(cases).contains("- [42] BTCUSDT LONG " + TIME.format(Instant.ofEpochMilli(openMs)) + " → "
                + TIME.format(Instant.ofEpochMilli(closeMs))
                + "（持有 5小时）｜ 入场 100000 止损 95500 目标 110000(100%) 出场 95000，止损带走 ｜ 净 -50，资金费 —");
        // R = 4500：最大浮盈 2500 是 +0.56R、最大逆向 5000 是 -1.11R；第一根就到了 +0.5R，+1R 没到过。
        // 出场 95000：出场后最有利价比它多走了 2000、4000
        assertThat(cases).contains("  价格路径（以最终均价为参照）：最大浮盈 102500（" + HM.format(Instant.ofEpochMilli(openMs + M5))
                + "，+0.56R）｜ 最大逆向 95000（-1.11R）｜ 到 +0.5R / +1R 用时 5 / — 分 ｜ 首次收回入场价 "
                + HM.format(Instant.ofEpochMilli(FROM + 2 * H))
                + " ｜ 出场后 4h / 24h 最有利 97000 / 99000（比出场价多走 +2000 / +4000），到过原目标 0/1 档");
        // R = 4500：净 -50 ÷（0.01 × 4500）；24h 内最高 102500、最低 95000、第 20 小时那根收 98000；止损原样在 95000 那根被打，放宽后都没碰到
        assertThat(cases).contains("  重放：实际 -1.11R ｜ 首档成交后 24h 内最大顺向 +0.56R、最大逆向 -1.11R，不设止损拿满 24h -0.44R"
                + " ｜ 止损原样 止损（-1.02R）、1.5 倍 未决、2 倍 未决、3 倍 未决");
        assertThat(cases).contains("本批重放汇总：\n原计划不动重放 1笔（")
                .contains("：止损原样 到目标0 止损1 未决0 合计-1.02R ｜ 止损1.5倍 到目标0 止损0 未决1 合计+0R ｜ ")
                .contains("管理动作 1笔：实际合计-1.11R，原计划不动合计-1.02R");
        assertThat(cases).contains("  1h 收盘：102000 99700 95200");
        assertThat(cases).contains("  计划：论点 突破前高+量比1.8 ｜ 失效 1h收盘跌回98000下方 ｜ 修订 stop_loss 95500→96000（锁利）");
        assertThat(cases).contains("  入场上下文：1h ADX 31.2 ｜ 1h 布林带宽 2.8 ｜ 1h ATR 350.5 ｜ 止损 12.8 个 ATR（4.5%）｜ 距最近的美国 High 事件 -90 分 CPI"
                + " ｜ 新加坡时间 9 时 ｜ 资金费 0.0001 ｜ MARKET 10x");
    }

    /** 出场后只有平仓那根的下一根，覆盖 5 分钟：写看得到多久，最有利价和到过几档按这一段算，4 小时那格留空 */
    @Test
    void caseAfterExitCoveragePartialRendersWhatItCovers() {
        long openMs = FROM + H;
        long closeMs = FROM + 2 * H;
        FuturesPositionDTO pos = closedPos("SHORT", "100000", "99000", "10", openMs, closeMs);
        pos.setId(43L);
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(pos));
        AiTraderPlan plan = planOf("BTCUSDT", "RANGE", openMs, false);
        plan.setSide("SHORT");
        plan.setPositionId(43L);
        plan.setStopLossPrice(new BigDecimal("101000"));
        plan.setTakeProfitsJson("[{\"price\":98000,\"percent\":100}]");
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        stubDecisionLists(List.of(debrief(43L, "")), List.of());
        when(historyStore.load(eq("BTCUSDT"), eq("5m"), anyLong(), anyLong())).thenReturn(List.of(
                bar5m(openMs, "100000", "100400", "99200", "99300"),
                bar5m(closeMs + M5, "99300", "99500", "97900", "98000")));

        String cases = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).casesBlock();

        assertThat(cases).contains("RANGE（1 笔，1 胜，净 +10）");
        // 空单出场 99000，之后最低 97900：多走了 1100
        assertThat(cases).contains("出场后只看得到 5分钟：4h / 5分钟 内最有利 — / 97900（比出场价多走 — / +1100），到过原目标 1/1 档");
        // 重放从开仓那根的下一根数到最后一根，只有 65 分钟：顺向 2100 是 2.10R，拿满 24 小时的位置没有；各档都了结了，不写走到哪
        assertThat(cases).contains("首档成交后 65分钟 内最大顺向 +2.10R").contains("不设止损拿满 24h 未满 24h")
                .doesNotContain("各档只走到");
    }

    /** 吃多档盘口的出场价、加仓后的均价带 8 位小数：事实表里取整写，价差和出场价写出来的小数位一样 */
    @Test
    void caseRoundsLongDecimalPrices() {
        long openMs = FROM + H;
        long closeMs = FROM + 2 * H;
        FuturesPositionDTO pos = closedPos("SHORT", "60404.11267605", "60313.68751174", "192.61", openMs, closeMs);
        pos.setId(44L);
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(pos));
        AiTraderPlan plan = planOf("BTCUSDT", "TREND_FOLLOW", openMs, false);
        plan.setSide("SHORT");
        plan.setPositionId(44L);
        plan.setStopLossPrice(new BigDecimal("61280"));
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        stubDecisionLists(List.of(debrief(44L, "")), List.of());
        when(historyStore.load(eq("BTCUSDT"), eq("5m"), anyLong(), anyLong())).thenReturn(List.of(
                bar5m(openMs, "60404.1", "60500", "60300", "60350"),
                bar5m(closeMs + M5, "60313.7", "60400", "59586.3", "59600")));

        String cases = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).casesBlock();

        assertThat(cases).contains("入场 60404.11 止损 61280").contains("出场 60313.69，");
        // 60313.68751174 − 59586.3 = 727.38751174，跟出场价一样写 2 位小数
        assertThat(cases).contains("内最有利 — / 59586.3（比出场价多走 — / +727.39）");
    }

    /** 价位写法：至少 2 位小数、凑够 6 位有效数字，末尾的 0 去掉 */
    @Test
    void priceKeepsSixSignificantDigitsAndAtLeastTwoDecimals() {
        assertThat(CaseFileRenderer.price(new BigDecimal("770.10738775"))).isEqualTo("770.107");
        assertThat(CaseFileRenderer.price(new BigDecimal("0.14532149"))).isEqualTo("0.145321");
        assertThat(CaseFileRenderer.price(new BigDecimal("58420"))).isEqualTo("58420");
    }

    /** 平仓后的 K 线没满 24 小时、又有一档没了结：重放行写各档只走到了哪；满 24 小时、各档都了结了不写 */
    @Test
    void replayLineSaysHowFarTheWidthsWereWalked() {
        CaseFileRenderer renderer = new CaseFileRenderer(prompts);
        CaseFileBuilder.WidthOutcome stopped = new CaseFileBuilder.WidthOutcome(BigDecimal.ONE, CaseFileBuilder.Outcome.STOP,
                new BigDecimal("-1.02"));
        CaseFileBuilder.Replay open = new CaseFileBuilder.Replay(null, BigDecimal.ONE, BigDecimal.ONE.negate(), null,
                CaseFileBuilder.DIRECTION_MS, List.of(stopped,
                new CaseFileBuilder.WidthOutcome(new BigDecimal("1.5"), CaseFileBuilder.Outcome.OPEN, null)), false);
        CaseFileBuilder.Replay decided = new CaseFileBuilder.Replay(null, BigDecimal.ONE, BigDecimal.ONE.negate(), null,
                CaseFileBuilder.DIRECTION_MS, List.of(stopped), false);

        assertThat(renderer.replayLine(open, 2 * H, AgentLang.ZH)).endsWith("、1.5 倍 未决（各档只走到平仓后 2小时）");
        assertThat(renderer.replayLine(open, 0, AgentLang.ZH)).endsWith("、1.5 倍 未决（平仓后还没有 K 线，各档只走到平仓）");
        assertThat(renderer.replayLine(open, CaseFileBuilder.AFTER_LONG_MS, AgentLang.ZH)).endsWith("、1.5 倍 未决");
        assertThat(renderer.replayLine(decided, 2 * H, AgentLang.ZH)).endsWith("止损原样 止损（-1.02R）");
    }

    /** 没有 K 线：路径全部留空、没有 1h 收盘行，其余事实照出；计划没标类型的归到"未标类型" */
    @Test
    void caseWithoutBarsLeavesPathBlank() {
        FuturesPositionDTO pos = closedPos("LONG", "100000", "101000", "10", FROM + H, FROM + 2 * H);
        pos.setId(45L);
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(pos));
        when(planMapper.selectList(any())).thenReturn(List.of(boundPlan(45L, null, FROM + H)));
        stubDecisionLists(List.of(debrief(45L, "")), List.of());

        String cases = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).casesBlock();

        assertThat(cases).contains("未标类型（1 笔，1 胜，净 +10）");
        assertThat(cases).contains("  价格路径（以最终均价为参照）：最大浮盈 —（—，—）｜ 最大逆向 —（—）｜ 到 +0.5R / +1R 用时 — / — 分 ｜ 首次收回入场价 — ｜ 出场后没有 K 线");
        assertThat(cases).doesNotContain("1h 收盘");
        assertThat(cases).doesNotContain("入场上下文");
    }

    /** 限价单记了下单时刻：入场上下文行带上挂了多久才成交 */
    @Test
    void limitEntryShowsHowLongItRested() {
        long openMs = FROM + 2 * H;
        FuturesPositionDTO pos = closedPos("LONG", "100000", "101000", "10", openMs, openMs + H);
        pos.setId(44L);
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(pos));
        AiTraderPlan plan = planOf("BTCUSDT", "PULLBACK", FROM + H, false);
        plan.setPositionId(44L);
        plan.setEntryCtxJson("{\"orderType\":\"LIMIT\",\"leverage\":10,\"placedAt\":" + (openMs - 35 * 60_000L) + "}");
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        stubDecisionLists(List.of(debrief(44L, "")), List.of());

        String cases = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).casesBlock();

        assertThat(cases).contains("｜ LIMIT 10x（挂了 35分钟 才成交）");
    }

    /** 没绑过仓位就归档的计划单列一块：从最后一次见它还挂着的那轮起，价格穿没穿过限价位、到没到过原目标 */
    @Test
    void cancelledLimitOrdersListedWithWhatPriceDidNext() {
        AiTraderPlan plan = planOf("BTCUSDT", "PULLBACK", FROM + H, false);
        plan.setEntryPrice(new BigDecimal("99000"));
        plan.setStopLossPrice(new BigDecimal("98000"));
        plan.setTakeProfitsJson("[{\"price\":102000,\"percent\":100}]");
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        // 归档那轮之前的最后一轮：那时单子还挂着，撤单发生在它之后
        long lastSeen = FROM + H + 6 * M5;
        when(decisionMapper.selectOne(any())).thenReturn(equityRow(lastSeen, "10000"));
        // 之后最低 99200 没穿过限价位，最高 102300 到了目标
        when(historyStore.load("BTCUSDT", "5m", lastSeen, lastSeen + 24 * H)).thenReturn(List.of(
                bar5m(FROM + 2 * H, "100000", "100500", "99200", "100400"),
                bar5m(FROM + 2 * H + M5, "100400", "102300", "100300", "102000")));

        String cases = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).casesBlock();

        assertThat(cases).contains("本批没有单子");
        assertThat(cases).contains("没成交就撤掉的限价档（撤单后 24 小时内的 5m K线）：\n- BTCUSDT LONG 限价 99000 止损 98000 目标 102000(100%)，"
                + TIME.format(Instant.ofEpochMilli(FROM + 2 * H)) + " 结束：撤单原因未知 ｜ 之后价格没穿过限价位，到过原目标 1/1 档");
    }

    // ==================== 本批单子：收哪些、怎么分组 ====================

    /**
     * 收的是做完单笔复盘、还没进过统一复盘的单子，不看平仓在不在窗口里：窗口之前平的也收；
     * 没有成功的单笔复盘的、已经进过统一复盘的不收。每个单子类型的组里每笔都给完整事实表
     */
    @Test
    void batchIsDebriefedTradesNotYetReviewedAndEveryGroupIsListedInFull() {
        FuturesPositionDTO p1 = closedPos("LONG", "100000", "99960", "-40", FROM - 40 * H, FROM - 39 * H);
        p1.setId(1L);
        FuturesPositionDTO p2 = closedPos("LONG", "100000", "100010", "10", FROM + 3 * H, FROM + 4 * H);
        p2.setId(2L);
        FuturesPositionDTO p3 = closedPos("LONG", "100000", "100500", "500", FROM + 5 * H, FROM + 6 * H);
        p3.setId(3L);
        FuturesPositionDTO p4 = closedPos("LONG", "100000", "99995", "-5", FROM + 7 * H, FROM + 8 * H);
        p4.setId(4L);
        FuturesPositionDTO notDebriefed = closedPos("LONG", "100000", "99997", "-3", FROM + 9 * H, FROM + 10 * H);
        notDebriefed.setId(5L);
        FuturesPositionDTO reviewed = closedPos("LONG", "100000", "99111", "-9", FROM + 9 * H, FROM + 10 * H);
        reviewed.setId(6L);
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(p1, p2, p3, p4, notDebriefed, reviewed));
        AiTraderPlan alreadyReviewed = boundPlan(6L, "RANGE", FROM + 9 * H);
        alreadyReviewed.setReviewId(800L);
        when(planMapper.selectList(any())).thenReturn(List.of(
                boundPlan(1L, "PULLBACK", FROM - 40 * H), boundPlan(2L, "PULLBACK", FROM + 3 * H),
                boundPlan(3L, "BREAKOUT", FROM + 5 * H), boundPlan(4L, "RANGE", FROM + 7 * H),
                boundPlan(5L, "RANGE", FROM + 9 * H), alreadyReviewed));
        // 5 号还没有成功的单笔复盘
        stubDecisionLists(List.of(debrief(1L, ""), debrief(2L, ""), debrief(3L, ""), debrief(4L, "")), List.of());

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        String cases = m.casesBlock();
        assertThat(cases).contains("PULLBACK（2 笔，1 胜，净 -30）").contains("BREAKOUT（1 笔，1 胜，净 +500）")
                .contains("RANGE（1 笔，0 胜，净 -5）").doesNotContain("★");
        // 四笔都有事实表（出场价都在）和各自的单笔复盘正文；没复盘的、进过统一复盘的不出现
        assertThat(cases).contains("出场 99960").contains("出场 100010").contains("出场 100500").contains("出场 99995")
                .contains("路径：第 1 笔的路径").contains("路径：第 4 笔的路径")
                .doesNotContain("99997").doesNotContain("99111");
        assertThat(m.statsBlock()).contains("本批单子 4 笔：2 胜 2 负");
        assertThat(m.trades()).containsOnlyKeys(1L, 2L, 3L, 4L);
        // 落账后要盖编号的计划行
        assertThat(m.planIds()).containsExactlyInAnyOrder(101L, 102L, 103L, 104L);
    }

    /** 一篇最多收 20 笔，取平仓最早的；收不下的不盖编号，留给下一篇 */
    @Test
    void batchTakesTheEarliestClosedTradesUpToTheCap() {
        List<FuturesPositionDTO> closed = new ArrayList<>();
        List<AiTraderPlan> plans = new ArrayList<>();
        List<AiTraderDecision> debriefs = new ArrayList<>();
        for (long id = 1; id <= ReviewMaterialAssembler.MAX_BATCH + 2; id++) {
            FuturesPositionDTO p = closedPos("LONG", "100000", "100010", "10", FROM + id * H, FROM + id * H + M5);
            p.setId(id);
            // sim 给的是新的在前
            closed.addFirst(p);
            plans.add(boundPlan(id, "PULLBACK", FROM + id * H));
            debriefs.add(debrief(id, ""));
        }
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(closed);
        when(planMapper.selectList(any())).thenReturn(plans);
        stubDecisionLists(debriefs, List.of());

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        assertThat(m.planIds()).hasSize(ReviewMaterialAssembler.MAX_BATCH).contains(101L, 120L).doesNotContain(121L, 122L);
        assertThat(m.trades()).hasSize(ReviewMaterialAssembler.MAX_BATCH);
        assertThat(m.casesBlock()).contains("路径：第 20 笔的路径").doesNotContain("路径：第 21 笔的路径");
    }

    /**
     * 窗口里持有过、不在本批的仓位在块末尾各列一行，不给盈亏：窗口之后才平的、现在还拿着的（上个窗口开的也算）、
     * 窗口里平了还没做单笔复盘的；有首批成交档的写首档成交价（不是加仓后的均价），没有的写均价。
     * 窗口之前就平了的、窗口之后才开的、主人标了不算数的不列；两次查 sim 之间平掉、两边都有的只列一次。
     * 窗口左端最多回看 48 小时
     */
    @Test
    void positionsHeldInTheWindowOutsideTheBatchAreListedAfterTheCases() {
        FuturesPositionDTO inBatch = closedPos("LONG", "100000", "100010", "10", FROM + 3 * H, FROM + 4 * H);
        inBatch.setId(1L);
        FuturesPositionDTO closedLater = closedPos("SHORT", "60191", "60488", "853", FROM + 5 * H, TO + 3 * H);
        closedLater.setId(2L);
        FuturesPositionDTO notDebriefed = closedPos("LONG", "100000", "99997", "-3", FROM + 6 * H, FROM + 7 * H);
        notDebriefed.setId(3L);
        FuturesPositionDTO closedBefore = closedPos("LONG", "100000", "99111", "-9", FROM - 5 * H, FROM - 4 * H);
        closedBefore.setId(4L);
        FuturesPositionDTO openedAfter = closedPos("LONG", "100000", "99222", "-8", TO + H, TO + 2 * H);
        openedAfter.setId(5L);
        FuturesPositionDTO ignored = closedPos("LONG", "100000", "99333", "-7", FROM + 2 * H, TO + H);
        ignored.setId(6L);
        FuturesPositionDTO longAgo = closedPos("LONG", "100000", "99444", "-6", FROM - 31 * H, FROM - 30 * H);
        longAgo.setId(9L);
        when(simTradeClient.getClosedPositions(eq(99L), anyInt()))
                .thenReturn(List.of(inBatch, closedLater, notDebriefed, closedBefore, openedAfter, ignored, longAgo));
        FuturesPositionDTO stillHeld = new FuturesPositionDTO();
        stillHeld.setId(7L);
        stillHeld.setSymbol("BTCUSDT");
        stillHeld.setSide("SHORT");
        stillHeld.setEntryPrice(new BigDecimal("61800"));
        stillHeld.setCreatedAt(at(FROM - 2 * H));
        FuturesPositionDTO heldButNew = new FuturesPositionDTO();
        heldButNew.setId(8L);
        heldButNew.setSymbol("BTCUSDT");
        heldButNew.setSide("LONG");
        heldButNew.setEntryPrice(new BigDecimal("62000"));
        heldButNew.setCreatedAt(at(TO + H));
        // 2 号在取当前持仓时还开着，取已平仓位时已经平了：两边都有
        FuturesPositionDTO closedMeanwhile = new FuturesPositionDTO();
        closedMeanwhile.setId(2L);
        closedMeanwhile.setSymbol("BTCUSDT");
        closedMeanwhile.setSide("SHORT");
        closedMeanwhile.setEntryPrice(new BigDecimal("60191"));
        closedMeanwhile.setCreatedAt(at(FROM + 5 * H));
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(stillHeld, heldButNew, closedMeanwhile));
        // 2 号加过仓：首档 61831.8，加仓后均价 60191
        AiTraderPlan withAdd = boundPlan(2L, "TREND_FOLLOW", FROM + 5 * H);
        PlanEntry first = new PlanEntry();
        first.setQuantity(new BigDecimal("0.62"));
        first.setStop(new BigDecimal("62500"));
        first.setPlacedAt(FROM + 5 * H);
        first.setClosedAt(FROM + 5 * H);
        first.setStatus("FILLED");
        first.setFilledPrice(new BigDecimal("61831.8"));
        PlanEntry add = new PlanEntry();
        add.setQuantity(new BigDecimal("0.88"));
        add.setStop(new BigDecimal("62500"));
        add.setPlacedAt(FROM + 6 * H);
        add.setClosedAt(FROM + 6 * H);
        add.setAdd(true);
        add.setStatus("FILLED");
        add.setFilledPrice(new BigDecimal("59000"));
        PlanEntry.save(withAdd, List.of(first, add));
        AiTraderPlan stale = boundPlan(6L, "REVERSAL", FROM + 2 * H);
        stale.setStale(true);
        when(planMapper.selectList(any())).thenReturn(List.of(
                boundPlan(1L, "PULLBACK", FROM + 3 * H), withAdd, boundPlan(3L, "PULLBACK", FROM + 6 * H),
                boundPlan(4L, "PULLBACK", FROM - 5 * H), boundPlan(5L, "PULLBACK", TO + H), stale,
                boundPlan(7L, "RANGE", FROM - 2 * H), boundPlan(8L, "RANGE", TO + H),
                boundPlan(9L, "PULLBACK", FROM - 31 * H)));
        stubDecisionLists(List.of(debrief(1L, "")), List.of());

        String cases = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).casesBlock();

        assertThat(cases).endsWith("窗口里持有过、不在本批的仓位（盈亏和细节留给它们自己那一篇复盘）：\n"
                + "- [7] BTCUSDT SHORT RANGE ｜ " + TIME.format(Instant.ofEpochMilli(FROM - 2 * H)) + " 开仓，均价 61800 ｜ 窗口结束时还拿着\n"
                + "- [2] BTCUSDT SHORT TREND_FOLLOW ｜ " + TIME.format(Instant.ofEpochMilli(FROM + 5 * H)) + " 开仓，首档成交价 61831.8 ｜ 窗口结束时还拿着\n"
                + "- [3] BTCUSDT LONG PULLBACK ｜ " + TIME.format(Instant.ofEpochMilli(FROM + 6 * H)) + " 开仓，均价 100000 ｜ 窗口里已平仓，还没进本批\n");
        // 2 号的出场价不给
        assertThat(cases).doesNotContain("60488");

        // 本局首篇复盘窗口从头算：窗口右端往前 48 小时之内平的 4 号列出来，更早平的 9 号不列
        String fromRoundStart = assembler.assemble(trader(), 0, TO, AgentLang.ZH).casesBlock();
        assertThat(fromRoundStart).contains("- [4] BTCUSDT LONG PULLBACK").doesNotContain("- [9] ");
    }

    @Test
    void noBatchTradesSaysSo() {
        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        // 窗口里没有不在本批的仓位，也要写明没有
        assertThat(m.casesBlock()).startsWith("【本批单子】").contains("本批没有单子")
                .endsWith("窗口里持有过、不在本批的仓位（盈亏和细节留给它们自己那一篇复盘）：\n（没有）\n");
        assertThat(m.statsBlock()).contains("本批没有单子");
        assertThat(m.trades()).isEmpty();
        assertThat(m.classifies(Map.of())).isEmpty();
        assertThat(m.planIds()).isEmpty();
    }

    /** 主人标了不算数的单子不进本批：单子块、笔数统计、可记账的单子里都没有它；权益/回撤线来自决策行序列，不动 */
    @Test
    void staleTradeIsNotInTheBatchButEquityIsUntouched() {
        when(decisionMapper.selectOne(any())).thenReturn(equityRow(FROM - H, "10000"));
        stubDecisionLists(List.of(debrief(1L, ""), debrief(2L, "")), List.of(equityRow(FROM + 3 * H, "10500")));
        FuturesPositionDTO stalePos = closedPos("LONG", "100000", "99000", "-50", FROM + H, FROM + 2 * H);
        stalePos.setId(1L);
        FuturesPositionDTO keptPos = closedPos("LONG", "100000", "101000", "30", FROM + 3 * H, FROM + 4 * H);
        keptPos.setId(2L);
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(stalePos, keptPos));
        AiTraderPlan stale = boundPlan(1L, "REVERSAL", FROM + H);
        stale.setStale(true);
        when(planMapper.selectList(any())).thenReturn(List.of(stale, boundPlan(2L, "PULLBACK", FROM + 3 * H)));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        assertThat(m.casesBlock()).contains("PULLBACK（1 笔，1 胜，净 +30）")
                .doesNotContain("REVERSAL").doesNotContain("-50");
        assertThat(m.statsBlock()).contains("本批单子 1 笔");
        // 权益线不许跟着变：钱账是决策行权益序列算的，stale 只动教材
        assertThat(m.statsBlock()).contains("+5.00%");
        assertThat(m.trades()).containsOnlyKeys(2L);
    }

    // ==================== 配对与了结方式 ====================

    @Test
    void closeMannerFollowsClosingOrder() {
        // 强平状态直判
        FuturesPositionDTO liq = closedPos("LONG", "100000", "90000", "-500", FROM, FROM + 1);
        liq.setStatus("LIQUIDATED");
        assertThat(TradePairing.closeManner(prompts, liq, AgentLang.ZH)).isEqualTo("强平");

        // 空头止盈 95000 触发后按盘口买回，成交在 95010、没越过止盈价：照样是止盈带走，不拿价去猜
        FuturesPositionDTO tp = closedPos("SHORT", "100000", "95010", "49", FROM, FROM + 1);
        tp.setTakeProfits(List.of(new FuturesTakeProfit("t1", new BigDecimal("95000"), new BigDecimal("0.01"))));
        tp.setClosedBy("TAKE_PROFIT");
        assertThat(TradePairing.closeManner(prompts, tp, AgentLang.ZH)).isEqualTo("止盈带走");

        FuturesPositionDTO sl = closedPos("LONG", "100000", "95400", "-46", FROM, FROM + 1);
        sl.setClosedBy("STOP_LOSS");
        assertThat(TradePairing.closeManner(prompts, sl, AgentLang.ZH)).isEqualTo("止损带走");

        // 主动平仓（模型自己调 close_position）是 FILLED
        FuturesPositionDTO manual = closedPos("LONG", "100000", "101000", "10", FROM, FROM + 1);
        assertThat(TradePairing.closeManner(prompts, manual, AgentLang.ZH)).isEqualTo("主动平仓");

        // 没有平仓单：判不出来
        FuturesPositionDTO unknown = closedPos("LONG", "100000", "101000", "10", FROM, FROM + 1);
        unknown.setClosedBy(null);
        assertThat(TradePairing.closeManner(prompts, unknown, AgentLang.ZH)).isEqualTo(TradePairing.MANNER_UNKNOWN);
    }

    /** 精确趟：position_id 说了算，时间就近想反着配也翻不了案——配对漂移的根治 */
    @Test
    void pairAllPrefersPositionIdOverTimeProximity() {
        FuturesPositionDTO pos1 = closedPos("LONG", "100000", "101000", "10", FROM + H, FROM + 2 * H);
        pos1.setId(101L);
        FuturesPositionDTO pos2 = closedPos("LONG", "100000", "99000", "-10", FROM + 3 * H, FROM + 4 * H);
        pos2.setId(102L);
        // planA 绑 pos1，但开仓时刻造得离 pos2 更近（反之亦然）：贪心时间就近会交叉错配
        AiTraderPlan planA = planOf("BTCUSDT", "BREAKOUT", FROM + 3 * H, false);
        planA.setPositionId(101L);
        AiTraderPlan planB = planOf("BTCUSDT", "PULLBACK", FROM + H, false);
        planB.setPositionId(102L);

        var paired = TradePairing.pairAll(List.of(pos1, pos2), List.of(planA, planB));

        assertThat(paired.get(pos1)).isSameAs(planA);
        assertThat(paired.get(pos2)).isSameAs(planB);
    }

    /** 兜底趟喂入顺序无关：统一按平仓时刻升序后，怎么喂结果都一样 */
    @Test
    void pairAllFallbackIsFeedOrderIndependent() {
        FuturesPositionDTO early = closedPos("LONG", "100000", "101000", "10", FROM + H, FROM + 3 * H);
        FuturesPositionDTO late = closedPos("LONG", "100000", "99000", "-10", FROM + 2 * H, FROM + 4 * H);
        AiTraderPlan plan = planOf("BTCUSDT", "BREAKOUT", FROM + 2 * H, false);

        var ascFeed = TradePairing.pairAll(List.of(early, late), List.of(plan));
        var descFeed = TradePairing.pairAll(List.of(late, early), List.of(plan));

        assertThat(ascFeed.get(early)).isSameAs(plan);
        assertThat(ascFeed.get(late)).isNull();
        assertThat(descFeed.get(early)).isSameAs(plan);
        assertThat(descFeed.get(late)).isNull();
    }

    /** 已绑别的仓位的计划不许被兜底趟借走 */
    @Test
    void pairAllNeverLendsBoundPlanToOtherPosition() {
        FuturesPositionDTO pos = closedPos("LONG", "100000", "101000", "10", FROM + H, FROM + 2 * H);
        pos.setId(101L);
        AiTraderPlan boundElsewhere = planOf("BTCUSDT", "BREAKOUT", FROM + H, false);
        boundElsewhere.setPositionId(999L);

        var paired = TradePairing.pairAll(List.of(pos), List.of(boundElsewhere));

        assertThat(paired.get(pos)).isNull();
    }

    // ==================== 本期行情 ====================

    /** 每个币一行 OHLC 与极值时刻；低到高幅度是机会审计的对照物；没 K 线的币写明 */
    @Test
    void marketBlockPerSymbol() {
        AiTrader t = trader();
        t.setSymbols("BTCUSDT, ETHUSDT");
        when(historyStore.load(eq("BTCUSDT"), eq("5m"), anyLong(), anyLong())).thenReturn(List.of(
                bar5m(FROM, "61000", "61500", "60800", "61200"),
                bar5m(FROM + H, "61200", "64000", "61100", "63500")));

        String market = assembler.assemble(t, FROM, TO, AgentLang.ZH).marketBlock();

        // (64000-60800)/60800 = 5.26%
        assertThat(market).startsWith("【本期行情】");
        assertThat(market).contains("- BTCUSDT 61000 → 63500，最低 60800（" + TIME.format(Instant.ofEpochMilli(FROM))
                + "）最高 64000（" + TIME.format(Instant.ofEpochMilli(FROM + H)) + "），低到高 5.26%");
        assertThat(market).contains("- ETHUSDT：窗口内无K线数据");
    }

    /** 行情块最多回看 48h：本局首篇复盘 fromMs=0 也只取最近两天 */
    @Test
    void marketBlockLooksBackAtMost48h() {
        assembler.assemble(trader(), 0L, TO, AgentLang.ZH);

        verify(historyStore).load(eq("BTCUSDT"), eq("5m"), eq(TO - ReviewMaterialAssembler.MARKET_LOOKBACK_MS), eq(TO));
    }

    // ==================== 经验账本 ====================

    private static AiTraderExperience exp(long id, String kind, String slot, String dir, String play, String text) {
        AiTraderExperience e = new AiTraderExperience();
        e.setId(id);
        e.setKind(kind);
        e.setSlot(slot);
        e.setDirection(dir);
        e.setPlayType(play);
        e.setText(text);
        e.setEvidence("证据-" + id);
        return e;
    }

    /** 现役条目带编号和代码记的账（引用不符大于 0 才带）；发现、退役名单分段列出；现役计数不含发现 */
    @Test
    void ledgerBlockListsActiveFindingsAndRetired() {
        AiTraderExperience rule = exp(3L, AiTraderExperience.KIND_RULE, AiTraderExperience.SLOT_RISK,
                AiTraderExperience.DIR_EXECUTION, "PULLBACK", "止损不小于 1 个 1h ATR");
        AiTraderExperience hyp = exp(6L, AiTraderExperience.KIND_HYPOTHESIS, AiTraderExperience.SLOT_ENTRY,
                AiTraderExperience.DIR_RESTRICTION, null, "破位后不市价追");
        AiTraderExperience gap = exp(4L, AiTraderExperience.KIND_GAP, AiTraderExperience.SLOT_EXEC,
                AiTraderExperience.DIR_EXECUTION, null, "止损不小于缓冲");
        AiTraderExperience finding = exp(5L, AiTraderExperience.KIND_FINDING, AiTraderExperience.SLOT_RISK,
                AiTraderExperience.DIR_EXECUTION, null, "指令第六条少赚 4800");
        AiTraderExperience retired = exp(2L, AiTraderExperience.KIND_HYPOTHESIS, AiTraderExperience.SLOT_ENTRY,
                AiTraderExperience.DIR_OPPORTUNITY, "BREAKOUT", "量比大于 2 才追");
        retired.setRetireReason("连续 10 篇复盘名下没有新单子，代码退役");
        when(ledger.active(7L, 1)).thenReturn(List.of(rule, hyp, gap, finding));
        when(ledger.retired(7L, 1)).thenReturn(List.of(retired));
        doReturn(Map.of(
                3L, new ExperienceLedger.Tally(6, 4, 2, new BigDecimal("40.00"), 4, 5, new BigDecimal("31.00"), 3, 0, 0, 2),
                6L, NO_TRADES,
                4L, new ExperienceLedger.Tally(0, 0, 0, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO, 0, 3, 1, 0)))
                .when(ledger).tallies(any());

        String block = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).ledgerBlock();

        assertThat(block).startsWith("【经验账本】（现役 3 条");
        assertThat(block).contains("#3 规律 风险限制 执行型 PULLBACK ｜ 止损不小于 1 个 1h ATR ｜ "
                + "名下 6 笔，赚 4 亏 2，合计 +40.00，4 天；当前段 5 笔，合计 +31.00，3 天；引用不符 2 次 ｜ 证据 证据-3\n");
        assertThat(block).contains("#6 假设 入场条件 限制型 通用 ｜ 破位后不市价追 ｜ "
                + "名下 0 笔，赚 0 亏 0，合计 +0.00，0 天；当前段 0 笔，合计 +0.00，0 天 ｜ 证据 证据-6\n");
        assertThat(block).contains("#4 执行缺口 执行缺口 执行型 通用 ｜ 止损不小于缓冲 ｜ 守 3 次，破 1 次 ｜ 证据 证据-4\n");
        assertThat(block).contains("【已记录的发现】").contains("#5 指令第六条少赚 4800 ｜ 证据-5");
        assertThat(block).contains("【退役名单】").contains("#2 量比大于 2 才追 ｜ 连续 10 篇复盘名下没有新单子，代码退役");
    }

    @Test
    void emptyLedgerSaysOnlyAddsPossible() {
        String block = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).ledgerBlock();

        assertThat(block).startsWith("【经验账本】（现役 0 条").contains("账本为空：本期只能新增");
        assertThat(block).doesNotContain("【已记录的发现】").doesNotContain("【退役名单】");
    }

    /**
     * 各笔单笔复盘的归类行合并成每条经验一行：账本块里列在那条下面（按标记分开），同一份随素材交出去落账。
     * 写了别的仓位编号的行、标记和条目种类对不上的行不算。本批单子按仓位编号带出净盈亏、平仓日，
     * 和开这笔仓的那一轮实际跑的时段；认不出是哪一轮开的，时段为空
     */
    @Test
    void ledgerRowListsTradesTheDebriefsSortedUnderItAndTheMergedRowsGoOut() {
        long opened = FROM + H;
        FuturesPositionDTO pos = closedPos("LONG", "100000", "99000", "-293.29", opened, opened + H);
        pos.setId(42L);
        FuturesPositionDTO other = closedPos("LONG", "2000", "1990", "-50", opened + M5, opened + 2 * H);
        other.setSymbol("ETHUSDT");
        other.setId(43L);
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(pos, other));
        AiTraderPlan otherPlan = boundPlan(43L, "PULLBACK", opened);
        otherPlan.setSymbol("ETHUSDT");
        when(planMapper.selectList(any())).thenReturn(List.of(boundPlan(42L, "BREAKOUT", opened), otherPlan));
        // 42 号：#62 名下、#61 破；#75 写了别人的编号，不算。43 号：#62 引用不符；#61 用了名下，和执行缺口对不上，不算
        stubDecisionLists(List.of(
                debrief(42L, "#62 名下 42 ｜ 条件对得上\n#61 破 42\n#75 名下 43"),
                debrief(43L, "#62 引用不符 43\n#61 名下 43\n#75 无")), List.of());
        when(ledger.active(7L, 1)).thenReturn(List.of(
                exp(62L, AiTraderExperience.KIND_RULE, AiTraderExperience.SLOT_ENTRY,
                        AiTraderExperience.DIR_RESTRICTION, "PULLBACK", "破位不挂内侧限价"),
                exp(75L, AiTraderExperience.KIND_RULE, AiTraderExperience.SLOT_ENTRY,
                        AiTraderExperience.DIR_OPPORTUNITY, "PULLBACK", "保留原主阻力限价单"),
                exp(61L, AiTraderExperience.KIND_GAP, AiTraderExperience.SLOT_EXEC,
                        AiTraderExperience.DIR_EXECUTION, null, "止损不小于缓冲")));
        // 42 号认得出是哪一轮开的：那一轮跑了 7 分钟，落库在开仓边界后 8 分钟；43 号认不出
        AiTraderDecision openRound = new AiTraderDecision();
        openRound.setWakeTime(opened);
        openRound.setLatencyMs(7 * 60_000);
        openRound.setCreatedAt(at(opened + 8 * 60_000L));
        when(tradeRecords.openDecisions(any(), any())).thenReturn(Map.of(142L, openRound));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        assertThat(m.ledgerBlock()).contains("证据 证据-62\n  本批单笔复盘归到它这里的单子：名下 [42] BTCUSDT LONG BREAKOUT "
                + TIME.format(Instant.ofEpochMilli(opened)) + " 净 -293.29；引用不符 [43] ETHUSDT LONG PULLBACK "
                + TIME.format(Instant.ofEpochMilli(opened + M5)) + " 净 -50\n");
        assertThat(m.ledgerBlock()).contains("证据 证据-61\n  本批单笔复盘归到它这里的单子：破 [42] BTCUSDT LONG BREAKOUT ");
        assertThat(m.ledgerBlock()).doesNotContain("证据 证据-75\n  本批单笔复盘");
        assertThat(m.classifies(Map.of())).containsExactly(
                new ExperienceLedger.Classify(62L, List.of(
                        new ExperienceLedger.Mark(AiTraderExperienceTrade.MARK_UNDER, 42L),
                        new ExperienceLedger.Mark(AiTraderExperienceTrade.MARK_MISCITED, 43L))),
                new ExperienceLedger.Classify(61L, List.of(new ExperienceLedger.Mark(AiTraderExperienceTrade.MARK_BROKEN, 42L))));
        assertThat(m.trades()).containsOnlyKeys(42L, 43L);
        assertThat(m.trades().get(42L)).isEqualTo(new ExperienceLedger.PeriodTrade(new BigDecimal("-293.29"),
                LocalDate.of(2026, 7, 27), opened + 60_000L, opened + 8 * 60_000L));
        assertThat(m.trades().get(43L).openRoundFrom()).isNull();
        assertThat(m.trades().get(43L).openRoundTo()).isNull();
    }

    /** 没有平仓盈亏的单子记不了账：它的归类不并进去，同一条经验名下别的单子照记 */
    @Test
    void tradeWithoutNetIsLeftOutOfTheMergedRows() {
        long opened = FROM + H;
        FuturesPositionDTO pos = closedPos("LONG", "100000", "99000", "-293.29", opened, opened + H);
        pos.setId(42L);
        FuturesPositionDTO noNet = closedPos("LONG", "100000", "99500", "0", opened + M5, opened + 2 * H);
        noNet.setId(43L);
        noNet.setClosedPnl(null);
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(pos, noNet));
        when(planMapper.selectList(any())).thenReturn(List.of(boundPlan(42L, "BREAKOUT", opened), boundPlan(43L, "BREAKOUT", opened)));
        stubDecisionLists(List.of(debrief(42L, "#62 名下 42"), debrief(43L, "#62 名下 43")), List.of());
        when(ledger.active(7L, 1)).thenReturn(List.of(exp(62L, AiTraderExperience.KIND_RULE, AiTraderExperience.SLOT_ENTRY,
                AiTraderExperience.DIR_OPPORTUNITY, "BREAKOUT", "破位后挂限价")));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        assertThat(m.trades()).containsOnlyKeys(42L);
        assertThat(m.classifies(Map.of())).containsExactly(new ExperienceLedger.Classify(62L,
                List.of(new ExperienceLedger.Mark(AiTraderExperienceTrade.MARK_UNDER, 42L))));
    }

    /**
     * 单笔复盘开跑之后账本才新增的条目：列在那一笔的正文下面；这篇补的编号归类行只认（条目，单子）对得上名单的，
     * 和单笔复盘写的一起合成每条一行。复盘之前就有的条目、名单外的单子不收
     */
    @Test
    void entriesAddedAfterADebriefAreListedForTheReviewToSort() {
        long opened = FROM + H;
        FuturesPositionDTO pos = closedPos("LONG", "100000", "99000", "-293.29", opened, opened + H);
        pos.setId(42L);
        FuturesPositionDTO other = closedPos("LONG", "100000", "99000", "-100", opened + M5, opened + 2 * H);
        other.setId(43L);
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(pos, other));
        when(planMapper.selectList(any())).thenReturn(List.of(boundPlan(42L, "BREAKOUT", opened), boundPlan(43L, "BREAKOUT", opened)));
        AiTraderDecision early = debrief(42L, "#62 名下 42");
        early.setWakeTime(opened + 2 * H);
        stubDecisionLists(List.of(early, debrief(43L, "#62 名下 43\n#90 名下 43")), List.of());
        AiTraderExperience added = exp(90L, AiTraderExperience.KIND_HYPOTHESIS, AiTraderExperience.SLOT_ENTRY,
                AiTraderExperience.DIR_OPPORTUNITY, "BREAKOUT", "回踩前高不破再进");
        added.setCreatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(opened + 3 * H), ZoneId.systemDefault()));
        when(ledger.active(7L, 1)).thenReturn(List.of(exp(62L, AiTraderExperience.KIND_RULE, AiTraderExperience.SLOT_ENTRY,
                AiTraderExperience.DIR_OPPORTUNITY, "BREAKOUT", "破位后挂限价"), added));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        assertThat(m.gaps()).containsExactly(Map.entry(42L, Set.of(90L)));
        assertThat(m.casesBlock()).contains("    路径：第 42 笔的路径\n    管理动作：没动过\n  做这笔单笔复盘时还没有的条目：#90（由你给这一笔补归类）\n");
        Map<Long, List<ExperienceLedger.Classify>> gapLines = m.gapLines(List.of(
                new ExperienceLedger.Classify(90L, List.of(new ExperienceLedger.Mark(AiTraderExperienceTrade.MARK_UNDER, 42L),
                        new ExperienceLedger.Mark(AiTraderExperienceTrade.MARK_UNDER, 43L))),
                new ExperienceLedger.Classify(62L, List.of(new ExperienceLedger.Mark(AiTraderExperienceTrade.MARK_MISCITED, 42L)))));
        assertThat(gapLines).containsOnlyKeys(42L);
        assertThat(m.classifies(gapLines)).containsExactly(
                new ExperienceLedger.Classify(62L, List.of(new ExperienceLedger.Mark(AiTraderExperienceTrade.MARK_UNDER, 42L),
                        new ExperienceLedger.Mark(AiTraderExperienceTrade.MARK_UNDER, 43L))),
                new ExperienceLedger.Classify(90L, List.of(new ExperienceLedger.Mark(AiTraderExperienceTrade.MARK_UNDER, 42L),
                        new ExperienceLedger.Mark(AiTraderExperienceTrade.MARK_UNDER, 43L))));
    }

    // ==================== 素材有无与上次复盘定位 ====================

    /** 窗口内有开仓动作（含被拒的尝试——粗筛 LIKE，宁可多复盘）就有素材，不打 sim */
    @Test
    void openAttemptsAreMaterialWithoutTouchingSim() {
        when(decisionMapper.selectCount(any())).thenReturn(1L);

        assertThat(assembler.hasNewMaterial(trader(), FROM, TO)).isTrue();
        verify(simTradeClient, never()).getClosedPositions(anyLong(), anyInt());
    }

    /**
     * 没有开仓动作时看有没有可收的单子：做完单笔复盘、还没进过统一复盘、sim 的已平仓位里还找得到的算素材；
     * 还没复盘成功的不算，sim 那边已经找不到的也不算
     */
    @Test
    void debriefedTradesAreMaterialWhenNoOpens() {
        when(decisionMapper.selectCount(any())).thenReturn(0L);
        when(planMapper.selectList(any())).thenReturn(List.of(boundPlan(1L, "PULLBACK", FROM - 40 * H)));
        stubDecisionLists(List.of(), List.of());
        assertThat(assembler.hasNewMaterial(trader(), FROM, TO)).isFalse();

        stubDecisionLists(List.of(debrief(1L, "")), List.of());
        assertThat(assembler.hasNewMaterial(trader(), FROM, TO)).as("sim 的已平仓位里没有这一笔").isFalse();

        FuturesPositionDTO pos = closedPos("LONG", "100000", "99960", "-40", FROM - 40 * H, FROM - 39 * H);
        pos.setId(1L);
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(pos));
        assertThat(assembler.hasNewMaterial(trader(), FROM, TO)).isTrue();
    }

    /** 提前做了单笔复盘、平仓晚于窗口右端的单子：这一篇不收，也不算素材；窗口盖住它平仓的下一篇才收 */
    @Test
    void debriefedTradeClosedAfterTheWindowWaitsForTheNextReview() {
        when(decisionMapper.selectCount(any())).thenReturn(0L);
        FuturesPositionDTO pos = closedPos("LONG", "100000", "101000", "10", TO + H, TO + 2 * H);
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(pos));
        when(planMapper.selectList(any())).thenReturn(List.of(boundPlan(pos.getId(), "BREAKOUT", TO + H)));
        stubDecisionLists(List.of(debrief(pos.getId(), "")), List.of());

        assertThat(assembler.hasNewMaterial(trader(), FROM, TO)).isFalse();
        assertThat(assembler.assemble(trader(), FROM, TO, AgentLang.ZH).planIds()).isEmpty();

        long nextTo = TO + 86_400_000L;
        assertThat(assembler.hasNewMaterial(trader(), TO, nextTo)).isTrue();
        assertThat(assembler.assemble(trader(), TO, nextTo, AgentLang.ZH).planIds()).containsExactly(100 + pos.getId());
    }

    /** 开仓动作只数交易行白名单（TRADE/ALERT/MANUAL）且 actions 里带 open_position；REVIEW 行天天有，不许充当素材 */
    @Test
    void openAttemptQueryFiltersByKindWhitelistAndAction() {
        when(decisionMapper.selectCount(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<AiTraderDecision> w = inv.getArgument(0);
            // MP 条件值懒求值：先拼一次 SQL，参数才落进 paramNameValuePairs
            w.getTargetSql();
            var values = w.getParamNameValuePairs().values();
            assertThat(values).contains(AiTraderDecision.KIND_TRADE, AiTraderDecision.KIND_ALERT, AiTraderDecision.KIND_MANUAL);
            assertThat(values).doesNotContain(AiTraderDecision.KIND_REVIEW);
            assertThat(values.stream().anyMatch(v -> String.valueOf(v).contains("open_position"))).isTrue();
            return 1L;
        });

        assertThat(assembler.hasNewMaterial(trader(), FROM, TO)).isTrue();
    }

    @Test
    void lastReviewReturnsNullWhenNone() {
        when(decisionMapper.selectOne(any())).thenReturn(null);
        assertThat(assembler.lastReview(7L, 1)).isNull();

        AiTraderDecision review = new AiTraderDecision();
        review.setWakeTime(FROM);
        review.setReasoning("【本期复盘】\n战绩：……");
        when(decisionMapper.selectOne(any())).thenReturn(review);
        assertThat(assembler.lastReview(7L, 1).getWakeTime()).isEqualTo(FROM);
    }

    // ==================== 盯盘布防 ====================

    /**
     * 期间布的条件、命中，以及得知命中那轮的交易动作与该币结论段都进块；整期没布过的币点名。
     * 动作记了时刻的前面带时刻，没记的不带；结论后面带那一轮写完的时刻
     */
    @Test
    void watchBlockListsHitsAndFollowUp() {
        AiTrader t = trader();
        t.setSymbols("BTCUSDT,ETHUSDT");
        AiTraderWatch hit = watch(31L, AiTraderWatch.CLOSE_ABOVE, FROM + H);
        hit.setStatus(AiTraderWatch.STATUS_FIRED);
        hit.setFiredAt(FROM + 3 * H);
        hit.setFiredPrice(new BigDecimal("63120"));
        hit.setWokeAt(FROM + 3 * H + 1);
        AiTraderWatch armed = watch(32L, AiTraderWatch.TOUCH_BELOW, FROM + 2 * H);
        when(watchStore.inWindow(7L, 1, FROM, TO)).thenReturn(List.of(hit, armed));
        when(watchStore.armed(7L, 1)).thenReturn(List.of(armed));
        AiTraderDecision woke = new AiTraderDecision();
        woke.setKind(AiTraderDecision.KIND_WATCH);
        long openedAt = FROM + 3 * H + 2 * 60_000L;
        long writtenAt = FROM + 3 * H + M5;
        woke.setCreatedAt(at(writtenAt));
        woke.setActionsJson("[{\"tool\":\"indicators\"},{\"tool\":\"open_position\",\"status\":\"ok\",\"at\":" + openedAt + "},{\"tool\":\"set_watch\",\"rejected\":\"太多\"},"
                + "{\"tool\":\"close_position\",\"status\":\"error\",\"error\":\"x\"}]");
        woke.setReasoning("先看结构…\n" + prompts.get(AgentLang.ZH, "trader.mark.conclusion")
                + "\n总评：顺势\n[BTCUSDT]\n判断：1h 收盘站上 63000，突破确认\n动作：开多 0.01\n等待：无\n[ETHUSDT]\n判断：跟随\n动作：HOLD\n等待：无");
        // 第一次 selectOne 是战绩表取起始权益，第二次才是得知命中的那轮
        when(decisionMapper.selectOne(any())).thenReturn(equityRow(FROM, "10000"), woke);

        String block = assembler.assemble(t, FROM, TO, AgentLang.ZH).watchBlock();

        assertThat(block).startsWith("【盯盘布防】")
                .contains("- 期间布防 2 条（BTCUSDT）；命中 1 条；复盘时仍在盯 1 条；整期没有盯盘条件的币：ETHUSDT")
                .contains("- " + TIME.format(Instant.ofEpochMilli(FROM + 3 * H))
                        + " 命中：BTCUSDT 1h 收盘 63120 站上 63000 ｜ 布时备注：突破确认 ｜ 得知于 "
                        + TIME.format(Instant.ofEpochMilli(FROM + 3 * H + 1))
                        + "（WATCH）｜ 那轮对该币的交易动作：" + TIME.format(Instant.ofEpochMilli(openedAt))
                        + " open_position, set_watch（被拒）, close_position（失败） ｜ 那轮该币结论（"
                        + TIME.format(Instant.ofEpochMilli(writtenAt)) + " 写完）：判断：1h 收盘站上 63000，突破确认 动作：开多 0.01 等待：无");
    }

    @Test
    void watchFollowUpReportsEachReverseLegWithoutInventingAnOpening() {
        AiTraderWatch hit = watch(31L, AiTraderWatch.CLOSE_ABOVE, FROM + H);
        hit.setStatus(AiTraderWatch.STATUS_FIRED);
        hit.setFiredAt(FROM + 3 * H);
        hit.setFiredPrice(new BigDecimal("63120"));
        hit.setWokeAt(FROM + 3 * H + 1);
        when(watchStore.inWindow(7L, 1, FROM, TO)).thenReturn(List.of(hit));
        AiTraderDecision woke = new AiTraderDecision();
        woke.setKind(AiTraderDecision.KIND_WATCH);
        woke.setCreatedAt(at(FROM + 3 * H + M5));
        for (String openStatus : List.of("error", "unknown", "not_attempted")) {
            woke.setActionsJson("""
                    [{"tool":"reverse_position","status":"partial","result":{
                      "close":{"tool":"close_position","status":"ok"},
                      "open":{"tool":"open_position","status":"%s"}
                    }}]
                    """.formatted(openStatus));
            when(decisionMapper.selectOne(any())).thenReturn(equityRow(FROM, "10000"), woke);
            String label = switch (openStatus) {
                case "error" -> "失败";
                case "unknown" -> "结果未知";
                default -> "未发起";
            };
            assertThat(assembler.assemble(trader(), FROM, TO, AgentLang.ZH).watchBlock())
                    .contains("那轮对该币的交易动作：close_position, open_position（" + label + "）");
        }
    }

    /**
     * 盯 BTC 价格、为 SOL 布的条件：算 SOL 布了条件，BTC 整期没有；得知那轮的结论段取 SOL 的。
     * 命中时那一轮已经在跑、中途插话告诉它的：woke_at 是插进去那一刻，对不上哪一轮的 wake_time，按时段找那时在跑的那一轮，
     * 说明那一轮几点开始、几点告诉的，不说"得知于"；交易动作只列告诉它之后对 SOL 做的（仓位编号按计划认币）
     */
    @Test
    void watchBlockCountsConditionUnderItsOwnerAndMarksMidRoundHit() {
        AiTrader t = trader();
        t.setSymbols("BTCUSDT,SOLUSDT");
        AiTraderWatch hit = watch(41L, AiTraderWatch.CLOSE_ABOVE, FROM + H);
        hit.setForSymbol("SOLUSDT");
        hit.setStatus(AiTraderWatch.STATUS_FIRED);
        hit.setFiredAt(FROM + 3 * H);
        hit.setFiredPrice(new BigDecimal("63120"));
        long toldAt = FROM + 3 * H + 30_000L;
        hit.setWokeAt(toldAt);
        when(watchStore.inWindow(7L, 1, FROM, TO)).thenReturn(List.of(hit));
        when(watchStore.armed(7L, 1)).thenReturn(List.of());
        AiTraderPlan sol = new AiTraderPlan();
        sol.setPositionId(5L);
        sol.setSymbol("SOLUSDT");
        when(planMapper.selectList(any())).thenReturn(List.of(sol));
        AiTraderDecision woke = new AiTraderDecision();
        woke.setWakeTime(FROM + 2 * H);
        woke.setKind(AiTraderDecision.KIND_TRADE);
        long writtenAt = toldAt + 2 * 60_000L;
        woke.setCreatedAt(at(writtenAt));
        woke.setActionsJson("[{\"tool\":\"set_stop_loss\",\"args\":{\"positionId\":5},\"status\":\"ok\",\"at\":" + (toldAt - 60_000L) + "},"
                + "{\"tool\":\"close_position\",\"args\":{\"positionId\":5},\"status\":\"ok\",\"at\":" + (toldAt + 60_000L) + "},"
                + "{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"},\"status\":\"ok\",\"at\":" + (toldAt + 90_000L) + "}]");
        woke.setReasoning(prompts.get(AgentLang.ZH, "trader.mark.conclusion")
                + "\n[SOLUSDT]\n判断：跟着 BTC 突破\n动作：HOLD\n等待：无");
        // 战绩表取起始权益；按 wake_time 对不上；按时段找到那时在跑的那一轮
        when(decisionMapper.selectOne(any())).thenReturn(equityRow(FROM, "10000"), null, woke);

        String block = assembler.assemble(t, FROM, TO, AgentLang.ZH).watchBlock();

        assertThat(block)
                .contains("- 期间布防 1 条（SOLUSDT）；命中 1 条；复盘时仍在盯 0 条；整期没有盯盘条件的币：BTCUSDT")
                .contains("命中：BTCUSDT 1h 收盘 63120 站上 63000（为 SOLUSDT 布的）")
                .contains("命中时 " + TIME.format(Instant.ofEpochMilli(FROM + 2 * H)) + " 开始的那一轮（TRADE）正在跑，"
                        + TIME.format(Instant.ofEpochMilli(toldAt)) + " 中途告诉了它 ｜ 那轮在这之后对该币的交易动作："
                        + TIME.format(Instant.ofEpochMilli(toldAt + 60_000L)) + " close_position ｜")
                .contains("那轮该币结论（" + TIME.format(Instant.ofEpochMilli(writtenAt)) + " 写完）：判断：跟着 BTC 突破 动作：HOLD 等待：无")
                .doesNotContain("得知于");
    }

    /** 窗口里一条都没布也要说（窗口前布下还在盯的币不算没条件）；命中了还没哪轮告诉过它的照样列出来 */
    @Test
    void watchBlockWithoutWatchesOrUnreportedHit() {
        when(watchStore.inWindow(7L, 1, FROM, TO)).thenReturn(List.of());
        when(watchStore.armed(7L, 1)).thenReturn(List.of());
        when(decisionMapper.selectOne(any())).thenReturn(equityRow(FROM, "10000"));

        assertThat(assembler.assemble(trader(), FROM, TO, AgentLang.ZH).watchBlock())
                .contains("- 期间布防 0 条（—）；命中 0 条；复盘时仍在盯 0 条；整期没有盯盘条件的币：BTCUSDT");

        when(watchStore.armed(7L, 1)).thenReturn(List.of(watch(30L, AiTraderWatch.TOUCH_BELOW, FROM - H)));
        assertThat(assembler.assemble(trader(), FROM, TO, AgentLang.ZH).watchBlock())
                .contains("- 期间布防 0 条（—）；命中 0 条；复盘时仍在盯 1 条\n")
                .doesNotContain("整期没有盯盘条件");

        AiTraderWatch hit = watch(33L, AiTraderWatch.TOUCH_ABOVE, FROM + H);
        hit.setStatus(AiTraderWatch.STATUS_FIRED);
        hit.setFiredAt(FROM + 2 * H);
        hit.setFiredPrice(new BigDecimal("64000"));
        when(watchStore.inWindow(7L, 1, FROM, TO)).thenReturn(List.of(hit));

        assertThat(assembler.assemble(trader(), FROM, TO, AgentLang.ZH).watchBlock())
                .contains("命中：BTCUSDT 标记价 64000 触及 63000 以上 ｜ 布时备注：突破确认 ｜ 命中后还没有哪轮唤醒告诉过它");
    }

    private static AiTraderWatch watch(long id, String kind, long createdWakeTime) {
        AiTraderWatch w = new AiTraderWatch();
        w.setId(id);
        w.setTraderId(7L);
        w.setRoundNo(1);
        w.setSymbol("BTCUSDT");
        w.setForSymbol("BTCUSDT");
        w.setKind(kind);
        w.setLevel(new BigDecimal("63000"));
        w.setPeriod(kind.startsWith("CLOSE_") ? "1h" : null);
        w.setNote("突破确认");
        w.setStatus(AiTraderWatch.STATUS_ARMED);
        w.setCreatedWakeTime(createdWakeTime);
        return w;
    }
}
