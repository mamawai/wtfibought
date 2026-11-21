package com.mawai.wiibcommon.config;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.List;

/** 调外部 HTTP 接口的客户端都继承它，拿一个带超时的 RestClient */
public abstract class BaseRestClientConfig {

    /** 两个超时都是毫秒 */
    protected RestClient createRestClient(int connectTimeout, int readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .requestFactory(factory)
                // Accept 写明要 JSON，不写的话只会发 */*
                .defaultHeaders(headers -> headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL)))
                .build();
    }
}
