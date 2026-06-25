package com.mawai.wiibsim.service;

/**
 * AI调用服务接口
 * 支持多模型优先级重试
 */
public interface AiService {

    /**
     * 调用AI生成文本（带优先级重试）
     * @param prompt 提示词
     * @return AI生成的文本
     */
    String chat(String prompt);

    /**
     * 调用指定提供商的AI
     * @param providerName 提供商名称
     * @param prompt 提示词
     * @return AI生成的文本
     */
    String chatWithProvider(String providerName, String prompt);
}
