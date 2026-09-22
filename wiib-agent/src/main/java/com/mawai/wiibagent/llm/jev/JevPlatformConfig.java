package com.mawai.wiibagent.llm.jev;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 平台自己的 Jev（环境变量 JEV_API_KEY / JEV_BASE_URL / JEV_MODEL），只给预测员用，
 * 与用户 BYOK 的 user_jev_config 是两回事。key 为空即未启用。
 */
@Getter
@Component
public class JevPlatformConfig {

    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public JevPlatformConfig(@Value("${jev.platform.api-key:}") String apiKey,
                             @Value("${jev.platform.base-url:https://api.typesafe.ai}") String baseUrl,
                             @Value("${jev.platform.model:jev-latest}") String model) {
        this.apiKey = apiKey.trim();
        // .env 里写了空的 JEV_BASE_URL= / JEV_MODEL= 时占位符默认值不生效，拿到的是空串
        this.baseUrl = baseUrl.isBlank() ? JevClient.DEFAULT_BASE_URL : baseUrl.trim();
        this.model = model.isBlank() ? JevClient.DEFAULT_MODEL : model.trim();
    }

    public boolean enabled() {
        return !apiKey.isEmpty();
    }
}
