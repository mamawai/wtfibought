package com.mawai.wiibagent.trader.trade;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 一个 trader 的账户闸：几轮可以同时在跑，同一个账户上的写动作一次只做一个。
 * 下单、平仓、改止损止盈、撤单、布盯盘、预案执行、计划对账都先过这里。
 * 另记一份最近哪一轮动过哪个币：后动的那一轮被拦下时，拿它说明是谁、什么时候动的。
 */
public final class AccountGate {

    /** 记多少笔最近的动作 */
    private static final int KEEP = 50;

    /**
     * 一笔动作。
     *
     * @param no   第几笔，从 1 起往上编
     * @param by   哪一轮做的（那一轮的交易工具实例），自己的不用告诉自己
     * @param text 给别的轮看的一句：谁做了什么
     */
    public record Change(long no, Object by, String symbol, String text) {
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<Change> recent = new ArrayDeque<>();
    /** 记到第几笔了 */
    private final AtomicLong lastNo = new AtomicLong();

    /** 排队做：前一个做完才轮到下一个 */
    public <T> T exclusive(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** 记一笔动作；在 {@link #exclusive} 里调 */
    public void record(Object by, String symbol, String text) {
        recent.addLast(new Change(lastNo.incrementAndGet(), by, symbol, text));
        if (recent.size() > KEEP) {
            recent.removeFirst();
        }
    }

    /** 现在记到第几笔动作：一轮看账户时记下这个数，之后的动作就是它没看到的 */
    public long lastNo() {
        return lastNo.get();
    }

    /**
     * 最近别的轮做过的动作，按先后；在 {@link #exclusive} 里调
     *
     * @param notBy 自己做的不算
     */
    public List<Change> changes(Object notBy) {
        return recent.stream().filter(c -> c.by() != notBy).toList();
    }
}
