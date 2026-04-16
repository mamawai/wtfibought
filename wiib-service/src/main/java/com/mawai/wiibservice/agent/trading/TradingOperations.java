package com.mawai.wiibservice.agent.trading;

import java.math.BigDecimal;

/**
 * 交易操作抽象接口。
 * <p>
 * DeterministicTradingExecutor 通过此接口执行交易，
 * 生产环境使用 {@link AiTradingTools}（真实下单），
 * 回测环境使用 {@link BacktestTradingTools}（模拟撮合）。
 */
public interface TradingOperations {

    /**
     * 开仓。
     *
     * @return 以"开仓成功"开头表示成功，否则为失败原因
     */
    String openPosition(String side, BigDecimal quantity, Integer leverage,
                        String orderType, BigDecimal limitPrice,
                        BigDecimal stopLossPrice, BigDecimal tp1Price, BigDecimal tp2Price,
                        String memo);

    /**
     * 平仓（全部或部分）。
     */
    String closePosition(Long positionId, BigDecimal quantity);

    /**
     * 修改止损。
     */
    String setStopLoss(Long positionId, BigDecimal stopLossPrice, BigDecimal quantity);

    /**
     * 修改止盈。
     */
    String setTakeProfit(Long positionId, BigDecimal takeProfitPrice, BigDecimal quantity);
}
