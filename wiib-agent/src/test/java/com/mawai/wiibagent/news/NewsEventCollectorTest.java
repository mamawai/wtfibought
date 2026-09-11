package com.mawai.wiibagent.news;

import com.mawai.wiibagent.runtime.AiAgentRuntime;
import com.mawai.wiibagent.runtime.AiAgentRuntimeManager;
import com.mawai.wiibquant.market.domain.news.NewsFlash;
import com.mawai.wiibquant.market.service.NewsCache;
import com.mawai.wiibquant.mapper.NewsEventMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采集轨的三条语义：增量去重（存量不再打标不再插）、失败跳过本轮（快讯还在拉取窗口里，
 * 下轮自愈——硬插空标会让这批永远失去打标机会）、正常路标签与译文随行落库。
 */
class NewsEventCollectorTest {

    private final NewsCache newsCache = mock(NewsCache.class);
    private final NewsTagger tagger = mock(NewsTagger.class);
    private final NewsEventMapper mapper = mock(NewsEventMapper.class);
    private final AiAgentRuntimeManager runtimeManager = mock(AiAgentRuntimeManager.class);

    private NewsEventCollector collector() {
        NewsEventCollector c = new NewsEventCollector(newsCache, tagger, mapper, runtimeManager);
        ReflectionTestUtils.setField(c, "enabled", true);
        ReflectionTestUtils.setField(c, "vocabulary", List.of("OIL", "GOLD", "BTC"));
        return c;
    }

    private static NewsTagger.Tagged tagged(String tags, String titleEn, String contentEn) {
        return new NewsTagger.Tagged(tags, titleEn, contentEn);
    }

    private static NewsFlash flash(long id, String title) {
        return new NewsFlash(id, title, "<p>正文</p>", "https://x.com/1", "2026-08-12 14:03:00");
    }

    private void runtimeReady() {
        when(runtimeManager.current()).thenReturn(
                new AiAgentRuntime(mock(ChatModel.class), "light-model"));
    }

    @Test
    void 新快讯打标后落库且标签译文与模型名随行() {
        runtimeReady();
        when(newsCache.getFlashes()).thenReturn(List.of(flash(1, "BTC 突破十万")));
        when(mapper.selectExistingSourceIds(anyList())).thenReturn(List.of());
        when(tagger.tag(any(), anyList(), anyList()))
                .thenReturn(Map.of(1L, tagged("BTC", "BTC tops 100k", "Body")));

        collector().collect();

        verify(mapper).insertIgnore(eq(1L), eq("BTC 突破十万"), eq("正文"),
                eq("BTC tops 100k"), eq("Body"), anyString(), anyLong(), eq("BTC"), eq("light-model"));
    }

    @Test
    void 没译成的那条译文列写NULL不拿原文冒充() {
        runtimeReady();
        when(newsCache.getFlashes()).thenReturn(List.of(flash(1, "BTC 突破十万")));
        when(mapper.selectExistingSourceIds(anyList())).thenReturn(List.of());
        when(tagger.tag(any(), anyList(), anyList()))
                .thenReturn(Map.of(1L, tagged("BTC", null, null)));

        collector().collect();

        verify(mapper).insertIgnore(eq(1L), eq("BTC 突破十万"), eq("正文"),
                isNull(), isNull(), anyString(), anyLong(), eq("BTC"), eq("light-model"));
    }

    @Test
    void 打标产出里没有的那条本轮不落库() {
        // 它那一批挂了：落了空标就再没有打标机会（去重键挡住重入），留到下轮重试
        runtimeReady();
        when(newsCache.getFlashes()).thenReturn(List.of(flash(1, "甲"), flash(2, "乙")));
        when(mapper.selectExistingSourceIds(anyList())).thenReturn(List.of());
        when(tagger.tag(any(), anyList(), anyList()))
                .thenReturn(Map.of(2L, tagged("", null, null)));

        collector().collect();

        verify(mapper, never()).insertIgnore(eq(1L), anyString(), anyString(), any(), any(),
                anyString(), anyLong(), anyString(), anyString());
        verify(mapper).insertIgnore(eq(2L), anyString(), anyString(), any(), any(),
                anyString(), anyLong(), eq(""), anyString());
    }

    @Test
    void 已入库的快讯不再打标不再插() {
        runtimeReady();
        when(newsCache.getFlashes()).thenReturn(List.of(flash(1, "旧闻"), flash(2, "新闻")));
        when(mapper.selectExistingSourceIds(anyList())).thenReturn(List.of(1L));
        when(tagger.tag(any(), anyList(), anyList())).thenReturn(Map.of(2L, tagged("", null, null)));

        collector().collect();

        // 只有新的那条进打标与落库；打标调用的名单也只有它——存量白烧打标的钱正是要防的
        verify(tagger).tag(any(), eq(List.of(flash(2, "新闻"))), anyList());
        verify(mapper, never()).insertIgnore(eq(1L), anyString(), anyString(), any(), any(),
                anyString(), anyLong(), anyString(), anyString());
        verify(mapper).insertIgnore(eq(2L), anyString(), anyString(), any(), any(),
                anyString(), anyLong(), eq(""), anyString());
    }

    @Test
    void 打标失败本轮一条都不插() {
        runtimeReady();
        when(newsCache.getFlashes()).thenReturn(List.of(flash(1, "新闻")));
        when(mapper.selectExistingSourceIds(anyList())).thenReturn(List.of());
        when(tagger.tag(any(), anyList(), anyList())).thenThrow(new RuntimeException("上游挂了"));

        collector().collect();

        verify(mapper, never()).insertIgnore(anyLong(), anyString(), anyString(), any(), any(),
                anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void 功能位未配置时跳过不插() {
        when(runtimeManager.current()).thenThrow(new IllegalStateException("AI未配置"));
        when(newsCache.getFlashes()).thenReturn(List.of(flash(1, "新闻")));
        when(mapper.selectExistingSourceIds(anyList())).thenReturn(List.of());

        collector().collect();

        verify(mapper, never()).insertIgnore(anyLong(), anyString(), anyString(), any(), any(),
                anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void 开关关掉时连快讯都不拉() {
        NewsEventCollector c = collector();
        ReflectionTestUtils.setField(c, "enabled", false);

        c.collect();

        verify(newsCache, never()).getFlashes();
    }
}
