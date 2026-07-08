package com.mawai.wiibquant.agent.toolkit;

import com.mawai.wiibquant.agent.quant.domain.news.NewsFlash;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewsToolkitTest {

    private final NewsCache newsCache = mock(NewsCache.class);
    private final NewsToolkit toolkit = new NewsToolkit(newsCache);

    @Test
    void newsSearchReturnsFlashesFromCacheStrippingHtml() {
        when(newsCache.getFlashes()).thenReturn(List.of(
                new NewsFlash(1L, "美联储维持利率", "<p>据 CME 数据…</p>", "https://x", "2026-07-09 00:30:12")));

        String json = toolkit.newsSearch();

        assertThat(json).contains("美联储维持利率").contains("据 CME 数据");
        assertThat(json).doesNotContain("<p>"); // HTML 标签已去除
    }

    @Test
    void newsSearchDegradesWhenCacheEmpty() {
        when(newsCache.getFlashes()).thenReturn(List.of());

        assertThat(toolkit.newsSearch()).contains("\"available\":false");
    }
}
