package com.mawai.wiibsim.config;
import com.mawai.wiibcommon.config.BaseRestTemplateConfig;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Getter
@Configuration
public class LinuxDoConfig extends BaseRestTemplateConfig implements OAuthClientConfig {

    @Value("${linuxdo.connect-timeout:3000}")
    private int connectTimeout;

    @Value("${linuxdo.read-timeout:5000}")
    private int readTimeout;

    // 默认空串：不配 linuxdo 时也能启动，client-id 为空就不开 LinuxDo 登录
    @Value("${linuxdo.client-id:}")
    private String clientId;

    @Value("${linuxdo.client-secret:}")
    private String clientSecret;

    @Value("${linuxdo.redirect-uri:}")
    private String redirectUri;

    @Value("${linuxdo.authorize-url:https://connect.linux.do/oauth2/authorize}")
    private String authorizeUrl;

    @Value("${linuxdo.token-url:https://connect.linux.do/oauth2/token}")
    private String tokenUrl;

    @Value("${linuxdo.user-url:https://connect.linux.do/api/user}")
    private String userUrl;

    /** 授权页地址，前端只再拼上 state；没开 LinuxDo 登录返回 null */
    public String buildAuthorizeUrl() {
        if (!isEnabled()) return null;
        return UriComponentsBuilder.fromUriString(authorizeUrl)
                .queryParam("client_id", clientId)
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", redirectUri)
                .encode().build().toUriString();
    }

    @Bean(name = "linuxDoRestTemplate")
    public RestTemplate linuxDoRestTemplate() {
        return createRestTemplate(connectTimeout, readTimeout);
    }
}

