package com.mawai.wiibagent.promptgen;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.LlmClient;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibcommon.enums.AgentLang;
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
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 题数上限（缺必问项时不挡）、一轮一题、必问项与已推题卡挡写稿、空稿不存、NOTES 切分、超长重写一次后截断 */
class PromptGenToolkitTest {

    private final PromptCatalog prompts = new PromptCatalog();
    private final ChatModel model = mock(ChatModel.class);
    private final PromptGenStore store = mock(PromptGenStore.class);
    private final List<String> events = new ArrayList<>();
    private int seq;

    PromptGenToolkitTest() {
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(store.nextNo(1L)).thenReturn(4);
    }

    @Test
    void eleventhQuestionSinceLastDraftIsRefusedUnlessARequiredItemIsMissing() {
        List<Message> history = new ArrayList<>(List.of(new UserMessage("开场")));
        for (int i = 0; i < PromptGenToolkit.MAX_ASKS; i++) {
            history.addAll(answeredAsk("q" + i));
        }

        JsonNode detail = MAPPER.readTree(toolkit(history).askUser("q10", "还有？", "A", "因为", List.of("B")));
        // 问满了但必问项还缺：照样能问，不然写不了稿也问不了，对话就卡死了
        JsonNode required = MAPPER.readTree(toolkit(history).askUser("Style", "风格？", "均衡", "因为", List.of("保守")));

        assertThat(detail.path("shown").asBoolean()).isFalse();
        assertThat(required.path("shown").asBoolean()).isTrue();
    }

    @Test
    void onlyOneQuestionPerTurn() {
        PromptGenToolkit toolkit = toolkit(List.of(new UserMessage("开场")));

        JsonNode first = MAPPER.readTree(toolkit.askUser("direction", "方向？", "只做多", "因为", List.of("多空都做")));
        JsonNode second = MAPPER.readTree(toolkit.askUser("holding", "拿多久？", "日内", "因为", List.of("波段")));

        assertThat(first.path("shown").asBoolean()).isTrue();
        assertThat(second.path("shown").asBoolean()).isFalse();
    }

    @Test
    void draftRefusedUntilRequiredItemsAreAnswered() {
        List<Message> history = new ArrayList<>(List.of(new UserMessage("开场")));
        history.addAll(answeredAsk("direction"));

        JsonNode result = MAPPER.readTree(toolkit(history).writeDraft("要点", false));

        assertThat(result.has("no")).isFalse();
        assertThat(result.path("message").asString()).contains("holding").contains("style").doesNotContain("direction");
        verify(model, never()).stream(any(Prompt.class));
    }

    /** 同一批先推题卡再写稿：不写，不然这道题在历史里永远配不上回答 */
    @Test
    void draftRefusedAfterAQuestionWasShownThisTurn() {
        PromptGenToolkit toolkit = toolkit(requiredAnswered());
        toolkit.askUser("exit", "怎么离场？", "移动止损", "因为", List.of("固定止盈"));

        JsonNode result = MAPPER.readTree(toolkit.writeDraft("要点", false));

        assertThat(result.has("no")).isFalse();
        verify(model, never()).stream(any(Prompt.class));
    }

    @Test
    void emptyDraftIsNotStaged() {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(frame("\n=====NOTES=====\n只有说明")));
        PromptGenRun run = run(requiredAnswered());

        JsonNode result = MAPPER.readTree(new PromptGenToolkit(run, writer(), mock(PlayStatsAssembler.class), prompts)
                .writeDraft("要点", false));

        assertThat(result.has("no")).isFalse();
        assertThat(run.staged()).isNull();
    }

    @Test
    void draftSplitsBodyAndNotesAndWaitsToBeSaved() {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(frame("【边界】\n- 只做多"),
                frame("\n=====NOTES=====\n"), frame("第一版")));
        PromptGenRun run = run(requiredAnswered());

        JsonNode result = MAPPER.readTree(new PromptGenToolkit(run, writer(), mock(PlayStatsAssembler.class), prompts)
                .writeDraft("要点", true));

        assertThat(result.path("no").asInt()).isEqualTo(4);
        assertThat(result.path("body").asString()).isEqualTo("【边界】\n- 只做多");
        assertThat(result.path("notes").asString()).isEqualTo("第一版");
        assertThat(run.staged()).isEqualTo(new PromptGenHistory.Drafted(4, "【边界】\n- 只做多", "第一版", true));
        assertThat(events.getFirst()).contains("writing");
        assertThat(events).filteredOn(e -> e.startsWith("draft")).hasSize(3);
    }

    @Test
    void overlongDraftIsRewrittenOnceThenTruncated() {
        String tooLong = "x".repeat(4001);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(frame(tooLong)), Flux.just(frame(tooLong)));

        JsonNode result = MAPPER.readTree(toolkit(requiredAnswered()).writeDraft("要点", false));

        verify(model, times(2)).stream(any(Prompt.class));
        assertThat(result.path("body").asString()).hasSize(4000);
        assertThat(result.path("notes").asString()).contains("截断");
        assertThat(events).filteredOn(e -> e.contains("writing")).hasSize(2);
    }

    @Test
    void truncateNeverSplitsASurrogatePair() {
        assertThat(PromptGenToolkit.truncate("ab😀", 3)).isEqualTo("ab");
    }

    private List<Message> requiredAnswered() {
        List<Message> history = new ArrayList<>(List.of(new UserMessage("开场")));
        for (String key : List.of("goal", "style", "holding", "direction", "limits")) {
            history.addAll(answeredAsk(key));
        }
        return history;
    }

    /** 展示成功并已回答的一题 */
    private List<Message> answeredAsk(String key) {
        String id = "c" + seq++;
        return List.of(
                AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(id, "function",
                        "ask_user", "{\"key\":\"" + key + "\",\"question\":\"" + key + "？\"}"))).build(),
                ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(id, "ask_user",
                        MAPPER.writeValueAsString("{\"shown\":true}")))).build(),
                new UserMessage("答 " + key));
    }

    private PromptGenRun run(List<Message> history) {
        return new PromptGenRun(1L, AgentLang.ZH, AgentLang.ZH, null, history,
                (event, data) -> events.add(event + " " + data), new CompletableFuture<>(), store);
    }

    private PromptGenToolkit toolkit(List<Message> history) {
        return new PromptGenToolkit(run(history), writer(), mock(PlayStatsAssembler.class), prompts);
    }

    private LlmClient writer() {
        return LlmClient.builder().model(model).systemPrompt("写稿").build();
    }

    private static ChatResponse frame(String text) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
    }
}
