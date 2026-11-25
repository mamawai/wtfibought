package com.mawai.wiibcommon.handover;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排：只认口（{@link Intake}）和手上的活（{@link InFlight}），不认识具体组件。
 * 启动时定身份，在任就逐个开口；关停时逐个关口 → 等手上的活 → 还没完的提前收尾 → 再等一会儿
 */
@Slf4j
public class Handover {

    private static final long POLL_MS = 100;

    private final ObjectProvider<Intake> intakes;
    private final ObjectProvider<InFlight> works;
    private final HandoverPolicy policy;
    private final ActiveSlot activeSlot;
    private final ApplicationEventPublisher events;
    /** 这个实例的槽位 a / b；空是没分槽位（本地开发、单测），直接在任，不碰 Redis */
    private final String slot;
    /** 镜像版本（提交哈希），/status 里报 */
    private final String version;
    /** 身份，启动之前是 null */
    private volatile Role role;

    public Handover(ObjectProvider<Intake> intakes, ObjectProvider<InFlight> works, HandoverPolicy policy,
                    ActiveSlot activeSlot, ApplicationEventPublisher events, String slot, String version) {
        if (!slot.isEmpty() && !slot.equals("a") && !slot.equals("b")) {
            throw new IllegalStateException("WIIB_SLOT 只能是 a 或 b：" + slot);
        }
        this.intakes = intakes;
        this.works = works;
        this.policy = policy;
        this.activeSlot = activeSlot;
        this.events = events;
        this.slot = slot;
        this.version = version;
    }

    /** 启动：定身份。在任就发 RoleChanged，再按关口的反序开口（下游先开着，上游进来的活才有地方去）；待命什么都不做 */
    public void start() {
        role = decideRole();
        if (role == Role.STANDBY) {
            log.info("[Handover] 启动，槽位 {} 待命", slot);
            return;
        }
        events.publishEvent(new RoleChanged(null, Role.ACTIVE, Start.COLD));
        List<Intake> all = intakes.orderedStream().toList();
        all.reversed().forEach(i -> i.open(Start.COLD));
        log.info("[Handover] 启动，{}在任，开口 {} 个", slot.isEmpty() ? "" : "槽位 " + slot + " ", all.size());
    }

    /**
     * 启动时的身份。没分槽位直接在任；分了槽位看 Redis 里记的在任槽位：
     * 没记过就记成自己，记上了在任、被对端抢先了待命；记的是自己在任（在任的实例崩溃后被拉起）；记的是对端待命
     */
    private Role decideRole() {
        if (slot.isEmpty()) {
            return Role.ACTIVE;
        }
        String owner = activeSlot.current();
        if (owner == null) {
            return activeSlot.claim(slot) ? Role.ACTIVE : Role.STANDBY;
        }
        return owner.equals(slot) ? Role.ACTIVE : Role.STANDBY;
    }

    /** 关停：在任的先逐个关口，再等手上的活 */
    public void stop() {
        long begin = System.currentTimeMillis();
        if (role == Role.ACTIVE) {
            intakes.orderedStream().forEach(Handover::closeQuietly);
        }
        List<InFlight> all = works.orderedStream().toList();
        log.info("[Handover] 关停：口已全部关上，手上还有 {}", busy(all));
        if (awaitDrained(all, policy.shutdownWait())) {
            log.info("[Handover] 手上的活已清零，用时 {}ms", System.currentTimeMillis() - begin);
            return;
        }
        if (policy.cutWait().isZero()) {
            log.warn("[Handover] 等了 {}s 还剩 {}，不再等", policy.shutdownWait().toSeconds(), busy(all));
            return;
        }
        log.warn("[Handover] 等了 {}s 还剩 {}，提前收尾", policy.shutdownWait().toSeconds(), busy(all));
        all.forEach(Handover::cutQuietly);
        if (awaitDrained(all, policy.cutWait())) {
            log.info("[Handover] 提前收尾后清零，用时 {}ms", System.currentTimeMillis() - begin);
        } else {
            log.warn("[Handover] 提前收尾后又等了 {}s 还剩 {}，不再等", policy.cutWait().toSeconds(), busy(all));
        }
    }

    /**
     * 给脚本和对端看的现状。ready：在任并且每个口都真的在进活；drained：收尾并且手上的活全清零。
     * inFlight 每份手上的活都列上，清零的是 0
     */
    public Status status() {
        Map<String, Integer> inFlight = new LinkedHashMap<>();
        works.orderedStream().forEach(w -> inFlight.merge(w.name(), w.count(), Integer::sum));
        Role now = role;
        boolean ready = now == Role.ACTIVE && intakes.orderedStream().allMatch(Intake::ready);
        boolean drained = now == Role.DRAINING && inFlight.values().stream().allMatch(n -> n == 0);
        return new Status(policy.service(), slot, now, policy.protocol(), version, ready, inFlight, drained);
    }

    /** /internal/handoff/status 的返回 */
    public record Status(String service, String slot, Role role, int protocol, String version, boolean ready,
                         Map<String, Integer> inFlight, boolean drained) {
    }

    /** 关一个口；关失败只记日志，接着关后面的 */
    private static void closeQuietly(Intake intake) {
        try {
            intake.close();
        } catch (Exception e) {
            log.error("[Handover] 关口失败 {}", intake.getClass().getSimpleName(), e);
        }
    }

    private static void cutQuietly(InFlight work) {
        try {
            work.cut();
        } catch (Exception e) {
            log.error("[Handover] 提前收尾失败 {}", work.name(), e);
        }
    }

    /** 等手上的活清零，最多等 timeout；清零了返回 true */
    private static boolean awaitDrained(List<InFlight> all, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!busy(all).isEmpty()) {
            if (System.nanoTime() - deadline >= 0) {
                return false;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** 还在跑的：名字 → 件数 */
    private static Map<String, Integer> busy(List<InFlight> all) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (InFlight w : all) {
            int n = w.count();
            if (n > 0) {
                out.put(w.name(), n);
            }
        }
        return out;
    }
}
