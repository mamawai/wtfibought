package com.mawai.wiibquant.agent.toolkit;

import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.market.BinanceRestClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 新闻工具：列表搜索 + 单篇详读（原孤儿 CryptoNewsTool 的 readNewsArticle 并入此处）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsToolkit {

    private static final int MAX_LIMIT = 30;

    private final BinanceRestClient binanceRestClient;

    @Tool(name = "news_search", description = """
            Search recent crypto news for a symbol. Returns a JSON list of articles with
            title, summary, source_key and guid. Use read_news_article for full content.""")
    public String newsSearch(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol,
                             @ToolParam(description = "Max articles, 1-30, default 10") int limit) {
        String coin = normalizeCoin(symbol);
        int clamped = Math.clamp(limit <= 0 ? 10 : limit, 1, MAX_LIMIT);
        String result = binanceRestClient.getCryptoNews(coin, clamped, "EN");
        if (result == null || result.isBlank()) {
            JSONObject out = new JSONObject();
            out.put("available", false);
            out.put("reason", "news feed unavailable for " + coin);
            return out.toJSONString();
        }
        return result;
    }

    @Tool(name = "read_news_article", description = "Get full article content by source_key and guid from news_search results.")
    public String readNewsArticle(
            @ToolParam(description = "The source key of the article (e.g., 'bitcoinworld')") String sourceKey,
            @ToolParam(description = "The guid/url identifier of the article") String guid) {
        log.info("[Toolkit] 读新闻详情 sourceKey={} guid={}", sourceKey, guid);
        String result = binanceRestClient.getArticleDetail(sourceKey, guid);
        return result != null ? result : "Failed to fetch article content";
    }

    private static String normalizeCoin(String symbol) {
        String s = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        return s.replace("USDT", "").replace("USDC", "");
    }
}
