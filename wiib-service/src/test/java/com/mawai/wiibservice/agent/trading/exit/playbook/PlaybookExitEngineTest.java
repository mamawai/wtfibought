package com.mawai.wiibservice.agent.trading.exit.playbook;

import com.mawai.wiibservice.agent.trading.backtest.BacktestTradingTools;
import com.mawai.wiibservice.agent.trading.DeterministicTradingExecutor;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPath;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPlan;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPlanFactory;
import com.mawai.wiibservice.agent.trading.MarketContext;
import com.mawai.wiibservice.agent.trading.SymbolProfile;
import com.mawai.wiibservice.agent.trading.TradingDecisionContext;
import com.mawai.wiibservice.agent.trading.TradingExecutionState;
import com.mawai.wiibservice.agent.trading.ops.TradingOperations;
import com.mawai.wiibservice.agent.trading.TradingRuntimeToggles;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybookExitEngineTest {

    private final PlaybookExitEngine engine = new PlaybookExitEngine();

    @Test
    void breakoutFullCloseClearsExitPlanOnlyAfterTradeSuccess() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 7, 4, 0);
        TradingExecutionState state = stateAt(now);
        ExitPlan plan = breakoutPlan(now.minusMinutes(6));
        state.putExitPlan(1L, plan);
        StubTools tools = new StubTools();

        DeterministicTradingExecutor.ExecutionResult result = engine.evaluate(
                context("100100", "55", "42", "sideways", 1, 1, now.minusMinutes(1),
                        position("LONG", "100000", "99000", "105000", "1"), state, tools),
                true);

        assertThat(result.action()).isEqualTo("PLAYBOOK_EXIT_FULL");
        assertThat(result.reasoning()).contains("BREAKOUT_BB_RETURN_NEUTRAL");
        assertThat(tools.closeQuantity).isEqualByComparingTo("1");
        assertThat(state.getExitPlan(1L)).isNull();
    }

    @Test
    void failedFullCloseKeepsExitPlan() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 7, 4, 0);
        TradingExecutionState state = stateAt(now);
        ExitPlan plan = breakoutPlan(now.minusMinutes(6));
        state.putExitPlan(1L, plan);
        StubTools tools = new StubTools();
        tools.closeSuccess = false;

        DeterministicTradingExecutor.ExecutionResult result = engine.evaluate(
                context("100100", "55", "42", "sideways", 1, 1, now.minusMinutes(1),
                        position("LONG", "100000", "99000", "105000", "1"), state, tools),
                true);

        assertThat(result.action()).isEqualTo("HOLD");
        assertThat(result.reasoning()).contains("全平失败");
        assertThat(state.getExitPlan(1L)).isSameAs(plan);
    }

    @Test
    void staleForecastShieldsIndicatorExitButKeepsHolding() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 7, 4, 0);
        TradingExecutionState state = stateAt(now);
        state.putExitPlan(1L, breakoutPlan(now.minusMinutes(6)));

        DeterministicTradingExecutor.ExecutionResult result = engine.evaluate(
                context("100100", "55", "42", "sideways", 1, 1, now.minusMinutes(6),
                        position("LONG", "100000", "99000", "105000", "1"), state, new StubTools()),
                true);

        assertThat(result.action()).isEqualTo("HOLD");
        assertThat(result.reasoning()).contains("指标护盾");
        assertThat(result.reasoning()).doesNotContain("BREAKOUT_BB_RETURN_NEUTRAL");
    }

    @Test
    void staleForecastStillAllowsPurePriceBreakevenMove() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 7, 4, 0);
        TradingExecutionState state = stateAt(now);
        ExitPlan plan = breakoutPlan(now.minusMinutes(16));
        state.putExitPlan(1L, plan);
        StubTools tools = new StubTools();

        DeterministicTradingExecutor.ExecutionResult result = engine.evaluate(
                context("101000", "55", "42", "sideways", 1, 1, now.minusMinutes(6),
                        position("LONG", "100000", "99000", "105000", "1"), state, tools),
                true);

        assertThat(result.action()).isEqualTo("PLAYBOOK_MOVE_SL");
        assertThat(tools.stopLossPrice).isEqualByComparingTo("100100.00");
        assertThat(plan.breakevenDone()).isTrue();
    }

    @Test
    void trendPartialMarksMilestoneAndSyncsRemainingRiskOrders() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 7, 4, 0);
        TradingExecutionState state = stateAt(now);
        ExitPlan plan = trendPlan(now.minusMinutes(40));
        plan.markBreakevenDone();
        state.putExitPlan(1L, plan);
        StubTools tools = new StubTools();

        DeterministicTradingExecutor.ExecutionResult result = engine.evaluate(
                context("103000", "80", "60", "sideways", 1, 1, now.minusMinutes(1),
                        position("LONG", "100000", "102200", "105000", "1"), state, tools),
                true);

        assertThat(result.action()).isEqualTo("PLAYBOOK_EXIT_PARTIAL");
        assertThat(tools.closeQuantity).isEqualByComparingTo("0.30000000");
        assertThat(tools.stopLossQuantity).isEqualByComparingTo("0.70000000");
        assertThat(tools.takeProfitQuantity).isEqualByComparingTo("0.70000000");
        assertThat(plan.isPartialDoneAtR(300)).isTrue();
    }

    @Test
    void partialSyncFailureIsReportedButMilestoneStillMarked() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 7, 4, 0);
        TradingExecutionState state = stateAt(now);
        ExitPlan plan = trendPlan(now.minusMinutes(40));
        plan.markBreakevenDone();
        state.putExitPlan(1L, plan);
        StubTools tools = new StubTools();
        tools.takeProfitSuccess = false;

        DeterministicTradingExecutor.ExecutionResult result = engine.evaluate(
                context("103000", "80", "60", "sideways", 1, 1, now.minusMinutes(1),
                        position("LONG", "100000", "102200", "105000", "1"), state, tools),
                true);

        assertThat(result.action()).isEqualTo("PLAYBOOK_EXIT_PARTIAL");
        assertThat(result.reasoning()).contains("剩余风险单同步失败");
        assertThat(result.executionLog()).contains("设置止盈失败");
        assertThat(tools.stopLossQuantity).isEqualByComparingTo("0.70000000");
        assertThat(tools.takeProfitQuantity).isEqualByComparingTo("0.70000000");
        assertThat(plan.isPartialDoneAtR(300)).isTrue();
    }

    @Test
    void backtestTrendPartialRiskOrdersMatchRemainingPositionQuantity() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 7, 4, 0);
        BacktestTradingTools tools = new BacktestTradingTools(bd("100000"), "BTCUSDT");
        tools.setCurrentTime(now.minusMinutes(40));
        tools.setCurrentPrice(bd("100000"));
        tools.openPosition("LONG", bd("1"), 10, "MARKET", null,
                bd("99000"), bd("105000"), "LEGACY_TREND");
        tools.setCurrentTime(now);
        tools.setCurrentPrice(bd("103000"));

        FuturesPositionDTO pos = tools.getOpenPositions("BTCUSDT").getFirst();
        tools.setStopLoss(pos.getId(), bd("102200"), bd("1"));
        TradingExecutionState state = stateAt(now);
        ExitPlan plan = trendPlan(now.minusMinutes(40));
        plan.markBreakevenDone();
        state.putExitPlan(pos.getId(), plan);

        DeterministicTradingExecutor.ExecutionResult result = engine.evaluate(
                context("103000", "80", "60", "sideways", 1, 1, now.minusMinutes(1),
                        pos, state, tools),
                true);

        FuturesPositionDTO remaining = tools.getOpenPositions("BTCUSDT").getFirst();
        assertThat(result.action()).isEqualTo("PLAYBOOK_EXIT_PARTIAL");
        assertThat(remaining.getQuantity()).isEqualByComparingTo("0.70000000");
        assertThat(remaining.getStopLosses().getFirst().getQuantity()).isEqualByComparingTo("0.70000000");
        assertThat(remaining.getTakeProfits().getFirst().getQuantity()).isEqualByComparingTo("0.70000000");
    }

    @Test
    void backtestMrHalfRiskOrdersMatchRemainingPositionQuantity() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 7, 4, 0);
        BacktestTradingTools tools = new BacktestTradingTools(bd("100000"), "BTCUSDT");
        tools.setCurrentTime(now.minusMinutes(18));
        tools.setCurrentPrice(bd("100000"));
        tools.openPosition("LONG", bd("1"), 10, "MARKET", null,
                bd("99000"), bd("102000"), "MR");
        tools.setCurrentTime(now);
        tools.setCurrentPrice(bd("100300"));

        FuturesPositionDTO pos = tools.getOpenPositions("BTCUSDT").getFirst();
        TradingExecutionState state = stateAt(now);
        state.putExitPlan(pos.getId(), mrPlan(now.minusMinutes(18)));

        DeterministicTradingExecutor.ExecutionResult result = engine.evaluate(
                context("100300", "30", "42", "sideways", 0, 0, now.minusMinutes(1),
                        pos, state, tools),
                true);

        FuturesPositionDTO remaining = tools.getOpenPositions("BTCUSDT").getFirst();
        assertThat(result.action()).isEqualTo("PLAYBOOK_EXIT_PARTIAL");
        assertThat(remaining.getQuantity()).isEqualByComparingTo("0.50000000");
        assertThat(remaining.getStopLosses().getFirst().getPrice()).isEqualByComparingTo("100000");
        assertThat(remaining.getStopLosses().getFirst().getQuantity()).isEqualByComparingTo("0.50000000");
        assertThat(remaining.getTakeProfits().getFirst().getQuantity()).isEqualByComparingTo("0.50000000");
    }

    @Test
    void mrHalfPathClosesHalfMovesStopAndMarksMrState() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 7, 4, 0);
        TradingExecutionState state = stateAt(now);
        ExitPlan plan = mrPlan(now.minusMinutes(18));
        state.putExitPlan(1L, plan);
        StubTools tools = new StubTools();

        DeterministicTradingExecutor.ExecutionResult result = engine.evaluate(
                context("100300", "30", "42", "sideways", 0, 0, now.minusMinutes(1),
                        position("LONG", "100000", "99000", "102000", "1"), state, tools),
                true);

        assertThat(result.action()).isEqualTo("PLAYBOOK_EXIT_PARTIAL");
        assertThat(tools.closeQuantity).isEqualByComparingTo("0.50000000");
        assertThat(tools.stopLossPrice).isEqualByComparingTo("100000");
        assertThat(tools.stopLossQuantity).isEqualByComparingTo("0.50000000");
        assertThat(plan.mrMidlineHalfDone()).isTrue();
        assertThat(plan.breakevenDone()).isTrue();
    }

    @Test
    void lowConfidenceFlagShieldsIndicatorExit() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 7, 4, 0);
        TradingExecutionState state = stateAt(now);
        state.putExitPlan(1L, breakoutPlan(now.minusMinutes(6)));

        DeterministicTradingExecutor.ExecutionResult result = engine.evaluate(
                context("100100", "55", "42", "sideways", 1, 1, now.minusMinutes(1),
                        position("LONG", "100000", "99000", "105000", "1"), state, new StubTools(),
                        "\"LOW_CONFIDENCE\""),
                true);

        assertThat(result.action()).isEqualTo("HOLD");
        assertThat(result.reasoning()).contains("指标护盾");
    }

    @Test
    void blockedPlaybookHoldPreventsEntryEvaluation() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 7, 4, 0);
        TradingExecutionState state = stateAt(now);
        state.putExitPlan(1L, breakoutPlan(now.minusMinutes(6)));
        PlaybookExitEngine engine = new PlaybookExitEngine(List.of(new BlockingBreakoutPlaybook()));

        PlaybookExitEngine.PlaybookEvaluation evaluation = engine.evaluateDetailed(
                context("100800", "88", "42", "sideways", 1, 1, now.minusMinutes(1),
                        position("LONG", "100000", "99000", "105000", "1"), state, new StubTools()),
                true);

        assertThat(evaluation.result().action()).isEqualTo("HOLD");
        assertThat(evaluation.result().reasoning()).contains("mock-blocked");
        assertThat(evaluation.holdKind()).isEqualTo(PlaybookExitEngine.HoldKind.BLOCKED);
        assertThat(evaluation.entryEvaluationAllowed()).isFalse();
    }

    private static TradingDecisionContext context(String price, String bollPb, String rsi,
                                                  String closeTrendClosed3, int ma1h, int ma15m,
                                                  LocalDateTime forecastTime,
                                                  FuturesPositionDTO position,
                                                  TradingExecutionState state,
                                                  TradingOperations tools) {
        return context(price, bollPb, rsi, closeTrendClosed3, ma1h, ma15m, forecastTime,
                position, state, tools, "");
    }

    private static TradingDecisionContext context(String price, String bollPb, String rsi,
                                                  String closeTrendClosed3, int ma1h, int ma15m,
                                                  LocalDateTime forecastTime,
                                                  FuturesPositionDTO position,
                                                  TradingExecutionState state,
                                                  TradingOperations tools,
                                                  String qualityFlagsJson) {
        QuantForecastCycle forecast = new QuantForecastCycle();
        forecast.setForecastTime(forecastTime);
        forecast.setSnapshotJson("""
                {
                  "atr5m": 400,
                  "qualityFlags": [%s],
                  "indicatorsByTimeframe": {
                    "5m": {
                      "boll_pb": %s,
                      "rsi14": %s,
                      "close_trend_recent_3_closed": "%s",
                      "ema20": 99000,
                      "macd_cross": "golden",
                      "macd_dif": 2,
                      "macd_dea": 1
                    },
                    "15m": {"ma_alignment": %d},
                    "1h": {"ma_alignment": %d}
                  }
                }
                """.formatted(qualityFlagsJson, bollPb, rsi, closeTrendClosed3, ma15m, ma1h));
        MarketContext market = MarketContext.parse(forecast, bd(price));
        return new TradingDecisionContext("BTCUSDT", null, List.of(position), forecast, List.of(), List.of(),
                bd(price), bd(price), bd("100000"), tools, state,
                new TradingRuntimeToggles(true, true), SymbolProfile.of("BTCUSDT"), market, forecastTime);
    }

    private static FuturesPositionDTO position(String side, String entry, String sl, String tp, String quantity) {
        FuturesPositionDTO pos = new FuturesPositionDTO();
        pos.setId(1L);
        pos.setSide(side);
        pos.setEntryPrice(bd(entry));
        pos.setQuantity(bd(quantity));
        pos.setStatus("OPEN");
        pos.setStopLosses(List.of(new FuturesStopLoss("sl-1", bd(sl), bd(quantity))));
        pos.setTakeProfits(List.of(new FuturesTakeProfit("tp-1", bd(tp), bd(quantity))));
        return pos;
    }

    private static TradingExecutionState stateAt(LocalDateTime now) {
        TradingExecutionState state = new TradingExecutionState();
        state.setMockNowMs(now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        return state;
    }

    private static ExitPlan breakoutPlan(LocalDateTime createdAt) {
        return ExitPlanFactory.fromEntry("BREAKOUT", "LONG", bd("100000"), bd("99000"),
                bd("400"), 98.0, 55.0, 1, 1, createdAt);
    }

    private static ExitPlan trendPlan(LocalDateTime createdAt) {
        return ExitPlanFactory.fromEntry("LEGACY_TREND", "LONG", bd("100000"), bd("99000"),
                bd("400"), 60.0, 55.0, 1, 1, createdAt);
    }

    private static ExitPlan mrPlan(LocalDateTime createdAt) {
        return ExitPlanFactory.fromEntry("MR", "LONG", bd("100000"), bd("99000"),
                bd("400"), 10.0, 38.0, 0, 0, createdAt);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static final class StubTools implements TradingOperations {
        boolean closeSuccess = true;
        boolean stopSuccess = true;
        boolean takeProfitSuccess = true;
        BigDecimal closeQuantity;
        BigDecimal stopLossPrice;
        BigDecimal stopLossQuantity;
        BigDecimal takeProfitPrice;
        BigDecimal takeProfitQuantity;

        @Override
        public String openPosition(String side, BigDecimal quantity, Integer leverage, String orderType,
                                   BigDecimal limitPrice, BigDecimal stopLossPrice,
                                   BigDecimal takeProfitPrice, String memo) {
            return "开仓成功 posId=1";
        }

        @Override
        public String closePosition(Long positionId, BigDecimal quantity) {
            this.closeQuantity = quantity;
            return closeSuccess ? "平仓成功" : "平仓失败";
        }

        @Override
        public String setStopLoss(Long positionId, BigDecimal stopLossPrice, BigDecimal quantity) {
            this.stopLossPrice = stopLossPrice;
            this.stopLossQuantity = quantity;
            return stopSuccess ? "设置止损成功" : "设置止损失败";
        }

        @Override
        public String setTakeProfit(Long positionId, BigDecimal takeProfitPrice, BigDecimal quantity) {
            this.takeProfitPrice = takeProfitPrice;
            this.takeProfitQuantity = quantity;
            return takeProfitSuccess ? "设置止盈成功" : "设置止盈失败";
        }
    }

    private static final class BlockingBreakoutPlaybook implements ExitPlaybook {
        @Override
        public ExitPath path() {
            return ExitPath.BREAKOUT;
        }

        @Override
        public ExitPlaybookDecision evaluate(TradingDecisionContext decision,
                                             FuturesPositionDTO position,
                                             ExitPlan plan,
                                             LocalDateTime now) {
            return ExitPlaybookDecision.holdBlocked("mock-blocked");
        }
    }
}
