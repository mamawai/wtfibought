package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.ChatConcurrencyGate;
import com.mawai.wiibagent.chat.gate.TraderPickRegistry;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.run.ChildRunRegistry;
import com.mawai.wiibagent.trader.TraderService;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.chat.store.ChatHistoryService;
import com.mawai.wiibagent.chat.store.ChatRowKind;
import com.mawai.wiibagent.controller.ChatWorkbenchController;
import com.mawai.wiibagent.llm.LlmClient;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibagent.llm.ModelCallLimiter;
import com.mawai.wiibagent.llm.ReactLoop;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import com.mawai.wiibagent.mapper.WorkbenchChatContextMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重新生成最后一条回答：回退到"提问已在、回答未出"，再用原提问重跑。
 * <p>
 * 四件必须钉住的事：
 * <ol>
 *   <li><b>轮起始标记只是候选</b>——整理上下文会把上一轮的提问原样留在摘要后面，老会话的压缩还把
 *       首条用户消息原样放在队首，那几条也带标记；不拿提问原文核对就会切错位置，把中间几轮一起抹掉。</li>
 *   <li><b>旧答案要留到新答案确实落库之后才删</b>——重跑失败/空产出的路上，
 *       用户至少还留着原来那条，也还能再点一次。</li>
 *   <li><b>页面上留着旧答案，模型上下文里也得有它</b>——一个字没出就被停下、产出为空时，上下文退回重新生成之前。</li>
 *   <li><b>拒绝时名额必须还回去</b>——不还的话这个用户此后永久占线，比不给重新生成严重得多。</li>
 * </ol>
 */
class ChatRegenerateTest {

    private static final String SESSION = "wb-1-regen";

    /** kind 按正文现认，与 ChatHistoryService.messages() 同一条路径 */
    private static ChatHistoryService.ChatMessage msg(long id, String role, String content) {
        return new ChatHistoryService.ChatMessage(id, role, content, 1_700_000_000_000L, null,
                ChatRowKind.of(role, content, ChatTestEndpoints.PROMPTS), null, null);
    }

    /** 上下文里一条轮起始提问，形状与 run() 拼的 enriched 一致（标记按语言取词表，同生产） */
    private static Message turnStart(String question) {
        return turnStart(AgentLang.ZH, question);
    }

    private static Message turnStart(AgentLang lang, String question) {
        return new UserMessage(ChatTestEndpoints.PROMPTS.get(lang, "chat.turn.timeMark",
                Map.of("time", "2026-08-18 14:32")) + "\n"
                + ChatTestEndpoints.PROMPTS.get(lang, "chat.turn.questionPrefix") + question);
    }

    private record Harness(ChatWorkbenchController controller, ChatHistoryService history,
                           ChatContextStore contextStore, ChatTurnRunner turnRunner,
                           ChatConcurrencyGate gate) {
    }

