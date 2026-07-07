package com.mawai.wiibquant.agent.toolkit;

import com.mawai.wiibcommon.market.BinanceRestClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewsToolkitTest {

    private final BinanceRestClient binanceRestClient = mock(BinanceRestClient.class);
    private final NewsToolkit toolkit = new NewsToolkit(binanceRestClient);

    @Test
    void newsSearchStripsSymbolSuffixAndPassesThrough() {
        when(binanceRestClient.getCryptoNews("BTC", 10, "EN")).thenReturn("{\"articles\":[]}");

        String json = toolkit.newsSearch("BTCUSDT", 10);

        assertThat(json).isEqualTo("{\"articles\":[]}");
    }

    @Test
    void newsSearchClampsLimit() {
        when(binanceRestClient.getCryptoNews("BTC", 30, "EN")).thenReturn("{}");

        toolkit.newsSearch("BTCUSDT", 999); // 超上限 → clamp 到 30

        verify(binanceRestClient).getCryptoNews("BTC", 30, "EN");
    }

    @Test
    void newsSearchDegradesWhenNull() {
        when(binanceRestClient.getCryptoNews("BTC", 10, "EN")).thenReturn(null);

        assertThat(toolkit.newsSearch("BTCUSDT", 10)).contains("\"available\":false");
    }

    @Test
    void readArticleDegradesWhenNull() {
        when(binanceRestClient.getArticleDetail("src", "guid1")).thenReturn(null);

        assertThat(toolkit.readNewsArticle("src", "guid1")).contains("Failed");
    }
}
