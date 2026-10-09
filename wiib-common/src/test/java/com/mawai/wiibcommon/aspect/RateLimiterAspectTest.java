package com.mawai.wiibcommon.aspect;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.annotation.RateLimiter;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.enums.RateLimiterType;
import com.mawai.wiibcommon.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

class RateLimiterAspectTest {

    /** 假 Redis：按顺序回放脚本结果（0 放行、i 第 i 个桶不够、异常照抛），记下每次的 keys 和参数 */
    static class FakeRedis extends StringRedisTemplate {
        final Deque<Object> results = new ArrayDeque<>();
        final List<List<String>> keys = new ArrayList<>();
        final List<Object[]> args = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> k, Object... a) {
            keys.add(k);
            args.add(a);
            Object r = results.removeFirst();
            if (r instanceof RuntimeException e) throw e;
            return (T) r;
        }
    }

    public static class Target {
        int calls;

        @RateLimiter(RateLimiterType.KLINE_MEMBER)
        public void global() { calls++; }

        @RateLimiter({RateLimiterType.KLINE_USER, RateLimiterType.KLINE_MEMBER})
        public void both() { calls++; }
    }

    private final FakeRedis redis = new FakeRedis();
    private final Target target = new Target();
    private Target proxy;

    @BeforeEach
    void setUp() {
        AspectJProxyFactory f = new AspectJProxyFactory(target);
        f.setProxyTargetClass(true);
        f.addAspect(new RateLimiterAspect(redis));
        proxy = f.getProxy();
    }

    /** 以 userId=42 登录的身份跑 */
    private static void asUser42(Runnable r) {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenReturn("42");
            r.run();
        }
    }

    @Test
    void 全站桶不用登录_key带global_传时间和桶参数() {
        redis.results.add(0L);

        proxy.global();

        assertThat(target.calls).isEqualTo(1);
        assertThat(redis.keys).containsExactly(List.of("limiter:KLINE_MEMBER:global"));
        assertThat(redis.args.getFirst()).hasSize(3).endsWith("2.0", "60");
    }

    @Test
    void 多个桶一次脚本传全部_每人桶key带登录ID() {
        redis.results.add(0L);

        asUser42(proxy::both);

        assertThat(target.calls).isEqualTo(1);
        assertThat(redis.keys).containsExactly(List.of("limiter:KLINE_USER:42", "limiter:KLINE_MEMBER:global"));
        assertThat(redis.args.getFirst()).hasSize(5).endsWith("0.5", "30", "2.0", "60");
    }

    @Test
    void 有桶不够就拒_方法不执行() {
        redis.results.add(2L);

        asUser42(() -> assertThatThrownBy(proxy::both)
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED)));

        assertThat(target.calls).isZero();
    }

    @Test
    void Redis出错放行() {
        redis.results.add(new RedisConnectionFailureException("down"));

        proxy.global();

        assertThat(target.calls).isEqualTo(1);
    }

    @Test
    void 取登录ID抛的异常照抛_不当成Redis故障放行() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenThrow(new IllegalStateException("未登录"));
            assertThatThrownBy(proxy::both).isInstanceOf(IllegalStateException.class);
        }

        assertThat(target.calls).isZero();
        assertThat(redis.keys).isEmpty();
    }
}
