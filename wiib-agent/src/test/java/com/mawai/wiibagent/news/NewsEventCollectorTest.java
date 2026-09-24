package com.mawai.wiibagent.news;

import com.mawai.wiibagent.runtime.AiAgentRuntime;
import com.mawai.wiibagent.runtime.AiAgentRuntime.NamedModel;
import com.mawai.wiibagent.runtime.AiAgentRuntimeManager;
import com.mawai.wiibquant.external.blockbeats.BlockBeatsNewsClient;
import com.mawai.wiibquant.market.domain.news.NewsFlash;
import com.mawai.wiibquant.market.service.NewsCache;
import com.mawai.wiibquant.mapper.NewsEventMapper;
import com.mawai.wiibquant.mapper.NewsEventMapper.Untranslated;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
 * 采集轨的语义：中文拉到就入库不等翻译（增量去重）；待译行按主备模型补译回填，
 * 译不成的留着待译下轮再试；功能位没配也照样存中文。手动补拉最近 N 条按页凑够、只存中文，失败或翻到头即停。
 */
class NewsEventCollectorTest {

    private final NewsCache newsCache = mock(NewsCache.class);
    private final BlockBeatsNewsClient client = mock(BlockBeatsNewsClient.class);
    private final NewsTranslator translator = mock(NewsTranslator.class);
    private final NewsEventMapper mapper = mock(NewsEventMapper.class);
    private final AiAgentRuntimeManager runtimeManager = mock(AiAgentRuntimeManager.class);
    private final List<NamedModel> models = List.of(new NamedModel("light-model", mock(ChatModel.class)));

    private NewsEventCollector collector() {
        NewsEventCollector c = new NewsEventCollector(newsCache, client, translator, mapper, runtimeManager);
        ReflectionTestUtils.setField(c, "enabled", true);
        return c;
    }

    private static NewsTranslator.Translated translated(String titleEn, String contentEn) {
        return new NewsTranslator.Translated(titleEn, contentEn, "light-model");
    }

    private static NewsFlash flash(long id, String title) {
        return new NewsFlash(id, title, "<p>正文</p>", "https://x.com/1", "2026-08-12 14:03:00");
    }

    private static Untranslated pending(long id) {
        Untranslated r = new Untranslated();
        r.setSourceId(id);
        r.setTitle("标题" + id);
        r.setContent("正文");
        return r;
    }

    private void runtimeReady() {
        when(runtimeManager.current()).thenReturn(new AiAgentRuntime(models));
    }

    @Test
    void 新快讯先存中文不等翻译() {
        when(newsCache.getFlashes()).thenReturn(List.of(flash(1, "BTC 突破十万")));
        when(mapper.selectExistingSourceIds(anyList())).thenReturn(List.of());

        collector().collect();

        verify(mapper).insertIgnore(eq(1L), eq("BTC 突破十万"), eq("正文"), eq("https://x.com/1"), anyLong());
    }

