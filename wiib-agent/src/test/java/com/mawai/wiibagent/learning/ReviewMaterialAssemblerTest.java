package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibagent.trader.wakeup.WakeWindow;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.entity.AiTraderExperienceVerdict;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * 复盘素材组装单测：战绩、案例事实表、行情、账本全是代码算的，数字必须对——模型只许复述，代码错一位就是复盘造假。
 */
class ReviewMaterialAssemblerTest {

    /** 窗口起点：某个整日边界；窗口 = (FROM, TO]，一天 */
    private static final long FROM = 1785110400000L;
    private static final long TO = FROM + 86_400_000L;
    private static final long H = 3600_000L;
    private static final long M5 = 300_000L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(WakeWindow.ZONE);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(WakeWindow.ZONE);

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

    private final ReviewMaterialAssembler assembler = new ReviewMaterialAssembler(
            decisionMapper, planMapper, simTradeClient, prompts, new CaseFileBuilder(historyStore), ledger,
            new PlayStatsAssembler(simTradeClient, planMapper, prompts));

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

    private static FuturesPositionDTO closedPos(String side, String entry, String closed, String pnl,
                                                long openMs, long closeMs) {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setSymbol("BTCUSDT");
        p.setSide(side);
        p.setStatus("CLOSED");
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

    /** 5m bar 造数：一根 5m 的 OHLC */
    private static KlineBar bar5m(long openTime, String o, String h, String l, String c) {
        return new KlineBar(openTime, openTime + M5 - 1,
                new BigDecimal(o), new BigDecimal(h), new BigDecimal(l), new BigDecimal(c), BigDecimal.ZERO);
    }

    // ==================== 战绩表 ====================

    @Test
    void statsComputedFromEquitySeriesAndClosedTrades() {
        // 窗口起点前最后一条权益 10000；窗口内 10200 → 9800 → 10500
        when(decisionMapper.selectOne(any())).thenReturn(equityRow(FROM - H, "10000"));
        when(decisionMapper.selectList(any())).thenReturn(List.of(
                equityRow(FROM + H, "10200"),
                equityRow(FROM + 2 * H, "9800"),
                equityRow(FROM + 3 * H, "10500")));
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(
                closedPos("LONG", "100000", "103000", "300", FROM + H, FROM + 2 * H),
                closedPos("LONG", "100000", "99000", "-100", FROM + 2 * H, FROM + 3 * H)));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        // 收益率 (10500-10000)/10000=+5%；回撤峰10200谷9800=3.92%；2笔1胜1负
        assertThat(m.statsBlock()).contains("10000.00").contains("10500.00").contains("+5.00%");
        assertThat(m.statsBlock()).contains("3.92%");
        assertThat(m.statsBlock()).contains("已了结 2 笔：1 胜 1 负（胜率 50%），合计净盈亏 +200.00");
        assertThat(m.closedTrades()).isEqualTo(2);
    }

    @Test
    void startEquityFallsBackToInitialBalanceWhenNoPriorRow() {
        when(decisionMapper.selectOne(any())).thenReturn(null);
        when(decisionMapper.selectList(any())).thenReturn(List.of(equityRow(FROM + H, "10100")));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        // 无前值 → 起始按初始资金 10000：(10100-10000)/10000=+1%
        assertThat(m.statsBlock()).contains("+1.00%");
    }

    /**
     * 已平仓位是先取最近 200 条再按窗口过滤，取回条数顶到上限时这份"硬事实"可能不全，
     * 而模型被明令不许核算——块头必须说清楚这是不完全统计。
     */
    @Test
    void statsWarnsWhenClosedFetchHitsLimit() {
        List<FuturesPositionDTO> full = new ArrayList<>();
        for (int i = 0; i < ReviewMaterialAssembler.CLOSED_FETCH_LIMIT; i++) {
            full.add(closedPos("LONG", "100000", "100100", "10", FROM + 60_000, FROM + 120_000));
        }
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(full);

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        assertThat(m.statsBlock()).contains("不完全统计").contains("200");
    }

