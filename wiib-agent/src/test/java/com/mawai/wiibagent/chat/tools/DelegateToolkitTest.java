package com.mawai.wiibagent.chat.tools;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.ChatAgentFactory;
import com.mawai.wiibagent.chat.ChatTestEndpoints;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.run.ChildRunRegistry;
import com.mawai.wiibagent.chat.run.WorkbenchPrefService;
import com.mawai.wiibagent.chat.run.WorkerKind;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.llm.ModelCallLimiter;
import com.mawai.wiibagent.llm.ReactLoop;
import com.mawai.wiibagent.llm.ResilientChatService;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * delegate 工具：参数错直接回错不派；派了立刻回 running，worker 最后一条正文作为报告回灌；
 * 用户关了开关就被注册表拒掉。
 */
class DelegateToolkitTest {

    private static final String SESSION = "wb-1-delegate";
    private static final ToolContext CTX = new ToolContext(Map.of(ToolRunContext.SESSION_KEY, SESSION));

    private final ChatContextStore contextStore = mock(ChatContextStore.class);
    private final ChatModel model = mock(ChatModel.class);

    private DelegateToolkit toolkit(WorkbenchPrefService prefs) {
        when(contextStore.load(any())).thenReturn(List.of());
        when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("调研报告正文")))));
        ChildRunRegistry childRuns = new ChildRunRegistry(contextStore, new WorkbenchRunRegistry(), prefs,
                ChatTestEndpoints.PROMPTS, 20, 300, 4000);
        // worker 不挂工具：这里验的是派发与回灌，不是预设的工具表（那在 ChatAgentFactoryTest）
        Function<WorkerKind, ChatAgentFactory.Worker> workers = kind -> {
            UsageTrackingChatModel usage = new UsageTrackingChatModel(model);
            ResilientChatService chat = ResilientChatService.builder().model(usage).systemPrompt("worker").build();
            return new ChatAgentFactory.Worker(ReactLoop.builder().chat(chat)
                    .limiter(new ModelCallLimiter(3, "未执行", "收尾")).build(), usage);
        };
        return new DelegateToolkit(childRuns, workers, ChatTestEndpoints.PROMPTS, 1L, AgentLang.ZH);
    }

    private List<Message> savedMessages() {
        ArgumentCaptor<List<Message>> saved = ArgumentCaptor.captor();
        verify(contextStore, timeout(5_000)).save(eq(SESSION), eq(1L), saved.capture());
        return saved.getValue();
    }

    @Test
    void 派发立即返回running_报告是worker最后一条正文() {
        String reply = toolkit(ChatTestEndpoints.prefs()).delegate("researcher", "查一下本周 ETF 资金流", "ETF 资金流", CTX);

        assertThat(reply).contains("\"status\":\"running\"").contains("\"runId\"");
        assertThat(savedMessages()).singleElement().satisfies(m -> assertThat(m.getText())
                .startsWith("【后台任务#").contains("ETF 资金流").contains("已完成").endsWith("调研报告正文"));
        // 任务书是 worker 唯一的用户消息
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions().getLast().getText()).isEqualTo("查一下本周 ETF 资金流");
    }

    @Test
    void 没给label时用预设名() {
        toolkit(ChatTestEndpoints.prefs()).delegate("analyst", "看 BTC 四小时结构", null, CTX);

        assertThat(savedMessages().getFirst().getText()).contains("行情分析");
    }

    @Test
    void 用户关了开关就拒绝不派() {
        String reply = toolkit(ChatTestEndpoints.prefs(false, 3)).delegate("researcher", "查点东西", null, CTX);

        assertThat(reply).contains("\"error\"").contains("关闭");
        verify(model, after(300).never()).call(any(Prompt.class));
    }

    @Test
    void 认不出的worker直接报错不派() {
        String reply = toolkit(ChatTestEndpoints.prefs()).delegate("oracle", "查点东西", null, CTX);

        assertThat(reply).contains("\"error\"").contains("researcher");
        verify(model, after(300).never()).call(any(Prompt.class));
    }

    @Test
    void 空任务书直接报错不派() {
        String reply = toolkit(ChatTestEndpoints.prefs()).delegate("analyst", "   ", null, CTX);

        assertThat(reply).contains("\"error\"");
        verify(model, never()).call(any(Prompt.class));
        verify(contextStore, never()).save(any(), anyLong(), any());
    }
}
