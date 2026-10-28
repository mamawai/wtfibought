package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.llm.AnthropicChatModel;
import com.mawai.wiibagent.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 整理线怎么判、整理请求长什么样、产物长什么样、失败和中断了怎么办 */
class ContextCompactorTest {

    private final ContextCompactor compactor = new ContextCompactor(ChatTestEndpoints.PROMPTS, 0.9);
    private final ChatModel model = mock(ChatModel.class);
    /** 整理时发给模型的请求 */
    private final List<Prompt> requests = new ArrayList<>();

    private static Message turnStart(String question) {
        return new UserMessage(ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.turn.timeMark",
                Map.of("time", "2026-10-28 09:00")) + "\n"
                + ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.turn.questionPrefix") + question);
    }

    private static AssistantMessage toolCall() {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "market_snapshot", "{}"))).build();
    }

    private static ToolResponseMessage toolResult(String data) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "market_snapshot", data))).build();
    }

    private static ChatResponse frame(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static String preamble() {
        return ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.compact.preamble");
    }

    private ChatAgentFactory.Leaves leaves(int contextWindow) {
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        LlmClient client = LlmClient.builder().model(model).systemPrompt("你是助手").build();
        return new ChatAgentFactory.Leaves("x", null, null, client, AgentLang.ZH, contextWindow);
    }

    private ChatAgentFactory.Leaves leaves() {
        return leaves(256_000);
    }

    /** 第 n 次整理请求（从 1 数）模型交回什么 */
    private void modelReplies(IntFunction<Flux<ChatResponse>> reply) {
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
            requests.add(inv.getArgument(0));
            return reply.apply(requests.size());
        });
    }

    @Test
    void 用量读数到窗口九成才整理() {
        List<Message> history = List.of(turnStart("问"), new AssistantMessage("答"));

        // 256K 的 90% 是 230400
        assertThat(compactor.shouldCompact(leaves(), 230_399L, history)).isFalse();
        assertThat(compactor.shouldCompact(leaves(), 230_400L, history)).isTrue();
    }

    /** 没有读数（老会话、中断过、回退过）：连系统提示词一起按字数估（中文 1 字≈1 token） */
    @Test
    void 没有读数时按字数估() {
        ChatAgentFactory.Leaves leaves = leaves(1000);
        int prefix = (int) leaves.mainClient().estimateTokens(List.of());

        assertThat(compactor.shouldCompact(leaves, null, List.of(new UserMessage("字".repeat(899 - prefix)))))
                .isFalse();
        assertThat(compactor.shouldCompact(leaves, null, List.of(new UserMessage("字".repeat(900 - prefix)))))
                .isTrue();
    }

    /**
     * 请求 = 主 agent 的系统提示词 + 原历史原样 + 末尾一条整理指令（前缀与上一轮相同才命中提示缓存）。
     * 产物 = 摘要（用户消息）+ 上一问起的对话：之后到的子运行报告、唤醒指令与补充回答都留着，
     * 工具过程丢掉，回答只留文本、原始内容（思考块、搜索密文）不跟过来
     */
    @Test
    void 整理请求是原历史加指令_产物是摘要加上一问起的文本() {
        modelReplies(n -> Flux.just(frame(new AssistantMessage("  摘要")), frame(new AssistantMessage("正文\n"))));
        AssistantMessage answer = AssistantMessage.builder().content("上一答")
                .properties(Map.of(AnthropicChatModel.BLOCKS_KEY, "[{\"type\":\"thinking\"}]")).build();
        Message report = new UserMessage("【后台任务#1 深研判 已完成】\n研判结论");
        Message wake = new UserMessage(ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.wake.instruction"));
        List<Message> history = List.of(turnStart("更早的问"), new AssistantMessage("更早的答"),
                turnStart("上一问"), toolCall(), toolResult("{}"), answer, report, wake, new AssistantMessage("补充回答"));

        List<Message> compacted = compactor.compact(leaves(), history, null).orElseThrow();

        List<Message> sent = requests.getFirst().getInstructions();
        assertThat(sent.getFirst().getText()).isEqualTo("你是助手");
        assertThat(sent.subList(1, sent.size() - 1)).containsExactlyElementsOf(history);
        assertThat(sent.getLast().getText())
                .isEqualTo(ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.compact.instruction"));
        assertThat(compacted).extracting(Message::getText).containsExactly(preamble() + "\n\n摘要正文",
                history.get(2).getText(), "上一答", report.getText(), wake.getText(), "补充回答");
        assertThat(compacted.get(2).getMetadata()).doesNotContainKey(AnthropicChatModel.BLOCKS_KEY);
    }

    /** 整段发出去失败（多半是超了窗口）：去掉原始内容、工具回执截短再发一次 */
    @Test
    void 整段失败就精简后再试一次() {
        modelReplies(n -> n == 1
                ? Flux.error(new NonTransientAiException("prompt is too long"))
                : Flux.just(frame(new AssistantMessage("摘要"))));
        AssistantMessage call = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "market_snapshot", "{}")))
                .properties(Map.of(AnthropicChatModel.BLOCKS_KEY, "[{\"type\":\"thinking\"}]")).build();
        List<Message> history = List.of(turnStart("问"), call, toolResult("行".repeat(5000)), new AssistantMessage("答"));

        assertThat(compactor.compact(leaves(), history, null)).isPresent();

        List<Message> slim = requests.get(1).getInstructions();
        assertThat(slim.get(2).getMetadata()).doesNotContainKey(AnthropicChatModel.BLOCKS_KEY);
        assertThat(((ToolResponseMessage) slim.get(3)).getResponses().getFirst().responseData()).hasSize(2001);
    }

    @Test
    void 精简后还失败就沿用原历史() {
        modelReplies(n -> Flux.error(new NonTransientAiException("prompt is too long")));

        assertThat(compactor.compact(leaves(), List.of(turnStart("问"), new AssistantMessage("答")), null)).isEmpty();
        assertThat(requests).hasSize(2);
    }

    /** 整理请求带着全部工具（为了前缀不变），模型偶尔会去调工具而不交摘要：算失败，也不重发 */
    @Test
    void 模型只回工具调用时沿用原历史() {
        modelReplies(n -> Flux.just(frame(toolCall())));

        assertThat(compactor.compact(leaves(), List.of(turnStart("问"), new AssistantMessage("答")), null)).isEmpty();
        assertThat(requests).hasSize(1);
    }

    /** 整理时点了停止：流掐断，半截摘要不用，也不重发 */
    @Test
    void 中途点停止就不整理() {
        CompletableFuture<Void> cancel = new CompletableFuture<>();
        modelReplies(n -> Flux.just(frame(new AssistantMessage("半截")))
                .doOnComplete(() -> cancel.complete(null)));

        assertThat(compactor.compact(leaves(), List.of(turnStart("问"), new AssistantMessage("答")), cancel)).isEmpty();
        assertThat(requests).hasSize(1);
    }
}
