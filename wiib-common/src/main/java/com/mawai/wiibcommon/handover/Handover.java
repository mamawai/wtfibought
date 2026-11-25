package com.mawai.wiibcommon.handover;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排：只认口（{@link Intake}）和手上的活（{@link InFlight}），不认识具体组件。
 * 启动时逐个开口；关停时逐个关口 → 等手上的活 → 还没完的提前收尾 → 再等一会儿
 */
@Slf4j
public class Handover {

    private static final long POLL_MS = 100;

    private final ObjectProvider<Intake> intakes;
    private final ObjectProvider<InFlight> works;
    private final HandoverPolicy policy;

    public Handover(ObjectProvider<Intake> intakes, ObjectProvider<InFlight> works, HandoverPolicy policy) {
        this.intakes = intakes;
        this.works = works;
        this.policy = policy;
    }

    /** 启动：按关口的反序开，下游先开着，上游进来的活才有地方去 */
    public void start() {
        List<Intake> all = intakes.orderedStream().toList();
        all.reversed().forEach(i -> i.open(Start.COLD));
        log.info("[Handover] 启动，开口 {} 个", all.size());
    }

    /** 关停 */
    public void stop() {
        long begin = System.currentTimeMillis();
        intakes.orderedStream().forEach(Handover::closeQuietly);
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
