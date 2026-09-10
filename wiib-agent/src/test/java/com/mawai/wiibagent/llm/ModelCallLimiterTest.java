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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 保险丝的核心契约：三个纯函数，计数在 {@link ReactLoop} 手里。
 * 到上限那一刻最后一条必然是带 toolCalls 的 AssistantMessage，只跳不补 = 留下永远等不到
 * tool_result 的孤儿 tool_call，这段历史进 Responses API 就是 400（工作台会持久化它 → 该会话彻底报废）。
 */
class ModelCallLimiterTest {

    /** 占位回执文案生产上按语言取词表传入（llm.callLimit.notExecuted）；这里只验"传进去的原样落到回执上" */
    private static final String NOT_EXECUTED = "未执行：本轮模型调用已达上限，工具被跳过。";
    /** 预算收尾提示（llm.callLimit.lastCall）同理 */
    private static final String LAST_CALL = "（系统提示：预算已用完）";

    private static AssistantMessage replyWith(String... toolCallIds) {
        return AssistantMessage.builder().content("")
                .toolCalls(java.util.Arrays.stream(toolCallIds)
                        .map(id -> new AssistantMessage.ToolCall(id, "function", "get_account", "{}"))
                        .toList())
                .build();
    }

    @Test
    void 达上限时给未执行的工具调用补齐占位回执() {
        ToolResponseMessage placeholder = new ModelCallLimiter(3, NOT_EXECUTED, LAST_CALL)
                .placeholders(replyWith("call_a", "call_b"));

        // 每个未执行的 tool_call 都要有配对的 tool_result，否则这段历史一送上游就是 400
        assertThat(placeholder.getResponses()).extracting(ToolResponseMessage.ToolResponse::id)
                .containsExactly("call_a", "call_b");
        // 占位内容要说清"没执行"，模型/复盘看得懂，不是伪造的成功结果——传进去的文案原样落回执
        assertThat(placeholder.getResponses()).allSatisfy(r ->
                assertThat(r.responseData()).isEqualTo(NOT_EXECUTED));
    }

    /** 倒数第二次能执行工具：回执末尾贴预算已尽提示，下一次模型调用直接收尾（不再撞保险丝硬切） */
    @Test
    void 倒数第二次调用把预算已尽提示贴在回执末尾() {
        ToolResponseMessage notified = new ModelCallLimiter(3, NOT_EXECUTED, LAST_CALL).withLastCallNotice(
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("call_a", "get_account", "{\"balance\":1}"))).build());

        String data = notified.getResponses().getFirst().responseData();
        assertThat(data).startsWith("{\"balance\":1}").endsWith(LAST_CALL);
        assertThat(notified.getResponses().getFirst().id()).isEqualTo("call_a");
    }

    /** 真跑一遍：被保险丝收束的最终历史是要被工作台持久化的，孤儿 tool_call 会让该会话彻底报废 */
    public static class EchoTools {
        @Tool(name = "echo", description = "回声")
        public String echo(String text) {
            return text;
        }
    }

    @Test
    void 收束后的最终历史里不留孤儿toolCall() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AtomicInteger round = new AtomicInteger();
        // 永远只想再调一次工具、永不收尾 → 只能靠保险丝收束（每轮独立 call id，同真实模型口径）
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            int i = round.incrementAndGet();
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("c" + i, "function", "echo",
                            "{\"text\":\"hi\"}")))
                    .build())));
        });

        ResilientChatService chat = ResilientChatService.builder()
                .model(model)
                .systemPrompt("测试")
                .tools(new LocalizedToolCallbacks(new PromptCatalog()).of(AgentLang.ZH, new EchoTools()))
                .build();
        ReactLoop.Result result = ReactLoop.builder()
                .chat(chat)
                .limiter(new ModelCallLimiter(3, NOT_EXECUTED, LAST_CALL))
                .build()
                .run(List.of(new UserMessage("说点什么")), null, null, null);

        assertThat(result.modelCalls()).isEqualTo(3);

        Set<String> called = result.messages().stream()
                .filter(AssistantMessage.class::isInstance).map(AssistantMessage.class::cast)
                .flatMap(a -> a.getToolCalls().stream()).map(AssistantMessage.ToolCall::id)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> answered = result.messages().stream()
                .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                .flatMap(t -> t.getResponses().stream()).map(ToolResponseMessage.ToolResponse::id)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(called).isNotEmpty();
        assertThat(answered).containsExactlyInAnyOrderElementsOf(called);
    }
}
