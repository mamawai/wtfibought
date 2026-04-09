package com.mawai.wiibservice.agent.config;

import org.springframework.ai.chat.model.ChatModel;

public record AiAgentRuntime(
        ChatModel behaviorChatModel,
        ChatModel quantChatModel,
        ChatModel chatChatModel,
        ChatModel reflectionChatModel
) {
}
