package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.enums.RateLimiterType;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.limiter.TokenBucketLimiter;
import com.mawai.wiibcommon.limiter.TokenBucketLimiter.Bucket;
import com.mawai.wiibsim.config.GoogleConfig;
import com.mawai.wiibsim.config.LinuxDoConfig;
import com.mawai.wiibsim.mapper.InviteCodeMapper;
import com.mawai.wiibsim.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 密码登录按 IP + 用户名扣令牌，注册按 IP 扣；IP 取 nginx 填的 X-Real-IP，客户端自己写的 X-Forwarded-For 不认 */
class AuthServiceRateLimitTest {

    private final UserService userService = mock(UserService.class);
    private final TokenBucketLimiter limiter = mock(TokenBucketLimiter.class);
    private final AuthServiceImpl service = new AuthServiceImpl(userService, mock(LinuxDoConfig.class), mock(RestTemplate.class),
            mock(GoogleConfig.class), mock(RestTemplate.class), mock(InviteCodeMapper.class), mock(MessageCatalog.class), limiter);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "passwordLoginEnabled", true);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Real-IP", "203.0.113.7");
        req.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void 密码登录按IP和去空格截到20字的用户名扣() {
        assertThatThrownBy(() -> service.passwordLogin("  " + "a".repeat(5000), "x")).isInstanceOf(BizException.class);
        verify(limiter).acquire(new Bucket(RateLimiterType.LOGIN_IP, "203.0.113.7"),
                new Bucket(RateLimiterType.LOGIN_USERNAME, "a".repeat(20)));
    }

    @Test
    void 被限流就不查库() {
        doThrow(new BizException(ErrorCode.RATE_LIMIT_EXCEEDED)).when(limiter).acquire(any(Bucket[].class));

        assertThatThrownBy(() -> service.passwordLogin("admin", "x")).isInstanceOf(BizException.class);
        verify(userService, never()).findByUsername(any());
    }

    @Test
    void 注册按IP扣_格式不对的也算() {
        assertThatThrownBy(() -> service.register("x", "1", "")).isInstanceOf(BizException.class);
        verify(limiter).acquire(new Bucket(RateLimiterType.REGISTER_IP, "203.0.113.7"));
    }
}
