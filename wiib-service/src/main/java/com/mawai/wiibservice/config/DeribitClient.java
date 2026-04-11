package com.mawai.wiibservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

import org.springframework.web.util.UriComponentsBuilder;

/**
 * Deribit 公开 API 客户端（免认证）。
 * 仅拉取 BTC 期权隐含波动率相关数据，供量化系统使用。
 */
@Slf4j
@Component
public class DeribitClient extends BaseRestTemplateConfig {

    private static final String BASE_URL = "https://www.deribit.com/api/v2/public";
    private final RestTemplate restTemplate;

    public DeribitClient() {
        this.restTemplate = createRestTemplate(5000, 10000);
    }

    /**
     * DVOL 波动率指数（类 VIX）。
     * GET /public/get_volatility_index_data?currency=BTC&resolution=3600
     * 返回最近若干小时的 DVOL 数据点。
     */
    public String getDvolIndex(String currency, int resolution) {
        URI uri = UriComponentsBuilder
                .fromUriString(BASE_URL + "/get_volatility_index_data")
                .queryParam("currency", currency)
                .queryParam("resolution", resolution)
                .queryParam("start_timestamp", System.currentTimeMillis() - 3600_000 * 6)
                .queryParam("end_timestamp", System.currentTimeMillis())
                .build().toUri();
        try {
            log.info("Deribit DVOL: {}", uri);
            return restTemplate.getForObject(uri, String.class);
        } catch (Exception e) {
            log.warn("Deribit DVOL获取失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 期权 book summary（全量）。
     * GET /public/get_book_summary_by_currency?currency=BTC&kind=option
     * 返回所有活跃期权合约的 mark_iv / underlying_price / instrument_name 等。
     * 下游用于计算 ATM IV、25d skew、term structure。
     */
    public String getBookSummaryByCurrency(String currency) {
        URI uri = UriComponentsBuilder
                .fromUriString(BASE_URL + "/get_book_summary_by_currency")
                .queryParam("currency", currency)
                .queryParam("kind", "option")
                .build().toUri();
        try {
            log.info("Deribit bookSummary: {}", uri);
            return restTemplate.getForObject(uri, String.class);
        } catch (Exception e) {
            log.warn("Deribit bookSummary获取失败: {}", e.getMessage());
            return null;
        }
    }
}
