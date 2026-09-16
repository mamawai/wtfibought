package com.mawai.wiibagent.llm;

import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibcommon.enums.AgentLang;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 执行工具：模型拼错工具名时回执报错并列出可用工具，循环照常往下走 */
class ReactLoopTest {

    public static class EchoTools {
        @Tool(name = "echo", description = "回声")
        public String echo(String text) {
            return text;
        }
    }

    @Test
    void unknownToolNameAnsweredWithErrorInsteadOfThrowing() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AtomicInteger round = new AtomicInteger();
        // 第一次调一个拼错的工具，第二次收尾
        when(model.call(any(Prompt.class))).thenAnswer(inv -> round.incrementAndGet() == 1
                ? new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "ecoh",
                                "{\"text\":\"hi\"}")))
                        .build())))
                : new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("收尾").build()))));

        ResilientChatService chat = ResilientChatService.builder()
                .model(model)
                .systemPrompt("测试")
                .tools(new LocalizedToolCallbacks(new PromptCatalog()).of(AgentLang.ZH, new EchoTools()))
                .build();
        ReactLoop.Result result = ReactLoop.builder()
                .chat(chat)
                .limiter(new ModelCallLimiter(5, "未执行", "预算已用完"))
                .build()
                .run(List.of(new UserMessage("说点什么")), null, null, null);

        ToolResponseMessage.ToolResponse response = result.messages().stream()
                .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                .flatMap(t -> t.getResponses().stream())
                .findFirst().orElseThrow();
        assertThat(response.id()).isEqualTo("c1");
        assertThat(response.responseData()).startsWith("ERROR:").contains("ecoh").contains("echo");
        assertThat(result.modelCalls()).isEqualTo(2);
    }
}
