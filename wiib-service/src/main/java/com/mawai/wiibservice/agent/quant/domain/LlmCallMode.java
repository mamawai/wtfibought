package com.mawai.wiibservice.agent.quant.domain;

import org.springframework.ai.chat.client.ChatClient;

/**
 * LLM调用策略：BLOCKING直接返回，STREAMING流式收集。
 * 部分API提供商要求stream=true，用STREAMING即可。
 */
public enum LlmCallMode {

    BLOCKING {
        @Override
        public String call(ChatClient chatClient, String prompt) {
            return chatClient.prompt().user(prompt).call().content();
        }
    },

    STREAMING {
        @Override
        public String call(ChatClient chatClient, String prompt) {
            return String.join("", chatClient.prompt().user(prompt)
                    .stream().content().collectList().block());
        }
    };

    public abstract String call(ChatClient chatClient, String prompt);
}
