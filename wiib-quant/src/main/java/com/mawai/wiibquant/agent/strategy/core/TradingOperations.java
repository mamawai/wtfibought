package com.mawai.wiibquant.agent.strategy.core;

import java.math.BigDecimal;

/**
 * 交易操作抽象接口。
 * <p>
 * 策略通过此接口表达开平仓/止损止盈意图；回测环境用 BacktestTradingTools 内存撮合实现，
 * 实盘执行层（testnet）按需翻译成交易所条件单。
 */
public interface TradingOperations {

    record OpenResult(boolean success, Long positionId, String message) {
        /** 唯一真实开仓实现(BacktestTradingTools)直接带 positionId 构造；此入口只做成败判定。 */
        public static OpenResult fromMessage(String message) {
            boolean success = message != null && message.startsWith("开仓成功");
            return new OpenResult(success, null, message);
        }
    }

    /**
     * 开仓。必须设置1个止损+1个止盈，各覆盖全部仓位。
     *
     * @return 以"开仓成功"开头表示成功，否则为失败原因
     */
    String openPosition(String side, BigDecimal quantity, Integer leverage,
                        String orderType, BigDecimal limitPrice,
                        BigDecimal stopLossPrice, BigDecimal takeProfitPrice,
                        String memo);

    /**
     * 结果型开仓。默认复用旧字符串接口并尽量解析 positionId，老实现无需立即改动。
     */
    default OpenResult openPositionWithResult(String side, BigDecimal quantity, Integer leverage,
                                              String orderType, BigDecimal limitPrice,
                                              BigDecimal stopLossPrice, BigDecimal takeProfitPrice,
                                              String memo) {
        return OpenResult.fromMessage(openPosition(side, quantity, leverage, orderType, limitPrice,
                stopLossPrice, takeProfitPrice, memo));
    }

    /**
     * 平仓（全部或部分）。
     */
    String closePosition(Long positionId, BigDecimal quantity);

    /**
     * 带业务原因的平仓。生产实现默认忽略 reason，回测实现用它拆分主动退出来源。
     */
    default String closePositionWithReason(Long positionId, BigDecimal quantity, String reason) {
        return closePosition(positionId, quantity);
    }

    /**
     * 修改止损。
     */
    String setStopLoss(Long positionId, BigDecimal stopLossPrice, BigDecimal quantity);

    /**
     * 修改止盈。
     */
    String setTakeProfit(Long positionId, BigDecimal takeProfitPrice, BigDecimal quantity);
}
