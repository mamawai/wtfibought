package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.run.ChildRunRegistry;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.llm.SearchEvent;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.behavior.BehaviorAnalysisService;
import com.mawai.wiibagent.toolkit.IndicatorToolkit;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.toolkit.NewsToolkit;
import com.mawai.wiibagent.trader.TraderChatService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 一轮对话的编排。跑的是<b>生产叶子</b>（真 {@link ChatAgentFactory} + 打桩的 ChatModel），
 * 不是手搭的假 agent——要钉的正是"runner 与真叶子接在一起"这件事。
 */
class ChatTurnRunnerTest {

    private static final String SESSION = "wb-1-runner";
    /** 阈值给足 = 这几跑都不碰历史压缩，那件事归 {@link SummarizerLeafTest} */
    private static final int NO_COMPRESSION = 999_999;
    /** 生产口径的调用上限 */
    private static final int LIMIT = 8;

    private final ChatModel deep = mock(ChatModel.class);
    private final ChatModel light = mock(ChatModel.class);
    private final ApprovalRegistry registry = new ApprovalRegistry();
    private final ChatContextStore contextStore = mock(ChatContextStore.class);
    private final ChildRunRegistry childRuns = ChatTestEndpoints.childRuns(contextStore);
    private final NewsToolkit newsToolkit = ChatTestEndpoints.newsToolkit();
    private final TraderChatService traderChatService = mock(TraderChatService.class);

    /** 主 agent 每次收到的 Prompt：上下文到底喂进去了什么，只有它说得清 */
    private final List<Prompt> mainPrompts = new CopyOnWriteArrayList<>();
    private final StringBuilder answer = new StringBuilder();
    /** 主 agent 报的搜索过程 */
    private final List<SearchEvent> search = new CopyOnWriteArrayList<>();

    private static ChatResponse responseOf(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static AssistantMessage toolCall(String name, String args) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", name, args))).build();
    }

