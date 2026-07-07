package com.mawai.wiibquant.agent.quant.domain.news;

/**
 * LLM 筛选后的新闻条目（原 NewsEventAgent 内部 record，旧管线拆除时提升为独立 domain）。
 * 消费方：SignalExtractor 新闻信号组；P2b 深研判新闻上下文复用。
 */
public record FilteredNewsItem(String title, String sentiment, String impact, String reason) {
}
