package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.llm.SseChatModel;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.behavior.BehaviorAnalysisService;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.toolkit.NewsToolkit;
import com.mawai.wiibagent.trader.TraderChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 叶子工厂本身：建出来的东西齐不齐、缓存有没有生效、搜索许可投放面对不对。
 * 编排（派发/去重/汇总/落库）在 {@link ChatTurnRunnerTest}，不在这里。
 */
class ChatAgentFactoryTest {

    private final ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);

    private ChatAgentFactory factory() {
        ChatModel model = mock(ChatModel.class);
        // 建叶子时 ChatService 会读 getOptions() 挂工具，null 会 NPE
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        return factory(model);
    }

    private ChatAgentFactory factory(ChatModel model) {
        when(chatModelFactory.modelsFor(any())).thenReturn(new ChatModelFactory.Models(model, model));
        return new ChatAgentFactory(chatModelFactory,
                mock(MarketToolkit.class), mock(NewsToolkit.class),
                mock(DeepAnalysisService.class), mock(BehaviorAnalysisService.class), mock(TraderChatService.class),
                mock(WorkbenchRunRegistry.class),
                new ApprovalRegistry(), ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS,
                12, 32000, 6, "X");
    }

    private static ChatEndpoints config(String model) {
        return ChatTestEndpoints.eps(1L, model);
    }

    @Test
    void 建出三个专家和一个汇总叶子() {
        ChatAgentFactory.Leaves leaves = factory().leavesFor(config("gpt-5"), AgentLang.ZH);

        // 保序：派发顺序、结论进历史的顺序都跟着它
        assertThat(leaves.experts()).containsOnlyKeys("market_agent", "news_agent", "trader_agent");
        assertThat(leaves.experts().keySet())
                .containsExactly("market_agent", "news_agent", "trader_agent");
        // news 走预取（无参工具，不指望模型自己调）；market 的工具要按问题选 symbol 只能现取，
        // trader 的四个工具各答一类问题，取哪个也得看问题
        assertThat(leaves.experts().get("news_agent").preload()).isNotNull();
        assertThat(leaves.experts().get("market_agent").preload()).isNull();
        assertThat(leaves.experts().get("trader_agent").preload()).isNull();
        assertThat(leaves.summarizer()).isNotNull();
        assertThat(leaves.light()).isNotNull();
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
     * 语言在叶子缓存键里：三个专家与 summarizer 的系统提示词、工具描述都是建叶子那一刻按语言
     * 烤死写进图里的，语言变则叶子须重建。
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

    // ===== 服务端搜索：提示词跟着端点能力走，许可只给 summarizer =====

    /** 提示词不许承诺端点给不了的能力——搜索静默失效一个月才被发现，病根之一就是文案与能力脱节 */
    @Test
    void summarizer提示词按端点搜索能力拼装() {
        ChatAgentFactory factory = factory();

        String withSearch = factory.summarizerInstruction(AgentLang.ZH, true);
        String withoutSearch = factory.summarizerInstruction(AgentLang.ZH, false);

        assertThat(withSearch).contains("用你的联网搜索").contains("[X]");
        assertThat(withoutSearch).doesNotContain("用你的联网搜索");
        assertThat(withoutSearch).contains("没有联网检索能力");
        // 其余原则两版共有：拼装只换新闻那一条，别的不许跟着丢
        assertThat(withSearch).contains("run_deep_analysis");
        assertThat(withoutSearch).contains("run_deep_analysis");
    }

    /**
     * 许可的投放面：端点声明了搜索时，summarizer 的每次模型调用都捎
     * {@link com.mawai.wiibagent.llm.SseChatModel#WEB_SEARCH_KEY}，
     * 专家（数据源必须可控）一个都不捎——双闸门里"调用方授权"这一半就是这里发的。
     */
    @Test
    void 端点声明搜索时只有summarizer的调用捎许可() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        List<Prompt> summarizerPrompts = new ArrayList<>();
        List<Prompt> expertPrompts = new ArrayList<>();
        // summarizer 是流式（stream），专家是阻塞（call）：同一个 mock 模型按调用方式分桶
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
            summarizerPrompts.add(inv.getArgument(0));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("答")))));
        });
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            expertPrompts.add(inv.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("专家答"))));
        });
        UserLlmEndpoint e = ChatTestEndpoints.endpoint("grok-4.6");
        e.setApiProtocol("responses");
        e.setWebSearch(true);

        ChatAgentFactory.Leaves leaves = factory(model).leavesFor(new ChatEndpoints(1L, e, null), AgentLang.ZH);
        leaves.summarizer().run(List.of(new UserMessage("过去24小时BTC新闻")), "wb-ws", null, null);
        leaves.experts().get(ChatAgentFactory.MARKET_AGENT).loop()
                .run(List.of(new UserMessage("BTC行情")), null, null, null);

        assertThat(summarizerPrompts).isNotEmpty();
        assertThat(summarizerPrompts).allMatch(p ->
                p.getOptions() instanceof ToolCallingChatOptions t && t.getToolContext() != null
                        && Boolean.TRUE.equals(t.getToolContext().get(SseChatModel.WEB_SEARCH_KEY)));
        assertThat(expertPrompts).isNotEmpty();
        assertThat(expertPrompts).allMatch(p ->
                !(p.getOptions() instanceof ToolCallingChatOptions t) || t.getToolContext() == null
                        || t.getToolContext().get(SseChatModel.WEB_SEARCH_KEY) == null);
    }
}
