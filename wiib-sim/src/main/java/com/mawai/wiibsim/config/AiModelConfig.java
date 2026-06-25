package com.mawai.wiibsim.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI模型配置
 * 支持多个AI提供商，按优先级重试
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiModelConfig {

    /**
     * AI模型配置列表（按优先级排序）
     */
    private List<ModelProvider> providers;

    /**
     * 单个模型提供商配置
     */
    @Data
    public static class ModelProvider {
        /**
         * 提供商名称（openai/google/anthropic）
         */
        private String name;

        /**
         * API密钥
         */
        private String apiKey;

        /**
         * API地址（OpenAI Compatible）
         */
        private String baseUrl;

        /**
         * 模型名称
         */
        private String model;

        /**
         * 鉴权方式（authorization / x-api-key）
         */
        private String authType = "authorization";

        /**
         * 优先级（数字越小优先级越高）
         */
        private Integer priority;

        /**
         * 是否启用
         */
        private Boolean enabled = true;

        /**
         * 超时时间（毫秒）
         */
        private Integer timeout = 60000;

        /**
         * 最大重试次数
         */
        private Integer maxRetries = 3;

        /**
         * 采样温度（越大越随机，0更确定）
         */
        private Double temperature;
    }
}
