package com.mawai.wiibquant.market.service;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibquant.market.domain.news.NewsFlash;
import com.mawai.wiibquant.mapper.NewsEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 快讯按语言取一份：源是 BlockBeats 中文快讯，译文由采集轨补译回填 news_event，
 * 这里按 source_id 把译文换进来；<b>缺译文回落中文原文</b>（存量老快讯、还没进采集轨的、
 * 已存中文还在待译的、模型没译成的都走这条）。
 * <p>
 * 只给模型侧用（对话 news 专家的预取、深研判素材），按 agent 语言取；首页快讯卡直接读
 * news_event 存档，不经这里。
 * <p>
 * 中文用户一次库都不查：直接原样返回。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsFlashLocalizer {

    private final NewsEventMapper newsEventMapper;

    /**
     * 取过语言的一条快讯。
     *
     * @param translated true=标题或正文用的是机器译文，false=中文原文
     */
    public record LocalizedFlash(long id, String title, String plain, String url,
                                 String createTime, boolean translated) {
    }

    /** 按语言取一份快讯。译文列只有英文一份，其余语言一律原文。 */
    public List<LocalizedFlash> localize(List<NewsFlash> flashes, AgentLang lang) {
        if (lang != AgentLang.EN || flashes.isEmpty()) {
            return flashes.stream().map(NewsFlashLocalizer::original).toList();
        }
        Map<Long, NewsEventMapper.Translation> byId = new HashMap<>();
        try {
            newsEventMapper.selectTranslations(flashes.stream().map(NewsFlash::id).toList())
                    .forEach(t -> byId.put(t.getSourceId(), t));
        } catch (Exception e) {
            // 译文查不到不该让快讯整块消失：退回原文，英文用户看到中文总好过看到空白
            log.warn("[NewsI18n] 译文查询失败，本次回落原文: {}", e.toString());
            return flashes.stream().map(NewsFlashLocalizer::original).toList();
        }
        return flashes.stream().map(f -> {
            NewsEventMapper.Translation t = byId.get(f.id());
            String titleEn = t == null ? null : t.getTitleEn();
            String contentEn = t == null ? null : t.getContentEn();
            // 标题/正文各自回落：只译成一半的那种也算译文
            return new LocalizedFlash(f.id(), pick(titleEn, f.title()),
                    pick(contentEn, f.plainContent()), f.url(), f.createTime(),
                    has(titleEn) || has(contentEn));
        }).toList();
    }

    private static boolean has(String translation) {
        return translation != null && !translation.isBlank();
    }

    private static LocalizedFlash original(NewsFlash f) {
        return new LocalizedFlash(f.id(), f.title(), f.plainContent(), f.url(), f.createTime(), false);
    }

    /** 译文空着就是没译成，回落原文——库里永远不会有"原文冒充译文"那种行 */
    private static String pick(String translation, String origin) {
        return has(translation) ? translation : origin;
    }
}
