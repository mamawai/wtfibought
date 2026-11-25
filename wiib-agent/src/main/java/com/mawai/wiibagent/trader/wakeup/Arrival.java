package com.mawai.wiibagent.trader.wakeup;

/** 到来的事：一条触发，或者一个到点的例行边界 */
public sealed interface Arrival {

    /** 一条非例行触发 */
    record Trigger(WakeTrigger trigger) implements Arrival {
    }

    /** 一个到点的例行边界 */
    record Routine(long boundary) implements Arrival {
    }
}
