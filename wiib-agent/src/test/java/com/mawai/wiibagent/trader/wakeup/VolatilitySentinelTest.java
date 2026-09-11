package com.mawai.wiibagent.trader.wakeup;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 哨兵探测层的纯逻辑：滚动窗口振幅/方向/过期剔除 + 生效阈值（基准×系数，只能调高）。 */
class VolatilitySentinelTest {

    @Test
    void windowAmplitudeAndDirection() {
        VolatilitySentinel.PriceWindow w = new VolatilitySentinel.PriceWindow();
        long t0 = 1_786_200_000_000L;
        w.add(t0, new BigDecimal("100000"));
        w.add(t0 + 60_000, new BigDecimal("100600"));
        w.add(t0 + 120_000, new BigDecimal("99800"));

        // (100600-99800)/99800 = 0.8016%
        assertThat(w.amplitudePct()).isEqualByComparingTo("0.8016");
        assertThat(w.direction()).isEqualTo(AlertTrigger.DOWN); // 现价 99800 < 窗口首价 100000
    }

    /** 超过 5 分钟的 tick 剔除出窗：旧尖峰不许一直撑着振幅 */
    @Test
    void ticksOlderThanWindowEvicted() {
        VolatilitySentinel.PriceWindow w = new VolatilitySentinel.PriceWindow();
        long t0 = 1_786_200_000_000L;
        w.add(t0, new BigDecimal("99000"));                                  // 将过期的低点
        w.add(t0 + VolatilitySentinel.WINDOW_MS + 1_000, new BigDecimal("100000"));
        w.add(t0 + VolatilitySentinel.WINDOW_MS + 2_000, new BigDecimal("100100"));

        // 99000 已出窗：振幅只剩 (100100-100000)/100000 = 0.1%
        assertThat(w.amplitudePct()).isEqualByComparingTo("0.1");
    }

    @Test
    void singleTickWindowIsZero() {
        VolatilitySentinel.PriceWindow w = new VolatilitySentinel.PriceWindow();
        w.add(1_786_200_000_000L, new BigDecimal("100000"));

        assertThat(w.amplitudePct()).isEqualByComparingTo("0");
        assertThat(w.direction()).isEqualTo(AlertTrigger.FLAT);
    }

    /**
     * 窗口必须自己线程安全：Redis 监听容器是线程池分发（RedisMessageConfig 4~16 线程）、
     * 所有币又共用一个 PRICE channel，同一 symbol 的两个 tick 会真并发落到同一个窗口上。
     * 裸 ArrayDeque 在并发下：amplitudePct() 迭代撞到被 poll 空的槽抛 CME、
     * add() 驱逐时 peekFirst 被别的线程 poll 走抛 NPE——异常在 onMessage 里被吞成 debug 日志，
     * 表面看不出来，实际窗口已静默损坏 → 警报漏报/误报。
     */
    @Test
    void windowSurvivesConcurrentTicks() throws Exception {
        long t0 = 1_786_200_000_000L;
        int threads = 6;
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        // 多跑几轮提高命中率：并发缺陷是概率事件，一轮没撞上不代表安全
        for (int round = 0; round < 10 && errors.isEmpty(); round++) {
            VolatilitySentinel.PriceWindow w = new VolatilitySentinel.PriceWindow();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                // 平台线程而非虚拟线程：这里是纯 CPU 循环，要的是 OS 抢占式交错
                Thread th = new Thread(() -> {
                    try {
                        start.await();
                        for (int n = 0; n < 800; n++) {
                            // 10s 一个 tick：窗口稳定在百来个元素，驱逐循环一直在跑，poll 与迭代真对撞
                            w.add(t0 + n * 10_000L, new BigDecimal(100000 + (n % 300)));
                            w.amplitudePct();
                            w.direction();
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        done.countDown();
                    }
                });
                th.setDaemon(true);
                th.start();
            }
            start.countDown();
            // 损坏还可能表现成驱逐循环卡死：超时也算失败，不许把整个套件挂住
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(errors).isEmpty();
    }

    /** 生效阈值 = 币基准 × 系数；系数 null/低于 1 回落 1.0（只能调高的兜底） */
    @Test
    void effectiveThresholdOnlyRaisable() {
        assertThat(VolatilitySentinel.effectiveThreshold("BTCUSDT", null))
                .isEqualByComparingTo("0.6");
        assertThat(VolatilitySentinel.effectiveThreshold("BTCUSDT", new BigDecimal("2")))
                .isEqualByComparingTo("1.2");
        assertThat(VolatilitySentinel.effectiveThreshold("BTCUSDT", new BigDecimal("0.5")))
                .isEqualByComparingTo("0.6");
        assertThat(VolatilitySentinel.effectiveThreshold("DOGEUSDT", null))
                .isEqualByComparingTo("1.0");
    }
}
