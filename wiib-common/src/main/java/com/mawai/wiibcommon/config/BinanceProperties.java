package com.mawai.wiibcommon.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "binance")
public class BinanceProperties {
    private String wsUrl;
    private String futuresWsUrl;
    private String restBaseUrl;
    private String futuresRestBaseUrl;
    private String newsApiKey;
    private List<String> symbols;
    private long fallbackPollInterval;
}
