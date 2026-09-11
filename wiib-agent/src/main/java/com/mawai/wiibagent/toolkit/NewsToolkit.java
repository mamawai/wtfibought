package com.mawai.wiibagent.toolkit;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibquant.market.service.NewsCache;
import com.mawai.wiibquant.market.service.NewsFlashLocalizer;
import com.mawai.wiibquant.market.service.NewsFlashLocalizer.LocalizedFlash;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 新闻工具（对话 news_agent 预取 + trader 唤醒挂工具）：返回缓存里的重要快讯列表。
 * 快讯短、信息密——直接给全量正文，不再有单篇精读/过滤。
 * <p>
 * 源是 BlockBeats 中文快讯：按语言取一份（英文取打标时同批产出的译文，缺译文回落中文原文），
 * 与首页快讯卡取的是同一份，不会出现"用户看到译文、trader 读到原文"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsToolkit {

    private final NewsCache newsCache;
    private final NewsFlashLocalizer localizer;

    public String newsSearch(AgentLang lang) {
        List<LocalizedFlash> flashes = localizer.localize(newsCache.getFlashes(), lang);
        log.info("[NewsTool] news_search 被调用（{}），BlockBeats 快讯 {} 条", lang.code(), flashes.size());
        if (flashes.isEmpty()) {
            JSONObject out = new JSONObject();
            out.put("available", false);
            out.put("reason", "news feed unavailable");
            return out.toJSONString();
        }
        JSONArray arr = new JSONArray(flashes.size());
        for (LocalizedFlash f : flashes) {
            JSONObject o = new JSONObject();
            o.put("title", f.title());
            o.put("content", f.plain());
            o.put("source", f.url());
            o.put("time", f.createTime());
            arr.add(o);
        }
        return arr.toJSONString();
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
                Get the recent market news flashes (title + full body + source + time).""")
        public String newsSearch() {
            return toolkit.newsSearch(lang);
        }
    }
}
