package com.mawai.wiibcommon.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "coindesk")
public class CoinDeskProperties {
    private String newsApiKey;
}
