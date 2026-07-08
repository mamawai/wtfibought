package com.mawai.wiibquant.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.config.BaseRestTemplateConfig;
import com.mawai.wiibquant.agent.quant.domain.news.NewsFlash;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * BlockBeats(律动)重要快讯客户端。
 * GET /v1/newsflash/important，api-key 走请求头；解析 data.data[] → NewsFlash 列表。
 * 仅供 NewsCache 的 poller 调用（免费额度有限，见 {@link BlockBeatsProperties}）。
 */
@Slf4j
@Component
public class BlockBeatsNewsClient extends BaseRestTemplateConfig {

    private final BlockBeatsProperties props;
    private final RestTemplate restTemplate;

    public BlockBeatsNewsClient(BlockBeatsProperties props) {
        this.props = props;
        this.restTemplate = createRestTemplate(5000, 10000);
    }

    /**
     * 拉取重要快讯。
     * 失败返回 null（区别于"成功但空列表"）——缓存层据此决定沿用旧缓存/过期置 NO_NEWS。
     */
    public List<NewsFlash> fetchImportant() {
        URI uri = UriComponentsBuilder
                .fromUriString(props.getBaseUrl() + "/v1/newsflash/important")
                .queryParam("page", 1)
                .queryParam("size", props.getSize())
                .queryParam("lang", props.getLang())
                .build().toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.set("api-key", props.getApiKey());
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return parse(resp.getBody());
        } catch (Exception e) {
            log.warn("[BlockBeats] 重要快讯拉取失败: {}", e.getMessage());
            return null;
        }
    }

    /** 解析 {status,message,data:{page,data:[...]}}；status!=0 或结构异常返回 null。 */
    private List<NewsFlash> parse(String body) {
        if (body == null || body.isBlank()) return null;
        // byte 模式解析：与 BuildFeaturesBuilder 同口径，绕开 fastjson2 char buffer 扩容 bug
        JSONObject root = JSON.parseObject(body.getBytes(StandardCharsets.UTF_8));
        if (root == null || root.getIntValue("status", -1) != 0) {
            log.warn("[BlockBeats] 响应异常: {}", body.length() > 200 ? body.substring(0, 200) : body);
            return null;
        }
        JSONObject data = root.getJSONObject("data");
        JSONArray arr = data == null ? null : data.getJSONArray("data");
        if (arr == null) return List.of();
        List<NewsFlash> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JSONObject it = arr.getJSONObject(i);
            if (it == null) continue;
            out.add(new NewsFlash(
                    it.getLongValue("id"),
                    it.getString("title"),
                    it.getString("content"),
                    it.getString("url"),
                    it.getString("create_time")));
        }
        return out;
    }
}
