package com.mawai.wiibsim.service;

/**
 * AI调用服务接口（配置来自 DB 的 'sim' 功能位，Admin 页可切换）
 */
public interface AiService {

    /**
     * 调用AI生成文本（失败同配置重试），不设置 temperature——走模型默认，思考模型也安全
     * @param prompt 提示词
     * @return AI生成的文本
     */
    default String chat(String prompt) {
        return chat(prompt, null);
    }

    /**
     * 调用AI生成文本，可指定 temperature（走势生成要高随机性时用）
     * @param prompt 提示词
     * @param temperature 采样温度；null=不传走模型默认
     * @return AI生成的文本
     */
    String chat(String prompt, Double temperature);
}
