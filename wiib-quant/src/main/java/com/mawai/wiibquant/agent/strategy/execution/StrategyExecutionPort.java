package com.mawai.wiibquant.agent.strategy.execution;

import com.mawai.wiibquant.agent.strategy.core.StrategyMarketView;
import com.mawai.wiibquant.agent.strategy.core.StrategySignal;
import com.mawai.wiibquant.agent.strategy.core.TradingStrategySpi;

import java.math.BigDecimal;

/**
 * 策略实盘执行端口：StrategyRuntime 只面向本接口，落地实现按
 * {@code strategy.execution.target=testnet|sim} 二选一（见 {@link ExecutionRoutingConfig}）。
 *
 * <p>全部方法由 5m 收盘事件驱动；实现方自带状态机与容错，任何异常须内部消化，
 * 不得抛出打断信号循环。</p>
 */
public interface StrategyExecutionPort {

    /** 新信号：LIMIT=挂单/同腿 reaffirm/换腿撤旧挂新；MARKET=直接进场。 */
    void onSignal(String symbol, StrategySignal signal, TradingStrategySpi strategy);

    /** 该策略本根无信号（腿失效等）：撤掉该策略在此 symbol 上的挂单。 */
    void noSignal(String symbol, String strategyId);

    /** 每根 5m 收盘轮询：推进成交/超时/平仓状态。 */
    void tick(String symbol, long nowMs);

    /**
     * 持仓期钩子：该策略在此 symbol 有持仓时回调 strategy.onPositionBarClosed（时间出场等）。
     * runtime 每根收盘先于新信号评估调用——镜像回测顺序，旧仓先出场新信号才评估。
     * 默认无动作（testnet 未接，LiqFade 只跑 sim）。
     */
    default void onPositionBarClosed(String symbol, TradingStrategySpi strategy, StrategyMarketView view) {
    }

    /**
     * 实时价 tick（feed:price futures，约1s粒度）：驱动 quant 侧虚拟触价单（STOP）盘中触发。
     * 与 5m 收盘事件不同线程，实现方自行同步。默认无动作（testnet 只做 LIMIT）。
     */
    default void onPriceTick(String symbol, BigDecimal price) {
    }
}
