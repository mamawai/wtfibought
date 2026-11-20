package com.mawai.wiibquant.market.domain.news;

/**
 * BlockBeats(律动)重要快讯单条。
 * content 为带 HTML 的正文；入库和喂 LLM 都用 {@link #plainContent()}，只去标签，不浓缩不过滤。
 * url 为原始消息源(X/官媒等)，createTime 形如 "2026-07-09 00:30:12"。
 */
public record NewsFlash(long id, String title, String content, String url, String createTime) {

    /** 去 HTML 标签的纯文本正文（content 形如 {@code <p>…</p>}），供喂 LLM。 */
    public String plainContent() {
        return content == null ? "" : content.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
