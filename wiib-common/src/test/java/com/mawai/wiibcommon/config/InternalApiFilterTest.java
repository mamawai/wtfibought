package com.mawai.wiibcommon.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 按 servletPath 判断：原始 URI 写成 /%69nternal/ 也拦得住 */
class InternalApiFilterTest {

    private final InternalApiFilter filter = new InternalApiFilter();

    {
        ReflectionTestUtils.setField(filter, "internalToken", "secret");
    }

    private static MockHttpServletRequest req(String rawUri, String servletPath, String token) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", rawUri);
        r.setServletPath(servletPath);
        if (token != null) {
            r.addHeader("X-Internal-Token", token);
        }
        return r;
    }

    @Test
    void 编码过的路径没带token也拦下() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req("/%69nternal/futures/1/balance", "/internal/futures/1/balance", null), resp, chain);
        assertThat(resp.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void 带对token放行_非internal路径不管() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req("/internal/futures/1/balance", "/internal/futures/1/balance", "secret"),
                new MockHttpServletResponse(), chain);
        filter.doFilter(req("/api/auth/mode", "/api/auth/mode", null), new MockHttpServletResponse(), chain);
        verify(chain, times(2)).doFilter(any(), any());
    }
}
