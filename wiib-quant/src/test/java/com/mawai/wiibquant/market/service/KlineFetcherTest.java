package com.mawai.wiibquant.market.service;

import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.KlineBar;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KlineFetcherTest {

    private static final long TTL = 60_000L;

    /** 合成 Binance 原始格式：openTime 递增，close 用序号，方便断言切的是不是尾部那几根 */
    private static String rawKlines(int n) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (int i = 0; i < n; i++) {
            long t = 1_700_000_000_000L + i * 300_000L;
            ArrayNode k = arr.addArray();
            k.add(t);
            k.add("100");
            k.add("110");
            k.add("90");
            k.add(String.valueOf(i));   // close = 序号
            k.add("10");
            k.add(t + 299_999L);
        }
        return MAPPER.writeValueAsString(arr);
    }

    private final BinanceRestClient client = mock(BinanceRestClient.class);

    private KlineFetcher fetcher() {
        return new KlineFetcher(client, TTL);
    }

    @Test
    void cacheHitAvoidsSecondRequest() {
        when(client.getFuturesKlines(anyString(), anyString(), anyInt(), isNull())).thenReturn(rawKlines(192));
        KlineFetcher f = fetcher();

        assertThat(f.fetch("BTCUSDT", "5m", 192)).hasSize(192);
        assertThat(f.fetch("BTCUSDT", "5m", 192)).hasSize(192);

        verify(client, times(1)).getFuturesKlines(anyString(), anyString(), anyInt(), isNull());
    }

    @Test
    void shorterRequestSlicesFromLongerCache() {
        // 这是整层的核心收益：klines 拉过 192 之后，indicators 要 120 根不该再打网络
        when(client.getFuturesKlines(anyString(), anyString(), eq(192), isNull())).thenReturn(rawKlines(192));
        KlineFetcher f = fetcher();

        f.fetch("BTCUSDT", "5m", 192);
        List<KlineBar> short120 = f.fetch("BTCUSDT", "5m", 120);

        assertThat(short120).hasSize(120);
        // 切的是最近 120 根：close 从 72 到 191
        assertThat(short120.getFirst().close()).isEqualByComparingTo("72");
        assertThat(short120.getLast().close()).isEqualByComparingTo("191");
        verify(client, never()).getFuturesKlines(anyString(), anyString(), eq(120), isNull());
    }

    @Test
    void shorterCacheDoesNotEvictLongerOne() {
        // 反向：先拿了 120，再要 192 得真去拉；拉回来的长货不能被后来的短货挤掉
        when(client.getFuturesKlines(anyString(), anyString(), eq(120), isNull())).thenReturn(rawKlines(120));
        when(client.getFuturesKlines(anyString(), anyString(), eq(192), isNull())).thenReturn(rawKlines(192));
        KlineFetcher f = fetcher();

        f.fetch("BTCUSDT", "5m", 120);
        f.fetch("BTCUSDT", "5m", 192);
        f.fetch("BTCUSDT", "5m", 120);      // 该从 192 那份里切，不再打网络

        verify(client, times(1)).getFuturesKlines(anyString(), anyString(), eq(120), isNull());
        verify(client, times(1)).getFuturesKlines(anyString(), anyString(), eq(192), isNull());
    }

    @Test
    void expiredCacheRefetches() {
        when(client.getFuturesKlines(anyString(), anyString(), anyInt(), isNull())).thenReturn(rawKlines(192));
        KlineFetcher f = fetcher();
        AtomicLong clock = new AtomicLong(1_000_000L);
        f.nowMs = clock::get;

        f.fetch("BTCUSDT", "5m", 192);
        clock.addAndGet(TTL);               // 正好到期（判据是 >= ttl）
        f.fetch("BTCUSDT", "5m", 192);

        verify(client, times(2)).getFuturesKlines(anyString(), anyString(), anyInt(), isNull());
    }

    @Test
    void concurrentMissesCollapseIntoOneRequest() throws Exception {
        // 10 个 trader 在同一个 5m 边界一起醒来的场景：只许一个真打上游
        int threads = 10;
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger calls = new AtomicInteger();
        when(client.getFuturesKlines(anyString(), anyString(), anyInt(), isNull())).thenAnswer(inv -> {
            calls.incrementAndGet();
            Thread.sleep(80);               // 撑开窗口，让后来者必然撞上在途请求
            return rawKlines(192);
        });
        KlineFetcher f = fetcher();

        for (int i = 0; i < threads; i++) {
            Thread.startVirtualThread(() -> {
                try {
                    startLine.await();
                    assertThat(f.fetch("BTCUSDT", "5m", 192)).hasSize(192);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        startLine.countDown();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void emptyResultIsNotCached() {
        // 熔断期返回 null，缓存了就等于把接下来 60s 一起黑掉
        when(client.getFuturesKlines(anyString(), anyString(), anyInt(), isNull()))
                .thenReturn(null)
                .thenReturn(rawKlines(192));
        KlineFetcher f = fetcher();

        assertThat(f.fetch("BTCUSDT", "5m", 192)).isEmpty();
        assertThat(f.fetch("BTCUSDT", "5m", 192)).hasSize(192);   // 立刻重试，不等 TTL
    }

    @Test
    void upstreamFailureDoesNotLeakInFlightSlot() {
        // 抛异常那次必须把在途槽清干净，否则同 key 的后续请求会永远等一个不会完成的 future
        when(client.getFuturesKlines(anyString(), anyString(), anyInt(), isNull()))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(rawKlines(192));
        KlineFetcher f = fetcher();

        try {
            f.fetch("BTCUSDT", "5m", 192);
        } catch (IllegalStateException ignored) {
            // 采集线程自己往上抛，符合预期
        }
        assertThat(f.fetch("BTCUSDT", "5m", 192)).hasSize(192);
    }

    @Test
    void insufficientBarsFromUpstreamAreReturnedAsIs() {
        // 上游给不满（新币/停牌）：不谎报，有多少给多少，由调用方判断够不够
        when(client.getFuturesKlines(anyString(), anyString(), anyInt(), isNull())).thenReturn(rawKlines(37));

        assertThat(fetcher().fetch("BTCUSDT", "5m", 192)).hasSize(37);
    }
}
