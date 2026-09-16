package com.mawai.wiibquant.external.binance.model;

import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 Binance 官方响应样例验证看板数据源 model 的字段映射零偏差。
 * 数据准确是监测看板第一要务，字段名写错会导致整个看板失真，故必测。
 */
class BinanceTestnetModelTest {

    /** GET /fapi/v1/userTrades 样例（Binance 文档原样） */
    @Test
    void userTradeDeserialization() {
        String json = """
                [{
                  "buyer": false,
                  "commission": "-0.07819010",
                  "commissionAsset": "USDT",
                  "id": 698759,
                  "maker": true,
                  "orderId": 25851813,
                  "price": "7819.01",
                  "qty": "0.002",
                  "quoteQty": "15.63802",
                  "realizedPnl": "-0.91539999",
                  "side": "SELL",
                  "positionSide": "SHORT",
                  "symbol": "BTCUSDT",
                  "time": 1569514978020
                }]""";
        List<UserTrade> trades = MAPPER.readValue(json, new TypeReference<List<UserTrade>>() {});
        assertEquals(1, trades.size());
        UserTrade t = trades.get(0);
        assertEquals(698759L, t.getId());
        assertEquals(25851813L, t.getOrderId());
        assertEquals("BTCUSDT", t.getSymbol());
        assertEquals("SELL", t.getSide());
        assertEquals(0, t.getPrice().compareTo(new BigDecimal("7819.01")));
        assertEquals(0, t.getQty().compareTo(new BigDecimal("0.002")));
        assertEquals(0, t.getRealizedPnl().compareTo(new BigDecimal("-0.91539999")));
        assertEquals(0, t.getCommission().compareTo(new BigDecimal("-0.07819010")));
        assertEquals("USDT", t.getCommissionAsset());
        assertTrue(t.getMaker(), "maker 标志必须正确解析（零滑点验证依赖它）");
        assertFalse(t.getBuyer());
        assertEquals(1569514978020L, t.getTime());
    }

    /** GET /fapi/v1/income 样例（混合 TRANSFER + COMMISSION + REALIZED_PNL） */
    @Test
    void incomeRecordDeserialization() {
        String json = """
                [
                  {"symbol":"","incomeType":"TRANSFER","income":"-0.37500000","asset":"USDT","info":"TRANSFER","time":1570608000000,"tranId":9689322392,"tradeId":""},
                  {"symbol":"BTCUSDT","incomeType":"COMMISSION","income":"-0.01000000","asset":"USDT","info":"COMMISSION","time":1570636800000,"tranId":9689322392,"tradeId":"2059192"},
                  {"symbol":"BTCUSDT","incomeType":"REALIZED_PNL","income":"1.23450000","asset":"USDT","info":"REALIZED_PNL","time":1570636800001,"tranId":9689322393,"tradeId":"2059193"}
                ]""";
        List<IncomeRecord> incomes = MAPPER.readValue(json, new TypeReference<List<IncomeRecord>>() {});
        assertEquals(3, incomes.size());

        IncomeRecord commission = incomes.get(1);
        assertEquals("BTCUSDT", commission.getSymbol());
        assertEquals("COMMISSION", commission.getIncomeType());
        assertEquals(0, commission.getIncome().compareTo(new BigDecimal("-0.01000000")));
        assertEquals("USDT", commission.getAsset());
        assertEquals("2059192", commission.getTradeId());

        IncomeRecord pnl = incomes.get(2);
        assertEquals("REALIZED_PNL", pnl.getIncomeType());
        assertEquals(0, pnl.getIncome().compareTo(new BigDecimal("1.23450000")));

        // 日净盈亏口径自检：sum(REALIZED_PNL + COMMISSION) = 1.2345 - 0.01 = 1.2245
        BigDecimal net = incomes.stream()
                .filter(i -> "REALIZED_PNL".equals(i.getIncomeType()) || "COMMISSION".equals(i.getIncomeType()))
                .map(IncomeRecord::getIncome)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, net.compareTo(new BigDecimal("1.22450000")));
    }

    /** POST /fapi/v1/order 样例：数值是字符串、布尔是 JSON 布尔，model 里没有的字段不能报错 */
    @Test
    void orderResponseDeserialization() {
        String json = """
                {
                  "clientOrderId": "testOrder", "cumQty": "0", "cumQuote": "0", "executedQty": "0",
                  "orderId": 22542179, "avgPrice": "0.00000", "origQty": "10", "price": "0",
                  "reduceOnly": false, "side": "BUY", "positionSide": "SHORT", "status": "NEW",
                  "stopPrice": "9300", "closePosition": true, "symbol": "BTCUSDT", "timeInForce": "GTD",
                  "type": "TRAILING_STOP_MARKET", "origType": "TRAILING_STOP_MARKET",
                  "activatePrice": "9020", "priceRate": "0.3", "updateTime": 1566818724722,
                  "workingType": "CONTRACT_PRICE", "priceProtect": false, "priceMatch": "NONE",
                  "selfTradePreventionMode": "NONE", "goodTillDate": 1693207680000, "notInModel": "x"
                }""";
        OrderResponse o = MAPPER.readValue(json, OrderResponse.class);
        assertEquals(22542179L, o.getOrderId());
        assertEquals(new BigDecimal("0.00000"), o.getAvgPrice());
        assertEquals(new BigDecimal("10"), o.getOrigQty());
        assertEquals(new BigDecimal("0.3"), o.getPriceRate());
        assertFalse(o.getReduceOnly());
        assertTrue(o.getClosePosition());
        assertEquals(1693207680000L, o.getGoodTillDate());
    }

    /** GET /fapi/v3/positionRisk 样例：unRealizedProfit 这种中间大写的字段名要对上 */
    @Test
    void positionRiskDeserialization() {
        String json = """
                [{"symbol":"ADAUSDT","positionSide":"BOTH","positionAmt":"30","entryPrice":"0.385",
                  "breakEvenPrice":"0.385077","markPrice":"0.41047590","unRealizedProfit":"0.76427700",
                  "liquidationPrice":"0","isolatedMargin":"0","notional":"12.31427700","marginAsset":"USDT",
                  "isolatedWallet":"0","initialMargin":"0.61571385","maintMargin":"0.08004280",
                  "positionInitialMargin":"0.61571385","openOrderInitialMargin":"0","adl":2,
                  "bidNotional":"0","askNotional":"0","updateTime":1720736417660}]""";
        PositionRisk p = MAPPER.readValue(json, new TypeReference<List<PositionRisk>>() {}).get(0);
        assertEquals(new BigDecimal("0.76427700"), p.getUnRealizedProfit());
        assertEquals(new BigDecimal("0.41047590"), p.getMarkPrice());
        assertEquals(2, p.getAdl());
        assertEquals(1720736417660L, p.getUpdateTime());
    }

    /** DELETE /fapi/v1/allOpenOrders：code 可能是字符串 */
    @Test
    void simpleAckStringCode() {
        SimpleAck ack = MAPPER.readValue("{\"code\":\"200\",\"msg\":\"The operation of cancel all open order is done.\"}",
                SimpleAck.class);
        assertEquals(200, ack.getCode());
    }
}
