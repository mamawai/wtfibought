package com.mawai.wiibquant.agent.toolkit;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibquant.agent.quant.domain.news.NewsFlash;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 新闻工具（对话 news_agent 用）：返回缓存里的重要快讯列表。
 * 快讯短、信息密——直接给全量正文，不再有单篇精读/过滤。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsToolkit {

    private final NewsCache newsCache;

    @Tool(name = "news_search", description = """
            获取最近的重要快讯列表（标题+正文+来源+时间）。快讯已是完整内容，无需再精读单篇。""")
    public String newsSearch() {
        List<NewsFlash> flashes = newsCache.getFlashes();
        if (flashes.isEmpty()) {
            JSONObject out = new JSONObject();
            out.put("available", false);
            out.put("reason", "news feed unavailable");
            return out.toJSONString();
        }
        JSONArray arr = new JSONArray(flashes.size());
        for (NewsFlash f : flashes) {
            JSONObject o = new JSONObject();
            o.put("title", f.title());
            o.put("content", f.plainContent());
            o.put("source", f.url());
            o.put("time", f.createTime());
            arr.add(o);
        }
        return arr.toJSONString();
    }
}
