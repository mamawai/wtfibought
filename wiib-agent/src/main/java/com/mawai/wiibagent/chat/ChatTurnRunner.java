package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.run.ChildRunRegistry;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.ContextUsage;
import com.mawai.wiibagent.llm.ReactLoop;
import com.mawai.wiibagent.llm.SearchEvent;
import com.mawai.wiibagent.llm.SseChatModel;
import com.mawai.wiibagent.llm.WebSearchTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 一轮对话的编排：载入历史 → 加本轮提问与收尾指令 → 主 agent 流式作答 → 终态落库。
 * <pre>
 * 挂上子运行收件箱 → 载入上下文（提示词或工具变了就按文本重建；上一轮用量到了整理线就先整理，见 {@link ContextCompactor}）
 *   ↓
 * 历史 + 本轮提问 + 收尾指令 → working
 *   ↓
 * 主 agent（ReactLoop · 深模型 · 流式；行情/新闻/trader/深研判/行为分析全是它手里的工具）
 *   ├ 模型要调工具 ────► 这一批连名带参推给前端（tool_calls），画成过程条目
 *   ├ 调的是 web_search ► 画成搜索条目（search），回执里的来源攒进本轮来源，没搜成画成搜索失败
 *   ├ 每次调完模型 ────► 上游报的上下文用量推给前端（context），底栏计量条跟着动
 *   ├ 子运行报告到达 ──► 下一次调模型前排进上下文
 *   ├ 用户点停止 ──────► 半截答案落库，本轮结束
 *   ↓
 * 摘掉收件箱（剩余报告并进落库消息，剩余非空即欠一次唤醒）
 *   ↓
 * 终态（整理过的历史 + 本轮全部新消息）整体覆盖会话历史，下一轮从这里起跑
 * </pre>
 * 没有路由、没有专家派发：取什么数由主 agent 自己定，同一批 tool_call 并发执行（见 {@link ReactLoop}）。
 * 长活走后台子运行（{@link ChildRunRegistry}），轮里只剩模型思考和快工具。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatTurnRunner {

    private final ChatContextStore contextStore;
    private final ChildRunRegistry childRuns;
    private final PromptCatalog prompts;
    /** 工具调用事件的出口：ReactLoop 手里只有 sessionId，经它转成 SSE 帧 */
    private final WorkbenchRunRegistry runRegistry;
    private final ContextCompactor compactor;

    /**
     * 一轮的结局。
     *
     * @param cancelled 用户中断：半截答案就是最终答案
     * @param pending   最后一次模型调用之后还到了子运行报告、这轮没答：前端空闲时发起唤醒轮
     * @param context   这一轮结束时的上下文用量：最后一次模型调用报的；那次服务端搜过、或一次都没报，就连系统提示词按字数估。
     *                  中断的轮为 null：工具过程没落库，轮中途测到的偏大，沿用上一轮的读数
     */
    public record TurnResult(boolean cancelled, boolean pending, ContextUsage context) {
        public static final TurnResult COMPLETED = new TurnResult(false, false, null);
        public static final TurnResult CANCELLED = new TurnResult(true, false, null);
    }

    /** 中断这一轮的最终文本：半截答案照留（token 已经烧掉了），一个字都没出就立块牌子。
     *  展示历史与模型上下文用同一份措辞，两边不打架 */
    static String cancelledAnswer(PromptCatalog prompts, AgentLang lang, String partial) {
        return partial == null || partial.isBlank()
                ? prompts.get(lang, "chat.cancelledEmpty")
                : partial + "\n\n" + prompts.get(lang, "chat.cancelledNote");
    }

    /**
     * 跑完一轮对话。
     * <p>
     * 两个 sink 是这一层与 SSE 的唯一接口：调用方断连后照样让这轮跑完（答案要落历史），
     * 只是不再往通道里写——那是调用方在 sink 里自己判断的事。
     *
     * @param enrichedMessage 带时间行与提问标记的用户消息（拼法见 ChatTurnStreamer，重新生成靠标记在上下文里定位本轮提问）；
     *                        唤醒轮给的是唤醒指令
     * @param intent          功能按钮直发的意图，可空（用户自己打字的普通一轮就是空）
     * @param answerTokenSink 答案增量，逐帧
     * @param searchSink      搜索过程：原生搜索取自帧 metadata，web_search 的按调用与回执合成（见 {@link SearchEvent}）
     * @param cancel          中断信号，可空；完成即中断，在途答案流一并掐断
     */
    public TurnResult run(ChatAgentFactory.Leaves leaves, long userId, String sessionId, String enrichedMessage,
                          ChatIntent intent, Consumer<String> answerTokenSink, Consumer<SearchEvent> searchSink,
                          CompletableFuture<Void> cancel) {
        long startedAt = System.currentTimeMillis();
        // 语言取自叶子：它是建叶子时按用户语言写死的，已经在叶子缓存键里，不必再查一次
        AgentLang lang = leaves.lang();
        String prefix = leaves.mainClient().fingerprint();
        // 先挂收件箱再读历史：之后到的报告进收件箱，之前到的已经在库里、随历史读进来
        Deque<Message> inbox = new ConcurrentLinkedDeque<>();
        childRuns.attachTurn(sessionId, inbox);
        ChatContextStore.State state;
        try {
            state = contextStore.load(sessionId);
        } catch (RuntimeException e) {
            childRuns.detachTurn(sessionId, userId);
            throw e;
        }
        List<Message> history = state.messages();
        Long contextTokens = state.contextTokens();
        // 系统提示词或工具跟上次不一样（改了提示词、切了语言、改了联网搜索）：以前的思考块、搜索块对不上了，按文本重建
        if (!prefix.equals(state.prefix())) {
            history = history.stream()
                    .map(m -> m instanceof AssistantMessage a ? SseChatModel.withoutRaw(a) : m)
                    .toList();
            contextTokens = null;
        }
        // 整理也是一次模型调用：开跑前就点了停的不整理
        boolean compacting = !cancelled(cancel) && compactor.shouldCompact(leaves, contextTokens, history);
        if (compacting) {
            runRegistry.publishProgress(sessionId, prompts.get(lang, "chat.compact.progress"));
            history = compactor.compact(leaves, history, cancel).orElse(history);
        }
        List<Message> working = new ArrayList<>(history);
        working.add(new UserMessage(enrichedMessage));
        if (cancelled(cancel)) {
            if (compacting) {
                // 整理那次调用可能被掐在半路，用量没收齐
                leaves.deep().markAbandoned();
            }
            // 开跑前或整理时点了停：作答一次都不调
            return cancelTurn(userId, sessionId, working, "", lang, prefix);
        }
        working.add(new UserMessage(turnTail(lang, intent)));

        // 中断时半截答案已经攒在这儿：token 逐帧进来，掐断在哪一帧就攒到哪一帧
        StringBuilder emitted = new StringBuilder();
        // 每次调完模型上游报的上下文用量，最后一次的就是这一轮结束时的；最后一次服务端搜过就是空
        AtomicReference<ContextUsage> measured = new AtomicReference<>();
        ReactLoop.Result result = streamMain(leaves, working, userId, sessionId, inbox, chunk -> {
            emitted.append(chunk);
            answerTokenSink.accept(chunk);
        }, searchSink, cancel, measured);
        if (cancelled(cancel)) {
            // 在途那条流已被中断信号掐断、循环在检查点退出了，但收尾帧没到、
            // 这次调用的 token 未知——账本就此不可信，标记让它退化成只报耗时
            leaves.deep().markAbandoned();
            return cancelTurn(userId, sessionId, working, emitted.toString(), lang, prefix);
        }

        List<Message> finalMessages = result.messages();
        ContextUsage context = measured.get() != null ? measured.get()
                : new ContextUsage(leaves.mainClient().estimateTokens(finalMessages), leaves.contextWindow(), true);
        // 终态（整理过的历史 + 本轮全部新消息）连同用量读数整体覆盖会话上下文，下一轮从这里起跑
        boolean pending = childRuns.settle(sessionId, userId,
                new ChatContextStore.State(finalMessages, context.tokens(), prefix));

        log.info("[TurnMetrics] session={} modelCalls={} historyIn={} historyOut={} contextTokens={} estimated={} pending={} durationMs={}",
                sessionId, result.modelCalls(), history.size(), finalMessages.size(),
                context.tokens(), context.estimated(), pending, System.currentTimeMillis() - startedAt);
        return new TurnResult(false, pending, context);
    }

    private static boolean cancelled(CompletableFuture<Void> cancel) {
        return cancel != null && cancel.isDone();
    }

    /**
     * 进模型前垫的最后一条消息，恒为用户侧——整段输入以 assistant 结尾，模型只会补一句"没什么可补充的"。
     * <ul>
     *   <li>{@link ChatIntent#promptKey()}：按钮意图的动作指令（本轮必须调哪个工具），只有按钮直发的一轮才垫。</li>
     *   <li>{@code chat.outputLanguage}：输出语言硬收尾，每轮都垫。用户打的字不翻译，
     *       聊天输入随时是另一门语言，且近因权重最高，语言指令必须排在它后面。</li>
     * </ul>
     * 几句合成一条消息：它随本轮终态落进会话历史，多一条就多占后续上下文。
     */
    // 包私有非 private：输出语言硬收尾那条钉子（ChatTurnRunnerTest）要拿成文验它在末尾
    String turnTail(AgentLang lang, ChatIntent intent) {
        StringBuilder tail = new StringBuilder();
        if (intent != null) {
            tail.append(prompts.get(lang, intent.promptKey())).append('\n');
        }
        return tail.append(prompts.get(lang, "chat.outputLanguage")).toString();
    }

    /**
     * 用户中断收尾：把已产出的半截当这一轮的答复存档，续聊接得上。
     * 这个问题就到此为止，用户要么接着问、要么点重新生成。
     */
    private TurnResult cancelTurn(long userId, String sessionId, List<Message> working, String partial,
                                  AgentLang lang, String prefix) {
        working.add(new AssistantMessage(cancelledAnswer(prompts, lang, partial)));
        boolean pending = childRuns.settle(sessionId, userId, new ChatContextStore.State(working, null, prefix));
        log.info("[Workbench] 用户中断 session={} 已产出={}字", sessionId, partial.length());
        return new TurnResult(true, pending, null);
    }

    /**
     * 主 agent 流式作答。
     * 摔了先把 working 落库再抛：用户消息此刻只在内存里，不落库的话下一轮就不知道这句问过。
     */
    private ReactLoop.Result streamMain(ChatAgentFactory.Leaves leaves, List<Message> working, long userId,
                                        String sessionId, Deque<Message> inbox, Consumer<String> tokenSink,
                                        Consumer<SearchEvent> searchSink, CompletableFuture<Void> cancel,
                                        AtomicReference<ContextUsage> measured) {
        // 这次调用里服务端搜过
        AtomicBoolean searched = new AtomicBoolean();
        // 在途的 web_search：tool_call id → 搜索词，回执到了按它配上"正在搜索"那条
        Map<String, String> webSearches = new HashMap<>();
        ReactLoop.Listener listener = new ReactLoop.Listener() {
            @Override
            public void chunk(ChatResponse frame) {
                String chunk = frame.getResults().getFirst().getOutput().getText();
                if (chunk != null && !chunk.isEmpty()) {
                    tokenSink.accept(chunk);
                }
                // 搜索过程帧是空文本帧，事件挂在这一帧的响应 metadata 上
                String json = frame.getMetadata().get(SearchEvent.KEY);
                if (json != null) {
                    SearchEvent event = SearchEvent.parse(json);
                    searchSink.accept(event);
                    if (SearchEvent.SEARCHED.equals(event.phase())) {
                        searched.set(true);
                    }
                }
                // 收尾帧带着这次调用的用量。服务端搜过的那次报的是计费口径：一次调用里每轮搜索都把上下文又算一遍，
                // 不是上下文大小，不用它
                Long tokens = ContextUsage.measured(frame.getMetadata().getUsage());
                if (tokens != null) {
                    if (searched.getAndSet(false)) {
                        measured.set(null);
                    } else {
                        ContextUsage context = new ContextUsage(tokens, leaves.contextWindow(), false);
                        measured.set(context);
                        runRegistry.publishContext(sessionId, context);
                    }
                }
            }

            @Override
            public void message(Message message) {
                // 模型要调工具了：这一批连名带参推给前端画过程条目。web_search 是包成本地工具的联网搜索（Antigravity 来源），
                // 画成搜索条目，跟原生搜索一个样：调用时"正在搜索"，回执到了"搜索了 N 个网站"、来源攒进本轮
                if (message instanceof AssistantMessage reply && reply.hasToolCalls()) {
                    List<AssistantMessage.ToolCall> tools = new ArrayList<>();
                    for (AssistantMessage.ToolCall call : reply.getToolCalls()) {
                        if (WebSearchTool.NAME.equals(call.name())) {
                            ObjectNode args = WorkbenchRunRegistry.argsOf(call);
                            String query = args == null ? null : args.path("query").asString(null);
                            webSearches.put(call.id(), query);
                            searchSink.accept(SearchEvent.searching(query));
                        } else {
                            tools.add(call);
                        }
                    }
                    // 搜索条目要先推：前端到了搜索会把亮着的工具条目熄掉
                    if (!tools.isEmpty()) {
                        runRegistry.publishToolCalls(sessionId, null, tools);
                    }
                } else if (message instanceof ToolResponseMessage responses) {
                    for (ToolResponseMessage.ToolResponse response : responses.getResponses()) {
                        if (WebSearchTool.NAME.equals(response.name())) {
                            String query = webSearches.remove(response.id());
                            List<SearchEvent.Source> sources = WebSearchTool.sourcesOf(response.responseData());
                            searchSink.accept(sources == null ? SearchEvent.failed(query) : SearchEvent.searched(query, sources));
                        }
                    }
                }
            }

            @Override
            public List<Message> inbox() {
                List<Message> arrived = ChildRunRegistry.drain(inbox);
                // 中断/摔了那两条出路落库的是 working（循环拿的是它的副本），排进循环的报告得跟着进去
                working.addAll(arrived);
                return arrived;
            }
        };
        try {
            // 会话号进 ToolContext 与闸门；中断信号直接掐模型流，点停止就在下一个检查点退出
            return leaves.main().run(working, sessionId, cancel, listener);
        } catch (RuntimeException e) {
            childRuns.settle(sessionId, userId,
                    new ChatContextStore.State(working, null, leaves.mainClient().fingerprint()));
            throw e;
        }
    }
}
