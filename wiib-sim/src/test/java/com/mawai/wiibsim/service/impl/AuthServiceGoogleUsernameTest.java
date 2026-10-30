package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.limiter.TokenBucketLimiter;
import com.mawai.wiibsim.config.GoogleConfig;
import com.mawai.wiibsim.config.LinuxDoConfig;
import com.mawai.wiibsim.mapper.InviteCodeMapper;
import com.mawai.wiibsim.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Google 名字转站内用户名：只剩注册允许的字符，撞名、太短都补随机后缀，补完也不超 20 个字 */
class AuthServiceGoogleUsernameTest {

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
        UserService userService = mock(UserService.class);
        when(userService.findByUsername("John_Smith")).thenReturn(new User());
        when(userService.findByUsername("abcdefghijklmnopqrst")).thenReturn(new User());
        AuthServiceImpl service = new AuthServiceImpl(userService, mock(LinuxDoConfig.class), mock(RestTemplate.class),
                mock(GoogleConfig.class), mock(RestTemplate.class), mock(InviteCodeMapper.class), mock(MessageCatalog.class),
                mock(TokenBucketLimiter.class));

        assertThat(service.googleUsername("Alice")).isEqualTo("Alice");
        assertThat(service.googleUsername("John Smith")).matches("John_Smith_\\d{4}");
        assertThat(service.googleUsername("明")).matches("明_\\d{4}");
        assertThat(service.googleUsername("abcdefghijklmnopqrst")).matches("abcdefghijklmno_\\d{4}");
    }
}
