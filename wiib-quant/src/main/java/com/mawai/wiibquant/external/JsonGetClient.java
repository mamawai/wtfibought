package com.mawai.wiibquant.external;

import com.mawai.wiibcommon.config.BaseRestTemplateConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

/** 免 key 公开 JSON 接口的客户端骨架：带自己的 UA 发 GET，失败回 null */
@Slf4j
public abstract class JsonGetClient extends BaseRestTemplateConfig {

    private final String name;
    private final RestTemplate restTemplate;
    private final HttpEntity<Void> request;

    /** @param name 日志里的来源名 */
    protected JsonGetClient(String name, int readTimeoutMs) {
        this.name = name;
        this.restTemplate = createRestTemplate(5000, readTimeoutMs);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, "wiib/1.0");
        this.request = new HttpEntity<>(headers);
    }

    protected String getBody(URI uri) {
        try {
            return restTemplate.exchange(uri, HttpMethod.GET, request, String.class).getBody();
        } catch (Exception e) {
            log.warn("{} 获取失败 {}: {}", name, uri, e.getMessage());
            return null;
        }
    }
}
