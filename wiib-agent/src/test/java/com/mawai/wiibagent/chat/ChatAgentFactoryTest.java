package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.llm.SseChatModel;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.behavior.BehaviorAnalysisService;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.run.WorkerKind;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.toolkit.IndicatorToolkit;
import com.mawai.wiibagent.trader.TraderChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 叶子工厂本身：主 agent 的工具表齐不齐、缓存有没有生效、提示词与搜索许可跟不跟端点能力走。
 * 编排（一轮怎么跑、落库）在 {@link ChatTurnRunnerTest}，不在这里。
 */
class ChatAgentFactoryTest {

    private final ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);

    private ChatAgentFactory factory() {
        return factory(ChatTestEndpoints.model());
    }

    private ChatAgentFactory factory(ChatModel model) {
        when(chatModelFactory.modelsFor(any())).thenReturn(new ChatModelFactory.Models(model, model));
        return new ChatAgentFactory(chatModelFactory,
                mock(MarketToolkit.class), mock(IndicatorToolkit.class), ChatTestEndpoints.newsToolkit(),
                mock(DeepAnalysisService.class), mock(BehaviorAnalysisService.class), mock(TraderChatService.class),
                mock(WorkbenchRunRegistry.class), ChatTestEndpoints.childRuns(mock(ChatContextStore.class)),
                new ApprovalRegistry(), ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS,
                12, "X");
    }

    /** worker 真跑一次（阻塞调用），抓它挂给模型的那个 Prompt */
    private static Prompt workerPrompt(ChatModel model, ChatAgentFactory.Worker worker) {
        List<Prompt> prompts = new ArrayList<>();
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            prompts.add(inv.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("报告"))));
        });
        worker.loop().run(List.of(new UserMessage("任务书")), "wb-w", null, null);
        return prompts.getFirst();
    }

    private static List<String> toolNames(Prompt prompt) {
        return ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks().stream()
                .map(ToolCallback::getToolDefinition).map(d -> d.name()).toList();
    }

    /** 这次调用捎没捎服务端搜索许可 */
    private static boolean carriesSearch(Prompt p) {
        return p.getOptions() instanceof ToolCallingChatOptions t && t.getToolContext() != null
                && Boolean.TRUE.equals(t.getToolContext().get(SseChatModel.WEB_SEARCH_KEY));
    }

    private static ChatEndpoints config(String model) {
        return ChatTestEndpoints.eps(1L, model);
    }

    /** 声明了原生服务端搜索的端点 */
    private static UserLlmEndpoint searchEndpoint() {
        UserLlmEndpoint e = ChatTestEndpoints.endpoint("grok-4.6");
        e.setApiProtocol("responses");
        e.setWebSearch(true);
        return e;
    }

    /** 主 agent 真跑一次，把它挂给模型的工具表抓出来 */
    private static List<String> toolNamesOf(ChatModel model, ChatAgentFactory.Leaves leaves) {
        List<Prompt> prompts = new ArrayList<>();
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
            prompts.add(inv.getArgument(0));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("答")))));
        });
        leaves.main().run(List.of(new UserMessage("你好")), "wb-tools", null, null);
        return ((ToolCallingChatOptions) prompts.getFirst().getOptions()).getToolCallbacks().stream()
                .map(ToolCallback::getToolDefinition).map(d -> d.name()).toList();
    }

    /** 行情 ×4 / K线与指标 ×3 / 新闻 / 深研判 / trader 只读 ×5 / trader 动作 ×3 / 行为分析 / 派子 agent，全在主 agent 一张表上 */
    @Test
    void 主agent工具表齐全() {
        ChatModel model = ChatTestEndpoints.model();

        ChatAgentFactory.Leaves leaves = factory(model).leavesFor(config("gpt-5"), AgentLang.ZH);

        assertThat(leaves.main()).isNotNull();
        assertThat(toolNamesOf(model, leaves)).containsExactlyInAnyOrder(
                "market_snapshot", "option_iv", "funding_history", "orderbook_depth",
                "klines", "indicators", "kline_structure",
                "news_search",
                "run_deep_analysis",
                "trader_overview", "trader_positions", "trader_decisions", "trader_plans", "trader_trades",
                "wake_trader", "review_trader_now", "leave_note_to_trader",
                "analyze_my_behavior",
                "delegate");
    }

    /** 整理上下文走的 client 与主 agent 同一份系统提示词和工具：请求前缀与平时一样才命中提示缓存 */
    @Test
    void 整理用的client与主agent请求前缀一致() {
        ChatModel model = ChatTestEndpoints.model();
        List<Prompt> prompts = new ArrayList<>();
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
            prompts.add(inv.getArgument(0));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("答")))));
        });
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            prompts.add(inv.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("摘要"))));
        });
        ChatAgentFactory.Leaves leaves = factory(model).leavesFor(config("gpt-5"), AgentLang.ZH);

        leaves.main().run(List.of(new UserMessage("你好")), "wb-prefix", null, null);
        leaves.mainClient().execute(List.of(new UserMessage("你好")));

        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(1).getInstructions().getFirst().getText())
                .isEqualTo(prompts.get(0).getInstructions().getFirst().getText());
        assertThat(toolNames(prompts.get(1))).isEqualTo(toolNames(prompts.get(0)));
        assertThat(leaves.deep().snapshot().modelCalls()).as("整理那次也记在本轮账上").isEqualTo(2);
    }

    /**
     * worker 预设各管各的只读工具，都没有 delegate、动作表单与深研判（深度一层、不许动手）；
     * 搜索许可只给 researcher，且随浅模型端点走
     */
    @Test
    void worker预设各自的工具表与搜索许可() {
        ChatModel model = ChatTestEndpoints.model();
        ChatAgentFactory factory = factory(model);

        Prompt researcher = workerPrompt(model, factory.newWorker(WorkerKind.RESEARCHER, AgentLang.ZH, searchEndpoint(), model));
        Prompt analyst = workerPrompt(model, factory.newWorker(WorkerKind.ANALYST, AgentLang.ZH, searchEndpoint(), model));

        assertThat(toolNames(researcher)).containsExactly("news_search");
        assertThat(carriesSearch(researcher)).isTrue();
        assertThat(toolNames(analyst)).containsExactlyInAnyOrder(
                "market_snapshot", "option_iv", "funding_history", "orderbook_depth",
                "klines", "indicators", "kline_structure");
        assertThat(carriesSearch(analyst)).isFalse();
        // 系统提示词是预设自己的那份
        assertThat(researcher.getInstructions().getFirst().getText()).contains("调研员");
        assertThat(analyst.getInstructions().getFirst().getText()).contains("行情分析员");
    }

    /**
     * 同一份配置反复取是同一套叶子（一个用户一份——userId 是指纹的第一个分量）；
     * 配置一变指纹就变、自然拿到新叶子——不需要任何显式 evict，也就不会有"改了配置还用旧模型"。
     */
    @Test
    void 同配置共享叶子改配置后重建() {
        ChatAgentFactory factory = factory();

        ChatAgentFactory.Leaves first = factory.leavesFor(config("gpt-5"), AgentLang.ZH);

        assertThat(factory.leavesFor(config("gpt-5"), AgentLang.ZH)).isSameAs(first);
        assertThat(factory.leavesFor(config("gpt-5.1"), AgentLang.ZH)).isNotSameAs(first);
        // 两条 verify 各管一件事，都是 isSameAs 抓不到的：
        // 1) 每份配置只建一次。把"先查缓存"那步删掉，第二次照样重建一整套、再被 putIfAbsent
        //    换回旧的——断言全绿而每轮对话都在白建（实测过）
        // 2) 取模型时用的就是调用方给的这份配置，而不是别处随便来的一份
        verify(chatModelFactory).modelsFor(config("gpt-5"));
        verify(chatModelFactory).modelsFor(config("gpt-5.1"));
    }

    /**
     * 语言在叶子缓存键里：主 agent 的系统提示词、工具描述都是建叶子那一刻按语言烤死的，语言变则叶子须重建。
     */
    @Test
    void 同一用户切语言拿到不同叶子() {
        ChatAgentFactory factory = factory();

        ChatAgentFactory.Leaves zh = factory.leavesFor(config("gpt-5"), AgentLang.ZH);
        ChatAgentFactory.Leaves en = factory.leavesFor(config("gpt-5"), AgentLang.EN);

        assertThat(ChatAgentFactory.leafKey(config("gpt-5"), AgentLang.EN))
                .as("语言不进键 = 切了语言还拿旧叶子")
                .isNotEqualTo(ChatAgentFactory.leafKey(config("gpt-5"), AgentLang.ZH));
        assertThat(en).isNotSameAs(zh);
        assertThat(en.lang()).isEqualTo(AgentLang.EN);
        assertThat(zh.lang()).isEqualTo(AgentLang.ZH);
        // 切回去要拿回原来那套，不是再建第三份
        assertThat(factory.leavesFor(config("gpt-5"), AgentLang.ZH)).isSameAs(zh);
        // 语言只加在叶子这一层：模型指纹本身不含语言，切语言不会连 SDK 客户端和连接池一起重建
        assertThat(ChatAgentFactory.leafKey(config("gpt-5"), AgentLang.EN))
                .startsWith(ChatModelFactory.fingerprint(config("gpt-5")));
    }

    /** 换了主模型端点（换模型、key 换到别的组织）：前缀指纹跟着变，旧端点留下的加密推理不再原样回放 */
    @Test
    void 换主模型端点前缀指纹跟着变() {
        ChatAgentFactory factory = factory();
        String base = factory.leavesFor(config("gpt-5"), AgentLang.ZH).mainClient().fingerprint();

        assertThat(factory.leavesFor(config("gpt-5.1"), AgentLang.ZH).mainClient().fingerprint()).isNotEqualTo(base);
        assertThat(factory.leavesFor(new ChatEndpoints(1L, ChatTestEndpoints.endpoint("gpt-5", "另一把key"), null),
                AgentLang.ZH).mainClient().fingerprint()).isNotEqualTo(base);
    }

    /** 上下文窗口烤在叶子上，改了要拿新叶子；它同样不进模型指纹，改窗口不重建 SDK 客户端 */
    @Test
    void 改上下文窗口拿到新叶子() {
        ChatEndpoints small = config("gpt-5");
        small.deep().setContextWindow(128_000);

        assertThat(ChatAgentFactory.leafKey(small, AgentLang.ZH))
                .isNotEqualTo(ChatAgentFactory.leafKey(config("gpt-5"), AgentLang.ZH));
        assertThat(ChatModelFactory.fingerprint(small)).isEqualTo(ChatModelFactory.fingerprint(config("gpt-5")));
        assertThat(factory().leavesFor(small, AgentLang.ZH).contextWindow()).isEqualTo(128_000);
    }

    // ===== 服务端搜索：提示词跟着端点能力走，许可随每次调用捎上 =====

    /** 提示词不许承诺端点给不了的能力——搜索静默失效一个月才被发现，病根之一就是文案与能力脱节 */
    @Test
    void 主agent提示词按端点搜索能力拼装() {
        ChatAgentFactory factory = factory();

        String withSearch = factory.mainInstruction(AgentLang.ZH, true);
        String withoutSearch = factory.mainInstruction(AgentLang.ZH, false);

        assertThat(withSearch).contains("用你的联网搜索").contains("[X]");
        assertThat(withoutSearch).doesNotContain("用你的联网搜索");
        assertThat(withoutSearch).contains("没有联网检索能力");
        // 其余原则两版共有：拼装只换新闻那一条，别的不许跟着丢
        assertThat(withSearch).contains("run_deep_analysis").contains("news_search");
        assertThat(withoutSearch).contains("run_deep_analysis").contains("news_search");
    }

    /**
     * 许可的投放面：端点声明了搜索时，主 agent 的每次模型调用都捎
     * {@link com.mawai.wiibagent.llm.SseChatModel#WEB_SEARCH_KEY}——双闸门里"调用方授权"这一半就是这里发的。
     */
    @Test
    void 端点声明搜索时主agent的调用捎许可() {
        ChatModel model = ChatTestEndpoints.model();
        List<Prompt> prompts = new ArrayList<>();
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
            prompts.add(inv.getArgument(0));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("答")))));
        });
        ChatAgentFactory.Leaves leaves = factory(model).leavesFor(new ChatEndpoints(1L, searchEndpoint(), null), AgentLang.ZH);
        leaves.main().run(List.of(new UserMessage("过去24小时BTC新闻")), "wb-ws", null, null);

        assertThat(prompts).isNotEmpty();
        assertThat(prompts).allMatch(ChatAgentFactoryTest::carriesSearch);
    }

    /**
     * Antigravity 来源不收服务端搜索和函数工具同一请求：主 agent 与 researcher 改挂本地工具 web_search、不捎原生许可，
     * 主 agent 照样用承诺联网的那版提示词
     */
    @Test
    void Antigravity来源的搜索包成web_search工具() {
        ChatModel model = ChatTestEndpoints.model();
        List<Prompt> prompts = new ArrayList<>();
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
            prompts.add(inv.getArgument(0));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("答")))));
        });
        ChatAgentFactory factory = factory(model);
        UserLlmEndpoint e = ChatTestEndpoints.antigravity("gemini-4-argon-high");

        factory.leavesFor(new ChatEndpoints(1L, e, null), AgentLang.ZH).main()
                .run(List.of(new UserMessage("过去24小时BTC新闻")), "wb-ag", null, null);
        Prompt researcher = workerPrompt(model, factory.newWorker(WorkerKind.RESEARCHER, AgentLang.ZH, e, model));

        assertThat(toolNames(prompts.getFirst())).contains("web_search", "news_search");
        assertThat(carriesSearch(prompts.getFirst())).isFalse();
        assertThat(prompts.getFirst().getInstructions().getFirst().getText()).contains("用你的联网搜索");
        assertThat(toolNames(researcher)).containsExactly("news_search", "web_search");
        assertThat(carriesSearch(researcher)).isFalse();
    }

    /** Antigravity 来源决定工具表，烤在叶子上：切了要拿新叶子；它不影响模型实例，不进模型指纹 */
    @Test
    void 切Antigravity来源拿到新叶子() {
        ChatEndpoints antigravity = config("gpt-5");
        antigravity.deep().setAntigravity(true);

        assertThat(ChatAgentFactory.leafKey(antigravity, AgentLang.ZH))
                .isNotEqualTo(ChatAgentFactory.leafKey(config("gpt-5"), AgentLang.ZH));
        assertThat(ChatModelFactory.fingerprint(antigravity)).isEqualTo(ChatModelFactory.fingerprint(config("gpt-5")));
    }

    /** 模型调 web_search：用主 agent 自己的模型单独发一次，搜的就是模型给的 query，这次也记在本轮账上 */
    @Test
    void web_search单独发一次搜索且记在本轮账上() {
        ChatModel model = ChatTestEndpoints.model();
        AtomicInteger streams = new AtomicInteger();
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> Flux.just(streams.getAndIncrement() == 0
                ? new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("c1", "function", "web_search", "{\"query\":\"BTC ETF\"}"))).build())))
                : new ChatResponse(List.of(new Generation(new AssistantMessage("答"))))));
        List<Prompt> searches = new ArrayList<>();
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            searches.add(inv.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("据报道"))));
        });
        ChatAgentFactory.Leaves leaves = factory(model).leavesFor(
                new ChatEndpoints(1L, ChatTestEndpoints.antigravity("gemini-4-argon-high"), null), AgentLang.ZH);

        leaves.main().run(List.of(new UserMessage("BTC ETF 最近怎么样")), "wb-ag", null, null);

        assertThat(searches).hasSize(1);
        assertThat(searches.getFirst().getInstructions().getLast().getText()).isEqualTo("BTC ETF");
        assertThat(leaves.deep().snapshot().modelCalls()).isEqualTo(3);
    }
}
