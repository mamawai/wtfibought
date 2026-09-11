package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.behavior.BehaviorAnalysisService;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.toolkit.NewsToolkit;
import com.mawai.wiibagent.llm.ReactLoop;
import com.mawai.wiibagent.trader.TraderChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 专家叶子的保险丝。专家也是 ReAct 循环，没有上限就能一路循环烧钱；
 * 而且 market 专家的工具（market_snapshot / orderbook_depth）每次调用都打真实上游，
 * 这是行情配额账里唯一没封顶的一项。
 * <p>
 * <b>必须调生产的 {@link ChatAgentFactory#expertLoop}</b>：测试自己搭循环自己塞保险丝的话，
 * 验的只是"ModelCallLimiter 挂上之后好使"——那件事 ModelCallLimiterTest 已经验过了，
 * 而 ChatAgentFactory 里漏挂它照样绿。孤儿 tool_call 的补齐同理，不在这里重复断言。
 */
class ExpertCallLimitTest {

    /** 复刻 market 专家的工具形状：带参数、必须被真调 */
    public static class FakeMarketTools {
        @Tool(description = "行情快照")
        public String market_snapshot(String symbol) {
            return "{\"available\":true}";
        }
    }

    /** 故意远小于生产默认的 8，跑得快；上限值必须从构造参数来（见断言） */
    private static final int LIMIT = 3;

    /** 与工厂的生产装配同款；模型工厂只在 leavesFor 用得到，这条路不碰它，不必打桩 */
    private ChatAgentFactory factory() {
        return new ChatAgentFactory(mock(ChatModelFactory.class),
                mock(MarketToolkit.class), mock(NewsToolkit.class),
                mock(DeepAnalysisService.class), mock(BehaviorAnalysisService.class),
                mock(TraderChatService.class),
                mock(WorkbenchRunRegistry.class),
                new ApprovalRegistry(), ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS,
                LIMIT, 32000, 6, "X");
    }

    /** 模型永不收尾（每轮都只想再调一次工具）时，必须被保险丝按配置的上限收束 */
    @Test
    void 带工具的专家在模型永不收尾时被保险丝收束() {
        ChatModel model = mock(ChatModel.class);
        // ChatService 建请求时无条件读 getOptions() 挂工具，null 会 NPE
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AtomicInteger round = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            int i = round.incrementAndGet();
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("c" + i, "function",
                            "market_snapshot", "{\"symbol\":\"BTCUSDT\"}")))
                    .build())));
        });

        ReactLoop expert = factory()
                .expertLoop(AgentLang.ZH, model, new FakeMarketTools(), "required", "你是市场状态专家");
        expert.run(List.of(new UserMessage("看看行情")), null, null, null);

        // 恰好等于而非"不超过"：到上限就补占位回执收尾，触发那一刻模型正好被调 runLimit 次。
        // 钉死这个数才验得到上限值是从构造参数来的——
        // 写成 new ModelCallLimiter(1) 这种取错值的写法，"不超过"照样绿。
        assertThat(round.get()).isEqualTo(LIMIT);
    }
}
