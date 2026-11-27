package com.mawai.wiibcommon.handover;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** 定时器的口关着时跳过 {@code @Scheduled} 方法。排在最外层：口关着就不再进事务之类的切面 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ScheduledGateAspect {

    private final ScheduledGate gate;

    public ScheduledGateAspect(ScheduledGate gate) {
        this.gate = gate;
    }

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        return gate.isOpen() ? point.proceed() : null;
    }
}
