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
public class GoogleConfig extends BaseRestTemplateConfig implements OAuthClientConfig {

    @Value("${google.connect-timeout:3000}")
    private int connectTimeout;

    @Value("${google.read-timeout:5000}")
    private int readTimeout;

    // 默认空串：client-id 为空就不开 Google 登录
    @Value("${google.client-id:}")
    private String clientId;

    @Value("${google.client-secret:}")
    private String clientSecret;

    @Value("${google.redirect-uri:}")
    private String redirectUri;

    @Value("${google.authorize-url:https://accounts.google.com/o/oauth2/v2/auth}")
    private String authorizeUrl;

    @Value("${google.token-url:https://oauth2.googleapis.com/token}")
    private String tokenUrl;

    @Value("${google.user-url:https://openidconnect.googleapis.com/v1/userinfo}")
    private String userUrl;

    /**
     * 授权页地址，前端只再拼上 state；没开 Google 登录返回 null。
     * scope 只要 openid profile（sub、名字、头像），不要邮箱；prompt=select_account 每次都让用户选账号
     */
    public String buildAuthorizeUrl() {
        if (!isEnabled()) return null;
        return UriComponentsBuilder.fromUriString(authorizeUrl)
                .queryParam("client_id", clientId)
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", "openid profile")
                .queryParam("prompt", "select_account")
                .encode().build().toUriString();
    }

    @Bean(name = "googleRestTemplate")
    public RestTemplate googleRestTemplate() {
        return createRestTemplate(connectTimeout, readTimeout);
    }
}
