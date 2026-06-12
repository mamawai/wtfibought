package com.mawai.wiibservice.agent.strategy.core;

import com.mawai.wiibservice.agent.research.kline.KlineBar;

import java.util.List;
import java.util.Optional;

/** 策略取数门面：只提供可回放的多周期K线，保证回测与实盘行为完全一致。 */
public interface StrategyMarketView {

    long BASE_INTERVAL_MILLIS = 5 * 60_000L;

    /** 指定周期的已闭合K线（含基础5m），升序，最后一根保证是完整桶。maxCount 截尾部。 */
    List<KlineBar> closedBars(long horizonMillis, int maxCount);

    /** 当前基础 5m 窗口是否存在断档或非标准 5m bar。 */
    boolean hasBaseGap();

    /** 断档原因；无断档时 empty。 */
    Optional<String> baseGapDescription();

    long nowMs();
}
