package com.mawai.wiibcommon.handover;

import org.springframework.context.SmartLifecycle;

/** 把编排的一条路挂到 Spring 生命周期上，相位决定它排在 Web 容器的前面还是后面 */
final class HandoverLifecycle implements SmartLifecycle {

    private final int phase;
    private final Runnable onStart;
    private final Runnable onStop;
    private volatile boolean running;

    HandoverLifecycle(int phase, Runnable onStart, Runnable onStop) {
        this.phase = phase;
        this.onStart = onStart;
        this.onStop = onStop;
    }

    @Override
    public void start() {
        onStart.run();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        onStop.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return phase;
    }
}
