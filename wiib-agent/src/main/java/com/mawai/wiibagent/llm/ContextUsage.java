package com.mawai.wiibagent.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.Usage;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 对话的上下文用量：tokens 是下一次调模型大约要带上的量，window 是对话主模型端点配的上下文窗口，
 * estimated=上游没报用量、按字数估的（界面显示"≈"）。
 */
public record ContextUsage(long tokens, int window, boolean estimated) {

    /**
     * 一次模型调用之后的上下文大小：这次的输入加输出（输出会进历史，下一次跟着带上）。
     * 没报用量返回 null——Spring AI 没 usage 时给的是全 0 的空壳，只认正数
     */
    public static Long measured(Usage usage) {
        if (usage == null || usage.getPromptTokens() == null || usage.getPromptTokens() <= 0) {
            return null;
        }
        Integer completion = usage.getCompletionTokens();
        return (long) usage.getPromptTokens() + (completion == null ? 0 : completion);
    }

    /**
     * 上游不报用量时按字数估：中文 1 字≈1 token，其余 4 字符≈1 token。
     * 存了原始内容的 assistant 按原始内容算（回放时发出去的就是它）；只算 messages，连系统提示词与工具定义一起估用
     * {@link LlmClient#estimateTokens}
     */
    public static long estimate(List<Message> messages) {
        long total = 0;
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant) {
                String raw = rawContent(assistant);
                if (raw != null) {
                    total += estimate(raw);
                    continue;
                }
                total += estimate(assistant.getText());
                for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                    total += estimate(toolCall.arguments());
                }
            } else if (message instanceof ToolResponseMessage toolResponse) {
                for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                    total += estimate(response.responseData());
                }
            } else {
                total += estimate(message.getText());
            }
        }
        return total;
    }

    public ObjectNode toJson() {
        return MAPPER.createObjectNode().put("tokens", tokens).put("window", window).put("estimated", estimated);
    }

    /** 三个协议各自存的原始内容 */
    private static String rawContent(AssistantMessage assistant) {
        for (String key : SseChatModel.RAW_KEYS) {
            if (assistant.getMetadata().get(key) instanceof String raw) {
                return raw;
            }
        }
        return null;
    }

    static long estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x2E80) { // CJK 及其标点起始区
                cjk++;
            }
        }
        return cjk + (text.length() - cjk) / 4;
    }
}
