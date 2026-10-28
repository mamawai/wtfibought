package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.behavior.BehaviorAnalysisService;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.run.ChildRun;
import com.mawai.wiibagent.chat.run.ChildRunRegistry;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.llm.AnthropicChatModel;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 一轮里上下文怎么进出：用量每次调完模型推给前端、结束时随上下文一起存；
 * 提示词或工具变了历史按文本重建；用量到线开轮先整理；上下文读不出来就不跑。
 */
class ChatTurnContextTest {

    private static final String SESSION = "wb-1-context";

    private final ChatModel deep = mock(ChatModel.class);
    private final ChatContextStore contextStore = ChatTestEndpoints.contextStore();
    private final ChildRunRegistry childRuns = ChatTestEndpoints.childRuns(contextStore);
    private final WorkbenchRunRegistry runRegistry = new WorkbenchRunRegistry();
    private final ChatAgentFactory.Leaves leaves = leaves();
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

    private ChatAgentFactory.Leaves leaves() {
        when(deep.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.modelsFor(any())).thenReturn(new ChatModelFactory.Models(deep, deep));
        return new ChatAgentFactory(chatModelFactory, mock(MarketToolkit.class),
                mock(IndicatorToolkit.class), ChatTestEndpoints.newsToolkit(), mock(DeepAnalysisService.class),
                mock(BehaviorAnalysisService.class), mock(TraderChatService.class), runRegistry, childRuns,
                new ApprovalRegistry(), ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS, 8, "X")
                .leavesFor(ChatTestEndpoints.eps(1L, "gpt-5"), AgentLang.ZH);
    }

    /** 库里存着的上下文，前缀指纹与当前叶子一致 */
    private void stored(List<Message> messages, Long contextTokens) {
        when(contextStore.load(SESSION)).thenReturn(
                new ChatContextStore.State(messages, contextTokens, leaves.mainClient().fingerprint()));
    }

    private ChatTurnRunner.TurnResult runTurn() {
        return runTurn(null);
    }

    private ChatTurnRunner.TurnResult runTurn(CompletableFuture<Void> cancel) {
        runRegistry.start(SESSION, (event, data) -> {
            if ("context".equals(event)) {
                events.add(data);
            } else if ("progress".equals(event)) {
                progress.add(data.path("text").asString());
            }
            return true;
        }, new CompletableFuture<>());
        return new ChatTurnRunner(contextStore, childRuns, ChatTestEndpoints.PROMPTS, runRegistry,
                new ContextCompactor(ChatTestEndpoints.PROMPTS, 0.9))
                .run(leaves, 1L, SESSION, "看看行情", null, s -> { }, s -> { }, cancel);
    }

    private ChatContextStore.State saved() {
        ArgumentCaptor<ChatContextStore.State> saved = ArgumentCaptor.captor();
        verify(contextStore).save(eq(SESSION), eq(1L), saved.capture());
        return saved.getValue();
    }

