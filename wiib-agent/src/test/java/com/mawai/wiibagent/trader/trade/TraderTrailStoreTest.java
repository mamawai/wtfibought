package com.mawai.wiibagent.trader.trade;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderTrailMapper;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.AiTraderTrail;
import com.mawai.wiibcommon.enums.AgentLang;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 跟踪止损规则在唤醒开头对账：限价成交的绑上仓位，仓位没了的结束并往计划记一条，挂单没了的直接删 */
class TraderTrailStoreTest {

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderTrail.class);
    }

    private final AiTraderTrailMapper mapper = mock(AiTraderTrailMapper.class);
    private final TraderPlanStore planStore = mock(TraderPlanStore.class);
    private final TraderTrailStore store = new TraderTrailStore(mapper, planStore, new PromptCatalog(),
            mock(ApplicationEventPublisher.class));

    private static AiTraderTrail trail(long id, String symbol, String side, Long positionId) {
        AiTraderTrail t = new AiTraderTrail();
        t.setId(id);
        t.setTraderId(7L);
        t.setRoundNo(1);
        t.setSymbol(symbol);
        t.setSide(side);
        t.setPositionId(positionId);
        t.setCallbackPct(BigDecimal.ONE);
        return t;
    }

    private static FuturesPositionDTO position(long id, String symbol, String side) {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId(id);
        p.setSymbol(symbol);
        p.setSide(side);
        return p;
    }

    @Test
    void reconcileBindsFilledEndsClosedAndKeepsPending() {
        AiTraderTrail filled = trail(1L, "BTCUSDT", "LONG", null);
        AiTraderTrail closed = trail(2L, "ETHUSDT", "SHORT", 50L);
        closed.setBestPrice(new BigDecimal("2000"));
        closed.setStopPrice(new BigDecimal("2020"));
        AiTraderTrail pending = trail(3L, "SOLUSDT", "LONG", null);
        AiTraderTrail cancelled = trail(4L, "DOGEUSDT", "LONG", null);
        when(mapper.selectList(any())).thenReturn(List.of(filled, closed, pending, cancelled));
        when(mapper.update(isNull(), any())).thenReturn(1);
        AiTraderPlan plan = new AiTraderPlan();
        plan.setId(60L);
        when(planStore.byPosition(7L, 1, 50L)).thenReturn(plan);

        Map<String, AiTraderTrail> live = store.reconcile(7L, 1,
                List.of(position(41L, "BTCUSDT", "LONG")), List.of(openOrder("SOLUSDT", "OPEN_LONG")), AgentLang.ZH);

        // 限价成交的绑上仓位；还挂着单的原样留着
        assertThat(live).containsOnlyKeys("BTCUSDT|LONG", "SOLUSDT|LONG");
        assertThat(live.get("BTCUSDT|LONG").getPositionId()).isEqualTo(41L);
        verify(mapper, times(1)).update(isNull(), any());
        // 仓位没了的结束：删行，计划记下跟到哪了
        verify(mapper).deleteById(2L);
        verify(planStore).appendRevision(eq(60L), anyLong(), eq("跟踪止损结束"), eq("最有利价 2000，止损跟到 2020"), eq("仓位已了结"));
        // 挂单没了的没有仓位，只删行
        verify(mapper).deleteById(4L);
        verify(planStore, never()).byPosition(eq(7L), eq(1), eq(4L));
    }

    /** 单还挂着、同币同向又有了仓位（另开的）：规则是那张单的，接着等它成交，不绑到这个仓位上 */
    @Test
    void reconcileKeepsUnboundRuleWaitingWhileItsOrderStillRests() {
        when(mapper.selectList(any())).thenReturn(List.of(trail(1L, "BTCUSDT", "LONG", null)));

        Map<String, AiTraderTrail> live = store.reconcile(7L, 1,
                List.of(position(41L, "BTCUSDT", "LONG")), List.of(openOrder("BTCUSDT", "OPEN_LONG")), AgentLang.ZH);

        assertThat(live.get("BTCUSDT|LONG").getPositionId()).isNull();
        verify(mapper, never()).update(isNull(), any());
    }

    /** 撤掉开仓挂单：同币同向没有别的开仓挂单了，才把它带的没绑仓位的规则删掉；撤的是平仓单不管 */
    @Test
    void cancellingTheLastOpenOrderDropsItsUnboundRule() {
        when(mapper.selectCount(any())).thenReturn(1L);
        when(mapper.delete(any())).thenReturn(1);

        store.orderCancelled(7L, 1, "BTCUSDT", "CLOSE_LONG", List::of);
        store.orderCancelled(7L, 1, "BTCUSDT", "OPEN_LONG", () -> List.of(openOrder("BTCUSDT", "OPEN_LONG")));
        verify(mapper, never()).delete(any());

        store.orderCancelled(7L, 1, "BTCUSDT", "OPEN_LONG", () -> List.of(openOrder("BTCUSDT", "OPEN_SHORT")));
        verify(mapper).delete(any());
    }

    private static FuturesOrderResponse openOrder(String symbol, String orderSide) {
        FuturesOrderResponse order = new FuturesOrderResponse();
        order.setSymbol(symbol);
        order.setOrderSide(orderSide);
        return order;
    }
}
