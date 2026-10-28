package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.llm.AnthropicChatModel;
import com.mawai.wiibagent.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 整理线怎么判、整理请求长什么样、产物长什么样、失败了怎么办 */
class ContextCompactorTest {

    private final ContextCompactor compactor = new ContextCompactor(ChatTestEndpoints.PROMPTS, 0.9);
    private final ChatModel model = mock(ChatModel.class);
    /** 整理时发给模型的请求 */
    private final List<Prompt> requests = new ArrayList<>();

    private static Message turnStart(AgentLang lang, String question) {
        return new UserMessage(ChatTestEndpoints.PROMPTS.get(lang, "chat.turn.timeMark",
                Map.of("time", "2026-10-28 09:00")) + "\n"
                + ChatTestEndpoints.PROMPTS.get(lang, "chat.turn.questionPrefix") + question);
    }

    private static Message turnStart(String question) {
        return turnStart(AgentLang.ZH, question);
    }

    private static AssistantMessage toolCall() {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "market_snapshot", "{}"))).build();
    }

    private static ToolResponseMessage toolResult() {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "market_snapshot", "{}"))).build();
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

    /** 模型这次整理交回的回复 */
    private void modelReplies(AssistantMessage reply) {
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            requests.add(inv.getArgument(0));
            return new ChatResponse(List.of(new Generation(reply)));
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

    /** 请求 = 主 agent 的系统提示词 + 原历史原样 + 末尾一条整理指令：前缀与上一轮相同才命中提示缓存 */
    @Test
    void 整理请求是原历史加一条整理指令() {
        modelReplies(new AssistantMessage("摘要"));
        List<Message> history = List.of(turnStart("问"), toolCall(), toolResult(), new AssistantMessage("答"));

        compactor.compact(leaves(), history);

        List<Message> sent = requests.getFirst().getInstructions();
        assertThat(sent.getFirst().getText()).isEqualTo("你是助手");
        assertThat(sent.subList(1, sent.size() - 1)).containsExactlyElementsOf(history);
        assertThat(sent.getLast()).isInstanceOf(UserMessage.class);
        assertThat(sent.getLast().getText())
                .isEqualTo(ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.compact.instruction"));
    }

    /**
     * 产物：摘要（用户消息）+ 上一轮提问原文 + 它的最终回答。回答只留文本，
     * 原始内容块（思考块、搜索密文）不跟过来——各协议按文本拼，不再回放以前的思考
     */
    @Test
    void 产物是摘要加上一轮问答纯文本() {
        modelReplies(new AssistantMessage("  摘要正文\n"));
        AssistantMessage answer = AssistantMessage.builder().content("上一答")
                .properties(Map.of(AnthropicChatModel.BLOCKS_KEY, "[{\"type\":\"thinking\"}]")).build();
        List<Message> history = List.of(turnStart("更早的问"), new AssistantMessage("更早的答"),
                turnStart("上一问"), toolCall(), toolResult(), answer);

        List<Message> compacted = compactor.compact(leaves(), history).orElseThrow();

        assertThat(compacted).hasSize(3);
        assertThat(compacted.get(0)).isInstanceOf(UserMessage.class);
        assertThat(compacted.get(0).getText()).isEqualTo(preamble() + "\n\n摘要正文");
        assertThat(compacted.get(1).getText()).isEqualTo(history.get(2).getText());
        assertThat(compacted.get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(compacted.get(2).getText()).isEqualTo("上一答");
        assertThat(compacted.get(2).getMetadata()).doesNotContainKey(AnthropicChatModel.BLOCKS_KEY);
    }

    /** 切过语言的会话：上一问是英文标记，叶子是中文——认的时候遍历全部语言 */
    @Test
    void 英文标记的上一问同样认得() {
        modelReplies(new AssistantMessage("摘要"));
        List<Message> history = List.of(turnStart(AgentLang.EN, "how is BTC"), new AssistantMessage("BTC answer"));

        List<Message> compacted = compactor.compact(leaves(), history).orElseThrow();

        assertThat(compacted).extracting(Message::getText)
                .containsExactly(preamble() + "\n\n摘要", history.get(0).getText(), "BTC answer");
    }

    /** 上一轮没有最终回答（摔在工具中间）：上一问一起进摘要，只留摘要 */
    @Test
    void 上一轮没有最终回答时只留摘要() {
        modelReplies(new AssistantMessage("摘要"));
        List<Message> history = List.of(turnStart("问"), toolCall(), toolResult());

        List<Message> compacted = compactor.compact(leaves(), history).orElseThrow();

        assertThat(compacted).extracting(Message::getText).containsExactly(preamble() + "\n\n摘要");
    }

    /** 老会话：历史里有老压缩留下的 SystemMessage 摘要，照样整段交给模型合并，产物里不再有 SystemMessage */
    @Test
    void 老会话的历史照样整理() {
        modelReplies(new AssistantMessage("合并后的摘要"));
        SystemMessage oldSummary = new SystemMessage("## 早前对话摘要：\n── 第1段 ──\n用户关注 BTC");
        List<Message> history = List.of(turnStart("第一问"), oldSummary, new AssistantMessage("中间的答"),
                turnStart("上一问"), new AssistantMessage("上一答"));

        List<Message> compacted = compactor.compact(leaves(), history).orElseThrow();

        assertThat(requests.getFirst().getInstructions()).contains(oldSummary);
        assertThat(compacted).noneMatch(SystemMessage.class::isInstance);
        assertThat(compacted).extracting(Message::getText)
                .containsExactly(preamble() + "\n\n合并后的摘要", history.get(3).getText(), "上一答");
    }

    @Test
    void 模型报错时沿用原历史() {
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("上游挂了"));

        assertThat(compactor.compact(leaves(), List.of(turnStart("问"), new AssistantMessage("答")))).isEmpty();
    }

    /** 整理请求带着全部工具（为了前缀不变），模型偶尔会去调工具而不交摘要：算失败，不拿空摘要换掉历史 */
    @Test
    void 模型只回工具调用时沿用原历史() {
        modelReplies(toolCall());

        assertThat(compactor.compact(leaves(), List.of(turnStart("问"), new AssistantMessage("答")))).isEmpty();
    }
}
