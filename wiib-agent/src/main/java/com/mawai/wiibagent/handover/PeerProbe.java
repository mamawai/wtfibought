package com.mawai.wiibagent.handover;

import com.mawai.wiibagent.learning.ReviewScheduler;
import com.mawai.wiibagent.trader.wakeup.TraderScheduler;
import com.mawai.wiibcommon.handover.PeerClient;
import com.mawai.wiibcommon.handover.RoleChanged;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 探活：上任后每 10 秒探一次对端的 /status。对端不在收尾了、或者连续 3 次没探到（旧实例没了），
 * 就把旧实例持有的 trader 和替它占着的复盘位全部当作放手，停止探活
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PeerProbe {

    private static final int MAX_FAILS = 3;

    private final PeerClient client;
    private final TraderScheduler scheduler;
    private final ReviewScheduler reviews;

    private final AtomicBoolean probing = new AtomicBoolean();
    /** 最近一次探到的对端是不是在收尾 */
    private volatile boolean peerDraining;

    /** 探活间隔，单测里调短 */
    long everyMs = 10_000;

    /** RoleChanged 只在上任时发（冷启动、接手、恢复） */
    @EventListener
    public void onRoleChanged(RoleChanged changed) {
        if (client.available() && probing.compareAndSet(false, true)) {
            Thread.ofVirtual().name("peer-probe").start(this::loop);
        }
    }

    public boolean peerDraining() {
        return peerDraining;
    }

    private void loop() {
        int fails = 0;
        while (fails < MAX_FAILS) {
            try {
                if (!"DRAINING".equals(client.status().path("role").asString(""))) {
                    break;
                }
                peerDraining = true;
                fails = 0;
            } catch (Exception e) {
                fails++;
                // 探到过对端在收尾、现在探不到了才值得警告；冷启动时本来就没有对端
                if (peerDraining) {
                    log.warn("[PeerProbe] 第 {} 次没探到对端 msg={}", fails, e.toString());
                } else {
                    log.debug("[PeerProbe] 第 {} 次没探到对端 msg={}", fails, e.toString());
                }
            }
            try {
                Thread.sleep(everyMs);
            } catch (InterruptedException e) {
                return;
            }
        }
        peerDraining = false;
        probing.set(false);
        log.info("[PeerProbe] 对端不在收尾了，旧实例持有的全部当作放手");
        scheduler.releaseAllHeldByOld();
        reviews.releaseAllHeldByOld();
    }
}
