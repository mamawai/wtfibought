package com.mawai.wiibquant.external.blockbeats;

import com.mawai.wiibcommon.config.BaseRestTemplateConfig;
import com.mawai.wiibquant.market.domain.news.NewsFlash;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * BlockBeats(律动)重要快讯客户端。
 * GET /v1/newsflash/important，api-key 走请求头；解析 data.data[] → NewsFlash 列表。
 * 平时只经 NewsCache 调用；Admin 手动补拉直连翻页（免费额度有限，见 {@link BlockBeatsProperties}）。
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
     * 拉取重要快讯第一页。
     * 失败返回 null（区别于"成功但空列表"）——缓存层据此决定沿用旧缓存/过期置 NO_NEWS。
     */
    public List<NewsFlash> fetchImportant() {
        return fetchImportant(1, props.getSize());
    }

    /** 按页拉，新的在前；手动补拉往前翻页用。失败返回 null */
    public List<NewsFlash> fetchImportant(int page, int size) {
        URI uri = UriComponentsBuilder
                .fromUriString(props.getBaseUrl() + "/v1/newsflash/important")
                .queryParam("page", page)
                .queryParam("size", size)
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
        JsonNode root = MAPPER.readTree(body);
        if (root.path("status").asInt(-1) != 0) {
            log.warn("[BlockBeats] 响应异常: {}", body.length() > 200 ? body.substring(0, 200) : body);
            return null;
        }
        JsonNode arr = root.path("data").path("data");
        if (!arr.isArray()) return List.of();
        List<NewsFlash> out = new ArrayList<>(arr.size());
        for (JsonNode it : arr) {
            if (it.isNull()) continue;
            out.add(new NewsFlash(
                    it.path("id").asLong(0),
                    it.path("title").asString(null),
                    it.path("content").asString(null),
                    it.path("url").asString(null),
                    it.path("create_time").asString(null)));
        }
        return out;
    }
}
