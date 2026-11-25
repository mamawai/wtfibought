package com.mawai.wiibcommon.handover;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

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
    public ActiveSlot activeSlot(ObjectProvider<StringRedisTemplate> redis, HandoverPolicy policy) {
        return new ActiveSlot(redis, policy.service());
    }

    /** 槽位和版本来自容器的环境变量 WIIB_SLOT、WIIB_VERSION；本地开发和单测不设 */
    @Bean
    public Handover handover(ObjectProvider<Intake> intakes, ObjectProvider<InFlight> works, HandoverPolicy policy,
                             ActiveSlot activeSlot, ApplicationEventPublisher events,
                             @Value("${WIIB_SLOT:}") String slot, @Value("${WIIB_VERSION:}") String version) {
        return new Handover(intakes, works, policy, activeSlot, events, slot, version);
    }

    /** /internal/handoff 下的接口，走 InternalApiFilter 的 token 校验 */
    @Bean
    public RouterFunction<ServerResponse> handoffRoutes(Handover handover) {
        return RouterFunctions.route()
                .GET("/internal/handoff/status", _ -> ServerResponse.ok().body(handover.status()))
                .build();
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
