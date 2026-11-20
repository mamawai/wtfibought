package com.mawai.wiibsim.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.limiter.TokenBucketLimiter;
import com.mawai.wiibsim.config.GoogleConfig;
import com.mawai.wiibsim.config.LinuxDoConfig;
import com.mawai.wiibsim.dto.LinuxDoUserInfo;
import com.mawai.wiibsim.mapper.InviteCodeMapper;
import com.mawai.wiibsim.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 站内用户名的规则：外面的名字只留注册允许的字符，撞名、太短都补随机后缀，补完也不超 20 个字；
 * AI Trader 子账户的名字前缀三条建号的路（Google、LinuxDo、邀请码注册）都不给真人
 */
class AuthServiceUsernameTest {

    private final UserService userService = mock(UserService.class);
    private final InviteCodeMapper inviteCodes = mock(InviteCodeMapper.class);
    private final LinuxDoConfig linuxDo = mock(LinuxDoConfig.class);
    private final RestTemplate linuxDoRest = mock(RestTemplate.class);
    private final AuthServiceImpl service = new AuthServiceImpl(userService, linuxDo, linuxDoRest,
            mock(GoogleConfig.class), mock(RestTemplate.class), inviteCodes, mock(MessageCatalog.class),
            mock(TokenBucketLimiter.class));

    @Test
    void 名字洗成注册规则内的字符() {
        assertThat(AuthServiceImpl.sanitizeUsername("John Smith")).isEqualTo("John_Smith");
        assertThat(AuthServiceImpl.sanitizeUsername("José García")).isEqualTo("Jose_Garcia");
        assertThat(AuthServiceImpl.sanitizeUsername("张三")).isEqualTo("张三");
        assertThat(AuthServiceImpl.sanitizeUsername("Tom 😀 Lee")).isEqualTo("Tom_Lee");
        assertThat(AuthServiceImpl.sanitizeUsername("abcdefghijklmnopqrstuvwxyz")).isEqualTo("abcdefghijklmnopqrst");
        // 韩文、日文假名这类注册不允许的字符全被洗掉，只能叫 user
        assertThat(AuthServiceImpl.sanitizeUsername("김민준")).isEqualTo("user");
        assertThat(AuthServiceImpl.sanitizeUsername(null)).isEqualTo("user");
        // AI Trader 子账户的名字前缀不给真人
        assertThat(AuthServiceImpl.sanitizeUsername("ai trader 7 r2")).isEqualTo("user");
    }

    @Test
    void 撞名或太短补随机后缀且不超20个字() {
        when(userService.findByUsername("John_Smith")).thenReturn(new User());
        when(userService.findByUsername("abcdefghijklmnopqrst")).thenReturn(new User());

        assertThat(service.freshUsername("Alice")).isEqualTo("Alice");
        assertThat(service.freshUsername("John Smith")).matches("John_Smith_\\d{4}");
        assertThat(service.freshUsername("明")).matches("明_\\d{4}");
        assertThat(service.freshUsername("abcdefghijklmnopqrst")).matches("abcdefghijklmno_\\d{4}");
    }

    @Test
    void LinuxDo的名字撞上子账户前缀_新号另起名_老号留旧名() {
        User old = new User();
        old.setUsername("bob");

        assertThat(service.linuxDoUsername("alice", null)).isEqualTo("alice");
        assertThat(service.linuxDoUsername("alice2", old)).isEqualTo("alice2");
        assertThat(service.linuxDoUsername("ai_trader_t7_r2", null)).isEqualTo("user");
        assertThat(service.linuxDoUsername("ai_trader_t7_r2", old)).isEqualTo("bob");
    }

    @Test
    void 老号在LinuxDo改的名字站内已有人用_留旧名() {
        User old = new User();
        old.setUsername("bob");
        when(userService.findByUsername("carol")).thenReturn(new User());
        // 名字没变：查到的是自己
        when(userService.findByUsername("bob")).thenReturn(old);

        assertThat(service.linuxDoUsername("carol", old)).isEqualTo("bob");
        assertThat(service.linuxDoUsername("bob", old)).isEqualTo("bob");
    }

    @Test
    void LinuxDo新用户和站内已有用户同名_另起名照常登录() {
        when(linuxDo.getTokenUrl()).thenReturn("https://linux.do/token");
        when(linuxDo.getUserUrl()).thenReturn("https://linux.do/user");
        when(linuxDoRest.postForObject(eq("https://linux.do/token"), any(), eq(String.class)))
                .thenReturn("{\"access_token\":\"at\"}");
        LinuxDoUserInfo info = new LinuxDoUserInfo();
        info.setId(42L);
        info.setUsername("alice");
        when(linuxDoRest.exchange(eq("https://linux.do/user"), eq(HttpMethod.GET), any(), eq(LinuxDoUserInfo.class)))
                .thenReturn(ResponseEntity.ok(info));
        // 站内已经有人叫 alice
        when(userService.findByUsername("alice")).thenReturn(new User());

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getTokenValue).thenReturn("token");
            assertThat(service.handleLinuxDoCallback("code")).isEqualTo("token");
        }

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userService).save(saved.capture());
        assertThat(saved.getValue().getUsername()).matches("alice_\\d{4}");
    }

    @Test
    void 邀请码注册不给子账户前缀_邀请码不扣() {
        ReflectionTestUtils.setField(service, "passwordLoginEnabled", true);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            assertThatThrownBy(() -> service.register("ai_trader_t7_r2", "123456", "CODE")).isInstanceOf(BizException.class);
            verify(inviteCodes, never()).consume(any());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
