package com.mawai.wiibcommon.handover;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Spring 派发 @Scheduled 时经过代理，定时器的口开关拦得住（14.3 第 4 条） */
class ScheduledGateAspectTest {

    @Configuration
    @EnableScheduling
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class Ctx {
        @Bean
        ScheduledGate scheduledGate() {
            return new ScheduledGate();
        }

        @Bean
        ScheduledGateAspect scheduledGateAspect(ScheduledGate gate) {
            return new ScheduledGateAspect(gate);
        }

        @Bean
        Ticker ticker() {
            return new Ticker();
        }
    }

    public static class Ticker {
        private final AtomicInteger runs = new AtomicInteger();

        @Scheduled(fixedDelay = 10)
        public void tick() {
            runs.incrementAndGet();
        }

        /** 走方法取：拿到的是代理，字段在被代理的那个对象上 */
        public int runs() {
            return runs.get();
        }
    }

    @Test
    void 口关着一次不跑_开了才跑_再关又停() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Ctx.class)) {
            Ticker ticker = ctx.getBean(Ticker.class);
            ScheduledGate gate = ctx.getBean(ScheduledGate.class);

            Thread.sleep(200);
            assertThat(ticker.runs()).isZero();

            gate.open(Start.COLD);
            long deadline = System.currentTimeMillis() + 2_000;
            while (ticker.runs() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertThat(ticker.runs()).isPositive();

            gate.close();
            // 关口那一刻已经过了切面的那一次让它跑完
            Thread.sleep(50);
            int closedAt = ticker.runs();
            Thread.sleep(200);
            assertThat(ticker.runs()).isEqualTo(closedAt);
        }
    }
}
