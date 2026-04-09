package com.mawai.wiibservice.agent.quant.domain;

import org.springframework.ai.chat.client.ChatClient;

import java.util.function.Consumer;

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

        @Override
        public void stream(ChatClient chatClient, String prompt, Consumer<String> chunkConsumer) {
            String content = call(chatClient, prompt);
            if (content != null && !content.isEmpty()) {
                chunkConsumer.accept(content);
            }
        }
    },

    STREAMING {
        @Override
        public String call(ChatClient chatClient, String prompt) {
            StringBuilder builder = new StringBuilder();
            stream(chatClient, prompt, builder::append);
            return builder.toString();
        }

        @Override
        public void stream(ChatClient chatClient, String prompt, Consumer<String> chunkConsumer) {
            chatClient.prompt().user(prompt)
                    .stream()
                    .content()
                    .doOnNext(chunk -> {
                        if (chunk != null && !chunk.isEmpty()) {
                            chunkConsumer.accept(chunk);
                        }
                    })
                    .blockLast();
        }
    };

    public abstract String call(ChatClient chatClient, String prompt);

    public abstract void stream(ChatClient chatClient, String prompt, Consumer<String> chunkConsumer);
}
