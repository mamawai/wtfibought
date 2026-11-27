package com.mawai.wiibsim.config;

import com.mawai.wiibcommon.handover.Start;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 请求闸：待命时压住、等不到开口回 503；在任放行并计数；关了回 503；交接接口、健康检查不过闸，WebSocket 不计数 */
class RequestGateTest {

    private final RequestGate gate = new RequestGate("b");
    /** 请求进到业务里那一刻闸上的计数 */
    private final AtomicInteger countInside = new AtomicInteger(-1);
    private final FilterChain chain = (_, _) -> countInside.set(gate.count());

    private MockHttpServletResponse call(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        gate.doFilter(request, response, chain);
        return response;
    }

    @Test
    void 在任放行并计数_做完清零() throws Exception {
        gate.open(Start.COLD);

        assertThat(call("/api/futures/open").getStatus()).isEqualTo(200);
        assertThat(countInside).hasValue(1);
        assertThat(gate.count()).isZero();
    }

    /** 待命时来的请求压住，接手开口后照常处理 */
    @Test
    void 待命时压住_开口后放行() throws Exception {
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
            gate.open(Start.HANDOVER);
        });

        assertThat(call("/api/futures/open").getStatus()).isEqualTo(200);
        assertThat(countInside).hasValue(1);
    }

    @Test
    void 待命时等不到开口回503() throws Exception {
        gate.holdMs = 100;

        MockHttpServletResponse response = call("/api/futures/open");

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(countInside).hasValue(-1);
    }

    @Test
    void 关了回503_不进业务() throws Exception {
        gate.open(Start.COLD);
        gate.close();

        assertThat(call("/api/futures/open").getStatus()).isEqualTo(503);
        assertThat(countInside).hasValue(-1);
        assertThat(gate.count()).isZero();
    }

    /** 交接接口、健康检查任何时候都不过闸；WebSocket 过闸但不计数 */
    @Test
    void 交接接口健康检查不过闸_WebSocket不计数() throws Exception {
        gate.holdMs = 100;
        assertThat(call("/internal/handoff/takeover").getStatus()).isEqualTo(200);
        assertThat(call("/actuator/health").getStatus()).isEqualTo(200);

        gate.open(Start.COLD);
        assertThat(call("/ws/quotes/info").getStatus()).isEqualTo(200);
        assertThat(countInside).hasValue(0);
    }

    /** 没分槽位（本地开发）不生效 */
    @Test
    void 没分槽位不生效() throws Exception {
        RequestGate local = new RequestGate("");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/futures/open");
        request.setServletPath("/api/futures/open");
        MockHttpServletResponse response = new MockHttpServletResponse();

        local.doFilter(request, response, (_, _) -> countInside.set(local.count()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(countInside).hasValue(0);
    }
}
