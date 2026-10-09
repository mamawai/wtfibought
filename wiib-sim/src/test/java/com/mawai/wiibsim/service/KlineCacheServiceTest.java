package com.mawai.wiibsim.service;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.TradeFilterDefaults;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * K 线缓存：合约分 100 / 500 两档、现货只用 500 档、游客和登录用户走不同回源、按 endTime 选 TTL、
 * 熔断中不回源、同一个 key 同时 miss 只回源一次
 */
class KlineCacheServiceTest {

    private static final String FIVE = "[[1,\"a\"],[2,\"b\"],[3,\"c\"],[4,\"d\"],[5,\"e\"]]";

    private final KlineOrigin origin = mock(KlineOrigin.class);
    /** 假 Redis：值和 TTL 各一个 HashMap */
    private final Map<String, String> store = new HashMap<>();
    private final Map<String, Duration> ttls = new HashMap<>();
    private MockedStatic<StpUtil> stp;
    private KlineCacheService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.multiGet(anyCollection()))
                .thenAnswer(i -> i.<Collection<String>>getArgument(0).stream().map(store::get).toList());
        doAnswer(i -> {
            store.put(i.getArgument(0), i.getArgument(1));
            return ttls.put(i.getArgument(0), i.getArgument(2));
        }).when(ops).set(anyString(), anyString(), any(Duration.class));

        TradeFilterRegistry registry = mock(TradeFilterRegistry.class);
        when(registry.allFutures()).thenReturn(TradeFilterDefaults.FUTURES);
        when(registry.allSpot()).thenReturn(TradeFilterDefaults.SPOT);
        service = new KlineCacheService(origin, redis, registry, mock(BStockService.class));
        stp = mockStatic(StpUtil.class);
    }

    @AfterEach
    void tearDown() {
        stp.close();
    }

    @Test
    void 游客合约小请求共用100档一次回源_各截各的() {
        when(origin.guest(true, "BTCUSDT", "1h", 100)).thenReturn(FIVE);

        assertThat(service.futuresKlines("BTCUSDT", "1h", 3, null)).isEqualTo("[[3,\"c\"],[4,\"d\"],[5,\"e\"]]");
        assertThat(service.futuresKlines("btcusdt", "1h", 2, null)).isEqualTo("[[4,\"d\"],[5,\"e\"]]");

        verify(origin, times(1)).guest(true, "BTCUSDT", "1h", 100);
        assertThat(store).containsOnlyKeys("kline:v2:fut:BTCUSDT:1h:100:latest");
        assertThat(ttls.get("kline:v2:fut:BTCUSDT:1h:100:latest")).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void 现货小请求只拉500档() {
        when(origin.guest(false, "BTCUSDT", "1h", 500)).thenReturn(FIVE);

        assertThat(service.spotKlines("BTCUSDT", "1h", 2, null)).isEqualTo("[[4,\"d\"],[5,\"e\"]]");

        assertThat(store).containsOnlyKeys("kline:v2:spot:BTCUSDT:1h:500:latest");
    }

    @Test
    void 合约小请求100档没有就用500档现成的_不回源() {
        store.put("kline:v2:fut:BTCUSDT:1h:500:latest", FIVE);

        assertThat(service.futuresKlines("BTCUSDT", "1h", 2, null)).isEqualTo("[[4,\"d\"],[5,\"e\"]]");

        verifyNoInteractions(origin);
    }

    @Test
    void 登录用户拉最新页走登录用户回源() {
        stp.when(StpUtil::isLogin).thenReturn(true);
        when(origin.member(true, "BTCUSDT", "1h", 100, null)).thenReturn(FIVE);

        assertThat(service.futuresKlines("BTCUSDT", "1h", 3, null)).isEqualTo("[[3,\"c\"],[4,\"d\"],[5,\"e\"]]");

        verify(origin, never()).guest(anyBoolean(), anyString(), anyString(), anyInt());
    }

    @Test
    void 带endTime走登录用户回源_按离现在多远选TTL() {
        long old = 1_700_000_000_000L, recent = System.currentTimeMillis() - 10_000;
        when(origin.member(true, "BTCUSDT", "1m", 500, old)).thenReturn(FIVE);
        when(origin.member(true, "BTCUSDT", "1m", 500, recent)).thenReturn(FIVE);

        assertThat(service.futuresKlines("BTCUSDT", "1m", 500, old)).isSameAs(FIVE);
        service.futuresKlines("BTCUSDT", "1m", 500, recent);

        assertThat(ttls.get("kline:v2:fut:BTCUSDT:1m:500:" + old)).isEqualTo(Duration.ofHours(1));
        assertThat(ttls.get("kline:v2:fut:BTCUSDT:1m:500:" + recent)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void 回源失败不进缓存() {
        when(origin.guest(true, "BTCUSDT", "5m", 500)).thenReturn(null);

        assertThat(service.futuresKlines("BTCUSDT", "5m", 300, null)).isNull();

        assertThat(store).isEmpty();
    }

    @Test
    void 熔断中直接回null_不回源不进缓存() {
        when(origin.blocked()).thenReturn(true);

        assertThat(service.futuresKlines("BTCUSDT", "1h", 3, null)).isNull();

        verify(origin).blocked();
        verifyNoMoreInteractions(origin);
        assertThat(store).isEmpty();
    }

    @Test
    void limit夹到1到500() {
        store.put("kline:v2:fut:BTCUSDT:1h:500:latest", FIVE);

        assertThat(service.futuresKlines("BTCUSDT", "1h", 0, null)).isEqualTo("[[5,\"e\"]]");
        assertThat(service.futuresKlines("BTCUSDT", "1h", 5000, null)).isSameAs(FIVE);
    }

    @Test
    void 缓存里不够n根_原样返回() {
        store.put("kline:v2:fut:BTCUSDT:1h:100:latest", FIVE);

        assertThat(service.futuresKlines("BTCUSDT", "1h", 10, null)).isSameAs(FIVE);
    }

    @Test
    void 同一个key同时miss只回源一次_等的人拿同一份各截各的() throws Exception {
        long end = 1_700_000_000_000L;
        CountDownLatch inOrigin = new CountDownLatch(1), release = new CountDownLatch(1);
        when(origin.member(true, "BTCUSDT", "1h", 100, end)).thenAnswer(i -> {
            inOrigin.countDown();
            release.await(5, TimeUnit.SECONDS);
            return FIVE;
        });
        FutureTask<String> leader = new FutureTask<>(() -> service.futuresKlines("BTCUSDT", "1h", 3, end));
        FutureTask<String> follower = new FutureTask<>(() -> service.futuresKlines("BTCUSDT", "1h", 2, end));

        raceSameKey(leader, follower, inOrigin, release);

        assertThat(leader.get()).isEqualTo("[[3,\"c\"],[4,\"d\"],[5,\"e\"]]");
        assertThat(follower.get()).isEqualTo("[[4,\"d\"],[5,\"e\"]]");
        verify(origin, times(1)).member(true, "BTCUSDT", "1h", 100, end);
    }

    @Test
    void 领头的回源失败_等的人自己再拉() throws Exception {
        long end = 1_700_000_000_000L;
        CountDownLatch inOrigin = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        // 第一次（领头的）卡住再被限流拒掉，第二次拉到
        when(origin.member(true, "BTCUSDT", "1h", 100, end)).thenAnswer(i -> {
            if (calls.incrementAndGet() > 1) return FIVE;
            inOrigin.countDown();
            release.await(5, TimeUnit.SECONDS);
            throw new BizException(ErrorCode.RATE_LIMIT_EXCEEDED);
        });
        FutureTask<String> leader = new FutureTask<>(() -> service.futuresKlines("BTCUSDT", "1h", 3, end));
        FutureTask<String> follower = new FutureTask<>(() -> service.futuresKlines("BTCUSDT", "1h", 2, end));

        raceSameKey(leader, follower, inOrigin, release);

        assertThatThrownBy(leader::get).hasCauseInstanceOf(BizException.class);
        assertThat(follower.get()).isEqualTo("[[4,\"d\"],[5,\"e\"]]");
        assertThat(calls.get()).isEqualTo(2);
    }

    /** 领头的卡在回源里时放第二个进来，等它停在等结果上再放行领头的，两个都跑完才返回 */
    private static void raceSameKey(FutureTask<String> leader, FutureTask<String> follower,
                                    CountDownLatch inOrigin, CountDownLatch release) throws Exception {
        Thread t1 = new Thread(leader), t2 = new Thread(follower);
        t1.start();
        assertThat(inOrigin.await(5, TimeUnit.SECONDS)).isTrue();
        t2.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (t2.getState() != Thread.State.WAITING && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertThat(t2.getState()).isEqualTo(Thread.State.WAITING);
        release.countDown();
        t1.join(5000);
        t2.join(5000);
    }
}
