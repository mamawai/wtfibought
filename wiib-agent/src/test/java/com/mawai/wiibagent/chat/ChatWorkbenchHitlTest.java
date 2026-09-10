package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibagent.llm.SseChannel;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.behavior.BehaviorAnalysisService;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.toolkit.NewsToolkit;
import com.mawai.wiibagent.trader.TraderChatService;
import com.mawai.wiibagent.mapper.WorkbenchChatContextMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HITL 整条链路在 Controller 这一头的四件事：确认卡怎么发、点回来怎么比对、拒绝之后怎么办，
 * 以及确认后的续跑轮还看不看得见上一轮的上下文。
 * <p>
 * <b>必须真跑 {@link ChatTurnStreamer#run}</b>：确认卡是它在这一轮跑完之后发的，
 * 发不发、发几张全是它自己的判断。测试自己调 registry 拼一遍只能证明 registry 好使，
 * streamer 里写错照样绿。SSE 事件靠一个记账用的 emitter 收下来。
 * <p>
 * 装的是<b>真</b> {@link ChatTurnRunner} + <b>真</b> {@link ChatContextStore}（mapper 换成
 * HashMap 假实现，字节真存真取）：跨轮上下文现在走这条路，mock 掉就等于把要验的东西验没了。
 */
class ChatWorkbenchHitlTest {

    private static final String SESSION = "wb-1-hitl";
    /** 生产口径的调用上限（application.yml 的 agent.workbench.run-model-call-limit） */
    private static final int PRODUCTION_LIMIT = 8;
    /** 阈值给足 = 这几跑都不碰历史压缩，别让它掺进来 */
    private static final int NO_COMPRESSION = 999_999;

    private final ApprovalRegistry registry = new ApprovalRegistry();
    /** 工具真跑起来的第一件事就是问它要新闻上下文——用它区分"闸门拦下了"和"工具跑过了" */
    private final DeepAnalysisService deepAnalysisService = mock(DeepAnalysisService.class);
    private final ChatModel deep = mock(ChatModel.class);
    private final ChatModel light = mock(ChatModel.class);
    /** 每轮 summarizer 的第几次模型调用（每轮开跑前归零） */
    private final AtomicInteger deepCallsThisTurn = new AtomicInteger();
    /** 本轮 summarizer 要不要调深研判工具（false=直接给答案） */
    private final AtomicBoolean wantsDeepAnalysis = new AtomicBoolean(true);
    /** 本轮路由要不要派一次 market 专家（用来给"上一轮的专家数据"造实体） */
    private final AtomicBoolean wantsMarketExpert = new AtomicBoolean(false);
    /** 消息去重用的全局序号，见下面 productionLeaves 的说明 */
    private final AtomicInteger seq = new AtomicInteger();
    /** summarizer 每次收到的 Prompt：跨轮上下文只能从这里看 */
    private final List<Prompt> summarizerPrompts = new CopyOnWriteArrayList<>();

    /** 会话上下文表的假实现：字节真存真取，跨轮上下文这条链才算真的被跑到 */
    private final Map<String, byte[]> contextRows = new HashMap<>();

    /** controller() 里装配：turn() 用 streamer 跑轮、用 yieldCoordinator 造让位句柄 */
    private ChatYieldCoordinator yieldCoordinator;
    private ChatTurnStreamer streamer;

    /** 记账用的 emitter：SseChannel 的每一次 send 都从这里过，事件原文攒起来供断言 */
    private static final class RecordingEmitter extends SseEmitter {
        private final List<String> raw = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            builder.build().forEach(d -> {
                if (d.getData() instanceof String s) raw.add(s);
            });
        }

        /** 事件帧是 "event:名字\n" + "data:JSON\n\n" 分开落的，按名字取紧随其后的那条 data */
        List<JSONObject> events(String name) {
            List<JSONObject> found = new ArrayList<>();
            for (int i = 0; i < raw.size() - 1; i++) {
                if (raw.get(i).contains(name)) {
                    String data = raw.get(i + 1).trim();
                    if (data.startsWith("{")) found.add(JSON.parseObject(data));
                }
            }
            return found;
        }
    }

    private static ChatResponse responseOf(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static AssistantMessage toolCall(String id, String name, String args) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args))).build();
    }

    /** 生产装配的叶子（真 {@link ChatAgentFactory#leavesFor}） */
    private ChatAgentFactory.Leaves productionLeaves() {
        // ChatService 建请求时无条件读 getOptions() 挂工具，null 会 NPE
        when(deep.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(light.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.modelsFor(any())).thenReturn(new ChatModelFactory.Models(deep, light));
        // 浅模型同时服务路由与专家，靠系统提示词首句分辨
        when(light.call(any(Prompt.class))).thenAnswer(inv -> {
            String head = ((Prompt) inv.getArgument(0)).getInstructions().getFirst().getText();
            if (!head.contains("你是研判工作台的调度器")) {
                return responseOf(new AssistantMessage("BTC 现价 97000（第 " + seq.incrementAndGet() + " 条）"));
            }
            // 派过一次之后 fresh 为空，循环自然收口，不必自己数轮次
            return responseOf(toolCall("r", "route",
                    wantsMarketExpert.get() ? "{\"next\":[\"market_agent\"]}" : "{\"next\":[\"FINISH\"]}"));
        });
        // 每轮：先要一次深研判（闸门在这儿拦），拿到回执后再说一句话收尾。
        // 每条消息带序号：几轮回同一句话时，断言按内容分得清是哪一轮的
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> {
            summarizerPrompts.add(inv.getArgument(0));
            return Flux.just(responseOf(
                    wantsDeepAnalysis.get() && deepCallsThisTurn.incrementAndGet() == 1
                            ? toolCall("c" + seq.incrementAndGet(), "run_deep_analysis",
                            "{\"symbol\":\"BTCUSDT\"}")
                            : new AssistantMessage("这是第 " + seq.incrementAndGet() + " 段回答")));
        });

        ChatEndpoints llmConfig = ChatTestEndpoints.eps(1L, "gpt-5");   // 叶子指纹含 userId（trader 工具按它认人）
        return new ChatAgentFactory(chatModelFactory, mock(MarketToolkit.class), mock(NewsToolkit.class),
                deepAnalysisService, mock(BehaviorAnalysisService.class),
                mock(TraderChatService.class), mock(WorkbenchRunRegistry.class),
                registry, ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS,
                PRODUCTION_LIMIT, NO_COMPRESSION, 6, "X")
                .leavesFor(llmConfig, AgentLang.ZH);
    }

    /** 叶子改由 chat() 取好传进 run()，这条测试直接调 run()，所以工厂和配置服务都用不上了 */
    private ChatWorkbenchController controller() {
        WorkbenchChatContextMapper contextMapper = mock(WorkbenchChatContextMapper.class);
        when(contextMapper.selectState(anyString())).thenAnswer(inv -> {
            byte[] row = contextRows.get(inv.getArgument(0));   // 无行=空表，跟 MyBatis selectList 一个语义
            return row == null ? List.of() : List.of(row);
        });
        when(contextMapper.upsert(anyString(), anyLong(), any())).thenAnswer(inv -> {
            contextRows.put(inv.getArgument(0), inv.getArgument(2));
            return 1;
        });
        ChatContextStore contextStore = new ChatContextStore(contextMapper);
        ChatTurnRunner turnRunner = new ChatTurnRunner(contextStore, registry, ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS);
        ChatConcurrencyGate gate = new ChatConcurrencyGate(10);
        WorkbenchRunRegistry runRegistry = mock(WorkbenchRunRegistry.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        yieldCoordinator = new ChatYieldCoordinator();
        streamer = new ChatTurnStreamer(turnRunner, history, runRegistry, yieldCoordinator, registry,
                ChatTestEndpoints.PROMPTS);
        return new ChatWorkbenchController(mock(ChatAgentFactory.class), mock(LlmEndpointService.class),
                registry, history, contextStore, streamer, mock(ChatTurnRewinder.class),
                runRegistry, gate, new MessageCatalog(), yieldCoordinator, ChatTestEndpoints.PROMPTS, ChatTestEndpoints.zhLang());
    }

    /** 跑一轮，返回这一轮发出去的全部 SSE 事件 */
    private RecordingEmitter turn(ChatAgentFactory.Leaves leaves, String message) {
        deepCallsThisTurn.set(0);
        RecordingEmitter emitter = new RecordingEmitter();
        streamer.run(new SseChannel(emitter), 1L, SESSION, message, leaves,
                yieldCoordinator.openTurn(1L), null, null, null);
        return emitter;
    }

    private static ChatWorkbenchController.ApprovalRequest decision(String requestId, boolean approved) {
        ChatWorkbenchController.ApprovalRequest request = new ChatWorkbenchController.ApprovalRequest();
        request.setSessionId(SESSION);
        request.setApproved(approved);
        request.setRequestId(requestId);
        return request;
    }

    /**
     * 卡片带 requestId，且点回来必须对得上号。
     * <p>
     * 对不上 = 用户点的是被新请求覆盖掉的旧卡片：照批的话，他看着"深研判 BTC"点的同意
     * 会授权给新请求里的另一个标的。
     */
    @Test
    void 确认标识对不上时拒绝授权() {
        ChatAgentFactory.Leaves leaves = productionLeaves();
        ChatWorkbenchController controller = controller();

        JSONObject card = turn(leaves, "深度研判 BTC").events("hitl_request").getFirst();
        assertThat(card.getString("requestId")).isNotBlank();

        Result<Void> stale = controller.approve(1L, decision("别的卡片的标识", true));

        assertThat(stale.getMsg()).contains("已失效");
        assertThat(registry.hasApproval(SESSION)).isFalse();   // 一分授权都没给出去

        // 原样回传那张卡的标识才算数
        Result<Void> ok = controller.approve(1L, decision(card.getString("requestId"), true));

        assertThat(ok.getCode()).isZero();
        assertThat(registry.hasApproval(SESSION)).isTrue();
    }

    /**
     * 用户不点卡、直接接着问下一句：不能再弹一遍同一张。
     * <p>
     * pending 是 approve/reject 才摘的，所以它会一直躺在服务端；run() 结尾若无脑 peek，
     * 之后每一轮结束都会重发同一张卡（同一个 requestId），用户界面上越堆越多。
     */
    @Test
    void 用户没点的确认卡不会在下一轮重复弹() {
        ChatAgentFactory.Leaves leaves = productionLeaves();
        controller();

        assertThat(turn(leaves, "深度研判 BTC").events("hitl_request")).hasSize(1);

        // 第二轮模型不再调深研判（用户问的是别的），但上一张卡还挂在 registry 里
        wantsDeepAnalysis.set(false);
        RecordingEmitter second = turn(leaves, "顺便说说最近行情");

        assertThat(second.events("hitl_request")).isEmpty();
        assertThat(registry.peekPending(SESSION)).isPresent(); // 卡还在，只是不再重发
    }

    /**
     * 拒绝之后模型又想调同一件事：要拿到"用户已拒绝"的回执，而不是又弹一张卡。
     * <p>
     * 这条同时钉住拒绝标记的<b>作用域</b>：它只有跨轮活着才可能被闸门看到——
     * 卡片是在一轮结束时才发出去的，用户点拒绝必然发生在两轮之间。
     * 谁在每轮开跑时把它清掉（"只对本轮有效"），这条立刻变成"又弹了一张新卡"。
     */
    @Test
    void 拒绝之后下一轮拿到拒绝回执而不是新卡() {
        ChatAgentFactory.Leaves leaves = productionLeaves();
        ChatWorkbenchController controller = controller();

        JSONObject card = turn(leaves, "深度研判 BTC").events("hitl_request").getFirst();
        assertThat(controller.approve(1L, decision(card.getString("requestId"), false)).getCode()).isZero();

        RecordingEmitter second = turn(leaves, "再研判一次 BTC");

        assertThat(second.events("hitl_request")).isEmpty();
        assertThat(registry.peekPending(SESSION)).isEmpty();   // 没有登记新的待确认
        // 三条一起才钉得住"走的是拒绝分支"：没卡 + 没登记 + 工具也没跑
        //（只断前两条的话，"闸门放行、工具真跑了 3 次深模型"也满足）
        verify(deepAnalysisService, never()).buildNewsContext(any());
    }

    /**
     * <b>验收硬条件</b>：续聊上下文来自会话上下文表而不是 checkpoint。
     * <p>
     * 第一轮派一次 market 专家，让"上一轮的数据"有实体；approve 之后跑第二轮，
     * 断言 summarizer 这一轮收到的 Prompt 里既有上一轮的专家结论、也有上一轮的答案。
     * 落库或读取任一头断了这条就红——而用户遭的罪是"AI 完全不记得刚才说过什么"。
     */
    @Test
    void 确认后的续跑轮仍看得见上一轮的上下文() {
        wantsMarketExpert.set(true);
        ChatAgentFactory.Leaves leaves = productionLeaves();
        ChatWorkbenchController controller = controller();

        RecordingEmitter first = turn(leaves, "先看行情，再深度研判 BTC");
        JSONObject card = first.events("hitl_request").getFirst();
        String expertConclusion = first.events("token").stream()
                .filter(e -> "process".equals(e.getString("role")))
                .map(e -> e.getString("text")).findFirst().orElseThrow();
        String firstAnswer = first.events("done").getFirst().getString("answer");
        assertThat(controller.approve(1L, decision(card.getString("requestId"), true)).getCode()).isZero();

        // 授权还在 → 第二轮直通汇总，专家不再派；上下文只能从存储来
        wantsMarketExpert.set(false);
        summarizerPrompts.clear();
        turn(leaves, "已确认，请继续执行深度研判");

        String secondTurnInput = summarizerPrompts.getFirst().getInstructions().stream()
                .map(m -> m.getText() == null ? "" : m.getText())
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(secondTurnInput).contains(expertConclusion);
        assertThat(secondTurnInput).contains(firstAnswer);
        assertThat(secondTurnInput).contains("先看行情，再深度研判 BTC");
    }
}
