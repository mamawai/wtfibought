package com.mawai.wiibagent.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.DefaultUsage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 上下文用量的两种来源：上游报的实测值，没报时按字数估 */
class ContextUsageTest {

    /** 调完一次模型的上下文 = 这次的输入 + 输出；没报（Spring AI 给全 0 的空壳）是 null，不能当成 0 */
    @Test
    void 实测用量是输入加输出_没报就是null() {
        assertThat(ContextUsage.measured(new DefaultUsage(1000, 200, 1200))).isEqualTo(1200L);
        assertThat(ContextUsage.measured(new DefaultUsage(0, 0, 0))).isNull();
    }

    /** 中文 1 字≈1 token、其余 4 字符≈1 token；存了原始内容的 assistant 只按原始内容算，文本不重复算 */
    @Test
    void 估算按字数且原始内容优先() {
        AssistantMessage withRaw = AssistantMessage.builder().content("答")
                .properties(Map.of(AnthropicChatModel.BLOCKS_KEY, "x".repeat(400))).build();
        ToolResponseMessage toolResult = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("1", "market_snapshot", "y".repeat(40)))).build();
        List<Message> messages = List.of(new UserMessage("比特币"), withRaw, toolResult);

        assertThat(ContextUsage.estimate(messages)).isEqualTo(3 + 100 + 10);
    }
}
