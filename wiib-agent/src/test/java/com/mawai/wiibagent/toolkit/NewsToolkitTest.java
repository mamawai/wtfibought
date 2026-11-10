package com.mawai.wiibagent.toolkit;

import com.mawai.wiibcommon.dto.NewsEventItem;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibquant.market.domain.news.NewsFlash;
import com.mawai.wiibquant.market.service.NewsCache;
import com.mawai.wiibquant.market.service.NewsFlashLocalizer;
import com.mawai.wiibquant.mapper.NewsEventMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewsToolkitTest {

    private final NewsCache newsCache = mock(NewsCache.class);
    private final NewsEventMapper newsEventMapper = mock(NewsEventMapper.class);
    private final NewsToolkit toolkit =
            new NewsToolkit(newsCache, new NewsFlashLocalizer(newsEventMapper));

    private static NewsEventMapper.Translation translation(long sourceId, String title, String content) {
        NewsEventMapper.Translation t = new NewsEventMapper.Translation();
        t.setSourceId(sourceId);
        t.setTitleEn(title);
        t.setContentEn(content);
        return t;
    }

    @Test
    void newsSearchReturnsFlashesFromCacheStrippingHtml() {
        when(newsCache.getFlashes()).thenReturn(List.of(
                new NewsFlash(1L, "美联储维持利率", "<p>据 CME 数据…</p>", "https://x", "2026-07-09 00:30:12")));

        String json = toolkit.newsSearch(AgentLang.ZH, null, null);

        assertThat(json).contains("美联储维持利率").contains("据 CME 数据");
        assertThat(json).doesNotContain("<p>"); // HTML 标签已去除
        verify(newsEventMapper, never()).selectTranslations(anyList());   // 中文一次库都不查
    }

    @Test
    void 英文取译文缺译文的那条回落中文原文() {
        when(newsCache.getFlashes()).thenReturn(List.of(
                new NewsFlash(1L, "美联储维持利率", "<p>据 CME 数据…</p>", "https://x", "2026-07-09 00:30:12"),
                new NewsFlash(2L, "某所遭黑客攻击", "<p>损失约 1 亿美元</p>", "https://y", "2026-07-09 00:31:12")));
        when(newsEventMapper.selectTranslations(List.of(1L, 2L))).thenReturn(List.of(
                translation(1L, "Fed holds rates", "Per CME data...")));

        String json = toolkit.newsSearch(AgentLang.EN, null, null);

        assertThat(json).contains("Fed holds rates").contains("Per CME data")
                .doesNotContain("美联储维持利率");
        // 2 号没有译文行：回落中文原文，绝不拿原文冒充译文
        assertThat(json).contains("某所遭黑客攻击").contains("损失约 1 亿美元");
    }

    @Test
    void newsSearchDegradesWhenCacheEmpty() {
        when(newsCache.getFlashes()).thenReturn(List.of());

        assertThat(toolkit.newsSearch(AgentLang.ZH, null, null)).contains("\"available\":false");
    }

    /** 带关键字就查存档：只给关键字往回看 24 小时，英文取译文；没查到给空数组，不报"没数据" */
    @Test
    void keywordSearchesTheArchive() {
        NewsEventItem hit = new NewsEventItem();
        hit.setId(9L);
        hit.setTitle("ZEC 减半临近");
        hit.setContent("区块奖励将减半");
        hit.setTitleEn("ZEC halving nears");
        hit.setUrl("https://z");
        hit.setPublishedAt(1794283200000L);
        long before = System.currentTimeMillis();
        when(newsEventMapper.search(eq("ZEC"), anyLong(), eq(30))).thenReturn(List.of(hit));

        String json = toolkit.newsSearch(AgentLang.EN, " ZEC ", null);

        // 标题有译文、正文没有：正文回落原文；时刻按北京时间
        assertThat(json).contains("ZEC halving nears").contains("区块奖励将减半").contains("2026-11-10 12:00:00");
        ArgumentCaptor<Long> from = ArgumentCaptor.forClass(Long.class);
        verify(newsEventMapper).search(eq("ZEC"), from.capture(), eq(30));
        assertThat(from.getValue()).isBetween(before - 24 * 3_600_000L, System.currentTimeMillis() - 24 * 3_600_000L);
        verify(newsCache, never()).getFlashes();

        assertThat(toolkit.newsSearch(AgentLang.ZH, "没有这个词", 6)).isEqualTo("[]");
    }
}