    /** 每次调完模型推一次；这一轮带上最后一次的读数，连同前缀指纹随上下文落库 */
    @Test
    void 调完模型推一次用量_随上下文落库() {
        when(deep.stream(any(Prompt.class))).thenReturn(Flux.just(textFrame("答"), finalFrame(1000, 200)));

        ChatTurnRunner.TurnResult result = runTurn();

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().path("tokens").asLong()).isEqualTo(1200L);
        assertThat(events.getFirst().path("window").asInt()).isEqualTo(256_000);
        assertThat(events.getFirst().path("estimated").asBoolean()).isFalse();
        assertThat(result.context()).isEqualTo(new ContextUsage(1200, 256_000, false));
        assertThat(saved().contextTokens()).isEqualTo(1200L);
        assertThat(saved().prefix()).isEqualTo(leaves.mainClient().fingerprint());
    }

    /** 上游一次都没报：按字数估，系统提示词和工具定义也算上 */
    @Test
    void 上游不报用量时按字数估() {
        when(deep.stream(any(Prompt.class))).thenReturn(Flux.just(textFrame("答")));

        ChatTurnRunner.TurnResult result = runTurn();

        assertThat(events).isEmpty();
        assertThat(result.context().estimated()).isTrue();
        assertThat(result.context().tokens()).isEqualTo(leaves.mainClient().estimateTokens(saved().messages()));
    }

    @Test
    void 用量到线时开轮先整理() {
        // 256K 的 90% 是 230400
        stored(lastTurn(), 240_000L);
        List<Prompt> streamed = new ArrayList<>();
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> {
            streamed.add(inv.getArgument(0));
            return streamed.size() == 1 ? Flux.just(textFrame("摘要正文"))
                    : Flux.just(textFrame("答"), finalFrame(1000, 200));
        });

        runTurn();

        // 第一次是整理：原历史 + 整理指令
        assertThat(streamed).hasSize(2);
        assertThat(streamed.getFirst().getInstructions().getLast().getText())
                .isEqualTo(ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.compact.instruction"));
        assertThat(progress).containsExactly(ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.compact.progress"));
        // 作答那次，系统提示词之后：摘要、上一问、上一答、本轮提问、收尾指令
        List<Message> seen = streamed.getLast().getInstructions();
        assertThat(seen).hasSize(6);
        assertThat(seen.get(1).getText()).endsWith("摘要正文");
        assertThat(seen.get(2).getText()).endsWith("上一问");
        assertThat(seen.get(3).getText()).isEqualTo("上一答");
        assertThat(seen.get(4).getText()).isEqualTo("看看行情");
        // 落库的终态同样从整理后的历史起跑
        assertThat(saved().messages().getFirst().getText()).endsWith("摘要正文");
    }

    @Test
    void 开跑前点了停就不整理() {
        stored(lastTurn(), 240_000L);

        ChatTurnRunner.TurnResult result = runTurn(CompletableFuture.completedFuture(null));

        assertThat(result.cancelled()).isTrue();
        verify(deep, never()).stream(any(Prompt.class));
        assertThat(progress).isEmpty();
    }

    /** 整理时点了停：整理作废、作答不调；整理那次调用可能被掐在半路，账本标成不可信 */
    @Test
    void 整理时点了停就收场() {
        stored(lastTurn(), 240_000L);
        CompletableFuture<Void> cancel = new CompletableFuture<>();
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> Flux.just(textFrame("半截"))
                .doOnComplete(() -> cancel.complete(null)));

        ChatTurnRunner.TurnResult result = runTurn(cancel);

        assertThat(result.cancelled()).isTrue();
        verify(deep, times(1)).stream(any(Prompt.class));
        assertThat(leaves.deep().untrusted()).isTrue();
        assertThat(saved().messages().getFirst().getText()).endsWith("上一问");
    }

    /**
     * 存着的前缀指纹和当前对不上（改了提示词、切了语言、改了联网搜索）：以前的原始内容不再回放，
     * 读数作废——这一轮就算存着 240K 也按字数估，不整理
     */
    @Test
    void 提示词或工具变了历史按文本重建() {
        AssistantMessage answer = AssistantMessage.builder().content("上一答")
                .properties(Map.of(AnthropicChatModel.BLOCKS_KEY, "[{\"type\":\"thinking\"}]")).build();
        when(contextStore.load(SESSION)).thenReturn(
                new ChatContextStore.State(List.of(turnStart("上一问"), answer), 240_000L, "旧指纹"));
        List<Prompt> streamed = new ArrayList<>();
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> {
            streamed.add(inv.getArgument(0));
            return Flux.just(textFrame("答"));
        });

        runTurn();

        assertThat(streamed).as("读数作废、按字数估够不着线，不该整理").hasSize(1);
        Message replayed = streamed.getFirst().getInstructions().get(2);
        assertThat(replayed.getText()).isEqualTo("上一答");
        assertThat(replayed.getMetadata()).doesNotContainKey(AnthropicChatModel.BLOCKS_KEY);
        assertThat(saved().prefix()).isEqualTo(leaves.mainClient().fingerprint());
    }

    /** 读不出上下文：不调模型、不落库；收件箱摘掉，之后到的报告走没轮在跑的路子落库 */
    @Test
    void 读不出上下文就不跑也不落库() {
        when(contextStore.load(SESSION)).thenThrow(new RuntimeException("db down"))
                .thenReturn(ChatContextStore.State.EMPTY);

        assertThatThrownBy(this::runTurn).hasMessage("db down");

        verify(deep, never()).stream(any(Prompt.class));
        verify(contextStore, never()).save(any(), anyLong(), any());
        childRuns.spawn(new ChildRunRegistry.Spec(SESSION, 1L, ChildRun.Kind.DELEGATE, "调研", "任务书",
                AgentLang.ZH), run -> "报告正文");
        verify(contextStore, timeout(5_000)).save(eq(SESSION), eq(1L), any());
    }
}
