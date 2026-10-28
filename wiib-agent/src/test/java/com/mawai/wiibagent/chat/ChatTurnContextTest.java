package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.behavior.BehaviorAnalysisService;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.run.ChildRunRegistry;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.llm.ContextUsage;
import com.mawai.wiibagent.toolkit.IndicatorToolkit;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.trader.TraderChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
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
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 上下文用量：每次调完模型推给前端一次，这一轮结束取最后一次；上游一次都没报就按字数估 */
class ChatTurnContextTest {

    private static final String SESSION = "wb-1-context";

    private final ChatModel deep = mock(ChatModel.class);
    private final ChatContextStore contextStore = mock(ChatContextStore.class);
    private final ChildRunRegistry childRuns = ChatTestEndpoints.childRuns(contextStore);
    private final WorkbenchRunRegistry runRegistry = new WorkbenchRunRegistry();
    /** 推到前端的 context 事件 */
    private final List<ObjectNode> events = new ArrayList<>();

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

    private ChatTurnRunner.TurnResult runTurn() {
        when(deep.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.modelsFor(any())).thenReturn(new ChatModelFactory.Models(deep, deep));
        ChatAgentFactory.Leaves leaves = new ChatAgentFactory(chatModelFactory, mock(MarketToolkit.class),
                mock(IndicatorToolkit.class), ChatTestEndpoints.newsToolkit(), mock(DeepAnalysisService.class),
                mock(BehaviorAnalysisService.class), mock(TraderChatService.class), runRegistry, childRuns,
                new ApprovalRegistry(), ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS, 8, 999_999, 6, "X")
                .leavesFor(ChatTestEndpoints.eps(1L, "gpt-5"), AgentLang.ZH);
        runRegistry.start(SESSION, (event, data) -> {
            if ("context".equals(event)) {
                events.add(data);
            }
            return true;
        }, new CompletableFuture<>());
        return new ChatTurnRunner(contextStore, childRuns, ChatTestEndpoints.PROMPTS, runRegistry)
                .run(leaves, 1L, SESSION, "看看行情", null, s -> { }, s -> { }, null);
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
}