    /** 与 SseChatModel.searchFrame 同形：空文本帧 + 响应 metadata 上挂事件 JSON */
    private static ChatResponse searchFrame(SearchEvent event) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(""))),
                ChatResponseMetadata.builder().keyValue(SearchEvent.KEY, event.toJson()).build());
    }

    /** 主 agent 一句话收尾，顺手记下它收到的 Prompt */
    private void mainAnswers(String text) {
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> {
            mainPrompts.add(inv.getArgument(0));
            return Flux.just(responseOf(new AssistantMessage(text)));
        });
    }

    /** 主 agent 先调一个工具再收尾：真走一遍 ReAct 的工具边，否则验不到工具挂没挂上 */
    private void mainCallsThenAnswers(String tool, String args, String text) {
        AtomicInteger calls = new AtomicInteger();
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> {
            mainPrompts.add(inv.getArgument(0));
            return Flux.just(calls.getAndIncrement() == 0
                    ? responseOf(toolCall(tool, args))
                    : responseOf(new AssistantMessage(text)));
        });
    }

    private ChatAgentFactory.Leaves leaves() {
        // ChatService 建请求时无条件读 getOptions() 挂工具，null 会 NPE
        when(deep.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(light.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.modelsFor(any())).thenReturn(new ChatModelFactory.Models(deep, light));
        ChatEndpoints llmConfig = ChatTestEndpoints.eps(1L, "gpt-5");   // 叶子指纹含 userId（trader 工具按它认人）
        return new ChatAgentFactory(chatModelFactory, mock(MarketToolkit.class), mock(IndicatorToolkit.class), newsToolkit,
                mock(DeepAnalysisService.class), mock(BehaviorAnalysisService.class), traderChatService,
                mock(WorkbenchRunRegistry.class), childRuns,
                registry, ChatTestEndpoints.PROMPTS, ChatTestEndpoints.TOOLS,
                LIMIT, NO_COMPRESSION, 6, "X")
                .leavesFor(llmConfig, AgentLang.ZH);
    }

    private ChatTurnRunner runner() {
        return new ChatTurnRunner(contextStore, childRuns, ChatTestEndpoints.PROMPTS);
    }

    private void turn(String message) {
        turn(message, null);
    }

    private void turn(String message, ChatIntent intent) {
        runner().run(leaves(), 1L, SESSION, message, intent, answer::append, search::add, null);
    }

    private static String textOf(Prompt prompt) {
        return prompt.getInstructions().stream()
                .map(m -> m.getText() == null ? "" : m.getText())
                .collect(Collectors.joining("\n"));
    }

    private String mainInput() {
        return textOf(mainPrompts.getLast());
    }

    /** 主 agent 真正收到的最后一条消息（形态钉子看的就是它） */
    private Message mainLastInput() {
        return mainPrompts.getLast().getInstructions().getLast();
    }

    /** 落库终态拼成一段文本比对 */
    private static String savedText(ArgumentCaptor<List<Message>> saved) {
        return saved.getValue().stream()
                .map(m -> m.getText() == null ? "" : m.getText())
                .collect(Collectors.joining("\n"));
    }

    private List<String> toolResponses() {
        return mainPrompts.getLast().getInstructions().stream()
                .filter(m -> m instanceof ToolResponseMessage)
                .map(m -> ((ToolResponseMessage) m).getResponses().getFirst().responseData())
                .toList();
    }

    /**
     * 这条同时钉住两件容易各自坏掉的事：生产叶子的主 agent 上真挂着 {@link com.mawai.wiibagent.chat.tools.TraderQueryToolkit}、
     * 以及 <b>userId 是建叶子时烤死的</b>——工具签名里没有用户参数，模型没有任何办法去读别人的 trader。
     */
    @Test
    void 主agent直接调trader只读工具且只读自己那份() {
        String overview = "{\"hasTrader\":true,\"status\":\"RUNNING\"}";
        when(traderChatService.overview(1L, AgentLang.ZH)).thenReturn(overview);
        mainCallsThenAnswers("trader_overview", "{}", "你的 trader 正在运行");

        turn("我的 trader 怎么样");

        verify(traderChatService).overview(1L, AgentLang.ZH);
        // 工具回执进了主 agent 的上下文，第二次调用才写得出答案
        assertThat(toolResponses()).containsExactly(overview);
        assertThat(answer.toString()).isEqualTo("你的 trader 正在运行");
    }

    /** 新闻是主 agent 的工具不是预取：语言烤在叶子里，模型没法挑新闻用哪门语言 */
    @Test
    void news工具挂在主agent上且按叶子语言取快讯() {
        when(newsToolkit.newsSearch(AgentLang.ZH)).thenReturn("BlockBeats 快讯原文若干");
        mainCallsThenAnswers("news_search", "{}", "这是答案");

        turn("最近有什么新闻");

        verify(newsToolkit).newsSearch(AgentLang.ZH);
        assertThat(toolResponses()).singleElement().asString().contains("BlockBeats 快讯原文若干");
        assertThat(answer.toString()).isEqualTo("这是答案");
    }

    /** 模型层把搜索过程挂在帧 metadata 上（空文本帧），runner 逐帧取出交给 searchSink，正文不受影响 */
    @Test
    void 帧metadata上的搜索事件交给searchSink() {
        when(deep.stream(any(Prompt.class))).thenReturn(Flux.just(
                searchFrame(SearchEvent.searching("BTC news")),
                searchFrame(SearchEvent.searched("BTC news", List.of(new SearchEvent.Source("https://a.com/1", "A1")))),
                responseOf(new AssistantMessage("这是答案"))));

        turn("BTC 新闻");

        assertThat(search).extracting(SearchEvent::phase)
                .containsExactly(SearchEvent.SEARCHING, SearchEvent.SEARCHED);
        assertThat(search.get(1).sources()).extracting(SearchEvent.Source::url).containsExactly("https://a.com/1");
        assertThat(answer.toString()).isEqualTo("这是答案");
    }

    /** 按钮直发的一轮：收尾指令里钉死"本轮必须调这个工具"，输出语言照样是最后一句 */
    @Test
    void 按钮意图轮垫动作指令() {
        mainAnswers("这是答案");

        turn("分析我的行为", ChatIntent.BEHAVIOR);

        Message last = mainLastInput();
        assertThat(last).isInstanceOf(UserMessage.class);
        assertThat(last.getText()).isEqualTo(
                ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, ChatIntent.BEHAVIOR.promptKey()) + "\n"
                        + ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.outputLanguage"));
    }

    /**
     * 续聊上下文来自 {@link ChatContextStore}：上一轮的对话要喂进这一轮的主 agent，这一轮的产出要落回去。
     * 两头任何一头断了，用户看到的都是"AI 失忆"。
     */
    @Test
    void 上一轮历史进得来本轮产出落得回去() {
        when(contextStore.load(SESSION)).thenReturn(List.of(
                new UserMessage("上轮问题"), new AssistantMessage("上轮回答")));
        mainAnswers("这是本轮答案");

        turn("这一轮问题");

        // 进：上轮内容原样在主 agent 的输入里
        assertThat(mainInput()).contains("上轮问题").contains("上轮回答");
        // 出：终态整体覆盖回存储，含本轮提问与答案
        ArgumentCaptor<List<Message>> saved = ArgumentCaptor.captor();
        verify(contextStore).save(eq(SESSION), eq(1L), saved.capture());
        assertThat(savedText(saved))
                .contains("上轮问题").contains("这一轮问题").contains("这是本轮答案");
    }

    /** 作答摔了也要把提问留住：不落库的话下一轮不知道这句问过 */
    @Test
    void 作答失败时用户消息仍然落库() {
        when(deep.stream(any(Prompt.class))).thenThrow(new RuntimeException("上游挂了"));

        assertThatThrownBy(() -> turn("看看行情")).isInstanceOf(RuntimeException.class);

        ArgumentCaptor<List<Message>> saved = ArgumentCaptor.captor();
        verify(contextStore).save(eq(SESSION), eq(1L), saved.capture());
        assertThat(savedText(saved)).contains("看看行情");
    }

    /** 没按钮意图时最后一条就是输出语言硬收尾，一个字不多 */
    @Test
    void 普通轮只垫输出语言() {
        mainAnswers("这是答案");

        turn("你好呀");

        assertThat(mainLastInput()).isInstanceOf(UserMessage.class);
        assertThat(mainLastInput().getText())
                .isEqualTo(ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.outputLanguage"));
    }

    /**
     * 输出语言硬收尾恒在整轮输入的最后一条，两门语言各钉一遍。
     * 用户打的字不翻译，聊天输入随时可能是另一门语言，而它近因权重最高。
     */
    @Test
    void 输出语言硬收尾恒在整轮输入末尾() {
        ChatTurnRunner runner = runner();
        for (AgentLang lang : AgentLang.values()) {
            String tail = ChatTestEndpoints.PROMPTS.get(lang, "chat.outputLanguage");
            assertThat(runner.turnTail(lang, null)).as("%s 普通轮", lang.code()).isEqualTo(tail);
            // 按钮意图那句垫在前面，语言指令照样是最后一句
            assertThat(runner.turnTail(lang, ChatIntent.BEHAVIOR))
                    .as("%s 按钮意图轮", lang.code()).endsWith(tail)
                    .contains(ChatTestEndpoints.PROMPTS.get(lang, ChatIntent.BEHAVIOR.promptKey()));
            // 另一门语言的那条一个字都不许混进来
            for (AgentLang other : AgentLang.values()) {
                if (other != lang) {
                    assertThat(runner.turnTail(lang, ChatIntent.BEHAVIOR))
                            .doesNotContain(ChatTestEndpoints.PROMPTS.get(other, "chat.outputLanguage"));
                }
            }
        }
    }

    /**
     * 内容相同的消息不许被静默丢弃：同一句话问第二遍，第二条也要进上下文，模型才看得见本轮问题。
     * 钉子看<b>落库终态里那句话出现两次</b>。
     */
    @Test
    void 内容相同的消息不再被静默丢弃() {
        when(contextStore.load(SESSION)).thenReturn(List.of(new UserMessage("同一句话")));
        mainAnswers("这是答案");

        turn("同一句话");

        ArgumentCaptor<List<Message>> saved = ArgumentCaptor.captor();
        verify(contextStore).save(eq(SESSION), eq(1L), saved.capture());
        assertThat(saved.getValue()).filteredOn(m -> "同一句话".equals(m.getText())).hasSize(2);
    }

    /** 一轮跑完必须落库，否则下一轮从零起跑（用户表现为 AI 失忆） */
    @Test
    void 没有工具参与时也要落库() {
        mainAnswers("这是答案");

        turn("随便问问");

        verify(contextStore).save(eq(SESSION), anyLong(), any());
        assertThat(answer.toString()).isEqualTo("这是答案");
    }
}
