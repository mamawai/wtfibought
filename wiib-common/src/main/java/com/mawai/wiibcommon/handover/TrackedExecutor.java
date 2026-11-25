package com.mawai.wiibcommon.handover;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/** 会计数的虚拟线程执行器：一件活一个虚拟线程，交进来加一、跑完减一。它本身是一份手上的活 */
public class TrackedExecutor implements Executor, InFlight {

    private final String name;
    private final AtomicInteger running = new AtomicInteger();

    public TrackedExecutor(String name) {
        this.name = name;
    }

    @Override
    public void execute(Runnable work) {
        running.incrementAndGet();
        Thread.startVirtualThread(() -> {
            try {
                work.run();
            } finally {
                running.decrementAndGet();
            }
        });
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int count() {
        return running.get();
    }
}
