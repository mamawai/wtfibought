package com.mawai.wiibservice.agent.trading;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTradingDecision;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import com.mawai.wiibcommon.entity.User;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record TradingDecisionContext(
        String symbol,
        User user,
        List<FuturesPositionDTO> symbolPositions,
        QuantForecastCycle forecast,
        List<QuantSignalDecision> signals,
        List<AiTradingDecision> recentDecisions,
        BigDecimal futuresPrice,
        BigDecimal markPrice,
        BigDecimal totalEquity,
        TradingOperations tools,
        TradingExecutionState state,
        TradingRuntimeToggles toggles,
        SymbolProfile profile,
        MarketContext market,
        LocalDateTime forecastTime,
        boolean indicatorExitShielded
) {
    public TradingDecisionContext(String symbol,
                           User user,
                           List<FuturesPositionDTO> symbolPositions,
                           QuantForecastCycle forecast,
                           List<QuantSignalDecision> signals,
                           List<AiTradingDecision> recentDecisions,
                           BigDecimal futuresPrice,
                           BigDecimal markPrice,
                           BigDecimal totalEquity,
                           TradingOperations tools,
                           TradingExecutionState state,
                           TradingRuntimeToggles toggles,
                           SymbolProfile profile,
                           MarketContext market,
                           LocalDateTime forecastTime) {
        this(symbol, user, symbolPositions, forecast, signals, recentDecisions,
                futuresPrice, markPrice, totalEquity, tools, state, toggles, profile, market, forecastTime, false);
    }

    public TradingDecisionContext withIndicatorExitShielded(boolean shielded) {
        if (indicatorExitShielded == shielded) {
            return this;
        }
        return new TradingDecisionContext(symbol, user, symbolPositions, forecast, signals, recentDecisions,
                futuresPrice, markPrice, totalEquity, tools, state, toggles, profile, market, forecastTime, shielded);
    }
}
