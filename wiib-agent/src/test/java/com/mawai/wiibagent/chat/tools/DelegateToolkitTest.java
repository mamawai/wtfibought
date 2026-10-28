package com.mawai.wiibagent.chat.tools;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.ChatAgentFactory;
import com.mawai.wiibagent.chat.ChatTestEndpoints;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.run.ChildRunRegistry;
import com.mawai.wiibagent.chat.run.WorkbenchPrefService;
import com.mawai.wiibagent.chat.run.WorkerKind;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.llm.LlmClient;
import com.mawai.wiibagent.llm.ModelCallLimiter;
import com.mawai.wiibagent.llm.ReactLoop;
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
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * 用户关了开关就被注册表拒掉；worker 每批工具调用带 runId 推给前端。
 */
class DelegateToolkitTest {

    private static final String SESSION = "wb-1-delegate";
    private static final ToolContext CTX = new ToolContext(Map.of(ToolRunContext.SESSION_KEY, SESSION));

    private final ChatContextStore contextStore = ChatTestEndpoints.contextStore();
    private final ChatModel model = mock(ChatModel.class);
    /** 真注册表，会话挂一个只记事件的出口 */
    private final WorkbenchRunRegistry runRegistry = new WorkbenchRunRegistry();
    private final List<Map.Entry<String, ObjectNode>> events = new CopyOnWriteArrayList<>();
    private ChildRunRegistry childRuns;

    private DelegateToolkit toolkit(WorkbenchPrefService prefs) {
        when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("调研报告正文")))));
        runRegistry.start(SESSION, (event, data) -> events.add(Map.entry(event, data)), new CompletableFuture<>());
        childRuns = new ChildRunRegistry(contextStore, runRegistry, prefs, ChatTestEndpoints.PROMPTS, 20, 300, 4000);
        // worker 不挂工具：这里验的是派发与回灌，不是预设的工具表（那在 ChatAgentFactoryTest）
        Function<WorkerKind, ChatAgentFactory.Worker> workers = kind -> {
            UsageTrackingChatModel usage = new UsageTrackingChatModel(model);
            LlmClient client = LlmClient.builder().model(usage).systemPrompt("worker").build();
            return new ChatAgentFactory.Worker(ReactLoop.builder().client(client)
                    .limiter(new ModelCallLimiter(3, "未执行", "收尾")).build(), usage);
        };
        return new DelegateToolkit(childRuns, workers, ChatTestEndpoints.PROMPTS, 1L, AgentLang.ZH);
    }

    private List<ObjectNode> eventsOf(String type) {
        return events.stream().filter(e -> e.getKey().equals(type)).map(Map.Entry::getValue).toList();
    }

    /** 子运行跑在别的线程上：等到这类事件出现（最多 5 秒） */
    private ObjectNode awaitEvent(String type) throws InterruptedException {
        for (int i = 0; i < 250 && eventsOf(type).isEmpty(); i++) {
            Thread.sleep(20);
        }
        return eventsOf(type).getFirst();
    }

    private List<Message> savedMessages() {
        ArgumentCaptor<ChatContextStore.State> saved = ArgumentCaptor.captor();
        verify(contextStore, timeout(5_000)).save(eq(SESSION), eq(1L), saved.capture());
        return saved.getValue().messages();
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

    /** 前端那条子运行点开要能看到任务书和它内部的每一步：child_start 带 brief，worker 的每批 tool_call 带 runId 推出去 */
    @Test
    void 任务书随child_start下发_worker工具调用带runId推给前端() {
        DelegateToolkit toolkit = toolkit(ChatTestEndpoints.prefs());
        // worker 先要调一个工具（没挂工具，回执是 unknown tool），再出报告
        AssistantMessage wantsTool = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "news_search", "{\"hours\":24}"))).build();
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(wantsTool))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("调研报告正文")))));

        String reply = toolkit.delegate("researcher", "查一下本周 ETF 资金流", "ETF 资金流", CTX);
        savedMessages();    // 等 worker 跑完

        String runId = reply.replaceAll(".*\"runId\":\"([^\"]+)\".*", "$1");
        assertThat(eventsOf("child_start")).singleElement().satisfies(start -> {
            assertThat(start.get("runId").asString(null)).isEqualTo(runId);
            assertThat(start.get("brief").asString(null)).isEqualTo("查一下本周 ETF 资金流");
        });
        assertThat(eventsOf("tool_calls")).singleElement().satisfies(batch -> {
            assertThat(batch.get("runId").asString(null)).isEqualTo(runId);
            assertThat(batch.get("calls")).hasSize(1);
            assertThat(batch.get("calls").get(0).get("name").asString(null)).isEqualTo("news_search");
            assertThat(batch.get("calls").get(0).get("args").get("hours").intValue()).isEqualTo(24);
        });
    }

    /** 取消之后模型还可能回一帧带 tool_call 的回复：不再推给前端，屏上那条子运行已经是"已取消" */
    @Test
    void 取消后worker再要调工具不再推tool_calls() throws InterruptedException {
        DelegateToolkit toolkit = toolkit(ChatTestEndpoints.prefs());
        AssistantMessage wantsTool = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "news_search", "{}"))).build();
        // 模型回话之前整个会话被取消了（停止键 / 删会话那条路）
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            childRuns.cancelAll(SESSION);
            return new ChatResponse(List.of(new Generation(wantsTool)));
        });

        toolkit.delegate("researcher", "查点东西", null, CTX);

        assertThat(awaitEvent("child_done").get("status").asString(null)).isEqualTo("CANCELLED");
        assertThat(eventsOf("tool_calls")).isEmpty();
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
