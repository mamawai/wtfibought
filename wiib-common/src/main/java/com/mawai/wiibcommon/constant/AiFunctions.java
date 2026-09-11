package com.mawai.wiibcommon.constant;

/**
 * AI 功能位名（ai_model_assignment.function_name 的契约值），现只有 wiib-agent 进程使用。
 * 前端 Admin.tsx 的 FUNCTION_LABEL_KEYS 需与此同步。
 * <p>
 * 只剩快讯翻译这一位：面向用户的功能位（对话、交易员、行为分析）全部走用户自带 key，
 * 见 user_llm_endpoint。库里 behavior 等残行是孤儿，无害——白名单只认这里。
 */
public final class AiFunctions {

    /** 快讯翻译：NewsEventCollector 后台批量译英文用的轻模型，内部调用不走用户 key */
    public static final String NEWS_TRANSLATION = "news-translation";

    private AiFunctions() {
    }
}
