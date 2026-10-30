package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.behavior.BehaviorAnalysisService;
import com.mawai.wiibagent.toolkit.DerivativesToolkit;
import com.mawai.wiibagent.toolkit.EconCalendarToolkit;
import com.mawai.wiibagent.toolkit.IndicatorToolkit;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.toolkit.WhaleToolkit;
import com.mawai.wiibagent.llm.ReactLoop;
import com.mawai.wiibagent.trader.TraderChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主 agent 那两样的装配：HITL 闸门 + 保险丝，一样都不能漏传给它的 {@link ReactLoop}。
 * <p>
 * <b>建的是生产的 {@link ChatAgentFactory#leavesFor} 并真跑</b>——自己搭个循环自己塞这两样，
 * 只能证明它们各自好使（那件事 ModelCallLimiterTest 已经证过），证明不了生产装配里传上了。
 */
class MainLeafTest {

    private static final String SESSION = "wb-1-main-leaf";

    private final ChatModel deep = ChatTestEndpoints.model();
    private final ChatModel light = ChatTestEndpoints.model();
    private final ApprovalRegistry registry = new ApprovalRegistry();
    private final DeepAnalysisService deepAnalysisService = mock(DeepAnalysisService.class);
    private final WorkbenchRunRegistry runRegistry = mock(WorkbenchRunRegistry.class);
    private final TraderChatService traderChatService = mock(TraderChatService.class);

    private static ChatResponse responseOf(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static AssistantMessage toolCall(String id, String name, String arguments) {
        return AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                id, "function", name, arguments))).build();
    }

    private static AssistantMessage deepAnalysisCall(String id) {
        return toolCall(id, "run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}");
    }

    /** @param limit 模型调用上限 */
    private ChatAgentFactory.Leaves leaves(int limit) {
        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.modelsFor(any())).thenReturn(new ChatModelFactory.Models(deep, light));
        // run_deep_analysis 必须是能执行的真工具（要真走到工具边）——
        // 工厂内部自己 new DeepAnalysisToolkit，天然就是真的，这里只喂它的依赖
        ChatEndpoints llmConfig = ChatTestEndpoints.eps(1L, "gpt-5");   // 叶子指纹含 userId（trader 工具按它认人）
        return new ChatAgentFactory(chatModelFactory, mock(MarketToolkit.class), mock(IndicatorToolkit.class), ChatTestEndpoints.newsToolkit(),
                mock(EconCalendarToolkit.class), mock(WhaleToolkit.class), mock(DerivativesToolkit.class),
                deepAnalysisService, mock(BehaviorAnalysisService.class),
                traderChatService, runRegistry, ChatTestEndpoints.childRuns(mock(ChatContextStore.class)),
                registry, ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS, limit, "X")
                .leavesFor(llmConfig, AgentLang.ZH);
    }

    /** 与 {@code ChatTurnRunner} 同款跑法：会话号一份进闸门、一份进工具的 ToolContext；答案 token 逐帧收进 chunks */
    private ReactLoop.Result consume(ChatAgentFactory.Leaves leaves, List<Message> input, List<String> chunks) {
        return leaves.main().run(input, SESSION, null, new ReactLoop.Listener() {
            @Override
            public void chunk(ChatResponse frame) {
                String text = frame.getResults().getFirst().getOutput().getText();
                if (text != null && !text.isEmpty()) {
                    chunks.add(text);
                }
            }
        });
    }

    /** 工具方法体的会话号来自 run 的 sessionId 入参：循环执行工具时放进 ToolContext，不靠 ThreadLocal */
    @Test
    void 工具从ToolContext里拿到会话号() {
        AtomicInteger round = new AtomicInteger();
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> Flux.just(responseOf(round.incrementAndGet() == 1
                ? toolCall("c1", "wake_trader", "{}")
                : new AssistantMessage("表单已打开"))));
        when(runRegistry.publishForm(any(), any(), any())).thenReturn(true);

        consume(leaves(8), List.of(new UserMessage("叫醒交易员")), new ArrayList<>());

        verify(runRegistry).publishForm(eq(SESSION), eq("wake"), isNull());
    }

    /**
     * 闸门真的传给了主 agent 的循环：生产叶子跑一轮，模型要调深研判，未授权时必须被拦下、
     * 留下待确认。
     * <p>
     * 这是唯一抓得住"忘了把 ApprovalGate 传给主 agent 的 {@link ReactLoop}"的钉子——
     * gate 是可空参数，漏传编译照过、一个字都不报，HITL 整条链直接哑掉。
     */
    @Test
    void 闸门在生产叶子上拦下未授权的深研判() {
        when(deep.stream(any(Prompt.class)))
                .thenReturn(Flux.just(responseOf(deepAnalysisCall("c1"))))
                .thenReturn(Flux.just(responseOf(new AssistantMessage("已请你确认"))));

        leaves(8).main()
                .run(List.of(new UserMessage("深度研判 BTC")), SESSION, null, null);

        assertThat(registry.peekPending(SESSION)).isPresent()
                .get().satisfies(p -> assertThat(p.symbol()).isEqualTo("BTCUSDT"));
    }

    /**
     * 模型永不收尾时收束它的是保险丝，模型被调次数恰好等于上限。
     * <p>
     * 制造"永不收尾"的办法是不授权：闸门每次都拦下、回一条 PENDING_APPROVAL，等于一个纯净的循环，
     * 一次深模型工具都不真跑。
     */
    @Test
    void 模型永不收尾时被保险丝收束() {
        int limit = 8;   // 生产口径
        AtomicInteger round = new AtomicInteger();
        when(deep.stream(any(Prompt.class))).thenAnswer(inv ->
                Flux.just(responseOf(deepAnalysisCall("c" + round.incrementAndGet()))));
        ChatAgentFactory.Leaves leaves = leaves(limit);

        assertThatCode(() -> consume(leaves, List.of(new UserMessage("深度研判 BTC")), new ArrayList<>()))
                .doesNotThrowAnyException();

        // 恰好等于而非"不超过"：到上限就补占位回执收尾，触发那刻模型正好被调 limit 次。
        // 钉死这个数才验得到上限值确实是从构造参数来的
        assertThat(round.get()).isEqualTo(limit);
    }
}
