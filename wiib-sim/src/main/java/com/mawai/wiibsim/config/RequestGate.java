package com.mawai.wiibsim.config;

import com.mawai.wiibcommon.handover.InFlight;
import com.mawai.wiibcommon.handover.Intake;
import com.mawai.wiibcommon.handover.Start;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 请求闸：设了 WIIB_SLOT 才生效的最高优先级 filter，同时是一个口（HTTP 请求）和一份手上的活（在处理的业务请求数）。
 * 三个状态：还没开过的压住（最多等 4 秒开口，等不到回 503 带 Retry-After: 1）、开着的放行、关了的回 503。
 * /actuator/**、/internal/handoff/** 直接放行；/ws/** 过闸但不计数（SockJS 降级传输是长请求）。
 * 请求先计数再看关没关，配着关口那边"先关、再等计数清零"，清零之后不会有请求溜进来
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestGate extends OncePerRequestFilter implements Intake, InFlight {

    private enum State { NOT_OPENED, OPEN, CLOSED }

    /** 还没开过时最多压多久；单测里调短 */
    long holdMs = 4_000;

    private final boolean enabled;
    private volatile State state = State.NOT_OPENED;
    /** 第一次开口时放开，压住的请求等它 */
    private final CountDownLatch firstOpen = new CountDownLatch(1);
    private final AtomicInteger inFlight = new AtomicInteger();

    public RequestGate(@Value("${WIIB_SLOT:}") String slot) {
        this.enabled = !slot.isEmpty();
    }

    @Override
    public void open(Start how) {
        state = State.OPEN;
        firstOpen.countDown();
    }

    @Override
    public void close() {
        state = State.CLOSED;
    }

    @Override
    public String name() {
        return "requests";
    }

    @Override
    public int count() {
        return inFlight.get();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !enabled || path.startsWith("/actuator/") || path.startsWith("/internal/handoff/");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        if (state == State.NOT_OPENED && !awaitFirstOpen()) {
            reject(response);
            return;
        }
        boolean counted = !request.getServletPath().startsWith("/ws/");
        if (counted) {
            inFlight.incrementAndGet();
        }
        try {
            if (state != State.OPEN) {
                reject(response);
                return;
            }
            chain.doFilter(request, response);
        } finally {
            if (counted) {
                inFlight.decrementAndGet();
            }
        }
    }

    private boolean awaitFirstOpen() {
        try {
            return firstOpen.await(holdMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void reject(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader("Retry-After", "1");
    }
}
