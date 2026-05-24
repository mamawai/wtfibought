package com.mawai.wiibservice.agent.trading.entry;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibservice.agent.trading.DeterministicTradingExecutor;
import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyCandidate;
import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyContext;
import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyResult;
import com.mawai.wiibservice.agent.trading.entry.strategy.BreakoutEntryStrategy;
import com.mawai.wiibservice.agent.trading.entry.strategy.EntryStrategy;
import com.mawai.wiibservice.agent.trading.entry.strategy.MeanReversionEntryStrategy;
import com.mawai.wiibservice.agent.trading.entry.strategy.TrendContinuationEntryStrategy;
import com.mawai.wiibservice.agent.trading.ops.TradingOperations;
import com.mawai.wiibservice.agent.trading.runtime.MarketContext;
import com.mawai.wiibservice.agent.trading.runtime.SymbolProfile;
import com.mawai.wiibservice.agent.trading.runtime.TradingDecisionContext;
import com.mawai.wiibservice.agent.trading.runtime.TradingExecutionState;
import com.mawai.wiibservice.agent.trading.runtime.TradingRuntimeToggles;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.PATH_BREAKOUT;
import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.PATH_LEGACY_TREND;
import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.PATH_MA_SLOPE;
import static org.assertj.core.api.Assertions.assertThat;

class EntryDecisionEngineTest {

    @Test
    void entryStrategyDefaultKindIsDirectionReceiver() {
        EntryStrategy strategy = new BreakoutEntryStrategy();

        assertThat(strategy.kind()).isEqualTo(EntryStrategy.StrategyKind.DIRECTION_RECEIVER);
        assertThat(strategy.path()).isEqualTo(PATH_BREAKOUT);
    }

    @Test
    void normalizesConfiguredStrategyAliases() {
        assertThat(EntryDecisionEngine.normalizeEnabledStrategyPaths(
                List.of("PATH_BREAKOUT", "mean_reversion", "TREND", "path_ma_slope")))
                .containsExactly("BREAKOUT", "MR", "LEGACY_TREND", "MA_SLOPE");
    }

    @Test
    void emptyEnabledStrategiesReturnsHold() {
        StubTools tools = new StubTools();
        EntryDecisionEngine engine = engine(List.of());

        DeterministicTradingExecutor.ExecutionResult result = engine.evaluate(
                decision(trendSnapshot(), signal("LONG", "0.75"), tools));

        assertThat(result.action()).isEqualTo("HOLD");
        assertThat(result.reasoning()).contains("无启用入场策略");
        assertThat(tools.openCount).isZero();
    }

    @Test
    void unknownEnabledStrategyReturnsHoldWithReason() {
        StubTools tools = new StubTools();
        EntryDecisionEngine engine = engine(List.of("PATH_NOT_EXISTS"));

        DeterministicTradingExecutor.ExecutionResult result = engine.evaluate(
                decision(trendSnapshot(), signal("LONG", "0.75"), tools));

        assertThat(result.action()).isEqualTo("HOLD");
        assertThat(result.reasoning()).contains("未注册策略: PATH_NOT_EXISTS");
        assertThat(tools.openCount).isZero();
    }

    @Test
    void onlyEnabledStrategiesAreCalled() {
        StubTools tools = new StubTools();
        EntryDecisionEngine engine = engine(List.of(PATH_BREAKOUT));

        DeterministicTradingExecutor.ExecutionResult result = engine.evaluate(
                decision(trendSnapshot(), signal("LONG", "0.75"), tools));

        assertThat(result.action()).isEqualTo("HOLD");
        assertThat(result.reasoning()).contains("成交量不足");
        assertThat(result.reasoning()).doesNotContain(PATH_LEGACY_TREND);
        assertThat(tools.openCount).isZero();
    }

    @Test
    void referenceShortDoesNotBlockLongCandidateAndScalesRisk() {
        StubTools tools = new StubTools();
        EntryDecisionEngine engine = engine(List.of(PATH_LEGACY_TREND));

        DeterministicTradingExecutor.ExecutionResult result = engine.evaluate(
                decision(trendSnapshot(), signal("SHORT", "0.75"), tools));

        assertThat(result.action()).isEqualTo("OPEN_LONG");
        assertThat(result.reasoning()).contains("scale=0.85");
        assertThat(tools.openCount).isEqualTo(1);
        assertThat(tools.lastSide).isEqualTo("LONG");
    }

    @Test
    void noTradeReferenceCanStillOpenWithReducedScale() {
        StubTools tools = new StubTools();
        EntryDecisionEngine engine = engine(List.of(PATH_LEGACY_TREND));

        DeterministicTradingExecutor.ExecutionResult result = engine.evaluate(
                decision(trendSnapshot(), signal("NO_TRADE", "0.75"), tools));

        assertThat(result.action()).isEqualTo("OPEN_LONG");
        assertThat(result.reasoning()).contains("scale=0.85");
        assertThat(tools.openCount).isEqualTo(1);
    }

