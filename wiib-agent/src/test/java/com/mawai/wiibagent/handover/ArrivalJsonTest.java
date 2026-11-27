package com.mawai.wiibagent.handover;

import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibagent.trader.wakeup.AlertTrigger;
import com.mawai.wiibagent.trader.wakeup.Arrival;
import com.mawai.wiibagent.trader.wakeup.EquityDropTrigger;
import com.mawai.wiibagent.trader.wakeup.EventTrigger;
import com.mawai.wiibagent.trader.wakeup.WatchTrigger;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.market.TradeEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 到来的事过一趟线（编码、转成字符串、读回来）还是原样；盯盘命中按条件 id 在对面读库 */
class ArrivalJsonTest {

    private final TraderWatchStore watchStore = mock(TraderWatchStore.class);
    private final ArrivalJson json = new ArrivalJson(watchStore);

    private Arrival overTheWire(Arrival arrival) {
        return json.decode(MAPPER.readTree(MAPPER.writeValueAsString(json.encode(arrival))));
    }

    @Test
    void everyKindSurvivesTheTrip() {
        AiTraderWatch w = new AiTraderWatch();
        w.setId(31L);
        w.setTraderId(7L);
        when(watchStore.byId(31L)).thenReturn(w);
        Arrival[] all = {
                new Arrival.Routine(1785171600000L),
                new Arrival.Trigger(new AlertTrigger("BTCUSDT", new BigDecimal("1.2"), new BigDecimal("63120.5"),
                        AlertTrigger.DOWN, 1785171600000L)),
                new Arrival.Trigger(new EventTrigger(new TradeEvent(TradeEvent.STOP_LOSS, 42L, 9L, null, "BTCUSDT", "LONG",
                        new BigDecimal("0.5"), new BigDecimal("63120"), new BigDecimal("-120"), 1785171601000L))),
                new Arrival.Trigger(new EquityDropTrigger(new BigDecimal("10000"), 1785168000000L, new BigDecimal("8500"),
                        new BigDecimal("15"), new BigDecimal("15"), 1785171000000L)),
                new Arrival.Trigger(new WatchTrigger(w, new BigDecimal("1899.5"), 1785171600000L)),
        };

        for (Arrival a : all) {
            assertThat(overTheWire(a)).isEqualTo(a);
        }
    }

    /** 盯盘条件在库里找不到（已删）：读不回来就抛，对面回错 */
    @Test
    void missingWatchThrows() {
        AiTraderWatch w = new AiTraderWatch();
        w.setId(31L);

        assertThatThrownBy(() -> overTheWire(new Arrival.Trigger(new WatchTrigger(w, BigDecimal.ONE, 1L))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
