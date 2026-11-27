package com.mawai.wiibcommon.handover;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * 订阅回调的闸：开着才把消息交给处理，关闸时等正在处理的那几条处理完再返回。
 * 退订时订阅线程池里还排着的消息，轮到它时闸已经关了，直接丢掉
 */
@Slf4j
public final class DeliveryGate {

    /** 关闸时最多等多久 */
    private static final long CLOSE_WAIT_MS = 5_000;

    private volatile boolean open;
    /** 正在过闸的条数。过闸先记数再看开没开，关闸先关再数，两边总有一边看得到对方 */
    private final AtomicInteger passing = new AtomicInteger();

    public void open() {
        open = true;
    }

    /** 开着就跑 handler，关着什么都不做 */
    public void pass(Runnable handler) {
        passing.incrementAndGet();
        try {
            if (open) {
                handler.run();
            }
        } finally {
            passing.decrementAndGet();
        }
    }

    /** 关闸，等正在跑的 handler 跑完，最多等 5 秒 */
    public void close() {
        open = false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_WAIT_MS);
        while (passing.get() > 0) {
            if (System.nanoTime() - deadline >= 0) {
                log.warn("[DeliveryGate] 等了 {}ms 还有 {} 条在处理，不再等", CLOSE_WAIT_MS, passing.get());
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }
}
