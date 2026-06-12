package com.mawai.wiibservice.agent.strategy.core;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibservice.agent.trading.ops.TradingOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 插拔式策略 SPI。每根闭合 5m bar 喂一次 onBarClosed，策略内部自行节流到自己的决策周期。
 * 信号主体只许使用 view 的可回放数据（K线）；live-only 数据只能做确认/缩放，保证回测判决有效。
 */
public interface TradingStrategySpi {

    /** 唯一标识，落库与日志用，如 "FIBO"。 */
    String id();

    /** 策略关注的标的（如 BTCUSDT/ETHUSDT）。 */
    List<String> symbols();

    StrategyRiskPolicy riskPolicy();

    /** 闭合bar回调；无信号返回 empty。实现必须无副作用可重入（同一bar重复调用结果一致）。 */
    Optional<StrategySignal> onBarClosed(String symbol, StrategyMarketView view);

    /**
     * 实际开仓前的最后校准。默认不改信号；结构策略可用下一根开盘价重算 SL/TP。
     */
    default StrategySignal prepareEntry(String symbol, StrategySignal signal,
                                        BigDecimal actualEntryPrice, StrategyMarketView view) {
        return signal;
    }

    /** 开仓成功后回调。默认无动作；需要持仓管理的策略可登记 positionId 与结构上下文。 */
    default void onPositionOpened(String symbol, StrategySignal signal, Long positionId,
                                  BigDecimal actualEntryPrice, StrategyMarketView view,
                                  TradingOperations tools) {
    }

    /** 每根 5m 收盘后管理已有仓位。默认无动作，且只在回测/执行层显式调用时生效。 */
    default void onOpenPositionBarClosed(String symbol, StrategyMarketView view,
                                         List<FuturesPositionDTO> openPositions,
                                         TradingOperations tools) {
    }
}
