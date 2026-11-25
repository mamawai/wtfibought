package com.mawai.wiibcommon.handover;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** 定时器的口：关着时 {@code @Scheduled} 方法一律跳过（见 {@link ScheduledGateAspect}）。最先关、最后开 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ScheduledGate implements Intake {

    private volatile boolean open;

    @Override
    public void open(Start how) {
        open = true;
    }

    @Override
    public void close() {
        open = false;
    }

    public boolean isOpen() {
        return open;
    }
}