    private static Harness harness(List<ChatHistoryService.ChatMessage> stored, List<Message> context) {
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(history.messages(SESSION)).thenReturn(stored);
        // 默认落库成功：删旧答案的前提就是这个返回值，桩成默认的 false 整条重生成都不会走到删
        when(history.append(any(), anyLong(), any(), any(), any(), any(), any())).thenReturn(true);
        ChatContextStore contextStore = mock(ChatContextStore.class);
        when(contextStore.load(SESSION)).thenReturn(new ChatContextStore.State(context, 1000L, "fp"));

        ChatTurnRunner turnRunner = mock(ChatTurnRunner.class);
        doAnswer((Answer<ChatTurnRunner.TurnResult>) inv -> {
            Consumer<String> sink = inv.getArgument(5);   // leaves/userId/session/message/intent 之后才是答案 sink
            sink.accept("新答案");
            return ChatTurnRunner.TurnResult.COMPLETED;
        }).when(turnRunner).run(any(), anyLong(), any(), any(), any(), any(), any(), any());

        LlmEndpointService endpointService = mock(LlmEndpointService.class);
        when(endpointService.chatEndpoints(1L)).thenReturn(ChatTestEndpoints.eps(1L, "gpt-5"));

        // 叶子只需要账本是真的：turnRunner 被替身顶了，图用不上
        UsageTrackingChatModel model = new UsageTrackingChatModel(mock(ChatModel.class));
        ChatAgentFactory agentFactory = mock(ChatAgentFactory.class);
        when(agentFactory.leavesFor(any(), any()))
                .thenReturn(new ChatAgentFactory.Leaves("端点 · gpt-5", model, null, null, AgentLang.ZH, 256_000));

        ChatConcurrencyGate gate = ChatTestEndpoints.openGate(10);
        WorkbenchRunRegistry runRegistry = mock(WorkbenchRunRegistry.class);
        ApprovalRegistry approvals = new ApprovalRegistry();
        ChatTurnStreamer streamer = new ChatTurnStreamer(turnRunner, history, runRegistry,
                approvals, ChatTestEndpoints.PROMPTS);
        ChatTurnRewinder rewinder = new ChatTurnRewinder(history, contextStore, ChatTestEndpoints.PROMPTS);
        ChatWorkbenchController controller = new ChatWorkbenchController(agentFactory, endpointService,
                approvals, mock(TraderPickRegistry.class), mock(TraderService.class),
                history, contextStore, streamer, rewinder, runRegistry,
                ChatTestEndpoints.childRuns(contextStore), ChatTestEndpoints.prefs(), gate, new MessageCatalog(),
                ChatTestEndpoints.PROMPTS, ChatTestEndpoints.zhLang(),
                Executors.newVirtualThreadPerTaskExecutor());
        return new Harness(controller, history, contextStore, turnRunner, gate);
    }

    private static void regenerate(Harness h) {
        regenerate(h, SESSION);
    }

    private static void regenerate(Harness h, String sessionId) {
        ChatWorkbenchController.RegenerateRequest r = new ChatWorkbenchController.RegenerateRequest();
        r.setSessionId(sessionId);
        h.controller().regenerate(1L, r, mock(HttpServletResponse.class));
    }

    private static void assertRejected(Harness h) {
        assertThatThrownBy(() -> regenerate(h))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.CHAT_REGENERATE_UNAVAILABLE.getCode());
        // 名额没还的话这个用户此后永久占线，比不给重新生成严重得多
        assertThat(h.gate().tryAcquire(1L, ChatConcurrencyGate.Owner.WORKBENCH)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
        // 拒绝就什么都别动
        verify(h.contextStore(), never()).save(any(), anyLong(), any());
        verify(h.history(), never()).deleteMessage(anyLong());
    }

    /** 会话上下文表的假实现：字节真存真取，编解码是生产那套 */
    private static ChatContextStore inMemoryContextStore() {
        Map<String, byte[]> rows = new ConcurrentHashMap<>();
        WorkbenchChatContextMapper mapper = mock(WorkbenchChatContextMapper.class);
        when(mapper.selectState(anyString())).thenAnswer(inv -> {
            byte[] row = rows.get(inv.getArgument(0));
            return row == null ? List.of() : List.of(row);
        });
        when(mapper.upsert(anyString(), anyLong(), any())).thenAnswer(inv -> {
            rows.put(inv.getArgument(0), inv.getArgument(2));
            return 1;
        });
        return new ChatContextStore(mapper);
    }

