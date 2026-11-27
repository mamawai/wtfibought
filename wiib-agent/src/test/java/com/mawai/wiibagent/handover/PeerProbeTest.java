package com.mawai.wiibagent.handover;

import com.mawai.wiibagent.learning.ReviewScheduler;
import com.mawai.wiibagent.trader.wakeup.TraderScheduler;
import com.mawai.wiibcommon.handover.PeerClient;
import com.mawai.wiibcommon.handover.RoleChanged;
import com.mawai.wiibcommon.handover.Start;
import org.junit.jupiter.api.Test;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 探活：对端在收尾就一直探，每次按它导出的名单核对；连续 3 次没探到、或者它不在收尾了，旧实例持有的全部当放手 */
class PeerProbeTest {

    private final PeerClient client = mock(PeerClient.class);
    private final TraderScheduler scheduler = mock(TraderScheduler.class);
    private final ReviewScheduler reviews = mock(ReviewScheduler.class);
    private final PeerProbe probe = new PeerProbe(client, scheduler, reviews);

    {
        when(client.available()).thenReturn(true);
        probe.everyMs = 20;
    }

    private static RoleChanged tookOver() {
        return new RoleChanged(null, null, Start.HANDOVER);
    }

    /** 收尾中探到两次，之后连不上：第 3 次没探到才放手 */
    @Test
    void releasesAfterThreeMisses() {
        when(client.status()).thenReturn(MAPPER.readTree("{\"role\":\"DRAINING\"}"))
                .thenReturn(MAPPER.readTree("{\"role\":\"DRAINING\"}"))
                .thenThrow(new IllegalStateException("连不上"));

        probe.onRoleChanged(tookOver());

        verify(scheduler, timeout(2_000)).releaseAllHeldByOld();
        verify(reviews).releaseAllHeldByOld();
        verify(client, times(5)).status();
        assertThat(probe.peerDraining()).isFalse();
    }

    /** 对端不在收尾（待命、冷启动时根本没对端在收尾）：马上放手，停止探活 */
    @Test
    void releasesAtOnceWhenPeerIsNotDraining() {
        when(client.status()).thenReturn(MAPPER.readTree("{\"role\":\"STANDBY\"}"));

        probe.onRoleChanged(tookOver());

        verify(scheduler, timeout(2_000)).releaseAllHeldByOld();
        verify(client, after(200).times(1)).status();
    }

    /** 对端还在收尾：每次探到都把它导出的那两格交给调度器核对；它不在收尾了才全部放手 */
    @Test
    void checksPeerHeldListWhileDraining() {
        when(scheduler.key()).thenReturn("trader-desks");
        when(reviews.key()).thenReturn("review-slots");
        when(client.status()).thenReturn(MAPPER.readTree(
                "{\"role\":\"DRAINING\",\"carried\":{\"trader-desks\":{\"held\":[7]},\"review-slots\":{\"held\":[8]}}}"));

        probe.onRoleChanged(tookOver());

        verify(scheduler, timeout(2_000)).releaseNotHeldByOld(argThat(cell -> cell.path("held").get(0).asLong() == 7));
        verify(reviews, timeout(2_000)).releaseNotHeldByOld(argThat(cell -> cell.path("held").get(0).asLong() == 8));
        verify(scheduler, never()).releaseAllHeldByOld();

        when(client.status()).thenReturn(MAPPER.readTree("{\"role\":\"STANDBY\"}"));
        verify(scheduler, timeout(2_000)).releaseAllHeldByOld();
    }

    /** 没分槽位：没有对端，不探 */
    @Test
    void noPeerNoProbe() {
        when(client.available()).thenReturn(false);

        probe.onRoleChanged(tookOver());

        verify(client, after(200).never()).status();
    }
}
