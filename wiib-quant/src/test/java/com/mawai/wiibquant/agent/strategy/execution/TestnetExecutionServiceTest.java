package com.mawai.wiibquant.agent.strategy.execution;

import com.mawai.wiibquant.agent.binance.BinanceFuturesTestnetClient;
import com.mawai.wiibquant.agent.binance.BinanceFuturesTestnetProperties;
import com.mawai.wiibquant.agent.binance.model.OrderResponse;
import com.mawai.wiibquant.agent.binance.model.PlaceOrderRequest;
import com.mawai.wiibquant.agent.binance.model.PositionRisk;
import com.mawai.wiibquant.agent.binance.model.SetLeverageResponse;
import com.mawai.wiibquant.agent.binance.model.SimpleAck;
import com.mawai.wiibquant.agent.strategy.core.StrategySignal;
import com.mawai.wiibquant.agent.strategy.fibo.FiboParams;
import com.mawai.wiibquant.agent.strategy.fibo.FiboRetracementStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestnetExecutionServiceTest {

    private static final String SYM = "ETHUSDT";
    private static final long T0 = 1_000_000L;
    private static final long BAR = 300_000L;

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** 可控 stub：记录下单/撤单调用，queryOrder 返回值与 positionAmt 可设。 */
    private static final class StubClient extends BinanceFuturesTestnetClient {
        final List<PlaceOrderRequest> placed = new ArrayList<>();
        final List<Long> canceled = new ArrayList<>();
        boolean cancelAllCalled;
        OrderResponse queryResult;
        BigDecimal positionAmt = BigDecimal.ZERO;
        long nextOrderId = 1000;

        StubClient() {
            super(new BinanceFuturesTestnetProperties());
        }

        @Override
        public SetLeverageResponse setLeverage(String symbol, int leverage) {
            return null;
        }

        @Override
        public OrderResponse placeOrder(PlaceOrderRequest req) {
            placed.add(req);
            OrderResponse r = new OrderResponse();
            r.setOrderId(nextOrderId++);
            r.setStatus("NEW");
            return r;
        }

        @Override
        public OrderResponse cancelOrder(String symbol, Long orderId, String origClientOrderId) {
            canceled.add(orderId);
            OrderResponse r = new OrderResponse();
            r.setStatus("CANCELED");
            return r;
        }

        @Override
        public OrderResponse queryOrder(String symbol, Long orderId, String origClientOrderId) {
            return queryResult;
        }

        @Override
        public SimpleAck cancelAllOpenOrders(String symbol) {
            cancelAllCalled = true;
            return null;
        }

        @Override
        public List<PositionRisk> getPositionRisk(String symbol) {
            PositionRisk pr = new PositionRisk();
            pr.setSymbol(symbol);
            pr.setPositionAmt(positionAmt);
            return List.of(pr);
        }
    }

    private TestnetExecutionService service(StubClient client) {
        TestnetExecutionService svc = new TestnetExecutionService(client);
        svc.enabled = true;
        svc.symbolsCsv = SYM;
        svc.fixedMarginUsdt = 50;
        svc.leverage = 20;
        svc.orderTimeoutBars = 12;
        return svc;
    }

    private FiboRetracementStrategy fibo() {
        return new FiboRetracementStrategy(FiboParams.defaults(), List.of(SYM));
    }

    private StrategySignal limitLong(BigDecimal entry, BigDecimal sl, long barClose) {
        return new StrategySignal("FIBO", SYM, "LONG", true, entry, sl, entry.add(bd("100")),
                0.5, "test", barClose, "LIMIT");
    }

    @Test
    void flatToWorkingPlacesMakerLimit() {
        StubClient client = new StubClient();
        service(client).onSignal(SYM, limitLong(bd("3000"), bd("2950"), T0), fibo());

        assertEquals(1, client.placed.size());
        PlaceOrderRequest o = client.placed.get(0);
        assertEquals("LIMIT", o.getType());
        assertEquals("GTX", o.getTimeInForce(), "必须 POST_ONLY 强制 maker");
        assertEquals("BUY", o.getSide());
        assertEquals(0, o.getPrice().compareTo(bd("3000")));
    }

    @Test
    void reaffirmSameLegDoesNotReplace() {
        StubClient client = new StubClient();
        TestnetExecutionService svc = service(client);
        svc.onSignal(SYM, limitLong(bd("3000"), bd("2950"), T0), fibo());
        svc.onSignal(SYM, limitLong(bd("3000"), bd("2950"), T0 + BAR), fibo()); // 同腿(价同)、新 bar
        assertEquals(1, client.placed.size(), "同腿 reaffirm 不应重复挂单");
        assertTrue(client.canceled.isEmpty());
    }

    @Test
    void legChangeReplacesOrder() {
        StubClient client = new StubClient();
        TestnetExecutionService svc = service(client);
        svc.onSignal(SYM, limitLong(bd("3000"), bd("2950"), T0), fibo());
        svc.onSignal(SYM, limitLong(bd("2800"), bd("2750"), T0 + BAR), fibo()); // 换腿(价变)
        assertEquals(2, client.placed.size(), "换腿应撤旧挂新");
        assertEquals(1, client.canceled.size());
    }

    @Test
    void noSignalCancelsWorking() {
        StubClient client = new StubClient();
        TestnetExecutionService svc = service(client);
        svc.onSignal(SYM, limitLong(bd("3000"), bd("2950"), T0), fibo());
        svc.noSignal(SYM);
        assertEquals(1, client.canceled.size(), "腿失效应撤单");
        svc.onSignal(SYM, limitLong(bd("3000"), bd("2950"), T0 + 2 * BAR), fibo()); // 回 FLAT 可重挂
        assertEquals(2, client.placed.size());
    }

    @Test
    void fillPlacesSlAndTpThenCloseGoesFlat() {
        StubClient client = new StubClient();
        TestnetExecutionService svc = service(client);
        svc.onSignal(SYM, limitLong(bd("3000"), bd("2950"), T0), fibo());

        // tick：LIMIT 成交 → 挂 SL + TP → POSITION
        OrderResponse filled = new OrderResponse();
        filled.setStatus("FILLED");
        filled.setAvgPrice(bd("3000"));
        client.queryResult = filled;
        client.positionAmt = bd("0.3");            // 成交后有持仓
        svc.tick(SYM, T0 + 60_000L);

        assertEquals(3, client.placed.size(), "成交后应挂 SL + TP");
        PlaceOrderRequest sl = client.placed.get(1);
        PlaceOrderRequest tp = client.placed.get(2);
        assertEquals("STOP_MARKET", sl.getType());
        assertEquals("SELL", sl.getSide(), "多头平仓单为 SELL");
        assertEquals(0, sl.getStopPrice().compareTo(bd("2950")), "SL=止损价");
        assertTrue(sl.getClosePosition());
        assertEquals("TAKE_PROFIT_MARKET", tp.getType());
        assertEquals(0, tp.getStopPrice().compareTo(bd("3100")), "TP=信号止盈价(entry+100)");

        // 仍持仓：不应撤单
        svc.tick(SYM, T0 + 120_000L);
        assertFalse(client.cancelAllCalled);

        // 平仓 → 撤剩余条件单 → FLAT
        client.positionAmt = BigDecimal.ZERO;
        svc.tick(SYM, T0 + 180_000L);
        assertTrue(client.cancelAllCalled, "平仓后撤剩余条件单");
        svc.onSignal(SYM, limitLong(bd("3100"), bd("3050"), T0 + 10 * BAR), fibo()); // 回 FLAT 可重挂
        assertEquals(4, client.placed.size());
    }

    @Test
    void timeoutCancelsWorking() {
        StubClient client = new StubClient();
        TestnetExecutionService svc = service(client);
        svc.onSignal(SYM, limitLong(bd("3000"), bd("2950"), T0), fibo());
        OrderResponse stillNew = new OrderResponse();
        stillNew.setStatus("NEW");
        client.queryResult = stillNew;
        svc.tick(SYM, T0 + 12 * BAR);              // 满 orderTimeoutBars=12 根
        assertEquals(1, client.canceled.size(), "超时应撤单");
    }

    @Test
    void disabledDoesNothing() {
        StubClient client = new StubClient();
        TestnetExecutionService svc = service(client);
        svc.enabled = false;
        svc.onSignal(SYM, limitLong(bd("3000"), bd("2950"), T0), fibo());
        assertTrue(client.placed.isEmpty(), "关闭时不下单");
    }
}