    @Test
    void statsHasNoTruncationWarningBelowLimit() {
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(
                closedPos("LONG", "100000", "103000", "300", FROM + H, FROM + 2 * H)));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        assertThat(m.statsBlock()).doesNotContain("不完全统计");
    }

    // ==================== 案例素材：一笔的事实表 ====================

    /**
     * 一笔多单的完整事实表：交易行、路径指标、1h 收盘、计划（含修订）、入场上下文。
     * 路径数字全从 5m 算：浮盈/逆向占比以计划原始目标/止损距离为分母。
     */
    @Test
    void caseRendersTradePathPlanAndEntryContext() {
        long openMs = FROM + H;
        long closeMs = FROM + 6 * H;
        FuturesPositionDTO pos = closedPos("LONG", "100000", "95000", "-50", openMs, closeMs);
        pos.setId(42L);
        pos.setStopLosses(List.of(new FuturesStopLoss("s1", new BigDecimal("95500"), new BigDecimal("0.01"))));
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(pos));

        AiTraderPlan plan = planOf("BTCUSDT", "BREAKOUT", openMs, false);
        plan.setPositionId(42L);
        plan.setSignalsUsed("突破前高+量比1.8");
        plan.setInvalidationCondition("1h收盘跌回98000下方");
        plan.setStopLossPrice(new BigDecimal("95500"));
        plan.setTakeProfitPrice(new BigDecimal("110000"));
        plan.setRevisionsJson("[{\"time\":\"x\",\"type\":\"stop_loss\",\"change\":\"95500→96000\",\"reason\":\"锁利\"}]");
        plan.setEntryCtxJson("{\"regime\":\"TREND_UP\",\"atr1h\":350.5,\"stopAtr\":12.8,\"stopPct\":4.5,\"hourBj\":9,"
                + "\"orderType\":\"MARKET\",\"leverage\":10,\"eventMin\":-90,\"eventTitle\":\"CPI\",\"funding\":0.0001}");
        when(planMapper.selectList(any())).thenReturn(List.of(plan));

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

        assertThat(cases).contains("★ BREAKOUT（1 笔，0 胜，净 -50）");
        assertThat(cases).contains("- BTCUSDT LONG " + TIME.format(Instant.ofEpochMilli(openMs)) + " → "
                + TIME.format(Instant.ofEpochMilli(closeMs))
                + "（持有 5小时）｜ 入场 100000 止损 95500 目标 110000 出场 95000，止损带走 ｜ 净 -50，资金费 —");
        assertThat(cases).contains("  路径：最大浮盈 102500（" + HM.format(Instant.ofEpochMilli(openMs + M5))
                + "，目标的 25%）｜ 最大逆向 95000（止损的 111%）｜ 到目标 25% / 50% 用时 5 / — 分 ｜ 首次收回入场价 "
                + HM.format(Instant.ofEpochMilli(FROM + 2 * H)) + " ｜ 出场后 4h / 24h 最有利 97000 / 99000，之后没到原目标");
        assertThat(cases).contains("  1h 收盘：102000 99700 95200");
        assertThat(cases).contains("  计划：论点 突破前高+量比1.8 ｜ 失效 1h收盘跌回98000下方 ｜ 修订 stop_loss 95500→96000（锁利）");
        assertThat(cases).contains("  入场上下文：regime TREND_UP ｜ 1h ATR 350.5 ｜ 止损 12.8 个 ATR（4.5%）｜ 距最近 High 事件 -90 分 CPI"
                + " ｜ 北京时间 9 时 ｜ 资金费 0.0001 ｜ MARKET 10x");
    }

    /** 出场后数据覆盖不到 4h / 24h：最有利价留空，结尾写"出场后数据不足"而不是没到目标 */
    @Test
    void caseAfterExitCoverageShortRendersNotice() {
        long openMs = FROM + H;
        long closeMs = FROM + 2 * H;
        FuturesPositionDTO pos = closedPos("SHORT", "100000", "99000", "10", openMs, closeMs);
        pos.setId(43L);
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(pos));
        AiTraderPlan plan = planOf("BTCUSDT", "RANGE", openMs, false);
        plan.setSide("SHORT");
        plan.setPositionId(43L);
        plan.setStopLossPrice(new BigDecimal("101000"));
        plan.setTakeProfitPrice(new BigDecimal("98000"));
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        when(historyStore.load(eq("BTCUSDT"), eq("5m"), anyLong(), anyLong())).thenReturn(List.of(
                bar5m(openMs, "100000", "100400", "99200", "99300"),
                bar5m(closeMs + M5, "99300", "99500", "97900", "98000")));

        String cases = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).casesBlock();

        assertThat(cases).contains("★ RANGE（1 笔，1 胜，净 +10）");
        assertThat(cases).contains("出场后 4h / 24h 最有利 — / —，出场后数据不足");
    }

    /** 没有 K 线：路径全部留空、没有 1h 收盘行，其余事实照出 */
    @Test
    void caseWithoutBarsLeavesPathBlank() {
        FuturesPositionDTO pos = closedPos("LONG", "100000", "101000", "10", FROM + H, FROM + 2 * H);
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(pos));

        String cases = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).casesBlock();

        assertThat(cases).contains("★ 未标类型（1 笔，1 胜，净 +10）");
        assertThat(cases).contains("  路径：最大浮盈 —（—，目标的 —）｜ 最大逆向 —（止损的 —）｜ 到目标 25% / 50% 用时 — / — 分 ｜ 首次收回入场价 — ｜ 出场后 4h / 24h 最有利 — / —，出场后数据不足");
        assertThat(cases).doesNotContain("1h 收盘");
        assertThat(cases).contains("  计划：（无计划记录）");
        assertThat(cases).doesNotContain("入场上下文");
    }

    // ==================== 案例素材：分组与选案 ====================

    /** 只有前两组展开（★）带交易行，其余组一行带过；没标类型的组叫"未标类型" */
    @Test
    void onlyTwoGroupsExpanded() {
        FuturesPositionDTO p1 = closedPos("LONG", "100000", "99960", "-40", FROM + H, FROM + 2 * H);
        p1.setId(1L);
        FuturesPositionDTO p2 = closedPos("LONG", "100000", "100010", "10", FROM + 3 * H, FROM + 4 * H);
        p2.setId(2L);
        FuturesPositionDTO p3 = closedPos("LONG", "100000", "100500", "500", FROM + 5 * H, FROM + 6 * H);
        p3.setId(3L);
        FuturesPositionDTO p4 = closedPos("LONG", "100000", "99995", "-5", FROM + 7 * H, FROM + 8 * H);
        p4.setId(4L);
        FuturesPositionDTO p5 = closedPos("LONG", "100000", "99997", "-3", FROM + 9 * H, FROM + 10 * H);
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(p1, p2, p3, p4, p5));
        AiTraderPlan a = planOf("BTCUSDT", "PULLBACK", FROM + H, false);
        a.setPositionId(1L);
        AiTraderPlan b = planOf("BTCUSDT", "PULLBACK", FROM + 3 * H, false);
        b.setPositionId(2L);
        AiTraderPlan c = planOf("BTCUSDT", "BREAKOUT", FROM + 5 * H, false);
        c.setPositionId(3L);
        AiTraderPlan d = planOf("BTCUSDT", "RANGE", FROM + 7 * H, false);
        d.setPositionId(4L);
        when(planMapper.selectList(any())).thenReturn(List.of(a, b, c, d));

        String cases = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).casesBlock();

        assertThat(cases).contains("★ PULLBACK（2 笔，1 胜，净 -30）").contains("★ BREAKOUT（1 笔，1 胜，净 +500）");
        assertThat(cases).contains("RANGE（1 笔，0 胜，净 -5）").doesNotContain("★ RANGE");
        assertThat(cases).contains("未标类型（1 笔，0 胜，净 -3）").doesNotContain("★ 未标类型");
        // 没展开的组没有交易行：RANGE 那笔的出场价不出现
        assertThat(cases).doesNotContain("99995").doesNotContain("99997");
        assertThat(cases).contains("99960").contains("100500");
    }

    @Test
    void noClosedTradesSaysSo() {
        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        assertThat(m.casesBlock()).startsWith("【案例素材】").contains("窗口内无已了结交易");
        assertThat(m.statsBlock()).contains("期间无已了结交易");
        assertThat(m.closedTrades()).isZero();
    }

    /**
     * stale 交易从案例与了结统计整体消失；权益/回撤线来自决策行序列，不动。
     * 过滤必须在配对之后：stale 计划提前拿掉的话，它的仓位会错配到同 symbol/side 的
     * 别的计划上——所以正常那笔配的必须还是自己的 PULLBACK（带 +30），不能被 -50 顶包。
     */
    @Test
    void staleTradeVanishesFromCasesAndStatsButNotEquity() {
        when(decisionMapper.selectOne(any())).thenReturn(equityRow(FROM - H, "10000"));
        when(decisionMapper.selectList(any())).thenReturn(List.of(equityRow(FROM + 3 * H, "10500")));
        FuturesPositionDTO stalePos = closedPos("LONG", "100000", "99000", "-50", FROM + H, FROM + 2 * H);
        FuturesPositionDTO keptPos = closedPos("LONG", "100000", "101000", "30", FROM + 3 * H, FROM + 4 * H);
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(stalePos, keptPos));
        when(planMapper.selectList(any())).thenReturn(List.of(
                planOf("BTCUSDT", "REVERSAL", FROM + H, true),
                planOf("BTCUSDT", "PULLBACK", FROM + 3 * H, false)));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        assertThat(m.casesBlock()).contains("★ PULLBACK（1 笔，1 胜，净 +30）")
                .doesNotContain("REVERSAL").doesNotContain("-50");
        assertThat(m.statsBlock()).contains("已了结 1 笔");
        // 权益线不许跟着变：钱账是决策行权益序列算的，stale 只动教材
        assertThat(m.statsBlock()).contains("+5.00%");
        assertThat(m.closedTrades()).isEqualTo(1);
    }

    // ==================== 配对与了结方式 ====================

    @Test
    void closeMannerInferredDirectionally() {
        // 强平状态直判
        FuturesPositionDTO liq = closedPos("LONG", "100000", "90000", "-500", FROM, FROM + 1);
        liq.setStatus("LIQUIDATED");
        assertThat(TradePairing.closeManner(prompts, liq, AgentLang.ZH)).isEqualTo("强平");

        // 触发价是探测时的markPrice会越过挂单价：方向性对照而非相等
        FuturesPositionDTO sl = closedPos("LONG", "100000", "95400", "-46", FROM, FROM + 1);
        sl.setStopLosses(List.of(new FuturesStopLoss("s1", new BigDecimal("95500"), new BigDecimal("0.01"))));
        assertThat(TradePairing.closeManner(prompts, sl, AgentLang.ZH)).isEqualTo("止损带走");

        FuturesPositionDTO tp = closedPos("SHORT", "100000", "94900", "51", FROM, FROM + 1);
        tp.setTakeProfits(List.of(new FuturesTakeProfit("t1", new BigDecimal("95000"), new BigDecimal("0.01"))));
        assertThat(TradePairing.closeManner(prompts, tp, AgentLang.ZH)).isEqualTo("止盈带走");

        // 保护单实时监控在先，带内成交只能是主动平仓（模型自己调 close_position）
        FuturesPositionDTO manual = closedPos("LONG", "100000", "101000", "10", FROM, FROM + 1);
        manual.setStopLosses(List.of(new FuturesStopLoss("s1", new BigDecimal("95500"), new BigDecimal("0.01"))));
        manual.setTakeProfits(List.of(new FuturesTakeProfit("t1", new BigDecimal("110000"), new BigDecimal("0.01"))));
        assertThat(TradePairing.closeManner(prompts, manual, AgentLang.ZH)).isEqualTo("主动平仓");
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
        e.setSupports(5);
        e.setOpposes(1);
        e.setUntested(0);
        e.setFlagged(false);
        return e;
    }

    /** 现役条目带编号与计数；发现、退役名单分段列出；现役计数不含发现 */
    @Test
    void ledgerBlockListsActiveFindingsAndRetired() {
        AiTraderExperience rule = exp(3L, AiTraderExperience.KIND_RULE, AiTraderExperience.SLOT_PROFIT_HANDLE,
                AiTraderExperience.DIR_EXECUTION, "PULLBACK", "出场只认目标位");
        rule.setFlagged(true);
        AiTraderExperience gap = exp(4L, AiTraderExperience.KIND_GAP, AiTraderExperience.SLOT_EXEC,
                AiTraderExperience.DIR_EXECUTION, null, "止损不小于缓冲");
        AiTraderExperience finding = exp(5L, AiTraderExperience.KIND_FINDING, AiTraderExperience.SLOT_RISK,
                AiTraderExperience.DIR_EXECUTION, null, "指令第六条少赚 4800");
        AiTraderExperience retired = exp(2L, AiTraderExperience.KIND_HYPOTHESIS, AiTraderExperience.SLOT_ENTRY,
                AiTraderExperience.DIR_OPPORTUNITY, "BREAKOUT", "量比大于 2 才追");
        retired.setRetireReason("近 20 次反对 14");
        when(ledger.active(7L, 1)).thenReturn(List.of(rule, gap, finding));
        when(ledger.retired(7L, 1)).thenReturn(List.of(retired));

        String block = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).ledgerBlock();

        assertThat(block).startsWith("【经验账本】（现役 2 条");
        assertThat(block).contains("#3 规律 盈利处理 执行型 PULLBACK ｜ 出场只认目标位 ｜ 支持 5 反对 1 未测试 0 ｜ 证据 证据-3 ｜ 待复审");
        assertThat(block).contains("#4 执行缺口 执行缺口 执行型 通用 ｜ 止损不小于缓冲 ｜ 支持 5 反对 1 未测试 0 ｜ 证据 证据-4\n");
        assertThat(block).contains("【已记录的发现】").contains("#5 指令第六条少赚 4800 ｜ 证据-5");
        assertThat(block).contains("【退役名单】").contains("#2 量比大于 2 才追 ｜ 近 20 次反对 14");
    }

    @Test
    void emptyLedgerSaysOnlyAddsPossible() {
        String block = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).ledgerBlock();

        assertThat(block).startsWith("【经验账本】（现役 0 条").contains("账本为空：本期只能新增");
        assertThat(block).doesNotContain("【已记录的发现】").doesNotContain("【退役名单】");
    }

    /**
     * 复审素材：条目全文 + 支持率 + 历史最长连续反对 + 逐条结论流水 + 支持 / 反对两组单子。
     * 仓位按流水里的 position_ids 从 sim 最近记录取，取不到的只列编号；计划按 position_id 配，有入场上下文就多一行
     */
    @Test
    void auditBlockCarriesVerdictLogStreakAndTradeGroups() {
        AiTraderExperience e = exp(5L, AiTraderExperience.KIND_RULE, AiTraderExperience.SLOT_PROFIT_HANDLE,
                AiTraderExperience.DIR_EXECUTION, null, "动能变小即落袋");
        e.setSupports(1);
        e.setOpposes(2);
        when(ledger.verdicts(5L)).thenReturn(List.of(
                verdict(AiTraderExperienceVerdict.SUPPORT, "09-21 BTC 平在目标位", "42"),
                verdict(AiTraderExperienceVerdict.OPPOSE, "出场后 4h 再走 1.1%", "43,44"),
                verdict(AiTraderExperienceVerdict.OPPOSE, "出场后 4h 再走 0.8%", null)));
        FuturesPositionDTO p42 = closedPos("LONG", "100000", "101400", "280", FROM + H, FROM + 3 * H);
        p42.setId(42L);
        FuturesPositionDTO p43 = closedPos("SHORT", "100000", "100900", "-90", FROM + 5 * H, FROM + 6 * H);
        p43.setId(43L);
        AiTraderPlan plan42 = planOf("BTCUSDT", "PULLBACK", FROM + H, false);
        plan42.setPositionId(42L);
        plan42.setStopLossPrice(new BigDecimal("99000"));
        plan42.setTakeProfitPrice(new BigDecimal("102000"));
        plan42.setEntryCtxJson("{\"regime\":\"TREND_UP\",\"atr1h\":420,\"stopAtr\":2.4,\"stopPct\":1.0,\"hourBj\":9,\"orderType\":\"MARKET\",\"leverage\":10}");
        when(planMapper.selectList(any())).thenReturn(List.of(plan42));

        String block = assembler.auditBlock(trader(), e, AgentLang.ZH, Map.of(42L, p42, 43L, p43));

        assertThat(block).startsWith("#5 通用 盈利处理 执行型 ｜ 动能变小即落袋 ｜ 支持 1 反对 2 未测试 0 ｜ 支持率 33% ｜ 历史最长连续反对 2 次\n");
        assertThat(block).contains("- 支持 ｜ 09-21 BTC 平在目标位\n- 反对 ｜ 出场后 4h 再走 1.1%\n- 反对 ｜ 出场后 4h 再走 0.8%\n");
        assertThat(block).contains("【支持的单子】（1 笔）\n- BTCUSDT LONG ")
                .contains("入场 100000 止损 99000 目标 102000 出场 101400，主动平仓 ｜ 净 +280")
                .contains("  入场上下文：regime TREND_UP ｜ 1h ATR 420 ｜ 止损 2.4 个 ATR（1%）");
        assertThat(block).contains("【反对的单子】（2 笔）\n- BTCUSDT SHORT ")
                .contains("入场 100000 止损 — 目标 — 出场 100900")
                .contains("- 仓位 #44（已不在最近记录里，只剩编号）");
        assertThat(block.indexOf("【支持的单子】")).isLessThan(block.indexOf("【反对的单子】"));
    }

    /** 没有结论流水：只有条目头一行加说明 */
    @Test
    void auditBlockWithoutVerdictsSaysSo() {
        AiTraderExperience e = exp(6L, AiTraderExperience.KIND_HYPOTHESIS, AiTraderExperience.SLOT_ENTRY,
                AiTraderExperience.DIR_OPPORTUNITY, "BREAKOUT", "量比大于 2 才追");
        e.setSupports(0);
        e.setOpposes(0);

        assertThat(assembler.auditBlock(trader(), e, AgentLang.ZH, Map.of()))
                .contains("#6 BREAKOUT 入场条件 机会型 ｜ 量比大于 2 才追 ｜ 支持 0 反对 0 未测试 0 ｜ 支持率 0% ｜ 历史最长连续反对 0 次")
                .contains("（尚无结论流水）")
                .doesNotContain("【支持的单子】");
    }

    private static AiTraderExperienceVerdict verdict(String verdict, String note, String positionIds) {
        AiTraderExperienceVerdict v = new AiTraderExperienceVerdict();
        v.setVerdict(verdict);
        v.setNote(note);
        v.setPositionIds(positionIds);
        return v;
    }

    // ==================== 素材有无与上次复盘定位 ====================

    /** 窗口内有开仓动作（含被拒的尝试——粗筛 LIKE，宁可多复盘）就有素材，不打 sim */
    @Test
    void openAttemptsAreMaterialWithoutTouchingSim() {
        when(decisionMapper.selectCount(any())).thenReturn(1L);

        assertThat(assembler.hasNewMaterial(trader(), FROM, TO)).isTrue();
        verify(simTradeClient, never()).getClosedPositions(anyLong(), anyInt());
    }

    /** 没有开仓动作才查 sim：窗口内有了结仓位算素材，了结在窗口外不算 */
    @Test
    void closedTradesCheckedOnlyWhenNoOpens() {
        when(decisionMapper.selectCount(any())).thenReturn(0L);
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(
                closedPos("LONG", "100000", "101000", "10", FROM + H, FROM + 2 * H)));
        assertThat(assembler.hasNewMaterial(trader(), FROM, TO)).isTrue();

        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(
                closedPos("LONG", "100000", "101000", "10", FROM - 2 * H, FROM - H)));
        assertThat(assembler.hasNewMaterial(trader(), FROM, TO)).isFalse();
    }

    /** 开仓动作只数交易行白名单（TRADE/ALERT/MANUAL）且 actions 里带 open_position；LEARN/REVIEW 行天天有，不许充当素材 */
    @Test
    void openAttemptQueryFiltersByKindWhitelistAndAction() {
        when(decisionMapper.selectCount(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<AiTraderDecision> w = inv.getArgument(0);
            // MP 条件值懒求值：先拼一次 SQL，参数才落进 paramNameValuePairs
            w.getTargetSql();
            var values = w.getParamNameValuePairs().values();
            assertThat(values).contains(AiTraderDecision.KIND_TRADE, AiTraderDecision.KIND_ALERT, AiTraderDecision.KIND_MANUAL);
            assertThat(values).doesNotContain(AiTraderDecision.KIND_REVIEW, AiTraderDecision.KIND_LEARN);
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
}
