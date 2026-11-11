package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.dto.FuturesStopLossRequest;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibquant.external.sim.SimOrderRetry;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预案执行：三种动作各走一遍，结果记到条件行、计划修订史和时间线的 PRESET 行；
 * 移止损和撤单做成了不叫醒，平仓做成了叫醒，没做成的都叫醒
 */
class PresetExecutorTest {

    private static final long AT = 1_786_200_000_000L;

    private final SimTradeClient sim = mock(SimTradeClient.class);
    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);
    private final TraderPlanStore planStore = mock(TraderPlanStore.class);
    private final TraderWatchStore watchStore = mock(TraderWatchStore.class);
    private final UserLangResolver langResolver = mock(UserLangResolver.class);
    private final PresetExecutor executor = new PresetExecutor(sim, traderMapper, decisionMapper, planStore, watchStore,
            langResolver, new PromptCatalog(), new MessageCatalog());

    {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(3L);
        t.setRoundNo(1);
        t.setSimUserId(99L);
        t.setIntervalCode("1h");
        when(traderMapper.selectById(7L)).thenReturn(t);
        when(langResolver.of(3L)).thenReturn(AgentLang.ZH);
        AiTraderPlan plan = new AiTraderPlan();
        plan.setId(40L);
        when(planStore.liveFor(7L, 1, "BTCUSDT", "LONG")).thenReturn(plan);
    }

    /** ETH 跌破 1900 这条条件，带一个动作 */
    private static AiTraderWatch watch(String actionJson) {
        AiTraderWatch w = new AiTraderWatch();
        w.setId(31L);
        w.setTraderId(7L);
        w.setSymbol("ETHUSDT");
        w.setKind(AiTraderWatch.TOUCH_BELOW);
        w.setLevel(new BigDecimal("1900"));
        w.setNote("大盘破位先保本");
        w.setActionJson(actionJson);
        return w;
    }

    /** BTC 多单 10 个：入场 100、标记价 104、止损 98 */
    private void holding() {
        FuturesStopLoss sl = new FuturesStopLoss();
        sl.setPrice(new BigDecimal("98"));
        sl.setQuantity(new BigDecimal("10"));
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId(5L);
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setQuantity(new BigDecimal("10"));
        p.setEntryPrice(new BigDecimal("100"));
        p.setMarkPrice(new BigDecimal("104"));
        p.setStopLosses(List.of(sl));
        when(sim.getAllPositions(99L)).thenReturn(List.of(p));
    }

    private AiTraderDecision timelineRow() {
        ArgumentCaptor<AiTraderDecision> row = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(row.capture());
        return row.getValue();
    }

    /** 移止损：走只许收紧那条路挪到 100，整仓一档；不叫醒；三处留痕 */
    @Test
    void moveStopCarriedOutWithoutWaking() {
        holding();
        AiTraderWatch w = watch("{\"type\":\"MOVE_STOP\",\"positionId\":5,\"stopLossPrice\":100,\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"}");

        boolean wake = executor.execute(w, new BigDecimal("1899"), AT);

        assertThat(wake).isFalse();
        ArgumentCaptor<FuturesStopLossRequest> req = ArgumentCaptor.forClass(FuturesStopLossRequest.class);
        verify(sim).setStopLoss(eq(99L), req.capture());
        assertThat(req.getValue().getStopLosses()).singleElement().satisfies(i -> {
            assertThat(i.getPrice()).isEqualByComparingTo("100");
            assertThat(i.getQuantity()).isEqualByComparingTo("10");
        });
        verify(watchStore).recordAction(31L, true, "BTCUSDT LONG 止损 98→100");
        assertThat(w.getActionOk()).isTrue();
        verify(planStore).appendRevision(40L, AT, "预案移止损", "98→100", "大盘破位先保本");
        AiTraderDecision row = timelineRow();
        assertThat(row.getKind()).isEqualTo(AiTraderDecision.KIND_PRESET);
        assertThat(row.getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(row.getWakeTime()).isEqualTo(AT);
        assertThat(row.getEquity()).isNull();
        assertThat(row.getReasoning()).contains("ETHUSDT 标记价 1899 触及 1900 以下").contains("大盘破位先保本")
                .contains("BTCUSDT LONG 止损 98→100");
        assertThat(row.getActionsJson()).contains("\"tool\":\"preset\"").contains("\"status\":\"ok\"");
    }

    /** 平仓：按命中这一刻的仓位折数量（10 的 50% = 5），市价平；做成了叫醒 */
    @Test
    void closeCarriedOutAndWakes() {
        holding();
        FuturesOrderResponse resp = new FuturesOrderResponse();
        resp.setFilledPrice(new BigDecimal("103.9"));
        when(sim.closePosition(eq(99L), any())).thenReturn(resp);
        AiTraderWatch w = watch("{\"type\":\"CLOSE\",\"positionId\":5,\"closePercent\":50,\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"}");

        boolean wake = executor.execute(w, new BigDecimal("1899"), AT);

        assertThat(wake).isTrue();
        ArgumentCaptor<FuturesCloseRequest> req = ArgumentCaptor.forClass(FuturesCloseRequest.class);
        verify(sim).closePosition(eq(99L), req.capture());
        assertThat(req.getValue().getQuantity()).isEqualByComparingTo("5");
        assertThat(req.getValue().getOrderType()).isEqualTo("MARKET");
        verify(watchStore).recordAction(31L, true, "BTCUSDT LONG 市价平掉 5 @103.9");
        verify(planStore).appendRevision(40L, AT, "预案平仓", "5", "大盘破位先保本");
    }

    /** 平仓指令发出去没问到结果：不说成没做成，如实写「可能已经执行」，叫醒；计划不记平仓 */
    @Test
    void closeWithUnknownOutcomeIsReportedAsUnknown() {
        holding();
        when(sim.closePosition(eq(99L), any())).thenThrow(new SimOrderRetry.UnknownOutcome(new IllegalStateException("Read timed out")));
        AiTraderWatch w = watch("{\"type\":\"CLOSE\",\"positionId\":5,\"closePercent\":50,\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"}");

        assertThat(executor.execute(w, new BigDecimal("1899"), AT)).isTrue();

        assertThat(w.getActionOk()).isFalse();
        assertThat(w.getActionResult()).contains("没问到结果（Read timed out）").contains("可能已经执行");
        verify(planStore, never()).appendRevision(anyLong(), anyLong(), any(), any(), any());
    }

    /** 撤单做成了不叫醒；sim 拒了（单子已经不在）按没做成报，叫醒 */
    @Test
    void cancelOrderQuietWhenDoneAndWakesWhenSimRefuses() {
        AiTraderWatch w = watch("{\"type\":\"CANCEL_ORDER\",\"orderId\":600,\"symbol\":\"BTCUSDT\",\"side\":\"OPEN_LONG\"}");

        assertThat(executor.execute(w, new BigDecimal("1899"), AT)).isFalse();
        verify(sim).cancelOrder(99L, 600L);
        verify(watchStore).recordAction(31L, true, "BTCUSDT OPEN_LONG 挂单 600 已撤");

        when(sim.cancelOrder(99L, 600L)).thenThrow(new IllegalStateException("订单不可撤"));
        assertThat(executor.execute(w, new BigDecimal("1899"), AT)).isTrue();
        verify(watchStore).recordAction(31L, false, "订单不可撤");
        assertThat(w.getActionOk()).isFalse();
    }

    /** 没做成的都叫醒，时间线行是 ERROR：仓位已经不在；新止损比现挂的松（没调 sim） */
    @Test
    void failedActionsWakeWithTheReason() {
        AiTraderWatch gone = watch("{\"type\":\"CLOSE\",\"positionId\":5,\"closePercent\":100,\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"}");
        assertThat(executor.execute(gone, new BigDecimal("1899"), AT)).isTrue();
        verify(watchStore).recordAction(31L, false, "动作指的那个仓位已经不在");
        AiTraderDecision row = timelineRow();
        assertThat(row.getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        assertThat(row.getError()).isEqualTo("动作指的那个仓位已经不在");

        holding();
        AiTraderWatch looser = watch("{\"type\":\"MOVE_STOP\",\"positionId\":5,\"stopLossPrice\":97,\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"}");
        assertThat(executor.execute(looser, new BigDecimal("1899"), AT)).isTrue();
        verify(watchStore).recordAction(31L, false, "新止损 97 比现挂的 98 松，没有移");
        verify(sim, never()).setStopLoss(anyLong(), any());
        verify(planStore, never()).appendRevision(anyLong(), anyLong(), any(), any(), any());
    }

    /** 结果落库失败：该叫醒的照样叫醒 */
    @Test
    void bookkeepingFailureStillWakes() {
        doThrow(new IllegalStateException("db down")).when(watchStore).recordAction(anyLong(), anyBoolean(), any());
        AiTraderWatch gone = watch("{\"type\":\"CLOSE\",\"positionId\":5,\"closePercent\":100,\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"}");

        assertThat(executor.execute(gone, new BigDecimal("1899"), AT)).isTrue();
    }

    /** 交出去的预案在做完之前，awaitIdle 等着；做完后给出要不要叫醒 */
    @Test
    void submittedPresetIsAwaitedBeforeTheNextWake() {
        holding();
        AiTraderWatch w = watch("{\"type\":\"MOVE_STOP\",\"positionId\":5,\"stopLossPrice\":100,\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"}");

        CompletableFuture<Boolean> wake = executor.submit(w, new BigDecimal("1899"), AT);
        executor.awaitIdle(7L);

        assertThat(wake).isCompletedWithValue(false);
        verify(sim).setStopLoss(eq(99L), any());
    }
}
