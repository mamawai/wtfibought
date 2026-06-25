package com.mawai.wiibsim.service;

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

    /**
     * 强平价计算：按强平价落点匹配 Binance 档位 MMR+速算数。
     * <pre>MM = notional × MMR − maintAmount</pre>
     * <pre>LONG : liq = (entry×qty − margin − maintAmount) / (qty × (1 − MMR))</pre>
     * <pre>SHORT: liq = (entry×qty + margin + maintAmount) / (qty × (1 + MMR))</pre>
     * 未配置 symbol 抛 FUTURES_SYMBOL_NOT_CONFIGURED。
     */
    BigDecimal calcStaticLiqPrice(String symbol, String side, BigDecimal entryPrice, BigDecimal margin,
                                  BigDecimal quantity);
}
