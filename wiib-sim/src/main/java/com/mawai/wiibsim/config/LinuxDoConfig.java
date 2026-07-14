package com.mawai.wiibsim.config;
import com.mawai.wiibcommon.config.BaseRestTemplateConfig;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Getter
@Configuration
public class LinuxDoConfig extends BaseRestTemplateConfig {

    @Value("${linuxdo.connect-timeout:3000}")
    private int connectTimeout;

    @Value("${linuxdo.read-timeout:5000}")
    private int readTimeout;

    // 默认空串：不配 linuxdo 时也能启动，client-id 为空即进入"仅管理员直登"模式
    @Value("${linuxdo.client-id:}")
    private String clientId;

    @Value("${linuxdo.client-secret:}")
    private String clientSecret;

    @Value("${linuxdo.redirect-uri:}")
    private String redirectUri;

    @Value("${linuxdo.token-url:https://connect.linux.do/oauth2/token}")
    private String tokenUrl;

    @Value("${linuxdo.user-url:https://connect.linux.do/api/user}")
    private String userUrl;

    /** LinuxDo OAuth 是否启用：client-id 配了就走 OAuth，没配就走仅管理员直登 */
    public boolean isEnabled() {
        return StringUtils.hasText(clientId);
    }

    @Bean(name = "linuxDoRestTemplate")
    public RestTemplate linuxDoRestTemplate() {
        return createRestTemplate(connectTimeout, readTimeout);
    }
}

