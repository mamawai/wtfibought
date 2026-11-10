package com.mawai.wiibagent.toolkit;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibquant.market.service.NewsCache;
import com.mawai.wiibquant.market.service.NewsFlashLocalizer;
import com.mawai.wiibquant.market.service.NewsFlashLocalizer.LocalizedFlash;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 新闻工具（对话 news_agent 预取 + trader 唤醒挂工具）：不带参数返回缓存里的重要快讯列表，
 * 带关键字或时间窗查本地存档（news_event）。快讯短、信息密——直接给全量正文，不再有单篇精读。
 * <p>
 * 源是 BlockBeats 中文快讯：按语言取一份（英文取采集轨补译的译文，缺译文回落中文原文），
 * 与首页快讯卡取的是同一份，不会出现"用户看到译文、trader 读到原文"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsToolkit {

    /** 查存档时默认往回看多久、最多往回看多久（小时） */
    static final int DEFAULT_HOURS = 24;
    static final int MAX_HOURS = 168;
    /** 查存档一次最多给几条 */
    static final int ARCHIVE_LIMIT = 30;

    private final NewsCache newsCache;
    private final NewsFlashLocalizer localizer;

    /**
     * 两个参数都不传：缓存里最新的那批快讯。传了关键字或小时数：查本地存档，
     * 往回看 hours 小时（没传按 24，最多 168）里标题或正文含关键字的，最多 30 条。
     */
    public String newsSearch(AgentLang lang, String keyword, Integer hours) {
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        boolean archive = hasKeyword || hours != null;
        List<LocalizedFlash> flashes = archive
                ? localizer.search(hasKeyword ? keyword.strip() : null,
                        System.currentTimeMillis() - Math.clamp(hours == null ? DEFAULT_HOURS : hours, 1, MAX_HOURS) * 3_600_000L,
                        ARCHIVE_LIMIT, lang)
                : localizer.localize(newsCache.getFlashes(), lang);
        log.info("[NewsTool] news_search 被调用（{}，keyword={} hours={}），快讯 {} 条", lang.code(), keyword, hours, flashes.size());
        if (flashes.isEmpty() && !archive) {
            ObjectNode out = MAPPER.createObjectNode();
            out.put("available", false);
            out.put("reason", "news feed unavailable");
            return MAPPER.writeValueAsString(out);
        }
        ArrayNode arr = MAPPER.createArrayNode();
        for (LocalizedFlash f : flashes) {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("title", f.title());
            o.put("content", f.plain());
            o.put("source", f.url());
            o.put("time", f.createTime());
            arr.add(o);
        }
        return MAPPER.writeValueAsString(arr);
    }

    /** 建叶子时取一个绑定语言的工具视图挂上去 */
    public NewsSearchTool boundTo(AgentLang lang) {
        return new NewsSearchTool(this, lang);
    }

    /**
     * 语言在建叶子时烤进来，<b>不做成工具参数</b>——做成参数就等于让模型自己挑新闻用哪门语言，
     * 同 {@code TraderQueryToolkit} 烤 userId 的道理。
     */
    @RequiredArgsConstructor
    public static class NewsSearchTool {

        private final NewsToolkit toolkit;
        private final AgentLang lang;

        // 描述按语言取自词表 tool.news_search，注解这份只当词表缺失时的兜底
        @Tool(name = "news_search", description = """
                Market news flashes (title + full body + source + time). With no arguments: the latest batch.
                With keyword and/or hours: search the local archive, newest first, at most 30 flashes.""")
        public String newsSearch(
                @ToolParam(required = false, description = "Keyword to match in title or body, Chinese or English, case-insensitive (e.g. ETH, 解锁, hack)")
                String keyword,
                @ToolParam(required = false, description = "Look back this many hours in the archive (1-168; 24 when only a keyword is given)")
                Integer hours) {
            return toolkit.newsSearch(lang, keyword, hours);
        }
    }
}
