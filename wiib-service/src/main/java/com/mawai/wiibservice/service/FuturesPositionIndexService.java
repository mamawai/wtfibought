package com.mawai.wiibservice.service;

import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;

import java.math.BigDecimal;
import java.util.List;

public interface FuturesPositionIndexService {

    /** 开仓时一次Pipeline注册所有索引(LIQ始终注册 + SL + TP) */
    void registerPositionIndex(FuturesPosition position);

    void unregisterAll(FuturesPosition position);

    void updateLiquidationPrice(Long positionId, String symbol, String side, BigDecimal liqPrice);

    void registerStopLosses(Long positionId, String symbol, String side, List<FuturesStopLoss> stopLosses);

    void unregisterStopLosses(Long positionId, String symbol, String side, List<FuturesStopLoss> stopLosses);

    void registerTakeProfits(Long positionId, String symbol, String side, List<FuturesTakeProfit> takeProfits);

    void unregisterTakeProfits(Long positionId, String symbol, String side, List<FuturesTakeProfit> takeProfits);

    BigDecimal calcStaticLiqPrice(String side, BigDecimal entryPrice, BigDecimal margin, BigDecimal quantity, Integer leverage);
}
