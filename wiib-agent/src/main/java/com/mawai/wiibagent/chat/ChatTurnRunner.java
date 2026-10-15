package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.run.ChildRunRegistry;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.ConversationSummarizer;
import com.mawai.wiibagent.llm.ReactLoop;
import com.mawai.wiibagent.llm.SearchEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

/**
 * 一轮对话的编排：载入历史 → 加本轮提问与收尾指令 → 主 agent 流式作答 → 终态落库。
 * <pre>
 * 挂上子运行收件箱 → 载入历史 + 本轮提问 + 收尾指令 → working
 *   ↓
 * 主 agent（ReactLoop · 深模型 · 流式；行情/新闻/trader/深研判/行为分析全是它手里的工具）
 *   ├ 模型要调工具 ────► 这一批连名带参推给前端（tool_calls），画成过程条目
 *   ├ 子运行报告到达 ──► 下一次调模型前排进上下文
 *   ├ 用户点停止 ──────► 半截答案落库，本轮结束
 *   ↓
 * 摘掉收件箱（剩余报告并进落库消息，剩余非空即欠一次唤醒）
 *   ↓
 * 终态（含压缩替换 + 本轮全部新消息）整体覆盖会话历史，下一轮从这里起跑
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

    /**
     * 一轮的结局。
     *
     * @param cancelled 用户中断：半截答案就是最终答案
     * @param pending   最后一次模型调用之后还到了子运行报告、这轮没答：前端空闲时发起唤醒轮
     */
    public record TurnResult(boolean cancelled, boolean pending) {
        public static final TurnResult COMPLETED = new TurnResult(false, false);
        public static final TurnResult CANCELLED = new TurnResult(true, false);
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
     * @param searchSink      服务端搜索过程（模型层挂在帧 metadata 上，见 {@link SearchEvent}）
     * @param cancel          中断信号，可空；完成即中断，在途答案流一并掐断
     */
    public TurnResult run(ChatAgentFactory.Leaves leaves, long userId, String sessionId, String enrichedMessage,
                          ChatIntent intent, Consumer<String> answerTokenSink, Consumer<SearchEvent> searchSink,
                          CompletableFuture<Void> cancel) {
        long startedAt = System.currentTimeMillis();
        // 语言取自叶子：它是建叶子时按用户语言写死的，已经在叶子缓存键里，不必再查一次
        AgentLang lang = leaves.lang();
        // 先挂收件箱再读历史：之后到的报告进收件箱，之前到的已经在库里、随历史读进来
        Deque<Message> inbox = new ConcurrentLinkedDeque<>();
        childRuns.attachTurn(sessionId, inbox);
        List<Message> history = contextStore.load(sessionId);
        List<Message> working = new ArrayList<>(history);
        working.add(new UserMessage(enrichedMessage));
        if (cancelled(cancel)) {
            // 开跑前就点了停：一次模型调用都不烧
            return cancelTurn(userId, sessionId, working, "", lang);
        }
        working.add(new UserMessage(turnTail(lang, intent)));

        // 中断时半截答案已经攒在这儿：token 逐帧进来，掐断在哪一帧就攒到哪一帧
        StringBuilder emitted = new StringBuilder();
        ReactLoop.Result result = streamMain(leaves, working, userId, sessionId, inbox, chunk -> {
            emitted.append(chunk);
            answerTokenSink.accept(chunk);
        }, searchSink, cancel);
        if (cancelled(cancel)) {
            // 在途那条流已被中断信号掐断、循环在检查点退出了，但收尾帧没到、
            // 这次调用的 token 未知——账本就此不可信，标记让它退化成只报耗时
            leaves.deep().markAbandoned();
            leaves.light().markAbandoned();
            return cancelTurn(userId, sessionId, working, emitted.toString(), lang);
        }

        // 终态含压缩替换 + 本轮全部新消息，整体覆盖会话历史（下一轮从这里起跑）
        List<Message> finalMessages = new ArrayList<>(result.messages());
        boolean pending = childRuns.settle(sessionId, userId, finalMessages);

        log.info("[TurnMetrics] session={} modelCalls={} historyIn={} historyOut={} estTokensIn={} pending={} durationMs={}",
                sessionId, result.modelCalls(), history.size(), finalMessages.size(),
                // 估算不是计费口径：按 CJK 1 字≈1 token 折的，只用来看"这轮喂进去多大"
                ConversationSummarizer.estimateTokens(working), pending,
                System.currentTimeMillis() - startedAt);
        return new TurnResult(false, pending);
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
                                  AgentLang lang) {
        working.add(new AssistantMessage(cancelledAnswer(prompts, lang, partial)));
        boolean pending = childRuns.settle(sessionId, userId, working);
        log.info("[Workbench] 用户中断 session={} 已产出={}字", sessionId, partial.length());
        return new TurnResult(true, pending);
    }

    /**
     * 主 agent 流式作答。
     * 摔了先把 working 落库再抛：用户消息此刻只在内存里，不落库的话下一轮就不知道这句问过。
     */
    private ReactLoop.Result streamMain(ChatAgentFactory.Leaves leaves, List<Message> working, long userId,
                                        String sessionId, Deque<Message> inbox, Consumer<String> tokenSink,
                                        Consumer<SearchEvent> searchSink, CompletableFuture<Void> cancel) {
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
                    searchSink.accept(SearchEvent.parse(json));
                }
            }

            @Override
            public void message(Message message) {
                // 模型要调工具了：这一批连名带参推给前端画过程条目
                if (message instanceof AssistantMessage reply && reply.hasToolCalls()) {
                    runRegistry.publishToolCalls(sessionId, null, reply.getToolCalls());
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
            childRuns.settle(sessionId, userId, working);
            throw e;
        }
    }
}
