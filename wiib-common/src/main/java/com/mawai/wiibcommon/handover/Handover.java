package com.mawai.wiibcommon.handover;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排：只认口（{@link Intake}）、手上的活（{@link InFlight}）、带走的状态（{@link Carried}），不认识具体组件。
 * 五条路：启动（定身份，在任就开口）、让位（关口 → 收尾 → 导出交接单 → 启动保险）、接手（收下交接单 → 记 Redis → 在任 → 开口）、
 * 恢复（在任 → 开口）、关停（关口 → 等手上的活 → 还没完的提前收尾 → 再等一会儿）。
 * 身份只在这几条路上变，合法的转换只有：启动 → 待命或在任；待命 → 在任；在任 → 收尾；收尾 → 在任
 */
@Slf4j
public class Handover {

    private static final long POLL_MS = 100;

    private final ObjectProvider<Intake> intakes;
    private final ObjectProvider<InFlight> works;
    private final ObjectProvider<Carried> carried;
    private final HandoverPolicy policy;
    private final ActiveSlot activeSlot;
    private final PeerClient peer;
    private final ApplicationEventPublisher events;
    /** 这个实例的槽位 a / b；空是没分槽位（本地开发、单测），直接在任，不碰 Redis */
    private final String slot;
    /** 镜像版本（提交哈希），/status 里报 */
    private final String version;
    /** 身份，启动之前是 null；改它都在 this 锁里 */
    private volatile Role role;
    /** 让位保险的代号：恢复或再次让位时加一，到点的保险对不上就作废 */
    private long insuranceGen;

    public Handover(ObjectProvider<Intake> intakes, ObjectProvider<InFlight> works, ObjectProvider<Carried> carried,
                    HandoverPolicy policy, ActiveSlot activeSlot, PeerClient peer, ApplicationEventPublisher events,
                    String slot, String version) {
        if (!slot.isEmpty() && !slot.equals("a") && !slot.equals("b")) {
            throw new IllegalStateException("WIIB_SLOT 只能是 a 或 b：" + slot);
        }
        this.intakes = intakes;
        this.works = works;
        this.carried = carried;
        this.policy = policy;
        this.activeSlot = activeSlot;
        this.peer = peer;
        this.events = events;
        this.slot = slot;
        this.version = version;
    }

