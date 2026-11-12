package com.mawai.wiibagent.trader;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeRecordServiceTest {

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderPlan.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
    }

    private static final long OPEN_MS = 1785110400000L;   // 2026-07-27 00:00 UTC
    private static final long CLOSE_MS = OPEN_MS + 7_200_000L;

    private final SimTradeClient sim = mock(SimTradeClient.class);
    private final AiTraderPlanMapper planMapper = mock(AiTraderPlanMapper.class);
    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);
    private final TradeRecordService service =
            new TradeRecordService(sim, planMapper, decisionMapper);

    private static AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setRoundNo(2);
        t.setSimUserId(99L);
        return t;
    }

    private static LocalDateTime at(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault());
    }

    /** 一笔 LONG 已平：入场 100 → 出场 closed，默认主动平仓，创建/更新时刻分别是开/平 */
    private static FuturesPositionDTO closedLong(long id, String closed) {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId(id);
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setLeverage(5);
        p.setEntryPrice(new BigDecimal("100"));
        p.setClosedPrice(new BigDecimal(closed));
        p.setClosedPnl(new BigDecimal(closed).subtract(new BigDecimal("100")));
        p.setStatus("CLOSED");
        p.setClosedBy("FILLED");
        p.setCreatedAt(at(OPEN_MS));
        p.setUpdatedAt(at(CLOSE_MS));
        return p;
    }

    private static AiTraderPlan plan(long openedWakeTime) {
        AiTraderPlan pl = new AiTraderPlan();
        pl.setId(11L);
        pl.setSymbol("BTCUSDT");
        pl.setSide("LONG");
        pl.setPlayType("BREAKOUT");
        pl.setSignalsUsed("4h 突破前高");
        pl.setInvalidationCondition("跌回 95");
        pl.setOpenedWakeTime(openedWakeTime);
        return pl;
    }

    private static AiTraderDecision decision(long id, long wakeTime, String reasoning, String actionsJson) {
        AiTraderDecision d = new AiTraderDecision();
        d.setId(id);
        d.setWakeTime(wakeTime);
        d.setKind(AiTraderDecision.KIND_TRADE);
        d.setReasoning(reasoning);
        d.setActionsJson(actionsJson);
        return d;
    }

    /** 止损带走：配上计划与开仓决策，不挂平仓决策（依据是计划里的原始止损） */
    @Test
    void pairsPlanAndOpenDecisionAndSkipsCloseDecisionWhenStoppedOut() {
        FuturesPositionDTO pos = closedLong(1L, "94");
        pos.setClosedBy("STOP_LOSS");
        when(sim.getClosedPositions(99L, TradeRecordService.LIMIT)).thenReturn(List.of(pos));
        when(planMapper.selectList(any())).thenReturn(List.of(plan(OPEN_MS)));
        // 第一次 selectList 是开仓决策查询，第二次是平仓决策查询
        when(decisionMapper.selectList(any())).thenReturn(
                List.of(decision(100L, OPEN_MS, "突破做多", "[]")),
                List.of());

        List<TradeRecordService.TradeRecord> out = service.closedTrades(trader());

        assertThat(out).hasSize(1);
        TradeRecordService.TradeRecord r = out.get(0);
        assertThat(r.positionId()).isEqualTo(1L);
        // 下发的是语言无关的码，文案在前端词表里
        assertThat(r.closeMannerKey()).isEqualTo("stopLoss");
        assertThat(r.plan().getSignalsUsed()).isEqualTo("4h 突破前高");
        assertThat(r.openDecision().id()).isEqualTo(100L);
        assertThat(r.openDecision().reasoning()).isEqualTo("突破做多");
        assertThat(r.closeDecision()).isNull();
        assertThat(r.openedAt()).isEqualTo(OPEN_MS);
        assertThat(r.closedAt()).isEqualTo(CLOSE_MS);
    }

    /** 主动平仓：从 actionsJson 里按 positionId 找到平仓那一轮，带 reason；多轮减仓取最后一轮 */
    @Test
    void activeCloseFindsDecisionByPositionIdKeepingLatest() {
        FuturesPositionDTO pos = closedLong(7L, "108");
        when(sim.getClosedPositions(99L, TradeRecordService.LIMIT)).thenReturn(List.of(pos));
        when(planMapper.selectList(any())).thenReturn(List.of(plan(OPEN_MS)));
        String first = "[{\"tool\":\"close_position\",\"args\":{\"positionId\":7,\"quantity\":0.5,\"reason\":\"先减半\"},\"status\":\"ok\"}]";
        String last = "[{\"tool\":\"klines\"},{\"tool\":\"close_position\",\"args\":{\"positionId\":7,\"quantity\":0.5,\"reason\":\"失效条件触发\"},\"status\":\"pending\"}]";
        String other = "[{\"tool\":\"close_position\",\"args\":{\"positionId\":8,\"quantity\":1,\"reason\":\"别的仓\"},\"status\":\"ok\"}]";
        when(decisionMapper.selectList(any())).thenReturn(
                List.of(decision(100L, OPEN_MS, "突破做多", "[]")),
                List.of(decision(101L, OPEN_MS + 900_000L, "减半", first),
                        decision(102L, OPEN_MS + 1_800_000L, "别的", other),
                        decision(103L, OPEN_MS + 3_600_000L, "走人", last)));

        TradeRecordService.TradeRecord r = service.closedTrades(trader()).get(0);

        assertThat(r.closeMannerKey()).isEqualTo("manual");
        assertThat(r.closeDecision().id()).isEqualTo(103L);
        assertThat(r.closeDecision().reason()).isEqualTo("失效条件触发");
        assertThat(r.closeDecision().reasoning()).isEqualTo("走人");
    }

    /** 同一边界既有例行轮又有手动轮（手动唤醒的 wake_time 就是当前边界）：开了仓的那条才是开仓决策 */
    @Test
    void sameBoundaryPrefersDecisionThatOpened() {
        FuturesPositionDTO pos = closedLong(1L, "108");
        when(sim.getClosedPositions(99L, TradeRecordService.LIMIT)).thenReturn(List.of(pos));
        when(planMapper.selectList(any())).thenReturn(List.of(plan(OPEN_MS)));
        AiTraderDecision manual = decision(101L, OPEN_MS, "主人叫醒，突破做多",
                "[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\"},\"status\":\"ok\"}]");
        manual.setKind(AiTraderDecision.KIND_MANUAL);
        when(decisionMapper.selectList(any())).thenReturn(
                List.of(decision(100L, OPEN_MS, "例行 HOLD", "[{\"tool\":\"klines\"}]"), manual),
                List.of());

        TradeRecordService.TradeRecord r = service.closedTrades(trader()).get(0);

        assertThat(r.openDecision().id()).isEqualTo(101L);
        assertThat(r.openDecision().kind()).isEqualTo(AiTraderDecision.KIND_MANUAL);
    }

    @Test
    void reverseOpeningDecisionIsRecognizedAndPartialCloseStillLinks() {
        when(sim.getClosedPositions(99L, TradeRecordService.LIMIT)).thenReturn(List.of(closedLong(7L, "108")));
        when(planMapper.selectList(any())).thenReturn(List.of(plan(OPEN_MS)));
        String opened = """
                [{"tool":"reverse_position","status":"ok","result":{
                  "close":{"tool":"close_position","status":"ok","args":{"positionId":6}},
                  "open":{"tool":"open_position","status":"ok","args":{"symbol":"BTCUSDT","side":"LONG"}}
                }}]
                """;
        String partial = """
                [{"tool":"reverse_position","status":"partial","result":{
                  "close":{"tool":"close_position","status":"ok","args":{"positionId":7,"reason":"反弹失效"}},
                  "open":{"tool":"open_position","status":"error"}
                }}]
                """;
        String unknown = """
                [{"tool":"reverse_position","status":"unknown","result":{
                  "close":{"tool":"close_position","status":"unknown","args":{"positionId":7,"reason":"不能顶掉已确认成交"}},
                  "open":{"tool":"open_position","status":"not_attempted"}
                }}]
                """;
        when(decisionMapper.selectList(any())).thenReturn(
                List.of(decision(100L, OPEN_MS, "反手做多", opened), decision(101L, OPEN_MS, "HOLD", "[]")),
                List.of(decision(102L, CLOSE_MS, "反手做空但只平掉", partial), decision(103L, CLOSE_MS, "未知", unknown)));
        var trade = service.closedTrades(trader()).getFirst();
        assertThat(trade.openDecision().id()).isEqualTo(100L);
        assertThat(trade.closeDecision().id()).isEqualTo(102L);
        assertThat(trade.closeDecision().reason()).isEqualTo("反弹失效");
    }

    /** 被拒/出错的 close_position 不算数：仓位早平了之后再对它下的失败动作不能顶掉真正的平仓决策 */
    @Test
    void rejectedOrErrorCloseActionsIgnored() {
        FuturesPositionDTO pos = closedLong(7L, "108");
        when(sim.getClosedPositions(99L, TradeRecordService.LIMIT)).thenReturn(List.of(pos));
        when(planMapper.selectList(any())).thenReturn(List.of());
        String real = "[{\"tool\":\"close_position\",\"args\":{\"positionId\":7,\"quantity\":1,\"reason\":\"失效条件触发\"},\"status\":\"ok\"}]";
        String rejected = "[{\"tool\":\"close_position\",\"args\":{\"positionId\":7,\"quantity\":1,\"reason\":\"再平一次\"},\"rejected\":\"仓位不存在\"}]";
        String error = "[{\"tool\":\"close_position\",\"args\":{\"positionId\":7,\"quantity\":1,\"reason\":\"又来\"},\"status\":\"error\",\"error\":\"sim 500\"}]";
        when(decisionMapper.selectList(any())).thenReturn(List.of(
                decision(101L, OPEN_MS + 900_000L, "走人", real),
                decision(102L, OPEN_MS + 1_800_000L, "幻觉", rejected),
                decision(103L, OPEN_MS + 3_600_000L, "又幻觉", error)));

        TradeRecordService.TradeRecord r = service.closedTrades(trader()).get(0);

        assertThat(r.closeDecision().id()).isEqualTo(101L);
        assertThat(r.closeDecision().reason()).isEqualTo("失效条件触发");
    }

    /** 配对走 pairAll 精确趟：position_id 绑定说了算，时间就近想交叉错配也不行 */
    @Test
    void positionIdJoinBeatsTimeProximity() {
        FuturesPositionDTO pos1 = closedLong(1L, "108");
        FuturesPositionDTO pos2 = closedLong(2L, "94");
        pos2.setCreatedAt(at(OPEN_MS + 600_000L));
        pos2.setUpdatedAt(at(CLOSE_MS + 600_000L));
        when(sim.getClosedPositions(99L, TradeRecordService.LIMIT)).thenReturn(List.of(pos1, pos2));
        // planX 绑 pos2 但开仓时刻贴着 pos1（反之亦然）：贪心时间就近会交叉错配
        AiTraderPlan planX = plan(OPEN_MS);
        planX.setId(11L);
        planX.setSignalsUsed("论点X");
        planX.setPositionId(2L);
        AiTraderPlan planY = plan(OPEN_MS + 600_000L);
        planY.setId(12L);
        planY.setSignalsUsed("论点Y");
        planY.setPositionId(1L);
        when(planMapper.selectList(any())).thenReturn(List.of(planX, planY));

        List<TradeRecordService.TradeRecord> out = service.closedTrades(trader());

        assertThat(out).hasSize(2);
        assertThat(out.get(0).positionId()).isEqualTo(1L);
        assertThat(out.get(0).plan().getSignalsUsed()).isEqualTo("论点Y");
        assertThat(out.get(1).plan().getSignalsUsed()).isEqualTo("论点X");
    }

    /** 无计划记录：plan/openDecision 为 null，记录本身仍在；开仓决策不查库 */
    @Test
    void noPlanStillListsTradeWithoutOpenDecision() {
        FuturesPositionDTO pos = closedLong(3L, "108");
        when(sim.getClosedPositions(99L, TradeRecordService.LIMIT)).thenReturn(List.of(pos));
        when(planMapper.selectList(any())).thenReturn(List.of());
        when(decisionMapper.selectList(any())).thenReturn(List.of());

        List<TradeRecordService.TradeRecord> out = service.closedTrades(trader());

        assertThat(out).hasSize(1);
        assertThat(out.get(0).plan()).isNull();
        assertThat(out.get(0).openDecision()).isNull();
        assertThat(out.get(0).closeDecision()).isNull();
        // 无计划 → 开仓决策不查；只剩平仓决策那一次
        verify(decisionMapper).selectList(any());
    }

    /** sim 无已平仓位：不查计划不查决策 */
    @Test
    void emptyWhenNothingClosed() {
        when(sim.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of());

        assertThat(service.closedTrades(trader())).isEmpty();

        verify(planMapper, never()).selectList(any());
        verify(decisionMapper, never()).selectList(any());
    }
}
