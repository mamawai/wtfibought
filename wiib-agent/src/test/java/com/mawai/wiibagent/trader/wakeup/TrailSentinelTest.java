package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.trader.trade.TraderTrailStore;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.dto.FuturesStopLossRequest;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderTrail;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跟踪止损哨兵。用例都是一张 BTC 空单、回调 1%、现挂止损 102：
 * 价格创新低止损就跟到新低上方 1%，反弹不动；不够一个步长（回调距离的十分之一）不挪；
 * 跟到哪了落库，重载后从那里接着跟；挪的时候仓位不在了就结束这条规则
 */
class TrailSentinelTest {

    private final TraderTrailStore store = mock(TraderTrailStore.class);
    private final SimTradeClient sim = mock(SimTradeClient.class);
    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final UserLangResolver langResolver = mock(UserLangResolver.class);
    private final TrailSentinel sentinel = new TrailSentinel(mock(RedisMessageListenerContainer.class), store, sim,
            traderMapper, langResolver);

    {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(3L);
        t.setSimUserId(99L);
        when(traderMapper.selectById(7L)).thenReturn(t);
        when(langResolver.of(3L)).thenReturn(AgentLang.ZH);
    }

    private static AiTraderTrail rule() {
        AiTraderTrail t = new AiTraderTrail();
        t.setId(31L);
        t.setTraderId(7L);
        t.setSymbol("BTCUSDT");
        t.setSide("SHORT");
        t.setPositionId(5L);
        t.setCallbackPct(BigDecimal.ONE);
        t.setStopPrice(new BigDecimal("102"));
        return t;
    }

    private void tracking(AiTraderTrail... rules) {
        when(store.all()).thenReturn(List.of(rules));
        sentinel.reload();
    }

    /** sim 里那张空单：止损挂在 stop，标记价 mark */
    private void holding(String stop, String mark) {
        FuturesStopLoss sl = new FuturesStopLoss();
        sl.setPrice(new BigDecimal(stop));
        sl.setQuantity(new BigDecimal("3"));
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId(5L);
        p.setSymbol("BTCUSDT");
        p.setSide("SHORT");
        p.setQuantity(new BigDecimal("3"));
        p.setMarkPrice(new BigDecimal(mark));
        p.setStopLosses(List.of(sl));
        when(sim.getAllPositions(99L)).thenReturn(List.of(p));
    }

    /** sim 收到的第 n 次改止损挪到了哪 */
    private BigDecimal movedTo(int n) {
        ArgumentCaptor<FuturesStopLossRequest> req = ArgumentCaptor.forClass(FuturesStopLossRequest.class);
        verify(sim, timeout(2_000).times(n)).setStopLoss(eq(99L), req.capture());
        return req.getAllValues().get(n - 1).getStopLosses().getFirst().getPrice();
    }

    private void tick(String price) {
        sentinel.onPriceTick("BTCUSDT", new BigDecimal(price));
    }

    /** 数值相等就算对上，不比小数位数 */
    private static BigDecimal num(String value) {
        return argThat(v -> v != null && v.compareTo(new BigDecimal(value)) == 0);
    }

    /** 只进不退：新低 100 跟到 101，再新低 99 跟到 99.99；中间和之后的反弹什么都不做 */
    @Test
    void followsNewLowsAndIgnoresBounces() {
        tracking(rule());
        holding("102", "100");

        tick("100");
        assertThat(movedTo(1)).isEqualByComparingTo("101");
        // 等这一步落完库、挪止损的线程收尾，再喂下一个 tick
        verify(store, after(200)).advance(eq(31L), num("100"), num("101"));

        tick("100.5");
        holding("101", "99");
        tick("99");
        assertThat(movedTo(2)).isEqualByComparingTo("99.99");

        tick("100.8");
        verify(sim, after(300).times(2)).setStopLoss(anyLong(), any());
    }

    /** 不到步长不动：止损在 101，新低 99.95 时应有的止损 100.95，只紧了 0.05，不到步长 0.09995 */
    @Test
    void belowOneStepNothingMoves() {
        AiTraderTrail r = rule();
        r.setBestPrice(new BigDecimal("100"));
        r.setStopPrice(new BigDecimal("101"));
        tracking(r);

        tick("99.95");

        verify(sim, after(300).never()).setStopLoss(anyLong(), any());
        verify(store, never()).advance(anyLong(), any(), any());
    }

    /** 重启接续：库里记着最有利价 100、止损 101，重载后反弹到 100.5 不动，新低 99 接着跟 */
    @Test
    void resumesFromPersistedBestAfterReload() {
        AiTraderTrail r = rule();
        r.setBestPrice(new BigDecimal("100"));
        r.setStopPrice(new BigDecimal("101"));
        tracking(r);
        holding("101", "99");

        tick("100.5");
        verify(sim, after(300).never()).setStopLoss(anyLong(), any());

        tick("99");
        assertThat(movedTo(1)).isEqualByComparingTo("99.99");
        verify(store, timeout(2_000)).advance(eq(31L), num("99"), num("99.99"));
    }

    /** 设了启动价 99.5：价格没到不跟；到了从那一刻的价格起算。还没绑仓位的规则（限价单没成交）不跟 */
    @Test
    void waitsForActivationPriceAndSkipsUnboundRules() {
        AiTraderTrail r = rule();
        r.setActivationPrice(new BigDecimal("99.5"));
        AiTraderTrail unbound = rule();
        unbound.setId(32L);
        unbound.setPositionId(null);
        tracking(r, unbound);
        holding("102", "99.4");

        tick("100");
        verify(sim, after(300).never()).setStopLoss(anyLong(), any());

        tick("99.4");
        assertThat(movedTo(1)).isEqualByComparingTo("100.394");
        verify(store, after(300).never()).advance(eq(32L), any(), any());
    }

    /** 仓位了结即清：要挪的时候 sim 里已经没有这个仓位，结束这条规则，不调改止损 */
    @Test
    void endsRuleWhenPositionIsGone() {
        AiTraderTrail r = rule();
        tracking(r);
        when(sim.getAllPositions(99L)).thenReturn(List.of());

        tick("100");

        verify(store, timeout(2_000)).endClosed(r, AgentLang.ZH);
        assertThat(r.getBestPrice()).isEqualByComparingTo("100");
        verify(sim, never()).setStopLoss(anyLong(), any());
        verify(store, never()).advance(anyLong(), any(), any());
    }

    /** 现挂的止损比应有的还紧（模型开仓时就挂得近）：不挪，记下它，等最有利价走到让应有的更紧 */
    @Test
    void keepsTighterRestingStopUntilTrailCatchesUp() {
        tracking(rule());
        holding("100.5", "100");

        tick("100");

        verify(store, timeout(2_000)).advance(eq(31L), num("100"), num("100.5"));
        verify(sim, never()).setStopLoss(anyLong(), any());
    }

    /** 多单在最有利价下方、空单在上方；止损价留 6 位有效数字 */
    @Test
    void desiredStopSitsOneCallbackBehindBest() {
        assertThat(TrailSentinel.desiredStop(new BigDecimal("100"), new BigDecimal("1.5"), true)).isEqualByComparingTo("98.5");
        assertThat(TrailSentinel.desiredStop(new BigDecimal("100"), new BigDecimal("1.5"), false)).isEqualByComparingTo("101.5");
        assertThat(TrailSentinel.desiredStop(new BigDecimal("104567.8"), BigDecimal.ONE, true)).isEqualByComparingTo("103522");
    }
}
