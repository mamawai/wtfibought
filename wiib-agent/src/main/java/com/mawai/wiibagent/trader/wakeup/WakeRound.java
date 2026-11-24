package com.mawai.wiibagent.trader.wakeup;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 在跑的一轮：管哪些币，和这一轮进行中又到来的事（归这一轮的触发、到点的例行）。
 * 调度器往里放，运行器的循环每次调模型前取走、插进对话；这一轮结束时调度器把没被读到的取回去另起一轮。
 */
public final class WakeRound {

    /**
     * 这一轮进行中到来的事。
     *
     * @param triggers        几条触发，按到来的先后
     * @param routineBoundary 到点的那根例行边界；没有为 null
     */
    public record Arrivals(List<WakeTrigger> triggers, Long routineBoundary) {
        public boolean isEmpty() {
            return triggers.isEmpty() && routineBoundary == null;
        }
    }

    private final WakeScope scope;
    private final Supplier<Set<String>> busyElsewhere;
    private final long startedAt;
    private final List<WakeTrigger> triggers = new ArrayList<>();
    private Long routineBoundary;

    /**
     * @param busyElsewhere 现查：此刻另有单币轮在处理的币
     * @param startedAt     这一轮登记开跑的时刻
     */
    public WakeRound(WakeScope scope, Supplier<Set<String>> busyElsewhere, long startedAt) {
        this.scope = scope;
        this.busyElsewhere = busyElsewhere;
        this.startedAt = startedAt;
    }

    public WakeScope scope() {
        return scope;
    }

    public long startedAt() {
        return startedAt;
    }

    /** 此刻另有单币轮在处理的币 */
    public Set<String> busyElsewhere() {
        return busyElsewhere.get();
    }

    /** 插一条触发 */
    public synchronized void offer(WakeTrigger trigger) {
        triggers.add(trigger);
    }

    /** 这一轮在跑的时候例行到点了：整盘轮才收 */
    public synchronized void offerRoutine(long boundary) {
        routineBoundary = boundary;
    }

    /** 把到来的事都取走 */
    public synchronized Arrivals take() {
        Arrivals out = new Arrivals(List.copyOf(triggers), routineBoundary);
        triggers.clear();
        routineBoundary = null;
        return out;
    }
}
