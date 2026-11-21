package com.mawai.wiibquant.external;

import com.mawai.wiibcommon.config.BaseRestClientConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.net.URI;

/** 免 key 公开 JSON 接口的客户端骨架：带自己的 UA 发 GET，失败回 null */
@Slf4j
public abstract class JsonGetClient extends BaseRestClientConfig {

    private final String name;
    private final RestClient restClient;

    /** @param name 日志里的来源名 */
    protected JsonGetClient(String name, int readTimeoutMs) {
        this.name = name;
        this.restClient = createRestClient(5000, readTimeoutMs).mutate()
                .defaultHeader(HttpHeaders.USER_AGENT, "wiib/1.0")
                .build();
    }

    protected String getBody(URI uri) {
        try {
            return restClient.get().uri(uri).retrieve().body(String.class);
        } catch (Exception e) {
            log.warn("{} 获取失败 {}: {}", name, uri, e.getMessage());
            return null;
        }
    }
}