    /**
     * 启动：定身份。在任：对端还在收尾的话先把它还持有的认下来（接手后崩溃被拉起），再发 RoleChanged、开口；
     * 待命什么都不做
     */
    public synchronized void start() {
        role = decideRole();
        if (role == Role.STANDBY) {
            log.info("[Handover] 启动，槽位 {} 待命", slot);
            return;
        }
        if (!slot.isEmpty()) {
            recoverFromDrainingPeer();
        }
        events.publishEvent(new RoleChanged(null, Role.ACTIVE, Start.COLD));
        openAll(Start.COLD);
        log.info("[Handover] 启动，{}在任", slot.isEmpty() ? "" : "槽位 " + slot + " ");
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

    /** 对端可达且在收尾：把它 /status 里导出的状态收一遍，和接手时走同一条路 */
    private void recoverFromDrainingPeer() {
        JsonNode status;
        try {
            status = peer.status();
        } catch (Exception e) {
            return;
        }
        if (status != null && Role.DRAINING.name().equals(status.path("role").asString(""))) {
            log.warn("[Handover] 对端槽位 {} 还在收尾，认回它持有的", peer.peerSlot());
            acceptAll(status.path("carried"));
        }
    }

    /** 让位：逐个关口 → 身份改成收尾 → 导出各格要带走的状态 → 启动保险 → 返回交接单 */
    public synchronized Note yieldRole() {
        if (role != Role.ACTIVE) {
            throw new IllegalStateException("只有在任的能让位，现在是 " + role);
        }
        intakes.orderedStream().forEach(Handover::closeQuietly);
        role = Role.DRAINING;
        Note note = new Note(policy.protocol(), System.currentTimeMillis(), exportAll());
        armInsurance();
        log.info("[Handover] 让位：口已全部关上，收尾，手上还有 {}，交接单 {}", busy(works.orderedStream().toList()),
                note.carried());
        return note;
    }

    /** 接手：各格收下交接单 → Redis 记成自己 → 在任，发 RoleChanged → 开口。已经在任的直接回成功 */
    public synchronized void takeover(Note note) {
        if (role == Role.ACTIVE) {
            return;
        }
        if (role != Role.STANDBY) {
            throw new IllegalStateException("只有待命的能接手，现在是 " + role);
        }
        if (note.protocol() != policy.protocol()) {
            throw new IllegalStateException("交接协议版本对不上：对端 " + note.protocol() + "，自己 " + policy.protocol());
        }
        acceptAll(toObjectNode(note.carried() == null ? Map.of() : note.carried()));
        activeSlot.set(slot);
        role = Role.ACTIVE;
        events.publishEvent(new RoleChanged(Role.STANDBY, Role.ACTIVE, Start.HANDOVER));
        openAll(Start.HANDOVER);
        log.info("[Handover] 接手：槽位 {} 在任", slot);
    }

    /** 恢复：收尾 → 在任，Redis 记回自己，开口。让位后接手失败时脚本调，或保险到点。已经在任的直接回成功 */
    public synchronized void resume() {
        if (role == Role.ACTIVE) {
            return;
        }
        if (role != Role.DRAINING) {
            throw new IllegalStateException("只有收尾中的能恢复，现在是 " + role);
        }
        insuranceGen++;
        if (!slot.isEmpty()) {
            activeSlot.set(slot);
        }
        role = Role.ACTIVE;
        events.publishEvent(new RoleChanged(Role.DRAINING, Role.ACTIVE, Start.HANDOVER));
        openAll(Start.HANDOVER);
        log.info("[Handover] 恢复在任{}", slot.isEmpty() ? "" : "：槽位 " + slot);
    }

    /** 让位后的保险：到点还在收尾、Redis 里的在任槽位也不是对端，就自己恢复在任 */
    private void armInsurance() {
        Duration wait = policy.insurance();
        if (wait.isZero() || slot.isEmpty()) {
            return;
        }
        long gen = ++insuranceGen;
        Thread.ofVirtual().name("handover-insurance").start(() -> {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (this) {
                if (gen != insuranceGen || role != Role.DRAINING || peer.peerSlot().equals(activeSlot.current())) {
                    return;
                }
                log.error("[Handover] 让位后 {}s 没等到对端接手，自己恢复在任", wait.toSeconds());
                resume();
            }
        });
    }

    private void openAll(Start how) {
        intakes.orderedStream().toList().reversed().forEach(i -> i.open(how));
    }

    private Map<String, JsonNode> exportAll() {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        carried.orderedStream().forEach(c -> out.put(c.key(), c.export()));
        return out;
    }

    /** 每一格交给同名的组件；交接单里没有这一格的组件不调 */
    private void acceptAll(JsonNode cells) {
        carried.orderedStream().forEach(c -> {
            JsonNode cell = cells.get(c.key());
            if (cell != null && !cell.isNull()) {
                c.accept(cell);
            }
        });
    }

    private static JsonNode toObjectNode(Map<String, JsonNode> map) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        map.forEach(node::set);
        return node;
    }

    /** 关停：在任的先逐个关口（收尾的口早关了），再等手上的活 */
    public void stop() {
        long begin = System.currentTimeMillis();
        synchronized (this) {
            insuranceGen++;
            if (role == Role.ACTIVE) {
                intakes.orderedStream().forEach(Handover::closeQuietly);
            }
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
     * 给脚本和对端看的现状。ready：在任并且每个口都真的在进活；drained：收尾并且手上的活全清零；
     * carried：收尾时各格此刻的导出（新实例崩溃重启后靠它认回），别的身份是 null。inFlight 每份手上的活都列上，清零的是 0
     */
    public Status status() {
        Map<String, Integer> inFlight = new LinkedHashMap<>();
        works.orderedStream().forEach(w -> inFlight.merge(w.name(), w.count(), Integer::sum));
        Role now = role;
        boolean ready = now == Role.ACTIVE && intakes.orderedStream().allMatch(Intake::ready);
        boolean drained = now == Role.DRAINING && inFlight.values().stream().allMatch(n -> n == 0);
        return new Status(policy.service(), slot, now, policy.protocol(), version, ready, inFlight,
                now == Role.DRAINING ? exportAll() : null, drained);
    }

    /** /internal/handoff/status 的返回 */
    public record Status(String service, String slot, Role role, int protocol, String version, boolean ready,
                         Map<String, Integer> inFlight, Map<String, JsonNode> carried, boolean drained) {
    }

    /** 交接单：协议版本、让位时刻、各格带走的状态 */
    public record Note(int protocol, long at, Map<String, JsonNode> carried) {
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
