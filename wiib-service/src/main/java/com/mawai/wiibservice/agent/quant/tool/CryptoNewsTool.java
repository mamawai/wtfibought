package com.mawai.wiibservice.agent.quant.tool;

import com.mawai.wiibcommon.market.BinanceRestClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CryptoNewsTool {

    private final BinanceRestClient binanceRestClient;

    @Tool(name = "readNewsArticle", description = "Get full article content by source_key and guid. Use this when you need to read a specific news article in detail.")
    public String getArticle(
            @ToolParam(description = "The source key of the article (e.g., 'bitcoinworld', 'cointurken')") String sourceKey,
            @ToolParam(description = "The guid/url identifier of the article") String guid) {
        log.info("Fetching article: sourceKey={}, guid={}", sourceKey, guid);
        String result = binanceRestClient.getArticleDetail(sourceKey, guid);
        if (result == null) {
            return "Failed to fetch article content";
        }
        return result;
    }
}
