package com.mawai.wiibcommon.handover;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 调对端实例：地址按槽位推（自己是 a，对端就是 http://wiib-<服务>-b:<端口>），直连不过网关，
 * 带 X-Internal-Token，连接超时 1 秒，读超时 5 秒。没分槽位时没有对端，{@link #available} 是 false
 */
public class PeerClient {

    /** 一般调用的读超时 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    /** 要等对端做完事的调用的读超时：对端执行预案要过 sim 下单重试，最坏二十来秒 */
    private static final Duration SLOW_READ_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient http;
    /** 读超时长的那个，{@link #postSlow} 用 */
    private final RestClient slowHttp;
    /** 转浏览器请求用，不带内部 token */
    private final RestClient relayHttp;
    private final String peerSlot;
    private final String baseUrl;

    public PeerClient(String service, String slot, int port, String token) {
        this.peerSlot = slot.isEmpty() ? null : (slot.equals("a") ? "b" : "a");
        this.baseUrl = peerSlot == null ? "http://localhost" : "http://wiib-" + service + "-" + peerSlot + ":" + port;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(READ_TIMEOUT);
        JdkClientHttpRequestFactory slowFactory = new JdkClientHttpRequestFactory(client);
        slowFactory.setReadTimeout(SLOW_READ_TIMEOUT);
        this.relayHttp = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .build();
        this.http = relayHttp.mutate()
                .defaultHeader("X-Internal-Token", token)
                .build();
        this.slowHttp = http.mutate()
                .requestFactory(slowFactory)
                .build();
    }

    /** 有没有对端：分了槽位才有 */
    public boolean available() {
        return peerSlot != null;
    }

    /** 对端的槽位；没分槽位是 null */
    public String peerSlot() {
        return peerSlot;
    }

    /** 对端的 /internal/handoff/status */
    public JsonNode status() {
        return get("/internal/handoff/status");
    }

    public JsonNode get(String path) {
        return http.get().uri(path).retrieve().body(JsonNode.class);
    }

    public <T> T post(String path, Object body, Class<T> type) {
        return http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(type);
    }

    /** 对端要做完一件事才回的调用，读超时 30 秒 */
    public <T> T postSlow(String path, Object body, Class<T> type) {
        return slowHttp.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(type);
    }

    /** 发过去，不要回包 */
    public void send(String path, Object body) {
        http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
    }

    /**
     * 转一个浏览器请求（不带内部 token）：pathAndQuery 是已经编码好的路径加查询串，原样拼上；body 为 null 不带请求体；
     * 回包按 type 读
     */
    public <T> T relay(HttpMethod method, String pathAndQuery, HttpHeaders headers, Object body,
                       ParameterizedTypeReference<T> type) {
        RestClient.RequestBodySpec spec = relayHttp.method(method).uri(URI.create(baseUrl + pathAndQuery))
                .headers(h -> h.addAll(headers));
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().body(type);
    }
}
