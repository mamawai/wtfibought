package com.mawai.wiibagent.trader.prompt;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibagent.trader.trade.PlanEntry;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.learning.CaseFileBuilder;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
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
 * 论点战绩统计单测：数字必须对——模型只许复述，代码错一位就是给决策喂假账。
 * 配对走 ReviewMaterialAssembler.bestMatch 同一套算法，这里重点验 stale 的"配对后过滤"语义。
 */
class PlayStatsAssemblerTest {

    private static final long T0 = 1785110400000L;

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderPlan.class);
    }

    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final AiTraderPlanMapper planMapper = mock(AiTraderPlanMapper.class);

    private final KlineHistoryStore historyStore = mock(KlineHistoryStore.class);

    private final PlayStatsAssembler assembler =
            new PlayStatsAssembler(simTradeClient, planMapper, new PromptCatalog(), new CaseFileBuilder(historyStore));

    private static AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setRoundNo(1);
        t.setSimUserId(99L);
        return t;
    }

    private static LocalDateTime at(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private static FuturesPositionDTO pos(String pnl, long openMs) {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setStatus("CLOSED");
        p.setClosedPnl(new BigDecimal(pnl));
        p.setCreatedAt(at(openMs));
        p.setUpdatedAt(at(openMs + 3600_000L));
        return p;
    }

    private static AiTraderPlan plan(String playType, long openedWakeTime, boolean stale) {
        AiTraderPlan pl = new AiTraderPlan();
        pl.setSymbol("BTCUSDT");
        pl.setSide("LONG");
        pl.setPlayType(playType);
        pl.setStatus(AiTraderPlan.STATUS_CLOSED);
        pl.setOpenedWakeTime(openedWakeTime);
        pl.setStale(stale);
        return pl;
    }

    @Test
    void ladderBucketAndManagementSummaryUseInitialEntriesAndCountAdds() {
        AiTraderPlan plan = plan("PULLBACK", T0, false); plan.setPositionId(1L);
        plan.setEntryCtxJson("{\"orderType\":\"MARKET\"}"); plan.setStopLossPrice(new BigDecimal("99"));
        List<PlanEntry> entries = new ArrayList<>();
        String[] prices = {"100", "98", "96", "101"}, quantities = {"3", "4", "3", "1"};
        for (int i = 0; i < 4; i++) {
            var entry = new PlanEntry();
            entry.setLimitPrice(new BigDecimal(prices[i])); entry.setQuantity(new BigDecimal(quantities[i]));
            entry.setStop(new BigDecimal(i == 3 ? "99" : "94")); entry.setPlacedAt(T0); entry.setClosedAt(T0);
            entry.setStatus(i == 0 || i == 3 ? "FILLED" : "CANCELLED"); entry.setAdd(i == 3);
            if ("FILLED".equals(entry.getStatus())) { entry.setFilledPrice(entry.getLimitPrice()); entry.setFee(new BigDecimal("0.06")); }
            entries.add(entry);
        }
        PlanEntry.save(plan, entries);
        FuturesPositionDTO position = pos("-10", T0); position.setId(1L); position.setEntryPrice(new BigDecimal("100.25"));
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(position));
        when(historyStore.load(eq("BTCUSDT"), eq("5m"), eq(T0), anyLong())).thenReturn(List.of(
                new KlineBar(T0, T0 + 299999, new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("99"), new BigDecimal("100"), BigDecimal.ONE),
                new KlineBar(T0 + 300000, T0 + 599999, new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("93"), new BigDecimal("94"), BigDecimal.ONE)));
        assertThat(assembler.assemble(trader(), AgentLang.ZH)).contains("分档 1笔");
        assertThat(assembler.assemble(trader(), AgentLang.EN)).contains("ladder 1 trades");
        var replays = List.of(new CaseFileBuilder(historyStore).replay(position, plan));
        assertThat(assembler.replayLines(replays, AgentLang.ZH)).contains("实际合计-0.25R", "其中 1 笔中途加过仓");
        assertThat(assembler.replayLines(replays, AgentLang.EN)).contains("actual total -0.25R", "1 of them were added to along the way");
    }

    /** 按标签聚合：达样本线的给胜负与盈亏合计，不足的只列笔数 */
    @Test
    void groupsByPlayTypeWithWinsAndPnl() {
        List<FuturesPositionDTO> positions = new ArrayList<>();
        List<AiTraderPlan> plans = new ArrayList<>();
        // 6 笔 PULLBACK：4 胜 2 负，合计 +120.50
        String[] pnls = {"50", "40", "30", "10.50", "-5", "-5"};
        for (int i = 0; i < pnls.length; i++) {
            long open = T0 + i * 7200_000L;
            positions.add(pos(pnls[i], open));
            plans.add(plan("PULLBACK", open, false));
        }
        // 2 笔 NEWS：样本不足
        for (int i = 0; i < 2; i++) {
            long open = T0 + (10 + i) * 7200_000L;
            positions.add(pos("20", open));
            plans.add(plan("NEWS", open, false));
        }
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(positions);
        when(planMapper.selectList(any())).thenReturn(plans);

        String out = assembler.assemble(trader(), AgentLang.ZH);

        assertThat(out).contains("最近8笔");
        assertThat(out).contains("PULLBACK：6笔 4胜2负 盈亏合计+120.50 USDT");
        assertThat(out).contains("NEWS：2笔（样本不足，不作参考）");
        // 排序按笔数降序：PULLBACK 行在 NEWS 行之前
        assertThat(out.indexOf("PULLBACK")).isLessThan(out.indexOf("NEWS"));
        // 没有一笔记了入场方式：不出那一行
        assertThat(out).doesNotContain("按入场方式");
    }

    /** 按入场方式分桶：限价 5 笔达样本线给胜负与合计，市价 2 笔只给笔数，没记方式的另计 */
    @Test
    void bucketsByEntryTypeFromEntryContext() {
        List<FuturesPositionDTO> positions = new ArrayList<>();
        List<AiTraderPlan> plans = new ArrayList<>();
        String[] limitPnls = {"50", "40", "-10", "30", "-5"};
        for (int i = 0; i < limitPnls.length; i++) {
            long open = T0 + i * 7200_000L;
            positions.add(pos(limitPnls[i], open));
            AiTraderPlan pl = plan("PULLBACK", open, false);
            pl.setEntryCtxJson("{\"orderType\":\"LIMIT\",\"leverage\":10}");
            plans.add(pl);
        }
        for (int i = 0; i < 2; i++) {
            long open = T0 + (10 + i) * 7200_000L;
            positions.add(pos("-20", open));
            AiTraderPlan pl = plan("PULLBACK", open, false);
            pl.setEntryCtxJson("{\"orderType\":\"MARKET\",\"leverage\":10}");
            plans.add(pl);
        }
        long open = T0 + 20 * 7200_000L;
        positions.add(pos("7", open));
        plans.add(plan("PULLBACK", open, false));
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(positions);
        when(planMapper.selectList(any())).thenReturn(plans);

        String out = assembler.assemble(trader(), AgentLang.ZH);

        assertThat(out).contains("PULLBACK：8笔 4胜4负 盈亏合计+72.00 USDT");
        assertThat(out).contains("按入场方式：限价 5笔 3胜2负 合计+105.00 USDT ｜ 市价 2笔（样本不足）；另 1 笔未记录入场方式");
    }

    /**
     * 重放汇总：5 笔多单入场 100、止损 98（R=2）、目标 106，开仓后那根 5m 最低 97.5、最高 100.5。
     * 止损原样被打（-1R），放宽到 1.5 倍以上没碰到；每笔净 -10、开仓数量 5，实际 = -10 ÷ (5×2) = -1R。
     */
    @Test
    void replaySummaryCountsOutcomesPerStopWidth() {
        List<FuturesPositionDTO> positions = new ArrayList<>();
        List<AiTraderPlan> plans = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long open = T0 + i * 7200_000L;
            FuturesPositionDTO p = pos("-10", open);
            p.setEntryPrice(new BigDecimal("100"));
            p.setOpenedQuantity(new BigDecimal("5"));
            positions.add(p);
            AiTraderPlan pl = plan("PULLBACK", open, false);
            pl.setStopLossPrice(new BigDecimal("98"));
            pl.setTakeProfitsJson("[{\"price\":106,\"percent\":100}]");
            plans.add(pl);
            when(historyStore.load(eq("BTCUSDT"), eq("5m"), eq(open), anyLong())).thenReturn(List.of(
                    new KlineBar(open + 300_000L, open + 599_999L, new BigDecimal("100"), new BigDecimal("100.5"),
                            new BigDecimal("97.5"), new BigDecimal("98"), BigDecimal.ONE)));
        }
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(positions);
        when(planMapper.selectList(any())).thenReturn(plans);

        String out = assembler.assemble(trader(), AgentLang.ZH);

        assertThat(out).contains("原计划不动重放 5笔（")
                .contains("：止损原样 到目标0 止损5 未决0 合计-5.20R ｜ 止损1.5倍 到目标0 止损0 未决5 合计+0R ｜ "
                        + "止损2倍 到目标0 止损0 未决5 合计+0R ｜ 止损3倍 到目标0 止损0 未决5 合计+0R\n")
                // 没有加过仓的单子，管理动作行不带加仓那半句
                .contains("管理动作 5笔：实际合计-5.00R，原计划不动合计-5.20R（两边都已扣手续费、都不含资金费）\n");
        assertThat(out.indexOf("管理动作")).isLessThan(out.indexOf("样本少时"));
    }

    /**
     * stale 计划必须先配对占位再被过滤：把它从配对池里提前拿掉，
     * 它的仓位就会错配到同 symbol/side 的别的计划上，统计的盈亏合计会被顶乱。
     */
    @Test
    void stalePlanExcludedButStillConsumesPairing() {
        List<FuturesPositionDTO> positions = new ArrayList<>();
        List<AiTraderPlan> plans = new ArrayList<>();
        // stale 的 REVERSAL：亏 -100，开仓时刻在最前
        positions.add(pos("-100", T0));
        plans.add(plan("REVERSAL", T0, true));
        // 5 笔正常 PULLBACK：合计 +100.50
        String[] pnls = {"10", "20", "30", "-5", "45.50"};
        for (int i = 0; i < pnls.length; i++) {
            long open = T0 + (i + 1) * 7200_000L;
            positions.add(pos(pnls[i], open));
            plans.add(plan("PULLBACK", open, false));
        }
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(positions);
        when(planMapper.selectList(any())).thenReturn(plans);

        String out = assembler.assemble(trader(), AgentLang.ZH);

        // stale 那笔整体消失（标签不出现、-100 不进任何合计），正常五笔的账一分不乱
        assertThat(out).doesNotContain("REVERSAL");
        assertThat(out).contains("最近5笔");
        assertThat(out).contains("PULLBACK：5笔 4胜1负 盈亏合计+100.50 USDT");
    }

    /**
     * 精确趟归位：绑了 position_id 的计划不受时间就近摆布——亏单的 -100 必须挂在
     * 自己的 REVERSAL 名下，不许把贴着它开仓时刻的 PULLBACK 顶下水。
     */
    @Test
    void positionIdJoinKeepsPnlUnderRightPlay() {
        List<FuturesPositionDTO> positions = new ArrayList<>();
        List<AiTraderPlan> plans = new ArrayList<>();
        // 5 笔 PULLBACK 各 +10，id 绑定
        for (int i = 0; i < 5; i++) {
            long open = T0 + i * 7200_000L;
            FuturesPositionDTO p = pos("10", open);
            p.setId((long) (i + 1));
            positions.add(p);
            AiTraderPlan pl = plan("PULLBACK", open, false);
            pl.setPositionId((long) (i + 1));
            plans.add(pl);
        }
        // 亏 -100 的 REVERSAL：开仓时刻故意造得跟第一笔 PULLBACK 一样近，仅靠 id 区分
        FuturesPositionDTO loser = pos("-100", T0);
        loser.setId(6L);
        positions.add(loser);
        AiTraderPlan reversal = plan("REVERSAL", T0, false);
        reversal.setPositionId(6L);
        plans.add(reversal);
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(positions);
        when(planMapper.selectList(any())).thenReturn(plans);

        String out = assembler.assemble(trader(), AgentLang.ZH);

        assertThat(out).contains("PULLBACK：5笔 5胜0负 盈亏合计+50.00 USDT");
        assertThat(out).contains("REVERSAL：1笔");
    }

    /** 无已平仓位 / 全配不上计划 → null，不注入 */
    @Test
    void returnsNullWhenNoPairedTrades() {
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of());
        assertThat(assembler.assemble(trader(), AgentLang.ZH)).isNull();

        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(pos("10", T0)));
        when(planMapper.selectList(any())).thenReturn(List.of());
        assertThat(assembler.assemble(trader(), AgentLang.ZH)).isNull();
    }

    /** sim 抖一下不许把唤醒带崩：统计块缺席即可 */
    @Test
    void returnsNullOnSimFailure() {
        when(simTradeClient.getClosedPositions(eq(99L), anyInt()))
                .thenThrow(new IllegalStateException("sim down"));
        assertThat(assembler.assemble(trader(), AgentLang.ZH)).isNull();
    }
}
