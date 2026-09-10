package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具本体只剩"执行"这一件事：HITL 判断已经上移到 {@link ApprovalGate}
 * （工具方法体看不到 sessionId 和 tool_call 参数，判断做在这里就绑不住标的），
 * 授权语义的用例全在 {@link ApprovalGateTest}。
 */
class DeepAnalysisToolkitTest {

    private static final String SESSION = "wb-1-x";
    /** 生产里会话号由 ChatTurnRunner 传给 ReactLoop，循环执行工具时经 ToolContext 交给工具 */
    private static final ToolContext CTX = new ToolContext(Map.of(ToolRunContext.SESSION_KEY, SESSION));

    private final ChatModel model = mock(ChatModel.class);
    private final DeepAnalysisService deepAnalysisService = mock(DeepAnalysisService.class);
    private final WorkbenchRunRegistry runRegistry = new WorkbenchRunRegistry();

    private final DeepAnalysisToolkit toolkit =
            new DeepAnalysisToolkit(model, deepAnalysisService, runRegistry, ChatTestEndpoints.PROMPTS, AgentLang.ZH);

    @Test
    void 执行成功时输出研判结论并落库() {
        when(deepAnalysisService.buildNewsContext(any())).thenReturn("ctx");
        when(deepAnalysisService.bullArgue(model, "BTCUSDT", "ctx", AgentLang.ZH)).thenReturn("bull");
        when(deepAnalysisService.bearArgue(model, "BTCUSDT", "ctx", AgentLang.ZH)).thenReturn("bear");
        QuantDeepAnalysis analysis = new QuantDeepAnalysis();
        analysis.setNarrative("研判叙事");
        analysis.setScenariosJson("{\"bullPct\":40,\"rangePct\":35,\"bearPct\":25}");
        analysis.setNoDirection(false);
        analysis.setInvalidation("若X则作废");
        when(deepAnalysisService.judge(eq(model), eq("BTCUSDT"), anyLong(), eq("chat"),
                eq("ctx"), eq("bull"), eq("bear"), eq(AgentLang.ZH))).thenReturn(analysis);

        String result = toolkit.runDeepAnalysis("BTCUSDT", CTX);

        assertThat(result).contains("\"status\":\"OK\"").contains("研判叙事").contains("作废");
        verify(deepAnalysisService).persist(analysis);
    }

    @Test
    void 裁决失败时报FAILED且不落库() {
        when(deepAnalysisService.buildNewsContext(any())).thenReturn("ctx");
        when(deepAnalysisService.bullArgue(any(), anyString(), anyString(), any())).thenReturn("b");
        when(deepAnalysisService.bearArgue(any(), anyString(), anyString(), any())).thenReturn("b");
        when(deepAnalysisService.judge(any(), anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), any())).thenReturn(null);

        String result = toolkit.runDeepAnalysis("BTCUSDT", CTX);

        assertThat(result).contains("FAILED");
        verify(deepAnalysisService, never()).persist(any());
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

        toolkit.runDeepAnalysis("btc", CTX);

        verify(deepAnalysisService).bullArgue(model, gateSymbol, "ctx", AgentLang.ZH);
        verify(deepAnalysisService).bearArgue(model, gateSymbol, "ctx", AgentLang.ZH);
        verify(deepAnalysisService).judge(eq(model), eq(gateSymbol), anyLong(), eq("chat"),
                anyString(), any(), any(), any());
    }

    private static AssistantMessage deepCall(String symbol) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function",
                        "run_deep_analysis", "{\"symbol\":\"" + symbol + "\"}")))
                .build();
    }
}
