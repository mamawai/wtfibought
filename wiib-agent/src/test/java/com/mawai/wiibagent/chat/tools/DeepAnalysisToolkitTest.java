package com.mawai.wiibagent.chat.tools;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.chat.ChatTestEndpoints;
import com.mawai.wiibagent.chat.gate.ApprovalGate;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.run.ChildRun;
import com.mawai.wiibagent.chat.run.ChildRunRegistry;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具本体两件事：工具调用只是派一个子运行、立刻回 RUNNING；子运行主体真跑辩论与裁决。
 * HITL 判断已经上移到 {@link ApprovalGate}（工具方法体看不到 sessionId 和 tool_call 参数，判断做在这里就绑不住标的），
 * 授权语义的用例全在 {@link ApprovalGateTest}。
 */
class DeepAnalysisToolkitTest {

    private static final String SESSION = "wb-1-x";
    /** 生产里会话号由 ChatTurnRunner 传给 ReactLoop，循环执行工具时经 ToolContext 交给工具 */
    private static final ToolContext CTX = new ToolContext(Map.of(ToolRunContext.SESSION_KEY, SESSION));

    private final ChatModel model = mock(ChatModel.class);
    private final DeepAnalysisService deepAnalysisService = mock(DeepAnalysisService.class);
    private final WorkbenchRunRegistry runRegistry = new WorkbenchRunRegistry();
    private final ChatContextStore contextStore = mock(ChatContextStore.class);
    private final ChildRunRegistry childRuns =
            new ChildRunRegistry(contextStore, runRegistry, ChatTestEndpoints.prefs(), ChatTestEndpoints.PROMPTS, 20, 300, 4000);

    private final DeepAnalysisToolkit toolkit = new DeepAnalysisToolkit(model, deepAnalysisService, runRegistry,
            childRuns, ChatTestEndpoints.PROMPTS, 1L, AgentLang.ZH);

    private static ChildRun run() {
        return new ChildRun("1", SESSION, 1L, ChildRun.Kind.DEEP_ANALYSIS, "深研判 BTCUSDT", null, AgentLang.ZH);
    }

    // 模型参数一律 any()：主体每次把裸模型包进自己的账本再往下传，不是构造时那个实例
    private void debateSucceeds() {
        when(deepAnalysisService.buildNewsContext(any())).thenReturn("ctx");
        when(deepAnalysisService.bullArgue(any(), eq("BTCUSDT"), eq("ctx"), eq(AgentLang.ZH))).thenReturn("bull");
        when(deepAnalysisService.bearArgue(any(), eq("BTCUSDT"), eq("ctx"), eq(AgentLang.ZH))).thenReturn("bear");
        QuantDeepAnalysis analysis = new QuantDeepAnalysis();
        analysis.setNarrative("研判叙事");
        analysis.setScenariosJson("{\"bullPct\":40,\"rangePct\":35,\"bearPct\":25}");
        analysis.setNoDirection(false);
        analysis.setInvalidation("若X则作废");
        when(deepAnalysisService.judge(any(), eq("BTCUSDT"), anyLong(), eq("chat"),
                eq("ctx"), eq("bull"), eq("bear"), eq(AgentLang.ZH))).thenReturn(analysis);
    }

    @Test
    void 执行成功时输出研判结论并落库() {
        debateSucceeds();

        String result = toolkit.execute(run(), "BTCUSDT");

        assertThat(result).contains("\"status\":\"OK\"").contains("研判叙事").contains("作废");
        verify(deepAnalysisService).persist(any());
    }

    @Test
    void 裁决失败时报FAILED且不落库() {
        when(deepAnalysisService.buildNewsContext(any())).thenReturn("ctx");
        when(deepAnalysisService.bullArgue(any(), anyString(), anyString(), any())).thenReturn("b");
        when(deepAnalysisService.bearArgue(any(), anyString(), anyString(), any())).thenReturn("b");
        when(deepAnalysisService.judge(any(), anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), any())).thenReturn(null);

        String result = toolkit.execute(run(), "BTCUSDT");

        assertThat(result).contains("FAILED");
        verify(deepAnalysisService, never()).persist(any());
    }

    /** 辩论期间被停掉（停止/重新生成/删会话/超时）：报告反正不回灌，裁决那次深模型调用省下 */
    @Test
    void 取消后不裁决() {
        debateSucceeds();
        ChildRun run = run();
        when(deepAnalysisService.bearArgue(any(), eq("BTCUSDT"), eq("ctx"), eq(AgentLang.ZH))).thenAnswer(inv -> {
            run.cancel().complete(null);
            return "bear";
        });

        assertThat(toolkit.execute(run, "BTCUSDT")).isNull();

        verify(deepAnalysisService, never()).judge(any(), anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), any());
    }

    /** 工具调用不等几分钟：立刻回 RUNNING，研判结论稍后作为报告落进会话上下文（没轮在跑的那条路） */
    @Test
    void 工具调用立即返回RUNNING_报告异步回灌() {
        debateSucceeds();
        when(contextStore.load(SESSION)).thenReturn(List.of());

        String reply = toolkit.runDeepAnalysis("BTCUSDT", CTX);

        assertThat(reply).contains("\"status\":\"RUNNING\"").contains("\"runId\"");
        ArgumentCaptor<List<Message>> saved = ArgumentCaptor.captor();
        verify(contextStore, timeout(5_000)).save(eq(SESSION), eq(1L), saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(m -> assertThat(m.getText())
                .startsWith("【后台任务#").contains("深研判 BTCUSDT").contains("研判叙事"));
        assertThat(childRuns.state(SESSION).pending()).isTrue();
    }

    /**
     * 工具执行用的标的必须和闸门算授权键/写确认卡用的<b>是同一个字符串</b>。
     * <p>
     * 模型填 {@code btc} 时闸门归成 {@code BTCUSDT}（要能对上后续的 {@code btcusdt} 写法），
     * 工具这边要是只 trim+大写就成了 {@code BTC}——卡片写着 BTCUSDT、实际拿 BTC 去
     * {@code MarketDataService.assemble}，一条数据都取不到，落库的 symbol 也是错的。
     * <p>
     * 断言比的是闸门的真实产物而不是硬编码字面量：两边哪天各自改归一化规则，这条都会红。
     */
    @Test
    void 工具执行的标的与闸门授权键一致() {
        ApprovalRegistry registry = new ApprovalRegistry();
        new ApprovalGate(registry, ChatTestEndpoints.PROMPTS, AgentLang.ZH)
                .intercept(SESSION, deepCall("btc"));
        String gateSymbol = registry.peekPending(SESSION).orElseThrow().symbol();
        when(deepAnalysisService.buildNewsContext(any())).thenReturn("ctx");
        when(contextStore.load(SESSION)).thenReturn(List.of());

        toolkit.runDeepAnalysis("btc", CTX);

        verify(deepAnalysisService, timeout(5_000)).bullArgue(any(), eq(gateSymbol), eq("ctx"), eq(AgentLang.ZH));
        verify(deepAnalysisService, timeout(5_000)).bearArgue(any(), eq(gateSymbol), eq("ctx"), eq(AgentLang.ZH));
        verify(deepAnalysisService, timeout(5_000)).judge(any(), eq(gateSymbol), anyLong(), eq("chat"),
                anyString(), any(), any(), any());
    }

    private static AssistantMessage deepCall(String symbol) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function",
                        "run_deep_analysis", "{\"symbol\":\"" + symbol + "\"}")))
                .build();
    }
}
