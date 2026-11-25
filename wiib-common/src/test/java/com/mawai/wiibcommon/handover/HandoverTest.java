package com.mawai.wiibcommon.handover;

import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 编排的启动和关停：定身份、开关口的先后、等手上的活、等不到就提前收尾；/status 报的现状 */
class HandoverTest {

    /** 各处按发生先后记一笔 */
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    private static final HandoverPolicy POLICY = new HandoverPolicy("test", 1, Duration.ofSeconds(5), Duration.ofSeconds(5));

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
            return new HandoverPolicy("test", 1, Duration.ofMillis(300), Duration.ofMillis(300));
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

        @EventListener
        public void on(RoleChanged event) {
            EVENTS.add("roleChanged " + event.to());
        }

        @Scheduled(fixedDelay = 60_000)
        public void tick() {
            EVENTS.add("scheduled");
        }
    }

    @Test
    void 挂到Spring上_PostConstruct在前_上任事件在开口之前_定时任务在开口之后_关上下文按顺序关口() throws Exception {
        EVENTS.clear();
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Wiring.class)) {
            long deadline = System.currentTimeMillis() + 2_000;
            while (!EVENTS.contains("scheduled") && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            // 开口按关口的反序；定时器的口最先关、最后开，它开之前派不出定时任务
            assertThat(EVENTS).containsExactly("postConstruct", "roleChanged ACTIVE", "open downstream", "open upstream",
                    "scheduled");
            EVENTS.clear();
        }
        assertThat(EVENTS).containsExactly("close upstream", "close downstream");
    }

    /** 不经 Spring 生命周期，直接调编排；上任事件也记一笔 */
    private static Handover handover(Work work, List<Intake> gates, HandoverPolicy policy, ActiveSlot activeSlot,
                                     String slot) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        for (int i = 0; i < gates.size(); i++) {
            beans.registerSingleton("gate" + i, gates.get(i));
        }
        beans.registerSingleton("work", work);
        return new Handover(beans.getBeanProvider(Intake.class), beans.getBeanProvider(InFlight.class), policy,
                activeSlot, event -> EVENTS.add("roleChanged " + ((RoleChanged) event).to()), slot, "abc1234");
    }

    /** 没分槽位、已经启动在任的编排，启动时的记录清掉 */
    private static Handover started(Work work, List<Intake> gates, HandoverPolicy policy) {
        Handover handover = handover(work, gates, policy, mock(ActiveSlot.class), "");
        handover.start();
        EVENTS.clear();
        return handover;
    }

    /** 没分槽位（本地开发、单测）：不碰 Redis，直接在任，先发上任事件再开口 */
    @Test
    void 没分槽位_直接在任() {
        EVENTS.clear();
        ActiveSlot activeSlot = mock(ActiveSlot.class);
        Handover handover = handover(new Work(true), List.of(new Gate("a", 0)), POLICY, activeSlot, "");

        handover.start();

        assertThat(EVENTS).containsExactly("roleChanged ACTIVE", "open a");
        assertThat(handover.status().role()).isEqualTo(Role.ACTIVE);
        verifyNoInteractions(activeSlot);
    }

    /** 分了槽位按 Redis 里记的在任槽位定身份 */
    @Test
    void 分了槽位_按Redis里记的定身份() {
        // 没记过：记上了在任，被对端抢先了待命
        assertThat(roleAfterStart(null, true)).isEqualTo(Role.ACTIVE);
        assertThat(roleAfterStart(null, false)).isEqualTo(Role.STANDBY);
        // 记的是自己：在任的实例崩溃后被拉起
        assertThat(roleAfterStart("a", false)).isEqualTo(Role.ACTIVE);
        // 记的是对端
        assertThat(roleAfterStart("b", false)).isEqualTo(Role.STANDBY);
    }

    private static Role roleAfterStart(String owner, boolean claimed) {
        ActiveSlot activeSlot = mock(ActiveSlot.class);
        when(activeSlot.current()).thenReturn(owner);
        when(activeSlot.claim("a")).thenReturn(claimed);
        Handover handover = handover(new Work(true), List.of(new Gate("g", 0)), POLICY, activeSlot, "a");
        handover.start();
        return handover.status().role();
    }

    /** 待命：不发事件、不开口；关停时也不关口；/status 不算就绪 */
    @Test
    void 待命_不开口_关停也不关口() {
        EVENTS.clear();
        ActiveSlot activeSlot = mock(ActiveSlot.class);
        when(activeSlot.current()).thenReturn("b");
        Handover handover = handover(new Work(true), List.of(new Gate("g", 0)), POLICY, activeSlot, "a");

        handover.start();
        handover.stop();

        assertThat(EVENTS).isEmpty();
        assertThat(handover.status().role()).isEqualTo(Role.STANDBY);
        assertThat(handover.status().ready()).isFalse();
    }

    @Test
    void 槽位只能是a或b() {
        assertThatThrownBy(() -> handover(new Work(true), List.of(), POLICY, mock(ActiveSlot.class), "c"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** /status：服务、槽位、身份、协议、版本；每份手上的活都列上，清零的是 0；有口没真的在进活就不算就绪 */
    @Test
    void status报现状() throws Exception {
        Work work = new Work(true);
        Handover handover = started(work, List.of(new Gate("a", 0)), POLICY);
        assertThat(handover.status()).isEqualTo(
                new Handover.Status("test", "", Role.ACTIVE, 1, "abc1234", true, Map.of("work", 0), false));

        MockMvc mvc = MockMvcBuilders.routerFunctions(new HandoverConfig().handoffRoutes(handover)).build();
        work.n.set(2);
        mvc.perform(get("/internal/handoff/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ACTIVE"))
                .andExpect(jsonPath("$.service").value("test"))
                .andExpect(jsonPath("$.inFlight.work").value(2))
                .andExpect(jsonPath("$.drained").value(false));

        Gate notReady = new Gate("late", 0) {
            @Override
            public boolean ready() {
                return false;
            }
        };
        assertThat(started(work, List.of(notReady), POLICY).status().ready()).isFalse();
    }

    @Test
    void 手上的活在等待时间内做完_不提前收尾() {
        Work work = new Work(true);
        work.n.set(1);
        Handover handover = started(work, List.of(new Gate("a", 0)), POLICY);
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
        Work work = new Work(true);
        work.n.set(1);
        Handover handover = started(work, List.of(new Gate("a", 0)),
                new HandoverPolicy("test", 1, Duration.ofMillis(300), Duration.ofSeconds(5)));

        long begin = System.currentTimeMillis();
        handover.stop();

        assertThat(EVENTS).containsExactly("close a", "cut");
        assertThat(System.currentTimeMillis() - begin).isBetween(250L, 2_000L);
    }

    @Test
    void 提前收尾也收不掉_再等一段就不等了() {
        Work work = new Work(false);
        work.n.set(1);
        Handover handover = started(work, List.of(new Gate("a", 0)),
                new HandoverPolicy("test", 1, Duration.ofMillis(200), Duration.ofMillis(300)));

        long begin = System.currentTimeMillis();
        handover.stop();

        assertThat(EVENTS).containsExactly("close a", "cut");
        assertThat(System.currentTimeMillis() - begin).isBetween(450L, 2_000L);
    }

    @Test
    void 提前收尾后不等的策略_等满就不再等_不提前收尾() {
        Work work = new Work(true);
        work.n.set(1);
        Handover handover = started(work, List.of(new Gate("a", 0)),
                new HandoverPolicy("test", 1, Duration.ofMillis(300), Duration.ZERO));

        long begin = System.currentTimeMillis();
        handover.stop();

        assertThat(EVENTS).containsExactly("close a");
        assertThat(work.count()).isEqualTo(1);
        assertThat(System.currentTimeMillis() - begin).isBetween(250L, 2_000L);
    }

    @Test
    void 一个口关失败_后面的口照关_手上的活照等() {
        Intake broken = new Gate("broken", 0) {
            @Override
            public void close() {
                throw new IllegalStateException("关不上");
            }
        };
        Work work = new Work(true);
        work.n.set(1);
        Handover handover = started(work, List.of(broken, new Gate("b", 1)),
                new HandoverPolicy("test", 1, Duration.ofMillis(200), Duration.ofMillis(200)));

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