    @Test
    void 回退后用原提问重跑且不重复落提问行() {
        Harness h = harness(
                List.of(msg(1, "user", "BTC 怎么样"), msg(2, "assistant", "旧答案")),
                List.of(turnStart("BTC 怎么样"), new AssistantMessage("旧答案")));

        regenerate(h);

        // 上下文回删到那条提问为止（含它）：重跑时 run() 会自己把提问重新垫进去；
        // 回删后读数作废，前缀指纹照旧带着
        ArgumentCaptor<ChatContextStore.State> ctx = ArgumentCaptor.captor();
        verify(h.contextStore()).save(eq(SESSION), eq(1L), ctx.capture());
        assertThat(ctx.getValue().messages()).isEmpty();
        assertThat(ctx.getValue().contextTokens()).isNull();
        assertThat(ctx.getValue().prefix()).isEqualTo("fp");

        // 重跑喂给 runner 的是库里那条原提问
        ArgumentCaptor<String> enriched = ArgumentCaptor.captor();
        verify(h.turnRunner(), timeout(5_000))
                .run(any(), anyLong(), eq(SESSION), enriched.capture(), any(), any(), any(), any());
        assertThat(enriched.getValue()).endsWith(
                ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.turn.questionPrefix") + "BTC 怎么样");
        // 提问行已经在库里，再落一遍历史里就成了连问两遍
        verify(h.history(), never()).append(any(), anyLong(), eq("user"), any());
        // 新答案落库之后旧答案才被顶掉；提问行一直留着（前端气泡不闪、时间戳不变）
        verify(h.history(), timeout(5_000)).append(eq(SESSION), eq(1L), eq("assistant"), eq("新答案"), any(), any(), any());
        verify(h.history(), timeout(5_000)).deleteMessage(2L);
        verify(h.history(), never()).deleteMessage(1L);
    }

    /** 切过语言的会话：末轮标记是英文、当次请求语言是中文——getCut 按语言遍历才认得出边界 */
    @Test
    void 英文轮起始标记同样认得() {
        Harness h = harness(
                List.of(msg(1, "user", "how is BTC"), msg(2, "assistant", "old answer")),
                List.of(turnStart(AgentLang.EN, "how is BTC"), new AssistantMessage("old answer")));

        regenerate(h);

        ArgumentCaptor<ChatContextStore.State> ctx = ArgumentCaptor.captor();
        verify(h.contextStore()).save(eq(SESSION), eq(1L), ctx.capture());
        assertThat(ctx.getValue().messages()).isEmpty();
    }

    /**
     * append 自己吞异常、只记日志（历史是增益不是主链），所以"落库了没有"必须看返回值。
     * 没落上还照删，这个提问下就一条答案都不剩了——末尾是 user 行，连重新生成都点不了。
     */
    @Test
    void 新答案没落库就绝不删旧答案() {
        Harness h = harness(
                List.of(msg(1, "user", "BTC 怎么样"), msg(2, "assistant", "旧答案")),
                List.of(turnStart("BTC 怎么样"), new AssistantMessage("旧答案")));
        when(h.history().append(any(), anyLong(), eq("assistant"), any(), any(), any(), any())).thenReturn(false);

        regenerate(h);

        verify(h.history(), timeout(5_000)).append(eq(SESSION), eq(1L), eq("assistant"), eq("新答案"), any(), any(), any());
        verify(h.history(), never()).deleteMessage(anyLong());
    }

    /**
     * 点了重新生成又马上点停止：一个字都没出，这时候顶掉旧答案等于拿一行"（未作答）"
     * 换掉用户原来那条好答案，而且找不回来。出了半截才算这一次重生成的产物，那才该顶替。
     */
    @Test
    void 重生成刚开跑就被中断_旧答案不许被顶掉() {
        Harness h = harness(
                List.of(msg(1, "user", "BTC 怎么样"), msg(2, "assistant", "旧答案")),
                List.of(turnStart("BTC 怎么样"), new AssistantMessage("旧答案")));
        doReturn(ChatTurnRunner.TurnResult.CANCELLED)
                .when(h.turnRunner()).run(any(), anyLong(), any(), any(), any(), any(), any(), any());

        regenerate(h);

        verify(h.history(), timeout(5_000)).append(eq(SESSION), eq(1L), eq("assistant"), any(), any(), any(), any());
        verify(h.history(), never()).deleteMessage(anyLong());
    }

    /**
     * 真 runner、真上下文存储跑一遍：一个字没出就被停下，页面留着旧答案，模型上下文也得退回有它的样子——
     * 不然用户接着问"展开第二个方案"，模型根本没见过屏幕上那份回答
     */
    @Test
    void 重生成一个字没出就被停下_上下文仍含旧答案() throws InterruptedException {
        // 出字前点了停止：模型一被调用就叫停这一轮，一帧都没来
        RealRun run = regenerateOnRealContext(runRegistry -> inv -> {
            runRegistry.requestCancel(SESSION);
            return Flux.empty();
        });

        assertThat(run.context()).containsExactly(turnStart("BTC 怎么样").getText(), "旧答案");
    }

    /** 正常跑完却一个字都没产出：页面同样换回旧答案，上下文也退回 */
    @Test
    void 重生成产出为空_上下文仍含旧答案() throws InterruptedException {
        RealRun run = regenerateOnRealContext(runRegistry -> inv -> Flux.just(new ChatResponse(List.of(new Generation(
                new AssistantMessage(""), ChatGenerationMetadata.builder().finishReason("STOP").build())))));

        // 走的是正常收尾：空答案交给了落库，旧答案没删
        verify(run.history()).append(eq(SESSION), eq(1L), eq("assistant"), eq(""), any(), any(), any());
        verify(run.history(), never()).deleteMessage(anyLong());
        assertThat(run.context()).containsExactly(turnStart("BTC 怎么样").getText(), "旧答案");
    }

    /** 真 runner、真上下文存储跑完的现场：落历史的 mock 和收尾后的上下文 */
    private record RealRun(ChatHistoryService history, List<String> context) {
    }

    /** 上下文是"BTC 怎么样 / 旧答案"，点重新生成；upstream 定模型这次怎么回，能拿到这一轮的运行登记 */
    private static RealRun regenerateOnRealContext(
            Function<WorkbenchRunRegistry, Answer<Flux<ChatResponse>>> upstream) throws InterruptedException {
        ChatContextStore contextStore = inMemoryContextStore();
        contextStore.save(SESSION, 1L, new ChatContextStore.State(
                List.of(turnStart("BTC 怎么样"), new AssistantMessage("旧答案")), 1000L, "fp"));
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(history.messages(SESSION)).thenReturn(List.of(msg(1, "user", "BTC 怎么样"), msg(2, "assistant", "旧答案")));
        when(history.append(any(), anyLong(), any(), any(), any(), any(), any())).thenReturn(true);
        WorkbenchRunRegistry runRegistry = new WorkbenchRunRegistry();
        ChatModel model = ChatTestEndpoints.model();
        when(model.stream(any(Prompt.class))).thenAnswer(upstream.apply(runRegistry));
        UsageTrackingChatModel deep = new UsageTrackingChatModel(model);
        LlmClient client = LlmClient.builder().model(deep).systemPrompt("你是助手").build();
        ReactLoop main = ReactLoop.builder().client(client).streaming(true)
                .limiter(new ModelCallLimiter(8, "未执行", "收尾")).build();
        ChatAgentFactory agentFactory = mock(ChatAgentFactory.class);
        when(agentFactory.leavesFor(any(), any()))
                .thenReturn(new ChatAgentFactory.Leaves("端点 · gpt-5", deep, main, client, AgentLang.ZH, 256_000));
        LlmEndpointService endpointService = mock(LlmEndpointService.class);
        when(endpointService.chatEndpoints(1L)).thenReturn(ChatTestEndpoints.eps(1L, "gpt-5"));
        ChildRunRegistry childRuns = ChatTestEndpoints.childRuns(contextStore);
        ChatTurnRunner turnRunner = new ChatTurnRunner(contextStore, childRuns, ChatTestEndpoints.PROMPTS, runRegistry,
                ChatTestEndpoints.compactor());
        ApprovalRegistry approvals = new ApprovalRegistry();
        ChatTurnStreamer streamer = new ChatTurnStreamer(turnRunner, history, runRegistry, approvals,
                ChatTestEndpoints.PROMPTS);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        ChatWorkbenchController controller = new ChatWorkbenchController(agentFactory, endpointService,
                approvals, mock(TraderPickRegistry.class), mock(TraderService.class),
                history, contextStore, streamer, new ChatTurnRewinder(history, contextStore, ChatTestEndpoints.PROMPTS),
                runRegistry, childRuns, ChatTestEndpoints.prefs(), ChatTestEndpoints.openGate(10), new MessageCatalog(),
                ChatTestEndpoints.PROMPTS, ChatTestEndpoints.zhLang(), executor);
        ChatWorkbenchController.RegenerateRequest request = new ChatWorkbenchController.RegenerateRequest();
        request.setSessionId(SESSION);

        controller.regenerate(1L, request, mock(HttpServletResponse.class));
        // 等这一轮彻底收完尾
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        return new RealRun(history, contextStore.load(SESSION).messages().stream().map(Message::getText).toList());
    }

    @Test
    void 重跑失败时旧答案还留着() {
        Harness h = harness(
                List.of(msg(1, "user", "BTC 怎么样"), msg(2, "assistant", "旧答案")),
                List.of(turnStart("BTC 怎么样"), new AssistantMessage("旧答案")));
        doThrow(new RuntimeException("上游挂了"))
                .when(h.turnRunner()).run(any(), anyLong(), any(), any(), any(), any(), any(), any());

        regenerate(h);

        // 这一轮不落 assistant 行；旧答案要是已经删了，用户点一次重新生成就把好答案弄丢了，还没法再点
        verify(h.history(), after(500).never()).deleteMessage(anyLong());
        verify(h.history(), never()).append(any(), anyLong(), eq("assistant"), any(), any(), any(), any());
    }

    /**
     * 老会话：老压缩把首问原样放回队首、后面紧跟 SystemMessage 摘要。本轮问的恰好是同一句，
     * 文本对得上但位置是假的——照切会把整段上下文连摘要清空
     */
    @Test
    void 老会话首问与本轮同一句时拒绝() {
        Harness h = harness(
                List.of(msg(1, "user", "BTC 怎么样"), msg(2, "assistant", "旧答案")),
                List.of(turnStart("BTC 怎么样"),
                        new SystemMessage("【历史摘要】前面聊过行情与新闻"),
                        new AssistantMessage("旧答案")));

        assertRejected(h);
    }

    @Test
    void 老会话被压缩过时拒绝而不是切在压缩留下的首问上() {
        // 老压缩产物的真实形状：首条用户消息被原样放回队首（它也带轮起始标记），中间是 SystemMessage 摘要。
        // 只认标记的话会命中 index 0，把中间好几轮连同摘要一起抹掉，而且悄无声息
        Harness h = harness(
                List.of(msg(1, "user", "BTC 怎么样"), msg(2, "assistant", "旧答案")),
                List.of(turnStart("很久以前的第一个问题"),
                        new SystemMessage("【历史摘要】前面聊过行情与新闻"),
                        new AssistantMessage("旧答案")));

        assertRejected(h);
    }

    @Test
    void 末尾是没答过的提问时拒绝() {
        // 上一轮摔了没落 assistant 行，末尾就是一条没答案的提问。这里两轮问的是同一句，
        // 好让"提问原文核对"那一关也能过——否则 role 判据形同虚设也看不出来
        Harness h = harness(
                List.of(msg(1, "user", "BTC 怎么样"), msg(2, "assistant", "旧答案"), msg(3, "user", "BTC 怎么样")),
                List.of(turnStart("BTC 怎么样"), new AssistantMessage("旧答案"), turnStart("BTC 怎么样")));

        assertRejected(h);
    }

    @Test
    void 补答行不给重新生成() {
        // 补答行对应的提问不在会话末尾，中间夹着别的问答，回退会误伤那些轮次
        Harness h = harness(
                List.of(msg(1, "user", "BTC 怎么样"), msg(2, "user", "ETH 呢"), msg(3, "assistant", "ETH 的答案"),
                        msg(4, "assistant", ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.deferred.prefix")
                                + "BTC 怎么样」】\n\n补上的答案")),
                List.of(turnStart("ETH 呢"), new AssistantMessage("ETH 的答案")));

        assertRejected(h);
    }

    @Test
    void 英文补答行同样不给重新生成() {
        // 补答标头跟着用户语言走。只认中文那一份的话，英文用户的补答行会冒出重新生成按钮
        Harness h = harness(
                List.of(msg(1, "user", "how is BTC"), msg(2, "user", "and ETH"), msg(3, "assistant", "ETH answer"),
                        msg(4, "assistant", ChatTestEndpoints.PROMPTS.get(AgentLang.EN, "chat.deferred.prefix")
                                + "how is BTC\"]\n\nthe deferred answer")),
                List.of(turnStart("and ETH"), new AssistantMessage("ETH answer")));

        assertRejected(h);
    }

    @Test
    void 别人的会话号一律拒绝() {
        Harness h = harness(List.of(), List.of());

        assertThatThrownBy(() -> regenerate(h, "wb-2-someone-else"))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.CHAT_REGENERATE_UNAVAILABLE.getCode());
        // 归属校验在拿名额之前，闸门根本没被碰过
        assertThat(h.gate().tryAcquire(1L, ChatConcurrencyGate.Owner.WORKBENCH)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
    }
}
