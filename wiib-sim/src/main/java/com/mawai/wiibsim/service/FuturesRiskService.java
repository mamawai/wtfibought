package com.mawai.wiibsim.service;

import java.math.BigDecimal;
import java.util.Collection;

import com.mawai.wiibcommon.dto.FuturesStopLossRequest;
import com.mawai.wiibcommon.dto.FuturesTakeProfitRequest;

public interface FuturesRiskService {

    void setStopLoss(Long userId, FuturesStopLossRequest request);

    void setTakeProfit(Long userId, FuturesTakeProfitRequest request);

    void forceClose(Long positionId, BigDecimal price);

    void batchTriggerStopLoss(Long positionId, Collection<String> slIds, BigDecimal price);

    void batchTriggerTakeProfit(Long positionId, Collection<String> tpIds, BigDecimal price);

    void checkAndLiquidate(Long positionId, BigDecimal currentPrice);
}
