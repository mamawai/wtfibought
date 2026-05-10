package com.mawai.wiibservice.agent.trading.entry;

import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibservice.agent.trading.MarketContext;
import com.mawai.wiibservice.agent.trading.SymbolProfile;
import com.mawai.wiibservice.agent.trading.TradingRuntimeToggles;
import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyCandidate;
import com.mawai.wiibservice.agent.trading.ops.TradingOperations;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.PATH_BREAKOUT;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.PATH_LEGACY_TREND;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.PATH_MR;
import static org.assertj.core.api.Assertions.assertThat;

class EntryRiskSizingServiceTest {

    private final EntryRiskSizingService service = new EntryRiskSizingService();

    @Test
    void keepsLegacyTpWhenPlaybookExitDisabled() {
        EntryRiskSizingService.EntryOrderPlan plan = buildPlan(PATH_BREAKOUT, bd("2"), bd("3"),
                new TradingRuntimeToggles(true, false));

        assertThat(plan.tpDistance()).isEqualByComparingTo("3");
        assertThat(plan.takeProfit()).isEqualByComparingTo("103");
    }

    @Test
    void usesFiveRiskHardTpForBreakoutAndTrendWhenEnabled() {
        EntryRiskSizingService.EntryOrderPlan breakout = buildPlan(PATH_BREAKOUT, bd("2"), bd("3"),
                new TradingRuntimeToggles(true, true));
        EntryRiskSizingService.EntryOrderPlan trend = buildPlan(PATH_LEGACY_TREND, bd("2"), bd("3"),
                new TradingRuntimeToggles(true, true));

        assertThat(breakout.tpDistance()).isEqualByComparingTo("10");
        assertThat(breakout.takeProfit()).isEqualByComparingTo("110");
        assertThat(trend.tpDistance()).isEqualByComparingTo("10");
        assertThat(trend.takeProfit()).isEqualByComparingTo("110");
    }

    @Test
    void keepsMeanReversionTpWhenPlaybookExitEnabled() {
        EntryRiskSizingService.EntryOrderPlan plan = buildPlan(PATH_MR, bd("2"), bd("3"),
                new TradingRuntimeToggles(true, true));

        assertThat(plan.tpDistance()).isEqualByComparingTo("3");
        assertThat(plan.takeProfit()).isEqualByComparingTo("103");
    }

    private EntryRiskSizingService.EntryOrderPlan buildPlan(String path, BigDecimal slDistance, BigDecimal tpDistance,
                                                            TradingRuntimeToggles toggles) {
        EntryStrategyCandidate candidate = new EntryStrategyCandidate(
                path, path, "LONG", true, 6.0, slDistance, tpDistance,
                10, 1.0, "test");

        EntryRiskSizingService.SizingResult result = service.buildPlan(
                "BTCUSDT", user(), bd("100000"), signal(), market(),
                candidate, 1.0, profile(), new NoopTools(), toggles);

        assertThat(result.rejectReason()).isNull();
        return result.plan();
    }

    private static MarketContext market() {
        QuantForecastCycle forecast = new QuantForecastCycle();
        forecast.setSnapshotJson("""
                {
                  "regime": "TREND",
                  "atr5m": 1,
                  "indicatorsByTimeframe": {
                    "5m": {"rsi14": 55, "volume_ratio": 1.2}
                  }
                }
                """);
        return MarketContext.parse(forecast, bd("100"));
    }

    private static SymbolProfile profile() {
        return new SymbolProfile(
                2.0, 3.0, 2.0, 2.0, 3.0, 2.0, 3.0,
                0.5, 1.5, 0.001, 0.12, 1.5, 0.8,
                5.0, 5.0);
    }

    private static User user() {
        User user = new User();
        user.setBalance(bd("100000"));
        user.setFrozenBalance(BigDecimal.ZERO);
        return user;
    }

    private static QuantSignalDecision signal() {
        QuantSignalDecision signal = new QuantSignalDecision();
        signal.setMaxLeverage(10);
        return signal;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static class NoopTools implements TradingOperations {
        @Override
        public String openPosition(String side, BigDecimal quantity, Integer leverage, String orderType,
                                   BigDecimal limitPrice, BigDecimal stopLossPrice,
                                   BigDecimal takeProfitPrice, String memo) {
            return "开仓成功|test";
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