    @Test
    void autonomousStrategyRunsOnceAndCarriesItsOwnSide() {
        StubTools tools = new StubTools();
        AutoEntryStrategy auto = new AutoEntryStrategy();
        EntryDecisionEngine engine = new EntryDecisionEngine(List.of(auto), List.of("AUTO_TEST"));

        DeterministicTradingExecutor.ExecutionResult result = engine.evaluate(
                decision(trendSnapshot(), signal("SHORT", "0.75"), tools));

        assertThat(result.action()).isEqualTo("OPEN_LONG");
        assertThat(auto.calls).isEqualTo(1);
        assertThat(auto.lastSymbol).isEqualTo("BTCUSDT");
        assertThat(auto.lastInterval).isEqualTo(KlineInterval.M5);
        assertThat(tools.lastSide).isEqualTo("LONG");
    }

    @Test
    void maSlopeCandidateCanOpenEvenWhenExistingMaSlopePositionIsOpen() {
        StubTools tools = new StubTools();
        AutoEntryStrategy auto = new AutoEntryStrategy(PATH_MA_SLOPE);
        EntryDecisionEngine engine = new EntryDecisionEngine(List.of(auto), List.of(PATH_MA_SLOPE));

        DeterministicTradingExecutor.ExecutionResult result = engine.evaluate(
                decision(trendSnapshot(), signal("LONG", "0.75"), tools,
                        List.of(openPosition(PATH_MA_SLOPE, "LONG", bd("99800")))));

        assertThat(result.action()).isEqualTo("OPEN_LONG");
        assertThat(auto.calls).isEqualTo(1);
        assertThat(tools.openCount).isEqualTo(1);
    }

    @Test
    void maSlopeCandidateRejectsNearDuplicateSameSideScaleIn() {
        StubTools tools = new StubTools();
        AutoEntryStrategy auto = new AutoEntryStrategy(PATH_MA_SLOPE);
        EntryDecisionEngine engine = new EntryDecisionEngine(List.of(auto), List.of(PATH_MA_SLOPE));

        DeterministicTradingExecutor.ExecutionResult result = engine.evaluate(
                decision(trendSnapshot(), signal("LONG", "0.75"), tools,
                        List.of(openPosition(PATH_MA_SLOPE, "LONG", bd("99950")))));

        assertThat(result.action()).isEqualTo("HOLD");
        assertThat(result.reasoning()).contains("同向加仓距离不足");
        assertThat(auto.calls).isEqualTo(1);
        assertThat(tools.openCount).isZero();
    }

    @Test
    void maSlopeCandidateCanOpenOppositeSideWhenExistingMaSlopePositionIsOpen() {
        StubTools tools = new StubTools();
        AutoEntryStrategy auto = new AutoEntryStrategy(PATH_MA_SLOPE, 20, "SHORT");
        EntryDecisionEngine engine = new EntryDecisionEngine(List.of(auto), List.of(PATH_MA_SLOPE));

        DeterministicTradingExecutor.ExecutionResult result = engine.evaluate(
                decision(trendSnapshot(), signal("NO_TRADE", "0.75"), tools,
                        List.of(openPosition(PATH_MA_SLOPE, "LONG", bd("100000")))));

        assertThat(result.action()).isEqualTo("OPEN_SHORT");
        assertThat(auto.calls).isEqualTo(1);
        assertThat(tools.openCount).isEqualTo(1);
        assertThat(tools.lastSide).isEqualTo("SHORT");
    }

    @Test
    void maSlopeUsesOwnLeverageCapInsteadOfReferenceSignalCap() {
        StubTools tools = new StubTools();
        AutoEntryStrategy auto = new AutoEntryStrategy(PATH_MA_SLOPE, 20);
        EntryDecisionEngine engine = new EntryDecisionEngine(List.of(auto), List.of(PATH_MA_SLOPE));

        DeterministicTradingExecutor.ExecutionResult result = engine.evaluate(
                decision(trendSnapshot(), signal("NO_TRADE", "0.75"), tools));

        assertThat(result.action()).isEqualTo("OPEN_LONG");
        assertThat(tools.lastLeverage).isEqualTo(20);
    }

    private static EntryDecisionEngine engine(List<String> enabledStrategies) {
        return new EntryDecisionEngine(List.of(
                new BreakoutEntryStrategy(),
                new MeanReversionEntryStrategy(),
                new TrendContinuationEntryStrategy()
        ), enabledStrategies);
    }

    private static TradingDecisionContext decision(String snapshot, QuantSignalDecision signal, StubTools tools) {
        return decision(snapshot, signal, tools, List.of());
    }

    private static TradingDecisionContext decision(String snapshot, QuantSignalDecision signal, StubTools tools,
                                                   List<FuturesPositionDTO> positions) {
        QuantForecastCycle forecast = forecast(snapshot);
        MarketContext market = MarketContext.parse(forecast, bd("100000"), KlineInterval.M5);
        return new TradingDecisionContext(
                "BTCUSDT",
                user(),
                positions,
                forecast,
                List.of(signal),
                List.of(),
                bd("100000"),
                bd("100000"),
                bd("100000"),
                tools,
                new TradingExecutionState(),
                new TradingRuntimeToggles(true, false),
                profile(),
                market,
                null);
    }

