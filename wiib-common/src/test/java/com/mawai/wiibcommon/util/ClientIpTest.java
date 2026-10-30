package com.mawai.wiibcommon.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/** IPv6 按前 /64 分桶：同一段里换地址还是同一个桶 */
class ClientIpTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static String bucketOf(String realIp) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Real-IP", realIp);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        return ClientIp.currentForLimit();
    }

    @Test
    void IPv6同一段64位是同一个桶() {
        assertThat(bucketOf("2001:db8:1:2:3:4:5:6")).isEqualTo("2001:db8:1:2::/64");
        assertThat(bucketOf("2001:db8:1:2::ffff")).isEqualTo("2001:db8:1:2::/64");
    }
}
