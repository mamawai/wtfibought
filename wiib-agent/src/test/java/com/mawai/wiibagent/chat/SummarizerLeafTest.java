package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.behavior.BehaviorAnalysisService;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.toolkit.NewsToolkit;
import com.mawai.wiibagent.llm.ReactLoop;
import com.mawai.wiibagent.trader.TraderChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * summarizer 那三样的装配：压缩 + HITL 闸门 + 保险丝，一样都不能漏传给它的 {@link ReactLoop}。
 * <p>
 * <b>建的是生产的 {@link ChatAgentFactory#leavesFor} 并真跑</b>——自己搭个循环自己塞这三样，
 * 只能证明它们各自好使（那件事 ModelCallLimiterTest/ConversationSummarizerTest 已经证过），
 * 证明不了生产装配里传上了。
 */
class SummarizerLeafTest {

    private static final String SESSION = "wb-1-summarizer-leaf";
    /** 压缩提示词的特征串：浅模型这里只服务摘要一种请求，留着当断言锚点 */
    private static final String SUMMARY_PROMPT_MARK = "请把下面的对话历史压缩成一段要点记录";
    private static final String SUMMARY_PREFIX = "## 早前对话摘要：";
    /** 阈值给足 = 这一跑不碰压缩 */
    private static final int NO_COMPRESSION = 999_999;

    private final ChatModel deep = mock(ChatModel.class);
    private final ChatModel light = mock(ChatModel.class);
    private final ApprovalRegistry registry = new ApprovalRegistry();
    private final DeepAnalysisService deepAnalysisService = mock(DeepAnalysisService.class);
    private final WorkbenchRunRegistry runRegistry = mock(WorkbenchRunRegistry.class);

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

    /**
     * @param threshold 压缩阈值（token），调小才触发
     * @param keep      保留最近几条，调小才有原文可压
     * @param limit     模型调用上限
     */
    private ChatAgentFactory.Leaves leaves(int threshold, int keep, int limit) {
        // ChatService 建请求时无条件读 getOptions() 挂工具，null 会 NPE
        when(deep.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(light.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.modelsFor(any())).thenReturn(new ChatModelFactory.Models(deep, light));
        // run_deep_analysis 必须是能执行的真工具（要真走到工具边）——
        // 工厂内部自己 new DeepAnalysisToolkit，天然就是真的，这里只喂它的依赖
        ChatEndpoints llmConfig = ChatTestEndpoints.eps(1L, "gpt-5");   // 叶子指纹含 userId（trader 工具按它认人）
        return new ChatAgentFactory(chatModelFactory, mock(MarketToolkit.class), mock(NewsToolkit.class),
                deepAnalysisService, mock(BehaviorAnalysisService.class),
                mock(TraderChatService.class), runRegistry,
                registry, ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS, limit, threshold, keep, "X")
                .leavesFor(llmConfig, AgentLang.ZH);
    }

    /** 与 {@code ChatTurnRunner} 同款跑法：会话号一份进闸门、一份进工具的 ToolContext；答案 token 逐帧收进 chunks */
    private ReactLoop.Result consume(ChatAgentFactory.Leaves leaves, List<Message> input, List<String> chunks) {
        return leaves.summarizer().run(input, SESSION, null, new ReactLoop.Listener() {
            @Override
            public void chunk(ChatResponse frame) {
                String text = frame.getResults().getFirst().getOutput().getText();
                if (text != null && !text.isEmpty()) {
                    chunks.add(text);
                }
            }
        });
    }

    /** 深研判工具的回包：narrative 撑得够大，一条回执就把历史顶过压缩阈值 */
    private void deepAnalysisReturnsBigResult() {
        QuantDeepAnalysis analysis = new QuantDeepAnalysis();
        analysis.setNarrative("行情研判正文".repeat(200));
        analysis.setScenariosJson("{\"bullPct\":40,\"rangePct\":35,\"bearPct\":25}");
        analysis.setNoDirection(Boolean.FALSE);
        analysis.setInvalidation("跌破前低即失效");
        analysis.setJudgeReasoning("裁决理由");
        when(deepAnalysisService.buildNewsContext(any())).thenReturn("新闻上下文");
        when(deepAnalysisService.bullArgue(any(), anyString(), anyString(), any())).thenReturn("多方论证");
        when(deepAnalysisService.bearArgue(any(), anyString(), anyString(), any())).thenReturn("空方论证");
        when(deepAnalysisService.judge(any(), anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), any())).thenReturn(analysis);
    }

    private void approveDeepAnalysis() {
        registry.requestApproval(SESSION, "run_deep_analysis", "BTCUSDT", "贵操作");
        registry.approve(SESSION, registry.peekPending(SESSION).orElseThrow().requestId());
    }

    /** 工具方法体的会话号来自 run 的 sessionId 入参：循环执行工具时放进 ToolContext，不靠 ThreadLocal */
    @Test
    void 工具从ToolContext里拿到会话号() {
        AtomicInteger round = new AtomicInteger();
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> Flux.just(responseOf(round.incrementAndGet() == 1
                ? toolCall("c1", "wake_trader", "{}")
                : new AssistantMessage("表单已打开"))));
        when(runRegistry.publishForm(any(), any(), any())).thenReturn(true);

        consume(leaves(NO_COMPRESSION, 6, 8), List.of(new UserMessage("叫醒交易员")), new ArrayList<>());

        verify(runRegistry).publishForm(eq(SESSION), eq("wake"), isNull());
    }

    /**
     * 闸门真的传给了 summarizer 的循环：生产叶子跑一轮，模型要调深研判，未授权时必须被拦下、
     * 留下待确认。
     * <p>
     * 这是唯一抓得住"忘了把 ApprovalGate 传给 summarizer 的 {@link ReactLoop}"的钉子——
     * gate 是可空参数，漏传编译照过、一个字都不报，HITL 整条链直接哑掉。
     */
    @Test
    void 闸门在生产叶子上拦下未授权的深研判() {
        when(deep.stream(any(Prompt.class)))
                .thenReturn(Flux.just(responseOf(deepAnalysisCall("c1"))))
                .thenReturn(Flux.just(responseOf(new AssistantMessage("已请你确认"))));

        leaves(NO_COMPRESSION, 6, 8).summarizer()
                .run(List.of(new UserMessage("深度研判 BTC")), SESSION, null, null);

        assertThat(registry.peekPending(SESSION)).isPresent()
                .get().satisfies(p -> assertThat(p.symbol()).isEqualTo("BTCUSDT"));
    }

    /**
     * <b>验收硬条件</b>：压缩必须在 tool_call/tool_response <b>配对完整</b>的前提下发生，
     * 而且压完之后这份历史还能原样发给上游。
     * <p>
     * 落单的回执（有 function_call_output 没有 function_call）会被 {@code ResponsesChatModel}
     * 无条件转进请求，OpenAI 自家 Responses 对此直接 400——会话只能删掉重开。
     * 所以这里逐条核对第二次模型调用收到的 Prompt。
     */
    @Test
    void 压缩发生且不切断工具调用配对() {
        approveDeepAnalysis();
        deepAnalysisReturnsBigResult();
        when(light.call(any(Prompt.class))).thenAnswer(inv -> {
            // 浅模型在这条链上只干一件事：出摘要
            assertThat(((Prompt) inv.getArgument(0)).getContents()).contains(SUMMARY_PROMPT_MARK);
            return responseOf(new AssistantMessage("早前聊了行情"));
        });
        List<Prompt> deepPrompts = new ArrayList<>();
        AtomicInteger round = new AtomicInteger();
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> {
            deepPrompts.add(inv.getArgument(0));
            // 第一轮要工具（制造一对 tool_call/回执 + 一大坨内容），第二轮出答案收尾
            return Flux.just(responseOf(round.incrementAndGet() == 1
                    ? deepAnalysisCall("c1")
                    : new AssistantMessage("这是答案")));
        });
        // keep=2：第二次调用时历史 5 条，理想切点落在 tool_call 之前，正好检验它切得安不安全
        ChatAgentFactory.Leaves leaves = leaves(200, 2, 8);

        List<String> chunks = new ArrayList<>();
        ReactLoop.Result result = consume(leaves, List.of(
                new UserMessage("上轮问题"), new AssistantMessage("上轮回答"),
                new UserMessage("深度研判 BTC")), chunks);

        assertThat(deepPrompts).hasSize(2);
        List<Message> second = deepPrompts.getLast().getInstructions();
        // 压缩真发生了：老原文被换成了一条带固定前缀的摘要
        assertThat(second).anyMatch(m -> m instanceof SystemMessage
                && m.getText() != null && m.getText().startsWith(SUMMARY_PREFIX)
                && m.getText().contains("早前聊了行情"));
        // 而且压短了：第一次调用 4 条（含 agent 自己的 system），第二次的历史多了一对工具消息
        // 却没变长——不压的话是 6 条
        assertThat(second).hasSizeLessThan(6);
        // 配对完整：每个 tool_call 都能在后面找到自己的回执
        assertThat(orphanToolCalls(second)).isEmpty();
        assertThat(orphanToolResponses(second)).isEmpty();
        // 压缩开着的时候 token 照样逐帧到达前端——这是用户唯一看得见的东西
        assertThat(chunks).containsExactly("这是答案");
        // 终态也得是配对完整的：它会被整体落进会话上下文表，下一轮原样重放
        List<Message> finalMessages = result.messages();
        assertThat(orphanToolResponses(finalMessages)).isEmpty();
        assertThat(finalMessages.getLast().getText()).isEqualTo("这是答案");
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
        ChatAgentFactory.Leaves leaves = leaves(NO_COMPRESSION, 6, limit);

        assertThatCode(() -> consume(leaves, List.of(new UserMessage("深度研判 BTC")), new ArrayList<>()))
                .doesNotThrowAnyException();

        // 恰好等于而非"不超过"：到上限就补占位回执收尾，触发那刻模型正好被调 limit 次。
        // 钉死这个数才验得到上限值确实是从构造参数来的
        assertThat(round.get()).isEqualTo(limit);
    }

    /** 每个 tool_call 的 id 是否都能找到配对回执 */
    private static Set<String> orphanToolCalls(List<Message> messages) {
        Set<String> ids = new HashSet<>();
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant) {
                assistant.getToolCalls().forEach(call -> ids.add(call.id()));
            }
        }
        ids.removeAll(toolResponseIds(messages));
        return ids;
    }

    /** 反方向：回执找不到自己的调用（这个是上游真会 400 的那种） */
    private static Set<String> orphanToolResponses(List<Message> messages) {
        Set<String> ids = new HashSet<>(toolResponseIds(messages));
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant) {
                assistant.getToolCalls().forEach(call -> ids.remove(call.id()));
            }
        }
        return ids;
    }

    private static Set<String> toolResponseIds(List<Message> messages) {
        Set<String> ids = new HashSet<>();
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage toolResponse) {
                toolResponse.getResponses().forEach(response -> ids.add(response.id()));
            }
        }
        return ids;
    }
}
