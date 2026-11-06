package com.mawai.wiibfeed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收件箱满了挤掉最旧的帧；consumer 追上之后把被挤掉的那段收到时刻报一次
 * （逐笔成交丢了就没了，handler 拿它发空窗补漏）
 */
class WsConnectionDropTest {

    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    @AfterEach
    void stop() {
        shutdown.set(true);
    }

    @Test
    void 挤掉过帧_追上后报被挤掉的那段() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch blocking = new CountDownLatch(1);
        WsConnection conn = new WsConnection("AggTrade", () -> "", msg -> {
            if (!"block".equals(msg)) return;
            blocking.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, ws -> { }, () -> { }, null, null, shutdown);
        CompletableFuture<long[]> reported = new CompletableFuture<>();
        conn.setOnDropped((from, to) -> reported.complete(new long[]{from, to}));

        // consumer 卡在第一帧上，收件箱塞满后再来 3 帧，挤掉收到时刻 100、101、102 那三帧
        conn.enqueue("block", 1L);
        assertThat(blocking.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < WsConnection.INBOX_CAPACITY; i++) conn.enqueue("f", 100L + i);
        for (int i = 0; i < 3; i++) conn.enqueue("f", 9_000L + i);
        assertThat(reported).isNotDone();

        release.countDown();

        assertThat(reported.get(5, TimeUnit.SECONDS)).containsExactly(100L, 102L);
    }
}
