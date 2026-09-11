package com.mawai.wiibagent.learning;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibcommon.enums.AgentLang;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TradePairing;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 复盘素材组装单测：四块硬事实全部代码算，数字必须对——模型只许复述，代码错一位就是复盘造假。
 * mock 说明：decisionMapper.selectList 按调用顺序先权益序列后时间线（assemble 内查询顺序固定）。
 */
class ReviewMaterialAssemblerTest {

    /** 窗口起点：某个整日边界；窗口 = (FROM, TO]，一天 */
    private static final long FROM = 1785110400000L;
    private static final long TO = FROM + 86_400_000L;

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderPlan.class);
    }

    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);
    private final AiTraderPlanMapper planMapper = mock(AiTraderPlanMapper.class);
    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final KlineHistoryStore historyStore = mock(KlineHistoryStore.class);

    private final ReviewMaterialAssembler assembler = new ReviewMaterialAssembler(
            decisionMapper, planMapper, simTradeClient, historyStore, new PromptCatalog(),
            new DecisionText(new PromptCatalog()));

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

    private static AiTraderDecision okRow(long wakeTime, String kind, String reasoning, String actionsJson) {
        AiTraderDecision d = new AiTraderDecision();
        d.setWakeTime(wakeTime);
        d.setKind(kind);
        d.setStatus(AiTraderDecision.STATUS_OK);
        d.setReasoning(reasoning);
        d.setActionsJson(actionsJson);
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

    // ==================== 战绩表 ====================

    @Test
    void statsComputedFromEquitySeriesAndClosedTrades() {
        // 窗口起点前最后一条权益 10000；窗口内 10200 → 9800 → 10500
        when(decisionMapper.selectOne(any())).thenReturn(equityRow(FROM - 3600_000, "10000"));
        when(decisionMapper.selectList(any())).thenReturn(
                List.of(equityRow(FROM + 3600_000, "10200"),
                        equityRow(FROM + 7200_000, "9800"),
                        equityRow(FROM + 10800_000, "10500")),
                List.of());
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(
                closedPos("LONG", "100000", "103000", "300", FROM + 3600_000, FROM + 7200_000),
                closedPos("LONG", "100000", "99000", "-100", FROM + 7200_000, FROM + 10800_000)));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        // 收益率 (10500-10000)/10000=+5%；回撤峰10200谷9800=3.92%；2笔1胜1负
        assertThat(m.statsBlock()).contains("10000").contains("10500").contains("+5.00%");
        assertThat(m.statsBlock()).contains("3.92%");
        assertThat(m.statsBlock()).contains("2 笔").contains("50%");
        assertThat(m.closedTrades()).isEqualTo(2);
    }

    @Test
    void startEquityFallsBackToInitialBalanceWhenNoPriorRow() {
        when(decisionMapper.selectOne(any())).thenReturn(null);
        when(decisionMapper.selectList(any())).thenReturn(
                List.of(equityRow(FROM + 3600_000, "10100")), List.of());

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        // 无前值 → 起始按初始资金 10000：(10100-10000)/10000=+1%
        assertThat(m.statsBlock()).contains("+1.00%");
    }

    /**
     * 战绩表块头写着"代码统计，只许原样复述，禁止自行计算"，但已平仓位是先取最近 200 条再按窗口过滤，
     * 无分页无溢出检测——5m 档一天成交超 200 笔时这份"硬事实"本身就是错的，而模型被明令不许核算，
     * 错数字会原样进 memory 长期传播。取回条数顶到上限时必须在块头说清楚这是不完全统计。
     */
    @Test
    void statsWarnsWhenClosedFetchHitsLimit() {
        List<FuturesPositionDTO> full = new ArrayList<>();
        for (int i = 0; i < ReviewMaterialAssembler.CLOSED_FETCH_LIMIT; i++) {
            full.add(closedPos("LONG", "100000", "100100", "10", FROM + 60_000, FROM + 120_000));
        }
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(full);
        when(decisionMapper.selectOne(any())).thenReturn(null);

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        assertThat(m.statsBlock()).contains("不完全统计").contains("200");
    }

    /** 没顶到上限就别乱贴警示——常规复盘的战绩表得是干净的硬事实 */
    @Test
    void statsHasNoTruncationWarningBelowLimit() {
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(
                closedPos("LONG", "100000", "103000", "300", FROM + 3600_000, FROM + 7200_000)));
        when(decisionMapper.selectOne(any())).thenReturn(null);

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        assertThat(m.statsBlock()).doesNotContain("不完全统计");
    }

    // ==================== 配对表与了结方式 ====================

    @Test
    void closedTradePairedWithArchivedPlan() {
        FuturesPositionDTO pos = closedPos("LONG", "100000", "95000", "-50", FROM + 3600_000, FROM + 21600_000);
        pos.setStopLosses(List.of(new FuturesStopLoss("s1", new BigDecimal("95500"), new BigDecimal("0.01"))));
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(pos));

        AiTraderPlan plan = new AiTraderPlan();
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        plan.setPlayType("BREAKOUT");
        plan.setSignalsUsed("突破前高+量比1.8");
        plan.setInvalidationCondition("1h收盘跌回98000下方");
        plan.setStatus(AiTraderPlan.STATUS_CLOSED);
        plan.setOpenedWakeTime(FROM + 3600_000);
        plan.setClosedWakeTime(FROM + 25200_000);
        when(planMapper.selectList(any())).thenReturn(List.of(plan));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        // 论点→结局配对：playType/失效条件/入场/出场/盈亏/持有时长/了结方式一行齐
        assertThat(m.tradesBlock()).contains("BREAKOUT").contains("98000")
                .contains("100000").contains("95000").contains("-50")
                .contains("5小时").contains("止损带走");
    }

    @Test
    void closeMannerInferredDirectionally() {
        // 强平状态直判
        FuturesPositionDTO liq = closedPos("LONG", "100000", "90000", "-500", FROM, FROM + 1);
        liq.setStatus("LIQUIDATED");
        assertThat(TradePairing.closeManner(new PromptCatalog(), liq, AgentLang.ZH)).isEqualTo("强平");

        // 触发价是探测时的markPrice会越过挂单价：方向性对照而非相等
        FuturesPositionDTO sl = closedPos("LONG", "100000", "95400", "-46", FROM, FROM + 1);
        sl.setStopLosses(List.of(new FuturesStopLoss("s1", new BigDecimal("95500"), new BigDecimal("0.01"))));
        assertThat(TradePairing.closeManner(new PromptCatalog(), sl, AgentLang.ZH)).isEqualTo("止损带走");

        FuturesPositionDTO tp = closedPos("SHORT", "100000", "94900", "51", FROM, FROM + 1);
        tp.setTakeProfits(List.of(new FuturesTakeProfit("t1", new BigDecimal("95000"), new BigDecimal("0.01"))));
        assertThat(TradePairing.closeManner(new PromptCatalog(), tp, AgentLang.ZH)).isEqualTo("止盈带走");

        // 保护单实时监控在先，带内成交只能是主动平仓（模型自己调 close_position）
        FuturesPositionDTO manual = closedPos("LONG", "100000", "101000", "10", FROM, FROM + 1);
        manual.setStopLosses(List.of(new FuturesStopLoss("s1", new BigDecimal("95500"), new BigDecimal("0.01"))));
        manual.setTakeProfits(List.of(new FuturesTakeProfit("t1", new BigDecimal("110000"), new BigDecimal("0.01"))));
        assertThat(TradePairing.closeManner(new PromptCatalog(), manual, AgentLang.ZH)).isEqualTo("主动平仓");
    }

    @Test
    void unmatchedClosedPlanListedAsUnfilled() {
        // 挂单未成交撤销：计划归档了但没有对应已平仓位，论点没得到执行机会也要留痕
        AiTraderPlan plan = new AiTraderPlan();
        plan.setSymbol("ETHUSDT");
        plan.setSide("SHORT");
        plan.setPlayType("RANGE");
        plan.setInvalidationCondition("4h收盘站上3200");
        plan.setStatus(AiTraderPlan.STATUS_CLOSED);
        plan.setOpenedWakeTime(FROM + 3600_000);
        plan.setClosedWakeTime(FROM + 7200_000);
        when(planMapper.selectList(any())).thenReturn(List.of(plan));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        assertThat(m.tradesBlock()).contains("ETHUSDT").contains("RANGE").contains("未配对");
    }

    // ==================== pairAll 统一配对 ====================

    /** 精确趟：position_id 说了算，时间就近想反着配也翻不了案——配对漂移的根治 */
    @Test
    void pairAllPrefersPositionIdOverTimeProximity() {
        FuturesPositionDTO pos1 = closedPos("LONG", "100000", "101000", "10", FROM + 3600_000, FROM + 7200_000);
        pos1.setId(101L);
        FuturesPositionDTO pos2 = closedPos("LONG", "100000", "99000", "-10", FROM + 10800_000, FROM + 14400_000);
        pos2.setId(102L);
        // planA 绑 pos1，但开仓时刻造得离 pos2 更近（反之亦然）：贪心时间就近会交叉错配
        AiTraderPlan planA = planOf("BTCUSDT", "BREAKOUT", FROM + 10800_000, false);
        planA.setPositionId(101L);
        AiTraderPlan planB = planOf("BTCUSDT", "PULLBACK", FROM + 3600_000, false);
        planB.setPositionId(102L);

        var paired = TradePairing.pairAll(List.of(pos1, pos2), List.of(planA, planB));

        assertThat(paired.get(pos1)).isSameAs(planA);
        assertThat(paired.get(pos2)).isSameAs(planB);
    }

    /**
     * 兜底趟喂入顺序无关：三个消费端喂入顺序不同（复盘升序/竞技场倒序）曾让同一笔交易
     * 配到不同计划——统一按平仓时刻升序后，怎么喂结果都一样。
     */
    @Test
    void pairAllFallbackIsFeedOrderIndependent() {
        FuturesPositionDTO early = closedPos("LONG", "100000", "101000", "10", FROM + 3600_000, FROM + 10800_000);
        FuturesPositionDTO late = closedPos("LONG", "100000", "99000", "-10", FROM + 7200_000, FROM + 14400_000);
        // 单个无 id 计划：谁先配谁得手，喂入顺序就是结果——统一顺序后必须永远归 early
        AiTraderPlan plan = planOf("BTCUSDT", "BREAKOUT", FROM + 7200_000, false);

        var ascFeed = TradePairing.pairAll(List.of(early, late), List.of(plan));
        var descFeed = TradePairing.pairAll(List.of(late, early), List.of(plan));

        assertThat(ascFeed.get(early)).isSameAs(plan);
        assertThat(ascFeed.get(late)).isNull();
        assertThat(descFeed.get(early)).isSameAs(plan);
        assertThat(descFeed.get(late)).isNull();
    }

    /** 已绑别的仓位的计划不许被兜底趟借走：它的仓位只是不在本批，拿去配别人就是明知故犯 */
    @Test
    void pairAllNeverLendsBoundPlanToOtherPosition() {
        FuturesPositionDTO pos = closedPos("LONG", "100000", "101000", "10", FROM + 3600_000, FROM + 7200_000);
        pos.setId(101L);
        AiTraderPlan boundElsewhere = planOf("BTCUSDT", "BREAKOUT", FROM + 3600_000, false);
        boundElsewhere.setPositionId(999L);

        var paired = TradePairing.pairAll(List.of(pos), List.of(boundElsewhere));

        assertThat(paired.get(pos)).isNull();
    }

    // ==================== stale：主人标记忽略的交易从教材消失 ====================

    private static AiTraderPlan planOf(String symbol, String playType, long openedWakeTime, boolean stale) {
        AiTraderPlan plan = new AiTraderPlan();
        plan.setSymbol(symbol);
        plan.setSide("LONG");
        plan.setPlayType(playType);
        plan.setSignalsUsed("信号-" + playType);
        plan.setInvalidationCondition("失效-" + playType);
        plan.setStatus(AiTraderPlan.STATUS_CLOSED);
        plan.setOpenedWakeTime(openedWakeTime);
        plan.setClosedWakeTime(openedWakeTime + 3600_000L);
        plan.setStale(stale);
        return plan;
    }

    /**
     * stale 交易从配对表与了结统计行整体消失；权益/回撤线来自决策行序列，不动。
     * 过滤必须在配对之后：stale 计划提前拿掉的话，它的仓位会错配到同 symbol/side 的
     * 别的计划上——所以正常那笔配的必须还是自己的 PULLBACK（带 +30），不能被 -50 顶包。
     */
    @Test
    void staleTradeVanishesFromTradesAndStatsButNotEquity() {
        when(decisionMapper.selectOne(any())).thenReturn(equityRow(FROM - 3600_000, "10000"));
        when(decisionMapper.selectList(any())).thenReturn(
                List.of(equityRow(FROM + 10800_000, "10500")), List.of());
        FuturesPositionDTO stalePos = closedPos("LONG", "100000", "99000", "-50", FROM + 3600_000, FROM + 7200_000);
        FuturesPositionDTO keptPos = closedPos("LONG", "100000", "101000", "30", FROM + 10800_000, FROM + 14400_000);
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(stalePos, keptPos));
        when(planMapper.selectList(any())).thenReturn(List.of(
                planOf("BTCUSDT", "REVERSAL", FROM + 3600_000, true),
                planOf("BTCUSDT", "PULLBACK", FROM + 10800_000, false)));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        assertThat(m.tradesBlock()).contains("PULLBACK").contains("+30")
                .doesNotContain("REVERSAL").doesNotContain("-50");
        assertThat(m.statsBlock()).contains("1 笔");
        // 权益线不许跟着变：钱账是决策行权益序列算的，stale 只动教材
        assertThat(m.statsBlock()).contains("+5.00%");
        assertThat(m.closedTrades()).isEqualTo(1);
    }

    /** 新格式：stale 仓位的分段整段从时间线消失，其余币的段保留（手术刀，不误伤） */
    @Test
    void timelineDropsStaleSegmentsKeepsOthers() {
        when(decisionMapper.selectOne(any())).thenReturn(null);
        AiTraderPlan staleBtc = planOf("BTCUSDT", "BREAKOUT", FROM + 3600_000, true);
        staleBtc.setClosedWakeTime(FROM + 36000_000);
        when(planMapper.selectList(any())).thenReturn(List.of(staleBtc));
        // 两轮同条件相隔 7h：ETH 段升格对账块（条件全文可见），BTC 段两轮都被剔——连短观望汇总都不该有它
        when(decisionMapper.selectList(any())).thenReturn(List.of(), List.of(
                okRow(FROM + 7200_000, AiTraderDecision.KIND_TRADE, SEGMENTED_ZH, "[]"),
                okRow(FROM + 32400_000, AiTraderDecision.KIND_TRADE, SEGMENTED_ZH, "[]")));

        String timeline = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).timelineBlock();

        assertThat(timeline).doesNotContain("63370–63480").doesNotContain("[BTCUSDT]");
        assertThat(timeline).contains("[ETHUSDT] 观望对账段").contains("等待：15m 收盘跌破 1888 转空");
        assertThat(timeline).doesNotContain("另有");
    }

    /** 双开粒度=币：该币该轮任一覆盖计划未被忽略，段就得留 */
    @Test
    void timelineKeepsSegmentWhenAnyCoveringPlanNotStale() {
        when(decisionMapper.selectOne(any())).thenReturn(null);
        AiTraderPlan staleLong = planOf("BTCUSDT", "BREAKOUT", FROM + 3600_000, true);
        staleLong.setClosedWakeTime(FROM + 36000_000);
        AiTraderPlan liveShort = planOf("BTCUSDT", "REVERSAL", FROM + 3600_000, false);
        liveShort.setSide("SHORT");
        liveShort.setClosedWakeTime(FROM + 36000_000);
        when(planMapper.selectList(any())).thenReturn(List.of(staleLong, liveShort));
        when(decisionMapper.selectList(any())).thenReturn(List.of(), List.of(
                okRow(FROM + 7200_000, AiTraderDecision.KIND_TRADE, SEGMENTED_ZH, "[]"),
                okRow(FROM + 32400_000, AiTraderDecision.KIND_TRADE, SEGMENTED_ZH, "[]")));

        String timeline = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).timelineBlock();

        assertThat(timeline).contains("[BTCUSDT] 观望对账段").contains("等待：回踩 63370–63480 企稳再做多");
    }

    /**
     * 错误格式（没分段）退化为按轮剔：落在 stale 计划生命期内的轮整行消失——开仓轮、调仓轮、中间的持有轮都算；
     * 生命期之外的轮保留，唤醒轮数不缩水
     */
    @Test
    void timelineDropsUnsegmentedRoundsInsideStaleLifetime() {
        when(decisionMapper.selectOne(any())).thenReturn(null);
        // 生命期 [FROM+1h, FROM+2h]（planOf 归档时刻=开仓+1h）
        AiTraderPlan stale = planOf("BTCUSDT", "BREAKOUT", FROM + 3600_000, true);
        stale.setPositionId(42L);
        when(planMapper.selectList(any())).thenReturn(List.of(stale));
        String openRound = "[本轮结论]\n判断：突破\n动作：开多\n等待：无";
        String holdRound = "[本轮结论]\n判断：浮盈\n动作：HOLD\n等待：多单继续拿着";
        String slRound = "[本轮结论]\n判断：走高\n动作：上移止损\n等待：无";
        String openActs = "[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"},\"status\":\"ok\"}]";
        String slActs = "[{\"tool\":\"set_stop_loss\",\"args\":{\"positionId\":42,\"stopLossPrice\":99000},\"status\":\"ok\"}]";
        when(decisionMapper.selectList(any())).thenReturn(List.of(), List.of(
                okRow(FROM + 3600_000, AiTraderDecision.KIND_TRADE, openRound, openActs),
                okRow(FROM + 5400_000, AiTraderDecision.KIND_TRADE, holdRound, "[]"),
                okRow(FROM + 7200_000, AiTraderDecision.KIND_TRADE, slRound, slActs),
                okRow(FROM + 10800_000, AiTraderDecision.KIND_TRADE,
                        "[本轮结论]\n判断：观望\n动作：HOLD\n等待：站稳 99000", "[]")));

        String timeline = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).timelineBlock();

        assertThat(timeline).doesNotContain("open_position").doesNotContain("set_stop_loss");
        // 生命期内的持有轮没有动作也照剔，生命期外那轮观望留下
        assertThat(timeline).doesNotContain("多单继续拿着");
        assertThat(timeline).contains("另有 1 段短观望共 1 轮");
        assertThat(timeline).contains("本期活动：唤醒 4 轮，动作轮 0");
    }

    /**
     * 新格式的动作也在忽略范围内：结论段剔了、开仓摘要还挂在时间线上就是承诺漏水。
     * 同轮的非 stale 动作照常保留（手术刀）；开仓统计跟着摘要走，不数被忽略的开仓。
     */
    @Test
    void timelineFiltersStaleActionsFromSummary() {
        when(decisionMapper.selectOne(any())).thenReturn(null);
        AiTraderPlan stale = planOf("BTCUSDT", "BREAKOUT", FROM + 3600_000, true);
        stale.setPositionId(42L);
        when(planMapper.selectList(any())).thenReturn(List.of(stale));
        // 开仓轮：开 stale 的 BTC 多（symbol/side+开仓时刻命中）+ 给别的仓位调止损（不剔）
        String acts = "[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"},\"status\":\"ok\"},"
                + "{\"tool\":\"set_stop_loss\",\"args\":{\"positionId\":42,\"stopLossPrice\":99000},\"status\":\"ok\"},"
                + "{\"tool\":\"set_take_profit\",\"args\":{\"positionId\":7,\"takeProfitPrice\":2000},\"status\":\"ok\"}]";
        when(decisionMapper.selectList(any())).thenReturn(List.of(), List.of(
                okRow(FROM + 3600_000, AiTraderDecision.KIND_TRADE, SEGMENTED_ZH, acts)));

        String timeline = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).timelineBlock();

        assertThat(timeline).doesNotContain("open_position").doesNotContain("set_stop_loss");
        assertThat(timeline).contains("set_take_profit(7)");
        assertThat(timeline).contains("开仓动作 0 次");
        // 结论段同轮剔除：BTC 段没了，ETH 段还在动作行结论里
        assertThat(timeline).doesNotContain("63370–63480");
    }

    /** 动作只冲断涉及币的观望游标：ETH 连续等同一条件跨过一次 BTC 动作，仍凑得满 ≥6h 对账块 */
    @Test
    void actionOnOneSymbolDoesNotBreakOtherSymbolsHold() {
        when(decisionMapper.selectOne(any())).thenReturn(null);
        String ethWait = """
                [本轮结论]
                [ETHUSDT]
                动作：HOLD
                等待：站上 1925 做多""";
        String btcAct = "[{\"tool\":\"set_stop_loss\",\"args\":{\"symbol\":\"BTCUSDT\",\"positionId\":5,\"stopLossPrice\":99000},\"status\":\"ok\"}]";
        when(decisionMapper.selectList(any())).thenReturn(List.of(), List.of(
                okRow(FROM + 3600_000, AiTraderDecision.KIND_TRADE, ethWait, "[]"),
                okRow(FROM + 10800_000, AiTraderDecision.KIND_TRADE,
                        "[本轮结论]\n[BTCUSDT]\n动作：上移止损\n等待：无", btcAct),
                okRow(FROM + 27000_000, AiTraderDecision.KIND_TRADE, ethWait, "[]")));

        String timeline = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).timelineBlock();

        // ETH 段跨 6.5h 未被 BTC 动作冲断 → 对账块；被冲断的话只剩两段碎观望、条件失声
        assertThat(timeline).contains("（2轮）[ETHUSDT] 观望对账段").contains("等待：站上 1925 做多");
    }

    /** 窗口内归档、未配对、stale 的计划：孤儿行也不出 */
    @Test
    void staleOrphanPlanOmitted() {
        when(decisionMapper.selectOne(any())).thenReturn(null);
        AiTraderPlan orphan = planOf("ETHUSDT", "RANGE", FROM + 3600_000, true);
        when(planMapper.selectList(any())).thenReturn(List.of(orphan));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        assertThat(m.tradesBlock()).doesNotContain("RANGE").doesNotContain("ETHUSDT");
    }

    // ==================== 时间线摘编 ====================

    @Test
    void timelineKeepsConclusionBlocksAndAggregatesErrors() {
        String openActions = "[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\","
                + "\"quantity\":0.01},\"result\":\"ok\"}]";
        AiTraderDecision act = okRow(FROM + 3600_000, AiTraderDecision.KIND_TRADE,
                "行情分析……[本轮结论]\n判断：突破确认站上100500\n动作：开多BTCUSDT 0.01\n等待：无", openActions);
        AiTraderDecision hold = okRow(FROM + 7200_000, AiTraderDecision.KIND_TRADE,
                "检查了持仓……[本轮结论]\n判断：趋势未变101200上方震荡\n动作：HOLD\n等待：1h收盘跌破100500减仓", "[]");
        AiTraderDecision alert = okRow(FROM + 9000_000, AiTraderDecision.KIND_ALERT,
                "被警报唤醒……[本轮结论]\n判断：急跌未破位\n动作：HOLD\n等待：不变", "[]");
        AiTraderDecision err = new AiTraderDecision();
        err.setWakeTime(FROM + 10800_000);
        err.setKind(AiTraderDecision.KIND_TRADE);
        err.setStatus(AiTraderDecision.STATUS_ERROR);
        err.setError("唤醒超时(600s)");
        when(decisionMapper.selectOne(any())).thenReturn(null);
        when(decisionMapper.selectList(any())).thenReturn(
                List.of(), List.of(act, hold, alert, err));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        // 动作行带工具摘要+结论；短观望（HOLD/警报各一段，条件不同不并）收进一行汇总；ERROR聚合计数
        assertThat(m.timelineBlock()).contains("open_position").contains("突破确认");
        assertThat(m.timelineBlock()).contains("另有 2 段短观望共 2 轮");
        assertThat(m.timelineBlock()).contains("1 轮 ERROR");
        // 活动统计头：保守度自检的对照物（唤醒计全部轮含 ERROR，动作轮/开仓只数 OK 行）
        assertThat(m.timelineBlock()).contains("本期活动：唤醒 4 轮，动作轮 1，开仓动作 1 次");
    }

    /** 转成待确认请求的动作没有成交，摘编里必须标出来——否则复盘会把"提了个请求"当成"平了仓" */
    @Test
    void timelineMarksPendingActionsAsAwaitingApproval() {
        String pendingAction = "[{\"tool\":\"close_position\",\"args\":{\"positionId\":5,\"quantity\":0.01},"
                + "\"status\":\"pending\",\"result\":\"减仓请求已提交给主人确认\"}]";
        when(decisionMapper.selectOne(any())).thenReturn(null);
        when(decisionMapper.selectList(any())).thenReturn(List.of(), List.of(
                okRow(FROM + 3600_000, AiTraderDecision.KIND_TRADE,
                        "[本轮结论]\n判断：失效条件触发\n动作：申请减仓\n等待：主人确认", pendingAction)));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        assertThat(m.timelineBlock()).contains("close_position").contains("待确认");
    }

    @Test
    void timelineCompressesOldHoldsWhenOverCap() {
        List<AiTraderDecision> rows = new ArrayList<>();
        // 85 段 ≥6h 的真观望（每段两轮同条件相隔 6h）+ 最早的 1 条动作行：
        // 动作行必须保住，早段对账块被省略且有说明。条件逐段不同，否则会并成一段够不到上限
        rows.add(okRow(FROM + 60_000, AiTraderDecision.KIND_TRADE,
                "[本轮结论]\n判断：早段开仓\n动作：开多\n等待：无",
                "[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\"},\"result\":\"ok\"}]"));
        for (int i = 1; i <= 85; i++) {
            long segStart = FROM + 120_000L + i * 25_200_000L;
            String reasoning = "[本轮结论]\n判断：无事(" + i + ")\n动作：HOLD\n等待：回踩 " + (100000 + i) + " 再评估";
            rows.add(okRow(segStart, AiTraderDecision.KIND_TRADE, reasoning, "[]"));
            rows.add(okRow(segStart + ReviewMaterialAssembler.LONG_HOLD_MS,
                    AiTraderDecision.KIND_TRADE, reasoning, "[]"));
        }
        when(decisionMapper.selectOne(any())).thenReturn(null);
        when(decisionMapper.selectList(any())).thenReturn(List.of(), rows);

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        assertThat(m.timelineBlock()).contains("open_position");
        assertThat(m.timelineBlock()).contains("已省略");
        // 上限内：条目数 = 动作1 + 补齐的最新对账块
        long lines = m.timelineBlock().lines().filter(l -> l.startsWith("- ")).count();
        assertThat(lines).isLessThanOrEqualTo(ReviewMaterialAssembler.MAX_TIMELINE_ENTRIES);
    }

    /**
     * 等待条件分条换行写是模型的常态写法，整段都要抓全（观望对账的唯一原料）；
     * 而"判断"段是当时的指标读数、复盘没有对照物，不许混进来占额度。
     */
    @Test
    void waitSectionReadsMultiLineBullets() {
        String reasoning = """
                空仓，无旧计划可验。[本轮结论]
                判断：BTC 63636、ETH 1906，15m 均为 TREND_UP，但价格已贴上轨
                动作：HOLD，不开仓
                计划依据：账户空仓，无持仓计划需要维护
                等待：
                - BTC 多：15m 回踩 63370–63480 且收盘仍站上 63280，目标 64000–64450
                - ETH 多：15m 回踩 1896–1901 且收盘站上 1892，目标 1925/1937
                - 转空：BTC 15m 收盘跌破 63140；ETH 15m 收盘跌破 1888""";

        String wait = assembler.waitsBySymbol(reasoning, AgentLang.ZH).get(ReviewMaterialAssembler.WHOLE);

        assertThat(wait).contains("63370–63480").contains("1896–1901").contains("63140");
        // 判断段是当时的指标读数，复盘没有对账物，不该混进等待里占额度
        assertThat(wait).doesNotContain("TREND_UP");
    }

    /**
     * 连续同一等待条件压成一段：15m 档一天 96 轮，行情不动时几十轮等的是同一句话。
     * 纯文字注解括号每轮微动不算条件变化；条件真变了要断开；警报轮不并进例行观望。
     * 全部短于 6h → 段数/轮数进一行汇总，合并语义靠段数验证（并错了段数就不是 3）。
     */
    @Test
    void timelineMergesConsecutiveSameWaits() {
        List<AiTraderDecision> rows = List.of(
                okRow(FROM + 900_000, AiTraderDecision.KIND_TRADE,
                        "[本轮结论]\n判断：贴上轨\n动作：HOLD\n等待：回踩 63370–63480 后再评估（前高）", "[]"),
                okRow(FROM + 1800_000, AiTraderDecision.KIND_TRADE,
                        "[本轮结论]\n判断：浅回撤\n动作：HOLD\n等待：回踩 63370–63480 后再评估（观望）", "[]"),
                okRow(FROM + 2700_000, AiTraderDecision.KIND_TRADE,
                        "[本轮结论]\n判断：继续走弱\n动作：HOLD\n等待：跌破 63140 转空", "[]"),
                okRow(FROM + 3600_000, AiTraderDecision.KIND_ALERT,
                        "[本轮结论]\n判断：急跌\n动作：HOLD\n等待：跌破 63140 转空", "[]"));
        when(decisionMapper.selectOne(any())).thenReturn(null);
        when(decisionMapper.selectList(any())).thenReturn(List.of(), rows);

        String timeline = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).timelineBlock();

        // 前两轮注解不同并成一段 + 条件变了断开一段 + 警报不并进例行一段 = 3 段 4 轮
        assertThat(timeline).contains("另有 3 段短观望共 4 轮");
        // 合并只省字，轮数统计仍按原始行走，保守度自检的对照物不能缩水
        assertThat(timeline).contains("本期活动：唤醒 4 轮");
    }

    /** 括号里带价位的两轮绝不能并段（丢价位=对着不存在的条件判命中）：段数必须是 2 不是 1 */
    @Test
    void timelineKeepsDifferentPricesInParenthesesApart() {
        List<AiTraderDecision> rows = List.of(
                okRow(FROM + 900_000, AiTraderDecision.KIND_TRADE,
                        "[本轮结论]\n判断：走弱\n动作：HOLD\n等待：转空（跌破 63140）", "[]"),
                okRow(FROM + 1800_000, AiTraderDecision.KIND_TRADE,
                        "[本轮结论]\n判断：更弱\n动作：HOLD\n等待：转空（跌破 62800）", "[]"));
        when(decisionMapper.selectOne(any())).thenReturn(null);
        when(decisionMapper.selectList(any())).thenReturn(List.of(), rows);

        String timeline = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).timelineBlock();

        assertThat(timeline).contains("另有 2 段短观望共 2 轮");
    }

    /** 没有[本轮结论]块就是这轮没给条件，不能拿正文尾巴冒充——那段是行情叙述，对账对不了 */
    @Test
    void waitSectionReturnsEmptyWhenNoConclusionBlock() {
        assertThat(assembler.waitsBySymbol("BTC 走强，我先看着。ETH 也在震荡，暂时不动手。", AgentLang.ZH)
                .get(ReviewMaterialAssembler.WHOLE)).isEmpty();
    }

    // ==================== 结论总分结构：按币分段 ====================

    private static final String SEGMENTED_ZH = """
            行情铺垫……[本轮结论]
            总评：账户整体轻仓观望，等方向
            [BTCUSDT]
            判断：63500 上方震荡收敛
            动作：HOLD
            等待：回踩 63370–63480 企稳再做多
            [ETHUSDT]
            判断：1900 附近弱势
            动作：HOLD
            等待：15m 收盘跌破 1888 转空""";

    /** 新格式：每个 [SYMBOL] 段各抽各的等待；总评不绑定任何币，不进结果 */
    @Test
    void waitsBySymbolSplitsSegments() {
        var waits = assembler.waitsBySymbol(SEGMENTED_ZH, AgentLang.ZH);

        assertThat(waits).containsOnlyKeys("BTCUSDT", "ETHUSDT");
        assertThat(waits.get("BTCUSDT")).contains("63370–63480");
        assertThat(waits.get("ETHUSDT")).contains("1888");
    }

    /** 错误格式（无分段标记）整块归 WHOLE 伪键：没人标 stale 时这行照样进素材 */
    @Test
    void waitsBySymbolFallsBackToWholeBlockForUnsegmentedRows() {
        var waits = assembler.waitsBySymbol(
                "[本轮结论]\n判断：观望\n动作：HOLD\n等待：站稳 99000", AgentLang.ZH);

        assertThat(waits).containsOnlyKeys(ReviewMaterialAssembler.WHOLE);
        assertThat(waits.get(ReviewMaterialAssembler.WHOLE)).isEqualTo("站稳 99000");
    }

    /** 英文新格式 + 切语言后旧语言行照样解析（结论块两套标记都认，段标记本身语言无关） */
    @Test
    void waitsBySymbolReadsEnglishSegmentsUnderZhLang() {
        String en = """
                context...[ROUND CONCLUSION]
                Overall: light exposure, waiting
                [BTCUSDT]
                Judgement: consolidating above 63500
                Action: HOLD
                Waiting: retest 63370-63480 then long""";

        var waits = assembler.waitsBySymbol(en, AgentLang.ZH);

        assertThat(waits).containsOnlyKeys("BTCUSDT");
        assertThat(waits.get("BTCUSDT")).contains("63370-63480");
    }

    /** [ROUND CONCLUSION] 自己带空格，不会被误认成分段标记 */
    @Test
    void segmentTagDoesNotMatchConclusionMark() {
        assertThat(DecisionText.splitSegments(
                "Overall: fine\n[BTCUSDT]\nWaiting: none")).hasSize(1);
        assertThat(DecisionText.splitSegments("Judgement: no tags here")).isEmpty();
    }

    /**
     * 观望按币段合并 + 6h 分层：BTC 三轮同一条件跨 6.5h → 升格对账块（段头/条件全文/起点结构快照）；
     * ETH 条件中途变了断成两短段，与 BTC 的连续段互不冲断，短段收进一行汇总。
     */
    @Test
    void timelineMergesHoldsPerSymbolAndUpgradesLongOnes() {
        String r1 = """
                [本轮结论]
                [BTCUSDT]
                动作：HOLD
                等待：回踩 63400 做多
                [ETHUSDT]
                动作：HOLD
                等待：跌破 1888 转空""";
        String r2 = """
                [本轮结论]
                [BTCUSDT]
                动作：HOLD
                等待：回踩 63400 做多
                [ETHUSDT]
                动作：HOLD
                等待：站上 1925 做多""";
        when(decisionMapper.selectOne(any())).thenReturn(null);
        when(decisionMapper.selectList(any())).thenReturn(List.of(), List.of(
                okRow(FROM + 3600_000, AiTraderDecision.KIND_TRADE, r1, "[]"),
                okRow(FROM + 14400_000, AiTraderDecision.KIND_TRADE, r2, "[]"),
                okRow(FROM + 27000_000, AiTraderDecision.KIND_TRADE, r2, "[]")));
        // 段起点结构快照的对照物：与价格路径同源的本地 5m。两根都在段起点（FROM+1h）之前的
        // 整点内——真实 load 是 [from,to)，openTime==段起点的 bar 生产上取不到，夹具不许造出前视
        when(historyStore.load(eq("BTCUSDT"), eq("5m"), anyLong(), anyLong())).thenReturn(List.of(
                bar5m(FROM, "61000", "61200", "60800", "61100"),
                bar5m(FROM + 300_000L, "61100", "61500", "61000", "61200")));

        String timeline = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).timelineBlock();

        // BTC 真观望：段头（3轮）+ 条件全文 + 起点结构快照一行（现价=段起点前最后一根1h收盘）
        assertThat(timeline).contains("（3轮）[BTCUSDT] 观望对账段");
        assertThat(timeline).contains("等待：回踩 63400 做多");
        assertThat(timeline).contains("起点结构 BTCUSDT：现价 61200");
        // ETH 两个短段收进汇总，条件从略
        assertThat(timeline).contains("另有 2 段短观望共 3 轮");
        assertThat(timeline).doesNotContain("1888").doesNotContain("1925");
        assertThat(timeline).contains("本期活动：唤醒 3 轮");
    }

    /** 错误格式与新格式混排：没分段的行走整块段、分段的行走币段，互不冲断——段数=WHOLE+BTC+ETH 三段 */
    @Test
    void timelineHandlesMixedUnsegmentedAndSegmentedRows() {
        when(decisionMapper.selectOne(any())).thenReturn(null);
        when(decisionMapper.selectList(any())).thenReturn(List.of(), List.of(
                okRow(FROM + 900_000, AiTraderDecision.KIND_TRADE,
                        "[本轮结论]\n判断：观望\n动作：HOLD\n等待：站稳 99000", "[]"),
                okRow(FROM + 1800_000, AiTraderDecision.KIND_TRADE, SEGMENTED_ZH, "[]")));

        String timeline = assembler.assemble(trader(), FROM, TO, AgentLang.ZH).timelineBlock();

        assertThat(timeline).contains("另有 3 段短观望共 3 轮");
    }

    // ==================== 价格路径 ====================

    /** 5m bar 造数：一根 5m 的 OHLC */
    private static KlineBar bar5m(long openTime, String o, String h, String l, String c) {
        return new KlineBar(openTime, openTime + 300_000L - 1,
                new BigDecimal(o), new BigDecimal(h), new BigDecimal(l), new BigDecimal(c), BigDecimal.ZERO);
    }

    /**
     * 本地 5m 现聚合成 1h：开=组内首根开、高/低=组内极值、收=组内末根收。
     * 这块是观望对账的对照物，聚合错一位复盘的价格证据就是假的。
     */
    @Test
    void pricePathAggregates5mIntoHourly() {
        when(historyStore.load(eq("BTCUSDT"), eq("5m"), anyLong(), anyLong())).thenReturn(List.of(
                // 第一个整点：开61000 高61500 低60800 收61200
                bar5m(FROM, "61000", "61200", "60800", "61100"),
                bar5m(FROM + 300_000L, "61100", "61500", "61000", "61200"),
                // 第二个整点：开61200 高64000 低61100 收63500
                bar5m(FROM + 3600_000L, "61200", "62000", "61100", "61900"),
                bar5m(FROM + 3900_000L, "61900", "64000", "61800", "63500")));
        when(decisionMapper.selectOne(any())).thenReturn(null);

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        // 开=61000 收=63500 高=64000 低=60800，涨跌幅 (63500-61000)/61000=+4.10%
        assertThat(m.pricePathBlock()).contains("61000").contains("63500")
                .contains("64000").contains("60800").contains("+4.10%");
        // 逐小时收盘只该有两根（每组末根收），不是四根 5m
        assertThat(m.pricePathBlock()).contains("1h收盘: 61200→63500");
        // 逐小时高低是组内极值：等待条件多是"回踩到某区间"，只给收盘判不出这一小时探到过没有
        assertThat(m.pricePathBlock()).contains("1h高/低: 61500/60800→64000/61100");
    }

    /**
     * 本局首篇复盘 fromMs=0，价格路径回看上限 48h，很可能早于本局开局——
     * 块头必须把真实覆盖范围写出来并点明可能含开局前行情，否则模型会拿开局前的价格
     * 给"等待条件"做观望对账（对账的对照物错了，结论就是假的）。
     */
    @Test
    void firstReviewPricePathDeclaresRangeAndPreRoundRisk() {
        when(historyStore.load(eq("BTCUSDT"), eq("5m"), anyLong(), anyLong()))
                .thenReturn(List.of(bar5m(TO - 300_000L, "61000", "61500", "60800", "61200")));
        when(decisionMapper.selectOne(any())).thenReturn(null);

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), 0L, TO, AgentLang.ZH);

        assertThat(m.pricePathBlock()).contains("覆盖").contains("开局前");
    }

    /** 常规窗口的复盘：块头照样标覆盖范围，但不该有开局前警示 */
    @Test
    void regularReviewPricePathDeclaresRangeOnly() {
        when(historyStore.load(eq("BTCUSDT"), eq("5m"), anyLong(), anyLong()))
                .thenReturn(List.of(bar5m(FROM, "61000", "61500", "60800", "61200")));
        when(decisionMapper.selectOne(any())).thenReturn(null);

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        assertThat(m.pricePathBlock()).contains("覆盖").doesNotContain("开局前");
    }

    /** 库里这段没数据（缺口/新币）：写明无数据，不挡其余三块素材 */
    @Test
    void pricePathMissingDataDoesNotBlockAssembly() {
        when(historyStore.load(any(), any(), anyLong(), anyLong())).thenReturn(List.of());
        when(decisionMapper.selectOne(any())).thenReturn(null);

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO, AgentLang.ZH);

        assertThat(m.pricePathBlock()).contains("无K线数据");
        assertThat(m.statsBlock()).contains("【战绩表】");
    }

    // ==================== 观望门控（口径8） ====================

    /** 纯观望 + 各币振幅低于阈值 → 平静，可跳过复盘；振幅一超线立刻不算平静（错失素材要照常复盘） */
    @Test
    void quietHoldWindowJudgesByAmplitude() {
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of());
        when(decisionMapper.selectCount(any())).thenReturn(0L);
        // 振幅 (61500-60900)/61000 ≈ 0.98% < 2% → 平静
        when(historyStore.load(eq("BTCUSDT"), eq("5m"), anyLong(), anyLong())).thenReturn(List.of(
                bar5m(FROM, "61000", "61500", "60900", "61200")));
        assertThat(assembler.quietHoldWindow(trader(), FROM, TO)).isTrue();

        // 振幅 (64000-60900)/61000 ≈ 5.08% ≥ 2% → 大动，不许跳
        when(historyStore.load(eq("BTCUSDT"), eq("5m"), anyLong(), anyLong())).thenReturn(List.of(
                bar5m(FROM, "61000", "64000", "60900", "63500")));
        assertThat(assembler.quietHoldWindow(trader(), FROM, TO)).isFalse();
    }

    /** 有已了结交易 / 有开仓动作 / K线缺数据，任一条都不算纯观望平静窗口 */
    @Test
    void quietHoldWindowBlockedByTradesOpensOrMissingBars() {
        // 有已了结交易
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(
                closedPos("LONG", "100000", "101000", "10", FROM + 3600_000, FROM + 7200_000)));
        assertThat(assembler.quietHoldWindow(trader(), FROM, TO)).isFalse();

        // 无了结但有开仓动作（含被拒的尝试——粗筛 LIKE，宁可多复盘）
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of());
        when(decisionMapper.selectCount(any())).thenReturn(1L);
        assertThat(assembler.quietHoldWindow(trader(), FROM, TO)).isFalse();

        // 纯观望但K线缺数据：判不了平静，照常复盘
        when(decisionMapper.selectCount(any())).thenReturn(0L);
        when(historyStore.load(eq("BTCUSDT"), eq("5m"), anyLong(), anyLong())).thenReturn(List.of());
        assertThat(assembler.quietHoldWindow(trader(), FROM, TO)).isFalse();
    }

    // ==================== 素材有无与上次复盘定位 ====================

    @Test
    void hasNewMaterialCountsTradeAndAlertOkRows() {
        when(decisionMapper.selectCount(any())).thenReturn(3L, 0L);
        assertThat(assembler.hasNewMaterial(7L, 1, FROM, TO)).isTrue();
        assertThat(assembler.hasNewMaterial(7L, 1, FROM, TO)).isFalse();
    }

    /**
     * 素材判定必须是交易行白名单（TRADE/ALERT/MANUAL），不是 ne(REVIEW) 黑名单：
     * LEARN 行每天必有一条，黑名单会让它天天充当"新素材"，无交易的日子复盘再也跳不过去。
     */
    @Test
    void hasNewMaterial按交易行白名单过滤() {
        when(decisionMapper.selectCount(any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiTraderDecision> w =
                    inv.getArgument(0);
            // MP 条件值懒求值：先拼一次 SQL，参数才落进 paramNameValuePairs
            w.getTargetSql();
            var values = w.getParamNameValuePairs().values();
            assertThat(values).contains(AiTraderDecision.KIND_TRADE,
                    AiTraderDecision.KIND_ALERT, AiTraderDecision.KIND_MANUAL);
            assertThat(values).doesNotContain(AiTraderDecision.KIND_REVIEW, AiTraderDecision.KIND_LEARN);
            return 1L;
        });

        assertThat(assembler.hasNewMaterial(7L, 1, FROM, TO)).isTrue();
    }

    @Test
    void lastReviewReturnsNullWhenNone() {
        when(decisionMapper.selectOne(any())).thenReturn(null);
        assertThat(assembler.lastReview(7L, 1)).isNull();

        AiTraderDecision review = new AiTraderDecision();
        review.setWakeTime(FROM);
        review.setReasoning("【本期复盘】上期纪律：无上期纪律");
        when(decisionMapper.selectOne(any())).thenReturn(review);
        // 一次查询两用：窗口起点 + 回注本期承接检验的全文
        assertThat(assembler.lastReview(7L, 1).getWakeTime()).isEqualTo(FROM);
        assertThat(assembler.lastReview(7L, 1).getReasoning()).contains("上期纪律");
    }
}
