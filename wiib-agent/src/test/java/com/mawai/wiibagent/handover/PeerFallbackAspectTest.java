package com.mawai.wiibagent.handover;

import com.mawai.wiibcommon.util.Result;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 标了 @PeerFallback 的接口：本地找到了不转；本地没找到才转，用对端的结果；转不成用本地的 */
class PeerFallbackAspectTest {

    /** 两个接口：默认判法的，和另指定判法的 */
    static class Endpoints {
        boolean found;

        @PeerFallback
        public Result<Boolean> cancel(long userId, @RequestBody Map<String, String> body) {
            return Result.ok(found);
        }

        @PeerFallback(missing = Empty.class)
        public Result<String> status() {
            return Result.ok(found ? "running" : "");
        }
    }

    public static final class Empty implements Predicate<Object> {
        @Override
        public boolean test(Object returned) {
            return returned instanceof Result<?> r && "".equals(r.getData());
        }
    }

    private final PeerRelay relay = mock(PeerRelay.class);
    private final Endpoints target = new Endpoints();
    private final Endpoints endpoints;

    {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new PeerFallbackAspect(relay));
        endpoints = factory.getProxy();
    }

    @Test
    void foundLocallyIsNotRelayed() {
        target.found = true;

        assertThat(endpoints.cancel(1L, Map.of()).getData()).isTrue();
        verify(relay, never()).relay(any(), any());
    }

    @Test
    void missingLocallyIsRelayedWithTheBody() {
        Map<String, String> body = Map.of("sessionId", "wb-1-a");
        when(relay.relay(eq(body), any())).thenReturn(Optional.of(Result.ok(true)));

        assertThat(endpoints.cancel(1L, body).getData()).isTrue();
    }

    @Test
    void localResultWhenRelayGivesNothing() {
        when(relay.relay(any(), any())).thenReturn(Optional.empty());

        assertThat(endpoints.cancel(1L, Map.of()).getData()).isFalse();
    }

    @Test
    void ownJudgmentDecidesWhatMissingMeans() {
        when(relay.relay(any(), any())).thenReturn(Optional.of(Result.ok("peer")));

        assertThat(endpoints.status().getData()).isEqualTo("peer");
        target.found = true;
        assertThat(endpoints.status().getData()).isEqualTo("running");
    }
}
