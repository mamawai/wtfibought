package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.behavior.BehaviorAnalysisService;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.run.ChildRunRegistry;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.chat.store.ChatHistoryService;
import com.mawai.wiibagent.llm.ContextUsage;
import com.mawai.wiibagent.toolkit.IndicatorToolkit;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.trader.TraderChatService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上下文用量：每次调完模型推给前端一次，这一轮结束取最后一次；上游一次都没报就按字数估。
 * 上一轮用量到了整理线，开轮先整理，本轮从整理后的历史起跑。
 */
class ChatTurnContextTest {

    private static final String SESSION = "wb-1-context";

    private final ChatModel deep = mock(ChatModel.class);
    private final ChatContextStore contextStore = mock(ChatContextStore.class);
    private final ChatHistoryService chatHistory = mock(ChatHistoryService.class);
    private final ChildRunRegistry childRuns = ChatTestEndpoints.childRuns(contextStore);
    private final WorkbenchRunRegistry runRegistry = new WorkbenchRunRegistry();
    /** 推到前端的 context 事件 */
    private final List<ObjectNode> events = new ArrayList<>();
    /** 推到前端的过程文案 */
    private final List<String> progress = new ArrayList<>();

    private static ChatResponse textFrame(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** 收尾帧：空文本 + 这次调用的用量 */
    private static ChatResponse finalFrame(int prompt, int completion) {
        Generation generation = new Generation(new AssistantMessage(""),
                ChatGenerationMetadata.builder().finishReason("STOP").build());
        return new ChatResponse(List.of(generation), ChatResponseMetadata.builder()
                .usage(new DefaultUsage(prompt, completion, prompt + completion)).build());
    }

    /** 上下文里一条轮起始提问，形状与 ChatTurnStreamer 拼的 enriched 一致 */
    private static Message turnStart(String question) {
        return new UserMessage(ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.turn.timeMark",
                Map.of("time", "2026-10-28 09:00")) + "\n"
                + ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.turn.questionPrefix") + question);
    }

    /** 上一轮：提问 → 调行情 → 回执 → 最终回答 */
    private static List<Message> lastTurn() {
        return List.of(turnStart("上一问"),
                AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "market_snapshot", "{}")))
                        .build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "market_snapshot", "{}")))
                        .build(),
                new AssistantMessage("上一答"));
    }

    private ChatTurnRunner.TurnResult runTurn() {
        return runTurn(null);
    }

    private ChatTurnRunner.TurnResult runTurn(CompletableFuture<Void> cancel) {
        when(deep.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.modelsFor(any())).thenReturn(new ChatModelFactory.Models(deep, deep));
        ChatAgentFactory.Leaves leaves = new ChatAgentFactory(chatModelFactory, mock(MarketToolkit.class),
                mock(IndicatorToolkit.class), ChatTestEndpoints.newsToolkit(), mock(DeepAnalysisService.class),
                mock(BehaviorAnalysisService.class), mock(TraderChatService.class), runRegistry, childRuns,
                new ApprovalRegistry(), ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS, 8, "X")
                .leavesFor(ChatTestEndpoints.eps(1L, "gpt-5"), AgentLang.ZH);
        runRegistry.start(SESSION, (event, data) -> {
            if ("context".equals(event)) {
                events.add(data);
            } else if ("progress".equals(event)) {
                progress.add(data.path("text").asString());
            }
            return true;
        }, new CompletableFuture<>());
        return new ChatTurnRunner(contextStore, childRuns, ChatTestEndpoints.PROMPTS, runRegistry,
                new ContextCompactor(chatHistory, ChatTestEndpoints.PROMPTS, 0.9))
                .run(leaves, 1L, SESSION, "看看行情", null, s -> { }, s -> { }, cancel);
    }

    @Test
    void 调完模型推一次用量_这一轮带上同一份() {
        when(deep.stream(any(Prompt.class))).thenReturn(Flux.just(textFrame("答"), finalFrame(1000, 200)));

        ChatTurnRunner.TurnResult result = runTurn();

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().path("tokens").asLong()).isEqualTo(1200L);
        assertThat(events.getFirst().path("window").asInt()).isEqualTo(256_000);
        assertThat(events.getFirst().path("estimated").asBoolean()).isFalse();
        assertThat(result.context()).isEqualTo(new ContextUsage(1200, 256_000, false));
    }

    @Test
    void 上游不报用量时按字数估() {
        when(deep.stream(any(Prompt.class))).thenReturn(Flux.just(textFrame("答")));

        ChatTurnRunner.TurnResult result = runTurn();

        assertThat(events).isEmpty();
        assertThat(result.context().estimated()).isTrue();
        assertThat(result.context().tokens()).isPositive();
        assertThat(result.context().window()).isEqualTo(256_000);
    }

    @Test
    void 上一轮用量到线时开轮先整理() {
        when(contextStore.load(SESSION)).thenReturn(lastTurn());
        // 256K 的 90% 是 230400
        when(chatHistory.lastContextTokens(SESSION)).thenReturn(240_000L);
        when(deep.call(any(Prompt.class))).thenReturn(textFrame("摘要正文"));
        List<Prompt> streamed = new ArrayList<>();
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> {
            streamed.add(inv.getArgument(0));
            return Flux.just(textFrame("答"), finalFrame(1000, 200));
        });

        runTurn();

        verify(deep, times(1)).call(any(Prompt.class));
        assertThat(progress).containsExactly(ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.compact.progress"));
        // 系统提示词之后：摘要、上一问、上一答、本轮提问、收尾指令
        List<Message> seen = streamed.getFirst().getInstructions();
        assertThat(seen).hasSize(6);
        assertThat(seen.get(1).getText()).endsWith("摘要正文");
        assertThat(seen.get(2).getText()).endsWith("上一问");
        assertThat(seen.get(3).getText()).isEqualTo("上一答");
        assertThat(seen.get(4).getText()).isEqualTo("看看行情");
        // 落库的终态同样从整理后的历史起跑
        ArgumentCaptor<List<Message>> saved = ArgumentCaptor.captor();
        verify(contextStore).save(eq(SESSION), eq(1L), saved.capture());
        assertThat(saved.getValue().getFirst().getText()).endsWith("摘要正文");
    }

    @Test
    void 开跑前点了停就不整理() {
        when(contextStore.load(SESSION)).thenReturn(lastTurn());
        when(chatHistory.lastContextTokens(SESSION)).thenReturn(240_000L);

        ChatTurnRunner.TurnResult result = runTurn(CompletableFuture.completedFuture(null));

        assertThat(result.cancelled()).isTrue();
        verify(deep, never()).call(any(Prompt.class));
        assertThat(progress).isEmpty();
    }
}
