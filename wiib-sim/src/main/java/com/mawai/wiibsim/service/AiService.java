package com.mawai.wiibsim.service;

/**
 * AI调用服务接口（配置来自 DB 的 'sim' 功能位，Admin 页可切换）
 */
public interface AiService {

    /**
     * 调用AI生成文本（失败同配置重试）
     * @param prompt 提示词
     * @return AI生成的文本
     */
    String chat(String prompt);
}
