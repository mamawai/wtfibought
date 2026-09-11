package com.mawai.wiibagent.trader.prompt;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
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

    private final PlayStatsAssembler assembler =
            new PlayStatsAssembler(simTradeClient, planMapper, new PromptCatalog());

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
