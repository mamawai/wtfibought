package com.mawai.wiibquant.external.yahoo;

import com.mawai.wiibcommon.config.BaseRestTemplateConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Yahoo Finance 图表接口（非官方、免 key）：美股指数与期货、美元指数、美债、VIX、黄金、原油的报价。
 * 不带 User-Agent 或带 Java 默认的那个会被 429，所以请求带自己的 UA。
 */
@Slf4j
@Component
public class YahooFinanceClient extends BaseRestTemplateConfig {

    private static final String BASE_URL = "https://query1.finance.yahoo.com/v8/finance/chart/";

    private final RestTemplate restTemplate = createRestTemplate(5000, 10000);
    private final HttpEntity<Void> request;

    public YahooFinanceClient() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, "wiib/1.0");
        request = new HttpEntity<>(headers);
    }

    /** 一个代码最近 5 个交易日的日线：meta 里有现价、相对昨收的涨跌幅、5 日窗口前一天的收盘。失败回 null */
    public String chart5d(String symbol) {
        URI uri = UriComponentsBuilder.fromUriString(BASE_URL).pathSegment(symbol)
                .queryParam("range", "5d").queryParam("interval", "1d")
                .build().encode().toUri();
        try {
            return restTemplate.exchange(uri, HttpMethod.GET, request, String.class).getBody();
        } catch (Exception e) {
            log.warn("Yahoo chart 获取失败 {}: {}", symbol, e.getMessage());
            return null;
        }
    }
}
