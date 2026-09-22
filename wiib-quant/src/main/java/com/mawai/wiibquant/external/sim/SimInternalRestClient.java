package com.mawai.wiibquant.external.sim;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 三个 sim internal 客户端共用的 RestClient 装配：同机 localhost、X-Internal-Token 鉴权、
 * connect 1s / read 5s 短超时（sim 挂了快速失败不拖死 agent）。
 */
final class SimInternalRestClient {

    private SimInternalRestClient() {
    }

    static RestClient build(String baseUrl, String token) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Token", token)
                .requestFactory(factory)
                .build();
    }
}
