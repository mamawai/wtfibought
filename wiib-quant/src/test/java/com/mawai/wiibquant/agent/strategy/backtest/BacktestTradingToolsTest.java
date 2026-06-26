package com.mawai.wiibquant.agent.strategy.backtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** T2: 验证 limit 进场 maker，TP/SCALE/SL 触发式平仓 taker，market 进场仍 taker。 */
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
    void limitEntryUsesLimitPriceAndMakerFee_tpExitTaker() {
        BacktestTradingTools t = tools();
        t.setCurrentPrice(new BigDecimal("100"));   // 现价100，但limit挂95
        var r = t.openPositionWithResult("LONG", BigDecimal.ONE, 5, "LIMIT",
                new BigDecimal("95"), new BigDecimal("90"), new BigDecimal("110"), "FIBO");
        assertThat(r.success()).isTrue();
        assertThat(t.getOpenPositions("BTCUSDT").getFirst().getEntryPrice()).isEqualByComparingTo("95");

        t.setCurrentBarIndex(1);
        t.tickBar(new BigDecimal("111"), new BigDecimal("100"), new BigDecimal("110"), 1); // high≥110 → TP
        var trade = t.getClosedTrades().getFirst();
        assertThat(trade.exitReason()).isEqualTo("TP");
        // fee = openMaker(95×万2) + closeTaker(110×万5)
        assertThat(trade.fee()).isEqualByComparingTo(
                new BigDecimal("95").multiply(MAKER).add(new BigDecimal("110").multiply(TAKER)));
    }

    @Test
    void limitEntryMaker_slExitTaker() {
        BacktestTradingTools t = tools();
        t.setCurrentPrice(new BigDecimal("100"));
        t.openPositionWithResult("LONG", BigDecimal.ONE, 5, "LIMIT",
                new BigDecimal("95"), new BigDecimal("90"), new BigDecimal("110"), "FIBO");

        t.setCurrentBarIndex(1);
        t.tickBar(new BigDecimal("96"), new BigDecimal("89"), new BigDecimal("90"), 1); // low≤90 → SL
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
        t.tickBar(new BigDecimal("96"), new BigDecimal("89"), new BigDecimal("90"), 1); // SL
        var trade = t.getClosedTrades().getFirst();
        // fee = openTaker(100×万5) + closeTaker(90×万5)
        assertThat(trade.fee()).isEqualByComparingTo(
                new BigDecimal("100").multiply(TAKER).add(new BigDecimal("90").multiply(TAKER)));
    }

    // ---- ST1: 盘中分批止盈 scale-out ----

    @Test
    void scaleOutPartialClosesAtTriggerWithTakerFee() {
        BacktestTradingTools t = tools();
        t.setCurrentPrice(new BigDecimal("100"));
        Long posId = t.openPositionWithResult("LONG", new BigDecimal("2"), 5, "MARKET",
                null, new BigDecimal("90"), new BigDecimal("130"), "FIBO").positionId();
        t.setScaleOut(posId, new BigDecimal("110"), 0.5);   // +1R 落袋 1/2 仓

        t.setCurrentBarIndex(1);
        t.tickBar(new BigDecimal("110"), new BigDecimal("100"), new BigDecimal("108"), 1); // high≥110 触发

        var trade = t.getClosedTrades().getFirst();
        assertThat(trade.exitReason()).isEqualTo("SCALE");
        assertThat(trade.exitPrice()).isEqualByComparingTo("110");
        assertThat(trade.quantity()).isEqualByComparingTo("1");
        // 触发式落袋走 taker：openTaker(100×万5 on 1) + closeTaker(110×万5 on 1)
        assertThat(trade.fee()).isEqualByComparingTo(
                new BigDecimal("100").multiply(TAKER).add(new BigDecimal("110").multiply(TAKER)));
        var pos = t.getOpenPositions("BTCUSDT").getFirst();
        assertThat(pos.getStatus()).isEqualTo("OPEN");
        assertThat(pos.getQuantity()).isEqualByComparingTo("1");                // 剩余仓续存
    }

    @Test
    void scaleOutIsOneShot() {
        BacktestTradingTools t = tools();
        t.setCurrentPrice(new BigDecimal("100"));
        Long posId = t.openPositionWithResult("LONG", new BigDecimal("2"), 5, "MARKET",
                null, new BigDecimal("90"), new BigDecimal("130"), "FIBO").positionId();
        t.setScaleOut(posId, new BigDecimal("110"), 0.5);

        t.setCurrentBarIndex(1);
        t.tickBar(new BigDecimal("110"), new BigDecimal("105"), new BigDecimal("108"), 1);
        t.setCurrentBarIndex(2);
        t.tickBar(new BigDecimal("112"), new BigDecimal("106"), new BigDecimal("109"), 2); // 再触不应二次落袋

        assertThat(t.getClosedTrades()).hasSize(1);
        assertThat(t.getOpenPositions("BTCUSDT").getFirst().getQuantity()).isEqualByComparingTo("1");
    }

    @Test
    void scaleOutNotTriggeredBelowLevel() {
        BacktestTradingTools t = tools();
        t.setCurrentPrice(new BigDecimal("100"));
        Long posId = t.openPositionWithResult("LONG", new BigDecimal("2"), 5, "MARKET",
                null, new BigDecimal("90"), new BigDecimal("130"), "FIBO").positionId();
        t.setScaleOut(posId, new BigDecimal("110"), 0.5);

        t.setCurrentBarIndex(1);
        t.tickBar(new BigDecimal("109"), new BigDecimal("100"), new BigDecimal("105"), 1); // high<110

        assertThat(t.getClosedTrades()).isEmpty();
        assertThat(t.getOpenPositions("BTCUSDT").getFirst().getQuantity()).isEqualByComparingTo("2");
    }
}
