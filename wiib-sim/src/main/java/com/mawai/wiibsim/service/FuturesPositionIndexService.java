package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;

import java.math.BigDecimal;
import java.util.List;

public interface FuturesPositionIndexService {

    /** 开仓时注册该仓位全部触发索引：LIQ(仅逐仓) + SL + TP。SL/TP 为 null 或空即跳过 */
    void registerPositionIndex(FuturesPosition position);

    /** 平仓/强平/爆仓/重置时摘掉该仓位全部触发索引 */
    void unregisterAll(FuturesPosition position);

    /** 逐仓按仓位当前保证金/数量算强平价写进强平索引（已在就覆盖）；全仓不注册 */
    void registerLiquidation(FuturesPosition position);

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
