package com.mawai.wiibagent.runtime;

import org.springframework.ai.chat.model.ChatModel;

/**
 * 各功能位的 ChatModel 分配（DB 驱动，Admin 可热更）。
 * <p>
 * 只剩 newsTranslation=快讯翻译：它是后台批量任务，没有"当前用户"可言，只能由平台买单；
 * 模型名跟着带出来——news_event.translated_model 落库追责用。
 * <p>
 * 面向用户的那几位全部切到用户自带 key：对话轨（原 quant / quant-light / chat）、
 * 交易员，以及最后退休的 behavior 行为分析（现为对话轨的 analyze_my_behavior 工具，
 * 用户 BYOK 的深模型跑）。
 */
public record AiAgentRuntime(ChatModel newsTranslationChatModel,
                             String newsTranslationModelName) {
}
