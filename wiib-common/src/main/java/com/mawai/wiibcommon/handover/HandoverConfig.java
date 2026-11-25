package com.mawai.wiibcommon.handover;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

/**
 * 交接骨架的公共件，不是 {@code @Configuration}：服务在声明自己 {@link HandoverPolicy} 的配置类上
 * {@code @Import} 它才挂上，没 import 的服务（feed）不挂骨架
 */
public class HandoverConfig {

    /** 不让 Spring 把 close() 推断成销毁方法 */
    @Bean(destroyMethod = "")
    public ScheduledGate scheduledGate() {
        return new ScheduledGate();
    }

    @Bean
    public ScheduledGateAspect scheduledGateAspect(ScheduledGate scheduledGate) {
        return new ScheduledGateAspect(scheduledGate);
    }

    @Bean
    public Handover handover(ObjectProvider<Intake> intakes, ObjectProvider<InFlight> works, HandoverPolicy policy) {
        return new Handover(intakes, works, policy);
    }

    /** 开口：最早的相位，先于 Web 容器开始接请求（MAX_VALUE - 2048）；这时所有 @PostConstruct 都已跑完 */
    @Bean
    public SmartLifecycle handoverStartup(Handover handover) {
        return new HandoverLifecycle(Integer.MIN_VALUE, handover::start, () -> {
        });
    }

    /** 关停：默认相位，先于 Web 容器优雅关停（MAX_VALUE - 1024） */
    @Bean
    public SmartLifecycle handoverShutdown(Handover handover) {
        return new HandoverLifecycle(SmartLifecycle.DEFAULT_PHASE, () -> {
        }, handover::stop);
    }
}
