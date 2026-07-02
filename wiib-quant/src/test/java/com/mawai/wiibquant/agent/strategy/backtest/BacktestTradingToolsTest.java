package com.mawai.wiibquant.agent.strategy.backtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 费率分账验证：limit 进场 maker、market 进场 taker；TP 平仓 maker 限价、SL 触发式平仓 taker。 */
class BacktestTradingToolsTest {

    private static final BigDecimal MAKER = new BigDecimal("0.0002");
    private static final BigDecimal TAKER = new BigDecimal("0.0005");

    private BacktestTradingTools tools() {
        BacktestTradingTools t = new BacktestTradingTools(new BigDecimal("100000"), "BTCUSDT");
        t.setCurrentTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        t.setCurrentBarIndex(0);
        return t;
    }

    @Test
    void limitEntryUsesLimitPriceAndMakerFee_tpExitMaker() {
        BacktestTradingTools t = tools();
        t.setCurrentPrice(new BigDecimal("100"));   // 现价100，但limit挂95
        var r = t.openPositionWithResult("LONG", BigDecimal.ONE, 5, "LIMIT",
                new BigDecimal("95"), new BigDecimal("90"), new BigDecimal("110"), "FIBO");
        assertThat(r.success()).isTrue();
        assertThat(t.getOpenPositions("BTCUSDT").getFirst().getEntryPrice()).isEqualByComparingTo("95");

        t.setCurrentBarIndex(1);
        t.tickBar(new BigDecimal("105"), new BigDecimal("111"), new BigDecimal("100"), new BigDecimal("110"), 1); // high≥110 → TP
        var trade = t.getClosedTrades().getFirst();
        assertThat(trade.exitReason()).isEqualTo("TP");
        // fee = openMaker(95×万2) + closeMaker(110×万2)：TP 挂 maker 限价
        assertThat(trade.fee()).isEqualByComparingTo(
                new BigDecimal("95").multiply(MAKER).add(new BigDecimal("110").multiply(MAKER)));
    }

    @Test
    void limitEntryMaker_slExitTaker() {
        BacktestTradingTools t = tools();
        t.setCurrentPrice(new BigDecimal("100"));
        t.openPositionWithResult("LONG", BigDecimal.ONE, 5, "LIMIT",
                new BigDecimal("95"), new BigDecimal("90"), new BigDecimal("110"), "FIBO");

        t.setCurrentBarIndex(1);
        t.tickBar(new BigDecimal("95"), new BigDecimal("96"), new BigDecimal("89"), new BigDecimal("90"), 1); // low≤90 → SL
        var trade = t.getClosedTrades().getFirst();
        assertThat(trade.exitReason()).isEqualTo("SL");
        // fee = openMaker(95×万2) + closeTaker(90×万5)
        assertThat(trade.fee()).isEqualByComparingTo(
                new BigDecimal("95").multiply(MAKER).add(new BigDecimal("90").multiply(TAKER)));
    }

    @Test
    void marketEntryUsesCurrentPriceAndTakerFee() {
        BacktestTradingTools t = tools();
        t.setCurrentPrice(new BigDecimal("100"));
        t.openPositionWithResult("LONG", BigDecimal.ONE, 5, "MARKET",
                null, new BigDecimal("90"), new BigDecimal("110"), "FIBO");
        assertThat(t.getOpenPositions("BTCUSDT").getFirst().getEntryPrice()).isEqualByComparingTo("100");

        t.setCurrentBarIndex(1);
        t.tickBar(new BigDecimal("95"), new BigDecimal("96"), new BigDecimal("89"), new BigDecimal("90"), 1); // SL
        var trade = t.getClosedTrades().getFirst();
        // fee = openTaker(100×万5) + closeTaker(90×万5)
        assertThat(trade.fee()).isEqualByComparingTo(
                new BigDecimal("100").multiply(TAKER).add(new BigDecimal("90").multiply(TAKER)));
    }

}
