package com.mawai.wiibquant.agent.binance.model;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

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
        List<UserTrade> trades = JSON.parseArray(json, UserTrade.class);
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
        List<IncomeRecord> incomes = JSON.parseArray(json, IncomeRecord.class);
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
}
