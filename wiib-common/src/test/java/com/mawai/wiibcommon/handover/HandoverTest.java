package com.mawai.wiibcommon.handover;

import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 编排的启动和关停：开关口的先后、等手上的活、等不到就提前收尾 */
class HandoverTest {

    /** 各处按发生先后记一笔 */
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /** 记下开关的口 */
    static class Gate implements Intake, Ordered {
        private final String name;
        private final int order;

        Gate(String name, int order) {
            this.name = name;
            this.order = order;
        }

        @Override
        public void open(Start how) {
            EVENTS.add("open " + name);
        }

        @Override
        public void close() {
            EVENTS.add("close " + name);
        }

        @Override
        public int getOrder() {
            return order;
        }
    }

    /** 件数由测试控制；cut 记一笔，clearOnCut 决定收不收得掉 */
    static class Work implements InFlight {
        final AtomicInteger n = new AtomicInteger();
        final boolean clearOnCut;

        Work(boolean clearOnCut) {
            this.clearOnCut = clearOnCut;
        }

        @Override
        public String name() {
            return "work";
        }

        @Override
        public int count() {
            return n.get();
        }

        @Override
        public void cut() {
            EVENTS.add("cut");
            if (clearOnCut) {
                n.set(0);
            }
        }
    }

    @Configuration
    @EnableScheduling
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import(HandoverConfig.class)
    static class Wiring {
        @Bean
        HandoverPolicy policy() {
            return new HandoverPolicy(Duration.ofMillis(300), Duration.ofMillis(300));
        }

        @Bean(destroyMethod = "")
        Gate upstream() {
            return new Gate("upstream", 10);
        }

        @Bean(destroyMethod = "")
        Gate downstream() {
            return new Gate("downstream", 20);
        }

        @Bean
        Beans beans() {
            return new Beans();
        }
    }

    public static class Beans {
        @PostConstruct
        void init() {
            EVENTS.add("postConstruct");
        }

        @Scheduled(fixedDelay = 60_000)
        public void tick() {
            EVENTS.add("scheduled");
        }
    }

    @Test
    void 挂到Spring上_PostConstruct在开口之前_定时任务在开口之后_关上下文按顺序关口() throws Exception {
        EVENTS.clear();
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Wiring.class)) {
            long deadline = System.currentTimeMillis() + 2_000;
            while (!EVENTS.contains("scheduled") && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            // 开口按关口的反序；定时器的口最先关、最后开，它开之前派不出定时任务
            assertThat(EVENTS).containsExactly("postConstruct", "open downstream", "open upstream", "scheduled");
            EVENTS.clear();
        }
        assertThat(EVENTS).containsExactly("close upstream", "close downstream");
    }

    /** 不经 Spring 生命周期，直接调编排 */
    private static Handover handover(Work work, List<Intake> gates, HandoverPolicy policy) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        for (int i = 0; i < gates.size(); i++) {
            beans.registerSingleton("gate" + i, gates.get(i));
        }
        beans.registerSingleton("work", work);
        return new Handover(beans.getBeanProvider(Intake.class), beans.getBeanProvider(InFlight.class), policy);
    }

    @Test
    void 手上的活在等待时间内做完_不提前收尾() {
        EVENTS.clear();
        Work work = new Work(true);
        work.n.set(1);
        Handover handover = handover(work, List.of(new Gate("a", 0)),
                new HandoverPolicy(Duration.ofSeconds(5), Duration.ofSeconds(5)));
        Thread.startVirtualThread(() -> {
            sleep(200);
            work.n.set(0);
        });

        long begin = System.currentTimeMillis();
        handover.stop();

        assertThat(EVENTS).containsExactly("close a");
        assertThat(System.currentTimeMillis() - begin).isBetween(150L, 2_000L);
    }

    @Test
    void 等不到就提前收尾_收得掉就不再等() {
        EVENTS.clear();
        Work work = new Work(true);
        work.n.set(1);
        Handover handover = handover(work, List.of(new Gate("a", 0)),
                new HandoverPolicy(Duration.ofMillis(300), Duration.ofSeconds(5)));

        long begin = System.currentTimeMillis();
        handover.stop();

        assertThat(EVENTS).containsExactly("close a", "cut");
        assertThat(System.currentTimeMillis() - begin).isBetween(250L, 2_000L);
    }

    @Test
    void 提前收尾也收不掉_再等一段就不等了() {
        EVENTS.clear();
        Work work = new Work(false);
        work.n.set(1);
        Handover handover = handover(work, List.of(new Gate("a", 0)),
                new HandoverPolicy(Duration.ofMillis(200), Duration.ofMillis(300)));

        long begin = System.currentTimeMillis();
        handover.stop();

        assertThat(EVENTS).containsExactly("close a", "cut");
        assertThat(System.currentTimeMillis() - begin).isBetween(450L, 2_000L);
    }

    @Test
    void 提前收尾后不等的策略_等满就不再等_不提前收尾() {
        EVENTS.clear();
        Work work = new Work(true);
        work.n.set(1);
        Handover handover = handover(work, List.of(new Gate("a", 0)),
                new HandoverPolicy(Duration.ofMillis(300), Duration.ZERO));

        long begin = System.currentTimeMillis();
        handover.stop();

        assertThat(EVENTS).containsExactly("close a");
        assertThat(work.count()).isEqualTo(1);
        assertThat(System.currentTimeMillis() - begin).isBetween(250L, 2_000L);
    }

    @Test
    void 一个口关失败_后面的口照关_手上的活照等() {
        EVENTS.clear();
        Intake broken = new Gate("broken", 0) {
            @Override
            public void close() {
                throw new IllegalStateException("关不上");
            }
        };
        Work work = new Work(true);
        work.n.set(1);
        Handover handover = handover(work, List.of(broken, new Gate("b", 1)),
                new HandoverPolicy(Duration.ofMillis(200), Duration.ofMillis(200)));

        handover.stop();

        assertThat(EVENTS).containsExactly("close b", "cut");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