    private static FuturesPositionDTO openPosition(String memo) {
        return openPosition(memo, "LONG");
    }

    private static FuturesPositionDTO openPosition(String memo, String side) {
        return openPosition(memo, side, null);
    }

    private static FuturesPositionDTO openPosition(String memo, String side, BigDecimal entryPrice) {
        FuturesPositionDTO position = new FuturesPositionDTO();
        position.setId(1L);
        position.setSymbol("BTCUSDT");
        position.setStatus("OPEN");
        position.setSide(side);
        position.setEntryPrice(entryPrice);
        position.setMemo(memo);
        return position;
    }

    private static QuantForecastCycle forecast(String snapshot) {
        QuantForecastCycle cycle = new QuantForecastCycle();
        cycle.setCycleId("test-cycle");
        cycle.setSymbol("BTCUSDT");
        cycle.setSnapshotJson(snapshot);
        return cycle;
    }

    private static QuantSignalDecision signal(String direction, String confidence) {
        QuantSignalDecision signal = new QuantSignalDecision();
        signal.setHorizon("0_10");
        signal.setDirection(direction);
        signal.setConfidence(bd(confidence));
        signal.setMaxLeverage(10);
        return signal;
    }

    private static User user() {
        User user = new User();
        user.setBalance(bd("100000"));
        user.setFrozenBalance(BigDecimal.ZERO);
        return user;
    }

    private static SymbolProfile profile() {
        return new SymbolProfile(
                2.0, 3.0, 2.0, 2.0, 3.0, 2.0, 3.0,
                0.5, 1.5, 0.001, 0.12, 1.5, 0.8,
                5.0, 5.0);
    }

    private static String trendSnapshot() {
        return """
                {
                  "regime": "TREND_UP",
                  "atr": 400,
                  "bollSqueeze": false,
                  "bidAskImbalance": 0,
                  "takerBuySellPressure": 0,
                  "lsrExtreme": 0,
                  "qualityFlags": [],
                  "indicatorsByTimeframe": {
                    "5m": {
                      "rsi14": 55,
                      "macd_cross": "golden",
                      "macd_hist_trend": "rising_3",
                      "macd_dif": 2,
                      "macd_dea": 1,
                      "ema20": 99900,
                      "ma_alignment": 1,
                      "close_trend": "rising",
                      "boll_pb": 60,
                      "boll_bandwidth": 4,
                      "volume_ratio": 1.0
                    },
                    "15m": {"ma_alignment": 1, "rsi14": 55},
                    "1h": {"ma_alignment": 1}
                  }
                }
                """;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static final class AutoEntryStrategy implements EntryStrategy {
        private final String path;
        private final int maxLeverage;
        private final String side;
        int calls;
        String lastSymbol;
        KlineInterval lastInterval;

        private AutoEntryStrategy() {
            this("AUTO_TEST");
        }

        private AutoEntryStrategy(String path) {
            this(path, 10);
        }

        private AutoEntryStrategy(String path, int maxLeverage) {
            this(path, maxLeverage, "LONG");
        }

        private AutoEntryStrategy(String path, int maxLeverage, String side) {
            this.path = path;
            this.maxLeverage = maxLeverage;
            this.side = side;
        }

        @Override
        public StrategyKind kind() {
            return StrategyKind.DIRECTION_AUTONOMOUS;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public EntryStrategyResult build(EntryStrategyContext input) {
            calls++;
            lastSymbol = input.symbol();
            lastInterval = input.decisionInterval();
            BigDecimal atr = input.market().atr;
            return EntryStrategyResult.accept(new EntryStrategyCandidate(
                    path(), "自动方向测试", side, true, 10.0,
                    atr.multiply(bd("2")), atr.multiply(bd("3")),
                    maxLeverage, 1.0, "auto-test"));
        }
    }

    private static class StubTools implements TradingOperations {
        int openCount;
        String lastSide;
        Integer lastLeverage;

        @Override
        public String openPosition(String side, BigDecimal quantity, Integer leverage, String orderType,
                                   BigDecimal limitPrice, BigDecimal stopLossPrice,
                                   BigDecimal takeProfitPrice, String memo) {
            openCount++;
            lastSide = side;
            lastLeverage = leverage;
            return "开仓成功|posId=1";
        }

        @Override
        public String closePosition(Long positionId, BigDecimal quantity) {
            return "平仓成功|test";
        }

        @Override
        public String setStopLoss(Long positionId, BigDecimal stopLossPrice, BigDecimal quantity) {
            return "修改止损成功";
        }

        @Override
        public String setTakeProfit(Long positionId, BigDecimal takeProfitPrice, BigDecimal quantity) {
            return "修改止盈成功";
        }
    }
}
