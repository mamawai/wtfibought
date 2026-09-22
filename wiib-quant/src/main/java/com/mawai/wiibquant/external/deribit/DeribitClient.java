package com.mawai.wiibquant.external.deribit;
import com.mawai.wiibcommon.config.BaseRestTemplateConfig;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

import org.springframework.web.util.UriComponentsBuilder;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * Deribit 公开 API 客户端（免认证）。
 * 拉期权隐含波动率相关数据：BTC/ETH 有 DVOL + 期权簿，SOL/XRP 只有期权簿，供量化系统使用。
 */
@Slf4j
@Component
public class DeribitClient extends BaseRestTemplateConfig {

    private static final String BASE_URL = "https://www.deribit.com/api/v2/public";
    /** 币本位期权：簿挂在币自己名下（currency=BTC），DVOL 指数也只有这两个 */
    private static final Set<String> INVERSE = Set.of("BTC", "ETH");
    /** USDC 结算的线性期权：簿挂在 currency=USDC 下，合约名形如 SOL_USDC-25SEP26-118-C，没有 DVOL。DOGE/BNB 没期权 */
    private static final Set<String> LINEAR = Set.of("SOL", "XRP");
    private final RestTemplate restTemplate;

    /** 有没有期权数据；没有的调用方直接跳过，不发无谓请求 */
    public static boolean supports(String currency) {
        return bookCurrency(currency) != null;
    }

    /** 有没有 DVOL 指数 */
    public static boolean hasDvol(String currency) {
        return currency != null && INVERSE.contains(currency.toUpperCase(Locale.ROOT));
    }

    /** 期权簿挂在哪个 currency 下：币本位挂币自己名下，线性挂 USDC；null=没期权 */
    static String bookCurrency(String currency) {
        if (currency == null) return null;
        String coin = currency.toUpperCase(Locale.ROOT);
        if (INVERSE.contains(coin)) return coin;
        if (LINEAR.contains(coin)) return "USDC";
        return null;
    }

    public DeribitClient() {
        this.restTemplate = createRestTemplate(5000, 10000);
    }

    /**
     * DVOL 波动率指数（类 VIX）。
     * GET /public/get_volatility_index_data?currency=BTC&resolution=3600
     * 返回最近若干小时的 DVOL 数据点。
     */
    public String getDvolIndex(String currency, int resolution) {
        if (!hasDvol(currency)) return null;
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
     * <p>
     * 线性币取的是整个 USDC 板块（多个币混在一起），筛到只剩本币再返回——下游按"一本簿一个标的"解析。
     */
    public String getBookSummaryByCurrency(String currency) {
        String bookCurrency = bookCurrency(currency);
        if (bookCurrency == null) return null;
        URI uri = UriComponentsBuilder
                .fromUriString(BASE_URL + "/get_book_summary_by_currency")
                .queryParam("currency", bookCurrency)
                .queryParam("kind", "option")
                .build().toUri();
        try {
            log.info("Deribit bookSummary: {}", uri);
            String raw = restTemplate.getForObject(uri, String.class);
            return "USDC".equals(bookCurrency)
                    ? filterBook(raw, currency.toUpperCase(Locale.ROOT) + "_USDC-")
                    : raw;
        } catch (Exception e) {
            log.warn("Deribit bookSummary获取失败: {}", e.getMessage());
            return null;
        }
    }

    /** 只留合约名以 prefix 开头的条目，形状不变 {"result":[...]} */
    static String filterBook(String raw, String prefix) {
        if (raw == null) return null;
        ArrayNode kept = MAPPER.createArrayNode();
        for (JsonNode item : MAPPER.readValue(raw, ObjectNode.class).path("result")) {
            String name = item.path("instrument_name").asString("");
            if (name.startsWith(prefix)) {
                kept.add(item);
            }
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.set("result", kept);
        return MAPPER.writeValueAsString(out);
    }
}
