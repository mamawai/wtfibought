package com.mawai.wiibservice.config;
import com.mawai.wiibcommon.config.BaseRestTemplateConfig;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Getter
@Configuration
public class LinuxDoConfig extends BaseRestTemplateConfig {

    @Value("${linuxdo.connect-timeout:3000}")
    private int connectTimeout;

    @Value("${linuxdo.read-timeout:5000}")
    private int readTimeout;

    @Value("${linuxdo.client-id}")
    private String clientId;

    @Value("${linuxdo.client-secret}")
    private String clientSecret;

    @Value("${linuxdo.redirect-uri}")
    private String redirectUri;

    @Value("${linuxdo.token-url:https://connect.linux.do/oauth2/token}")
    private String tokenUrl;

    @Value("${linuxdo.user-url:https://connect.linux.do/api/user}")
    private String userUrl;

    @Bean(name = "linuxDoRestTemplate")
    public RestTemplate linuxDoRestTemplate() {
        return createRestTemplate(connectTimeout, readTimeout);
    }
}

