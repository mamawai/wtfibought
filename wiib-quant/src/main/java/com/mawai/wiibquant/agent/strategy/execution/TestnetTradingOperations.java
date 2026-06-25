package com.mawai.wiibquant.agent.strategy.execution;

import com.mawai.wiibquant.agent.strategy.core.TradingOperations;

import java.math.BigDecimal;

/**
 * 实盘执行用的轻量 {@link TradingOperations}：只承接策略 onPositionOpened 里的 setScaleOut 意图
 * （记下触发价/比例），供 {@link TestnetExecutionService} 翻译成 testnet 条件单。
 *
 * <p>进场限价单、止损、平仓全由执行层状态机直接管 testnet 单，不走此接口的"同步成交"语义，
 * 故其余方法只返回提示、不产生副作用。每次开仓 new 一个实例承接一次 scaleOut 登记。</p>
 */
public final class TestnetTradingOperations implements TradingOperations {

    private BigDecimal scaleTriggerPrice;
    private double scaleFraction;
    private boolean scaleRegistered;

    @Override
    public String openPosition(String side, BigDecimal quantity, Integer leverage, String orderType,
                               BigDecimal limitPrice, BigDecimal stopLossPrice, BigDecimal takeProfitPrice,
                               String memo) {
        return "执行层直管进场，未走此接口";
    }

    @Override
    public String closePosition(Long positionId, BigDecimal quantity) {
        return "执行层直管平仓";
    }

    @Override
    public String setStopLoss(Long positionId, BigDecimal stopLossPrice, BigDecimal quantity) {
        return "执行层直管止损";
    }

    @Override
    public String setTakeProfit(Long positionId, BigDecimal takeProfitPrice, BigDecimal quantity) {
        return "执行层直管止盈";
    }

    /** 策略在 onPositionOpened 里登记分批/全仓止盈：记下触发价与比例，执行层据此挂 testnet 条件单。 */
    @Override
    public void setScaleOut(Long positionId, BigDecimal triggerPrice, double fraction) {
        this.scaleTriggerPrice = triggerPrice;
        this.scaleFraction = fraction;
        this.scaleRegistered = true;
    }

    public boolean hasScaleOut() {
        return scaleRegistered;
    }

    public BigDecimal scaleTriggerPrice() {
        return scaleTriggerPrice;
    }

    public double scaleFraction() {
        return scaleFraction;
    }
}
