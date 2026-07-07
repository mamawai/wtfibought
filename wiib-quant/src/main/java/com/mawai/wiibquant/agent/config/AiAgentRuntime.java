package com.mawai.wiibquant.agent.config;

import org.springframework.ai.chat.model.ChatModel;

/**
 * 各功能位的 ChatModel 分配（DB 驱动，Admin 可热更）。
 * quant=深模型（Supervisor 调度/Judge/Bull/Bear），quantLight=浅模型（对话子 agent/新闻浓缩/摘要）——
 * 成本工程：贵的只花在裁决上。
 */
public record AiAgentRuntime(
        ChatModel behaviorChatModel,
        ChatModel quantChatModel,
        ChatModel quantLightChatModel,
        ChatModel chatChatModel
) {
}
