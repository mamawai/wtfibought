package com.mawai.wiibquant.agent.config;

import org.springframework.ai.chat.model.ChatModel;

/** P2a：reflection 随方向反思链删除；quant 留给 P2b 深研判子图。 */
public record AiAgentRuntime(
        ChatModel behaviorChatModel,
        ChatModel quantChatModel,
        ChatModel chatChatModel
) {
}