    @Test
    void 已入库的快讯不再插() {
        when(newsCache.getFlashes()).thenReturn(List.of(flash(1, "旧闻"), flash(2, "新闻")));
        when(mapper.selectExistingSourceIds(anyList())).thenReturn(List.of(1L));

        collector().collect();

        verify(mapper, never()).insertIgnore(eq(1L), anyString(), anyString(), anyString(), anyLong());
        verify(mapper).insertIgnore(eq(2L), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void 没拉到快讯也照样补译积压() {
        runtimeReady();
        when(newsCache.getFlashes()).thenReturn(List.of());
        when(mapper.selectUntranslated(anyInt())).thenReturn(List.of(pending(1)));
        when(translator.translate(any(), anyList())).thenReturn(Map.of(1L, translated("a", "b")));

        collector().collect();

        verify(mapper, never()).selectExistingSourceIds(anyList());   // 空列表拼不出 IN ()
        verify(mapper).updateTranslation(1L, "a", "b", "light-model");
    }

    @Test
    void 待译行译好回填且记模型名() {
        runtimeReady();
        when(newsCache.getFlashes()).thenReturn(List.of());
        List<Untranslated> rows = List.of(pending(1));
        when(mapper.selectUntranslated(anyInt())).thenReturn(rows);
        when(translator.translate(any(), anyList())).thenReturn(Map.of(1L, translated("BTC tops 100k", "Body")));

        collector().collect();

        verify(translator).translate(models, rows);
        verify(mapper).updateTranslation(1L, "BTC tops 100k", "Body", "light-model");
    }

    @Test
    void 没译成的那条也回填模型名译文列写NULL() {
        // 模型过了这批只是没给译文：记上模型名算处理完，不再重试；不拿原文冒充译文
        runtimeReady();
        when(newsCache.getFlashes()).thenReturn(List.of());
        when(mapper.selectUntranslated(anyInt())).thenReturn(List.of(pending(1)));
        when(translator.translate(any(), anyList())).thenReturn(Map.of(1L, translated(null, null)));

        collector().collect();

        verify(mapper).updateTranslation(eq(1L), isNull(), isNull(), eq("light-model"));
    }

    @Test
    void 译文产出里没有的那条不回填留着待译() {
        // 它那一批主备全挂：translated_model 保持 NULL，下轮再译
        runtimeReady();
        when(newsCache.getFlashes()).thenReturn(List.of());
        when(mapper.selectUntranslated(anyInt())).thenReturn(List.of(pending(1), pending(2)));
        when(translator.translate(any(), anyList())).thenReturn(Map.of(2L, translated(null, null)));

        collector().collect();

        verify(mapper, never()).updateTranslation(eq(1L), any(), any(), any());
        verify(mapper).updateTranslation(eq(2L), any(), any(), eq("light-model"));
    }

    @Test
    void 没有待译行不调翻译() {
        when(newsCache.getFlashes()).thenReturn(List.of());
        when(mapper.selectUntranslated(anyInt())).thenReturn(List.of());

        collector().collect();

        verify(translator, never()).translate(any(), anyList());
    }

    @Test
    void 功能位未配置时中文照存只是不译() {
        when(runtimeManager.current()).thenThrow(new IllegalStateException("AI未配置"));
        when(newsCache.getFlashes()).thenReturn(List.of(flash(1, "新闻")));
        when(mapper.selectExistingSourceIds(anyList())).thenReturn(List.of());
        when(mapper.selectUntranslated(anyInt())).thenReturn(List.of(pending(1)));

        collector().collect();

        verify(mapper).insertIgnore(eq(1L), anyString(), anyString(), anyString(), anyLong());
        verify(translator, never()).translate(any(), anyList());
    }

    @Test
    void 开关关掉时连快讯都不拉() {
        NewsEventCollector c = collector();
        ReflectionTestUtils.setField(c, "enabled", false);

        c.collect();

        verify(newsCache, never()).getFlashes();
    }

    /** id 从 from 到 to 的一串快讯，模拟接口一页 */
    private static List<NewsFlash> flashes(long from, long to) {
        return LongStream.rangeClosed(from, to).mapToObj(i -> flash(i, "第" + i + "条")).toList();
    }

    private void insertsSucceed() {
        when(mapper.insertIgnore(anyLong(), anyString(), anyString(), anyString(), anyLong())).thenReturn(1);
    }

    @Test
    void 补最近N条按页凑够只存中文不翻译() {
        when(client.fetchImportant(1, 100)).thenReturn(flashes(1, 100));
        when(client.fetchImportant(2, 100)).thenReturn(flashes(101, 200));
        when(mapper.selectExistingSourceIds(anyList())).thenReturn(List.of(2L));
        insertsSucceed();

        NewsEventCollector.BackfillResult r = collector().backfill(150);

        // 第二页只取到够数；已有的那条跳过
        assertThat(r).isEqualTo(new NewsEventCollector.BackfillResult(150, 149, false));
        verify(mapper, never()).insertIgnore(eq(2L), anyString(), anyString(), anyString(), anyLong());
        verify(mapper, never()).insertIgnore(eq(151L), anyString(), anyString(), anyString(), anyLong());
        verify(translator, never()).translate(any(), anyList());   // 译文交给定时轮
    }

    @Test
    void 补一百条只调一次接口() {
        when(client.fetchImportant(1, 100)).thenReturn(flashes(1, 100));
        when(mapper.selectExistingSourceIds(anyList())).thenReturn(List.of());
        insertsSucceed();

        assertThat(collector().backfill(100)).isEqualTo(new NewsEventCollector.BackfillResult(100, 100, false));
        verify(client, never()).fetchImportant(2, 100);
    }

    @Test
    void 不满一页就当翻到头不再往后翻() {
        when(client.fetchImportant(1, 100)).thenReturn(flashes(1, 30));
        when(mapper.selectExistingSourceIds(anyList())).thenReturn(List.of());
        insertsSucceed();

        assertThat(collector().backfill(500)).isEqualTo(new NewsEventCollector.BackfillResult(30, 30, false));
        verify(client, never()).fetchImportant(2, 100);
    }

    @Test
    void 补拉中途失败报出已拉条数不再往后翻() {
        when(client.fetchImportant(1, 100)).thenReturn(flashes(1, 100));
        when(client.fetchImportant(2, 100)).thenReturn(null);
        when(mapper.selectExistingSourceIds(anyList())).thenReturn(List.of());
        insertsSucceed();

        assertThat(collector().backfill(300)).isEqualTo(new NewsEventCollector.BackfillResult(100, 100, true));
        verify(client, never()).fetchImportant(3, 100);
    }
}
