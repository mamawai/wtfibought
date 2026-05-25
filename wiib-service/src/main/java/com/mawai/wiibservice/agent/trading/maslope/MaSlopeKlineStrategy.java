package com.mawai.wiibservice.agent.trading.maslope;

import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibservice.agent.tool.CryptoIndicatorCalculator;
import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyCandidate;
import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyContext;
import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyResult;
import com.mawai.wiibservice.agent.trading.entry.strategy.MaSlopeEntryStrategy;
import com.mawai.wiibservice.agent.trading.runtime.MarketContext;
import com.mawai.wiibservice.agent.trading.runtime.SymbolProfile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.PATH_MA_SLOPE;

/**
 * MaSlope 独立 K 线入口。
 *
 * <p>这个类不接收 forecast、referenceSignal、overallDecision、riskStatus。
 * 它只从主周期/15m/1h K 线窗口计算策略真正需要的技术指标，再复用 MaSlopeEntryStrategy 判断入场。</p>
 */
public final class MaSlopeKlineStrategy {

    private final MaSlopeEntryStrategy delegate;

    public MaSlopeKlineStrategy() {
        this(new MaSlopeEntryStrategy());
    }

    MaSlopeKlineStrategy(MaSlopeEntryStrategy delegate) {
        this.delegate = delegate;
    }

    public Evaluation evaluate(Input input) {
        String validationError = validate(input);
        if (validationError != null) {
            return Evaluation.rejected(EntryStrategyResult.reject(PATH_MA_SLOPE, validationError));
        }

        KlineInterval interval = input.decisionInterval();
        Map<String, Object> primary = calcIndicators("primary", input.primaryKlines());
        if (hasError(primary)) {
            return Evaluation.rejected(EntryStrategyResult.reject(PATH_MA_SLOPE,
                    "MASLOPE_KLINE_PRIMARY_INVALID " + primary.get("error")));
        }

        Map<String, Object> tf15m = interval == KlineInterval.M15 && input.m15Klines() == null
                ? primary : calcIndicators("15m", input.m15Klines());
        if (hasError(tf15m)) {
            return Evaluation.rejected(EntryStrategyResult.reject(PATH_MA_SLOPE,
                    "MASLOPE_KLINE_15M_INVALID " + tf15m.get("error")));
        }

        Map<String, Object> tf1h = calcIndicators("1h", input.h1Klines());
        if (hasError(tf1h)) {
            return Evaluation.rejected(EntryStrategyResult.reject(PATH_MA_SLOPE,
                    "MASLOPE_KLINE_1H_INVALID " + tf1h.get("error")));
        }

        BigDecimal price = input.price() != null ? input.price() : lastClose(input.primaryKlines());
        MarketContext market = MarketContext.fromKlineIndicators(interval, price, primary, tf15m, tf1h);
        SymbolProfile profile = input.profile() != null ? input.profile() : SymbolProfile.of(input.symbol());

        EntryStrategyResult result = delegate.build(new EntryStrategyContext(
                input.symbol(), interval, market, profile, "AUTO", false, 1.0));
        return new Evaluation(market, result, primary, tf15m, tf1h);
    }

    public Evaluation evaluate(String symbol,
                               KlineInterval decisionInterval,
                               List<BigDecimal[]> primaryKlines,
                               List<BigDecimal[]> m15Klines,
                               List<BigDecimal[]> h1Klines) {
        return evaluate(new Input(symbol, decisionInterval, primaryKlines, m15Klines, h1Klines,
                null, SymbolProfile.of(symbol)));
    }

    private static String validate(Input input) {
        if (input == null) {
            return "MASLOPE_KLINE_INPUT_MISSING";
        }
        if (input.symbol() == null || input.symbol().isBlank()) {
            return "MASLOPE_KLINE_SYMBOL_MISSING";
        }
        if (input.decisionInterval() == null) {
            return "MASLOPE_KLINE_INTERVAL_MISSING";
        }
        if (input.primaryKlines() == null || input.primaryKlines().size() < 60) {
            return "MASLOPE_KLINE_PRIMARY_INSUFFICIENT";
        }
        if (input.decisionInterval() != KlineInterval.M15
                && (input.m15Klines() == null || input.m15Klines().size() < 60)) {
            return "MASLOPE_KLINE_15M_INSUFFICIENT";
        }
        if (input.h1Klines() == null || input.h1Klines().size() < 60) {
            return "MASLOPE_KLINE_1H_INSUFFICIENT";
        }
        return null;
    }

    private static Map<String, Object> calcIndicators(String label, List<BigDecimal[]> klines) {
        if (klines == null) {
            return Map.of("error", label + " K线缺失");
        }
        return CryptoIndicatorCalculator.calcAll(klines, true);
    }

    private static boolean hasError(Map<String, Object> indicators) {
        return indicators == null || indicators.containsKey("error");
    }

    private static BigDecimal lastClose(List<BigDecimal[]> klines) {
        if (klines == null || klines.isEmpty()) {
            return null;
        }
        BigDecimal[] last = klines.getLast();
        return last != null && last.length > 2 ? last[2] : null;
    }

    public record Input(String symbol,
                        KlineInterval decisionInterval,
                        List<BigDecimal[]> primaryKlines,
                        List<BigDecimal[]> m15Klines,
                        List<BigDecimal[]> h1Klines,
                        BigDecimal price,
                        SymbolProfile profile) {
        public Input {
            symbol = symbol != null ? symbol.trim().toUpperCase(Locale.ROOT) : null;
        }
    }

    public record Evaluation(MarketContext market,
                             EntryStrategyResult result,
                             Map<String, Object> primaryIndicators,
                             Map<String, Object> m15Indicators,
                             Map<String, Object> h1Indicators) {

        static Evaluation rejected(EntryStrategyResult result) {
            return new Evaluation(null, result, Map.of(), Map.of(), Map.of());
        }

        public boolean accepted() {
            return result != null && result.candidate() != null;
        }

        public EntryStrategyCandidate candidate() {
            return result != null ? result.candidate() : null;
        }

        public String rejectReason() {
            return result != null ? result.rejectReason() : null;
        }
    }
}
