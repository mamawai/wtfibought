package com.mawai.wiibagent.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 各协议的 tool_choice 落点各不相同，这里钉死：openai 协议落 OpenAiChatOptions.toolChoice 且类型不变
 *（Spring AI 2.0 的 OpenAiChatModel 硬转这个类型），responses 协议落 toolContext 信号；首轮判定只看最后一条用户消息之后。
 */
class ToolChoiceTest {

    @Test
    void openai协议落toolChoice字段且类型不变() {
        OpenAiChatOptions base = OpenAiChatOptions.builder().model("deepseek-chat").reasoningEffort("high").build();

        ChatOptions forced = ToolChoice.apply(base, ToolChoice.REQUIRED);

        assertThat(forced).isInstanceOf(OpenAiChatOptions.class);
        assertThat(((OpenAiChatOptions) forced).getToolChoice()).isEqualTo("required");
        assertThat(((OpenAiChatOptions) forced).getReasoningEffort()).isEqualTo("high");   // 其余字段原样带过去
        assertThat(base.getToolChoice()).isNull();                                          // 入参不动
    }

    @Test
    void responses协议经toolContext捎信号且不覆盖已有上下文() {
        ToolCallingChatOptions base = ToolCallingChatOptions.builder().toolContext(Map.of("k", "v")).build();

        ChatOptions forced = ToolChoice.apply(base, ToolChoice.REQUIRED);

        assertThat(ToolChoice.of(forced)).isEqualTo("required");
        assertThat(((ToolCallingChatOptions) forced).getToolContext()).containsEntry("k", "v");
        assertThat(ToolChoice.of(base)).isEqualTo(ToolChoice.AUTO);   // 没捎就是 auto
    }

    @Test
    void 挂工具从模型自己的options派生() {
        ChatModel openAi = mock(ChatModel.class);
        when(openAi.getOptions()).thenReturn(OpenAiChatOptions.builder().model("m").build());
        ToolCallback tool = mock(ToolCallback.class);

        ToolCallingChatOptions options = ToolChoice.withTools(openAi, List.of(tool));

        assertThat(options).isInstanceOf(OpenAiChatOptions.class);
        assertThat(options.getToolCallbacks()).containsExactly(tool);
        assertThat(options.getModel()).isEqualTo("m");
    }

    @Test
    void 首轮判定只看最后一条用户消息之后() {
        Message user = new UserMessage("BTC 怎么样");
        Message assistant = new AssistantMessage("看涨");
        Message toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "market_snapshot", "{}")))
                .build();

        // 干净首轮：只有提问
        assertThat(ToolChoice.isFirstTurn(List.of(user))).isTrue();
        // 本轮已拿过工具结果：不再是首轮（ReactLoop 收尾必须放开）
        assertThat(ToolChoice.isFirstTurn(List.of(user, assistant, toolResponse))).isFalse();
        // 上一轮的 TRM 在新提问之前（summarizer 深研判留痕）：新一轮仍是首轮
        assertThat(ToolChoice.isFirstTurn(List.of(toolResponse, assistant, user))).isTrue();
        // 新提问之后只有专家的普通回复（并行回环第二轮派发）：对没跑过的专家仍是首轮
        assertThat(ToolChoice.isFirstTurn(List.of(user, assistant))).isTrue();
    }
}
