package com.mawai.wiibcommon.constant;

/** LLM 上游协议常量：一条配置一个协议，quant/sim 按此分叉请求格式 */
public final class AiProtocols {

    /** OpenAI Chat Completions（/v1/chat/completions）——DeepSeek 等 OpenAI 兼容服务商的通用协议 */
    public static final String OPENAI = "openai";

    /** OpenAI Responses（/v1/responses）——CPA/OpenAI 官方/xAI，思考模型（reasoning.effort）优先走这个 */
    public static final String RESPONSES = "responses";

    private AiProtocols() {
    }

    /** 空/未知一律按 openai 兜底：存量行无此列值、手误值都不至于打挂调用链 */
    public static boolean isResponses(String protocol) {
        return RESPONSES.equalsIgnoreCase(protocol);
    }

    public static boolean isValid(String protocol) {
        return OPENAI.equalsIgnoreCase(protocol) || RESPONSES.equalsIgnoreCase(protocol);
    }
}
