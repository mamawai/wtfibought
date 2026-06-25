package com.mawai.wiibcommon.config;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate配置基类
 * 提供统一的RestTemplate创建方法
 */
public abstract class BaseRestTemplateConfig {

    /**
     * 创建配置好超时的RestTemplate
     *
     * @param connectTimeout 连接超时时间(毫秒)
     * @param readTimeout    读取超时时间(毫秒)
     * @return 配置好的RestTemplate实例
     */
    protected RestTemplate createRestTemplate(int connectTimeout, int readTimeout) {
        RestTemplate restTemplate = new RestTemplate();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        restTemplate.setRequestFactory(factory);

        return restTemplate;
    }
}

