package com.mawai.wiibservice.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * quant → sim internal API 客户端。
 * <p>RestClient + JDK HttpClient（连接池/keep-alive 复用 TCP）；quant/sim 同机 localhost 调用，
 * connect 1s / read 5s 超时——sim 挂了快速失败不拖死 agent。统一带 X-Internal-Token 鉴权头。
 * <p>behavior 及后续「持仓建议」等 agent 功能读 sim 用户数据的统一通道，与 sim 编译解耦。
 */
@Slf4j
@Component
public class SimInternalClient {

    private final RestClient restClient;

    public SimInternalClient(@Value("${sim.internal.base-url:http://localhost:8080}") String baseUrl,
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

    /** GET internal 端点，返回原始 JSON（agent 工具直接当结果用）。失败返回错误 JSON 不抛，避免打断 agent 推理。 */
    public String getJson(String path) {
        try {
            return restClient.get().uri(path).retrieve().body(String.class);
        } catch (Exception e) {
            log.warn("[SimInternal] 调用失败 path={} msg={}", path, e.toString());
            return "{\"error\":\"sim internal api 调用失败: " + e.getMessage() + "\"}";
        }
    }
}
