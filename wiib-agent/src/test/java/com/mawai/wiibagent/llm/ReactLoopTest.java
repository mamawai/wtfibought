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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 执行工具：拼错工具名回执报错；同批多个 tool_call 并发跑、回执按 tool_call 顺序；
 * 批里有串行工具就整批串行。
 */
class ReactLoopTest {

    public static class EchoTools {
        @Tool(name = "echo", description = "回声")
        public String echo(String text) {
            return text;
        }
    }

    /** 两个工具各自在栅栏上等对方：顺序执行的话先跑的那个等到超时，只有并发才都能过 */
    public static class BarrierTools {
        private final CyclicBarrier barrier = new CyclicBarrier(2);

        @Tool(name = "left", description = "左")
        public String left() {
            String r = await();
            // 故意比 right 晚完成：回执顺序要跟 tool_call 顺序走，不跟完成顺序走
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return r + " left";
        }

        @Tool(name = "right", description = "右")
        public String right() {
            return await() + " right";
        }

        private String await() {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                return "ok";
            } catch (Exception e) {
                return "ERROR " + e.getClass().getSimpleName();
            }
        }
    }

    /** 记同时在跑的最大个数与开跑顺序 */
    public static class CountingTools {
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxInFlight = new AtomicInteger();
        private final List<String> started = Collections.synchronizedList(new ArrayList<>());

        @Tool(name = "trade", description = "动作")
        public String trade() {
            return run("trade");
        }

        @Tool(name = "quote", description = "行情")
        public String quote() {
            return run("quote");
        }

        private String run(String name) {
            started.add(name);
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return name + " ok";
        }
    }

    @Test
    void unknownToolNameAnsweredWithErrorInsteadOfThrowing() {
        ReactLoop.Result result = loop(modelCalling(List.of(call("c1", "ecoh", "{\"text\":\"hi\"}"))),
                Set.of(), new EchoTools())
                .run(List.of(new UserMessage("说点什么")), null, null, null);

        ToolResponseMessage.ToolResponse response = toolResponses(result).getFirst();
        assertThat(response.id()).isEqualTo("c1");
        assertThat(response.responseData()).startsWith("ERROR:").contains("ecoh").contains("echo");
        assertThat(result.modelCalls()).isEqualTo(2);
    }

    @Test
    void sameBatchToolsRunConcurrentlyAndRepliesKeepCallOrder() {
        ReactLoop.Result result = loop(modelCalling(List.of(call("c1", "left", "{}"), call("c2", "right", "{}"))),
                Set.of(), new BarrierTools())
                .run(List.of(new UserMessage("两边一起")), null, null, null);

        List<ToolResponseMessage.ToolResponse> responses = toolResponses(result);
        assertThat(responses).extracting(ToolResponseMessage.ToolResponse::id).containsExactly("c1", "c2");
        // 字符串返回值经 Spring AI 默认转换器 JSON 编码，带引号
        assertThat(responses).extracting(ToolResponseMessage.ToolResponse::responseData)
                .containsExactly("\"ok left\"", "\"ok right\"");
    }

    @Test
    void batchWithSerialToolRunsWholeBatchInOrder() {
        CountingTools tools = new CountingTools();
        loop(modelCalling(List.of(call("c1", "quote", "{}"), call("c2", "trade", "{}"))), Set.of("trade"), tools)
                .run(List.of(new UserMessage("先看后动")), null, null, null);

        assertThat(tools.maxInFlight.get()).isEqualTo(1);
        assertThat(tools.started).containsExactly("quote", "trade");
    }

    private static AssistantMessage.ToolCall call(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    /** 第一次回这批 tool_call，第二次收尾 */
    private static ChatModel modelCalling(List<AssistantMessage.ToolCall> toolCalls) {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AtomicInteger round = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(inv -> round.incrementAndGet() == 1
                ? new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(toolCalls).build())))
                : new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("收尾").build()))));
        return model;
    }

    private static ReactLoop loop(ChatModel model, Set<String> serialTools, Object toolkit) {
        ResilientChatService chat = ResilientChatService.builder()
                .model(model)
                .systemPrompt("测试")
                .tools(new LocalizedToolCallbacks(new PromptCatalog()).of(AgentLang.ZH, toolkit))
                .build();
        return ReactLoop.builder()
                .chat(chat)
                .limiter(new ModelCallLimiter(5, "未执行", "预算已用完"))
                .serialTools(serialTools)
                .build();
    }

    private static List<ToolResponseMessage.ToolResponse> toolResponses(ReactLoop.Result result) {
        return result.messages().stream()
                .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                .flatMap(t -> t.getResponses().stream())
                .toList();
    }
}
