package com.mawai.wiibsim.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * sim → feed internal API 客户端（照 quant 的 SimInternalClient 惯例）。
 * <p>RestClient + JDK HttpClient；同机 localhost 调用，connect 1s / read 5s——feed 挂了快速失败。
 * 统一带 X-Internal-Token（feed 侧共享 InternalApiFilter 校验）。
 * <p>与 SimInternalClient 的差异：本客户端面向前端 UI，失败直接抛，由控制器转 Result.fail 提示"feed 不在线"，
 * 而非吞掉返回错误 JSON（那是给 agent 推理用的）。
 */
@Slf4j
@Component
public class FeedInternalClient {

    private final RestClient restClient;

    public FeedInternalClient(@Value("${feed.internal.base-url:http://localhost:8081}") String baseUrl,
                              @Value("${internal.api.token:}") String token) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Token", token)
                .requestFactory(factory)
                .build();
    }

    /** 拉 WS 流健康全量快照（原始 JSON 数组）；feed 不可达时抛。 */
    public String getStreams() {
        return restClient.get().uri("/internal/streams").retrieve().body(String.class);
    }

    /** 手动重试指定流，返回 feed 的 ack 原始 JSON；feed 不可达时抛。 */
    public String retry(String name) {
        return restClient.post().uri("/internal/streams/{name}/retry", name).retrieve().body(String.class);
    }
}
