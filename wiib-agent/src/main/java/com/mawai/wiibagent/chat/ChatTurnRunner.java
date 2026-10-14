package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 一轮对话的编排：载入历史 → 加本轮提问与收尾指令 → 主 agent 流式作答 → 终态落库。
 * <pre>
 * 载入历史 + 本轮提问 + 收尾指令 → working
 *   ↓
 * 主 agent（ReactLoop · 深模型 · 流式；行情/新闻/trader/深研判/行为分析全是它手里的工具）
 *   ├ 用户点停止 ──► 半截答案落库，本轮结束
 *   ↓
 * 终态（含压缩替换 + 本轮全部新消息）整体覆盖会话历史，下一轮从这里起跑
 * </pre>
 * 没有路由、没有专家派发：取什么数由主 agent 自己定，同一批 tool_call 并发执行（见 {@link ReactLoop}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatTurnRunner {

    private final ChatContextStore contextStore;
    private final PromptCatalog prompts;

    /** 一轮的结局：完整跑完，或被用户中断（半截答案就是最终答案） */
    public record TurnResult(boolean cancelled) {
        public static final TurnResult COMPLETED = new TurnResult(false);
        public static final TurnResult CANCELLED = new TurnResult(true);
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
     * @param enrichedMessage 带时间行与提问标记的用户消息（拼法见 ChatTurnStreamer 的两个
     *                        MARKER 常量，重新生成靠它们在上下文里定位本轮提问）
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
        ReactLoop.Result result = streamMain(leaves, working, userId, sessionId, chunk -> {
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
        List<Message> finalMessages = result.messages();
        contextStore.save(sessionId, userId, finalMessages);

        log.info("[TurnMetrics] session={} modelCalls={} historyIn={} historyOut={} estTokensIn={} durationMs={}",
                sessionId, result.modelCalls(), history.size(), finalMessages.size(),
                // 估算不是计费口径：按 CJK 1 字≈1 token 折的，只用来看"这轮喂进去多大"
                ConversationSummarizer.estimateTokens(working),
                System.currentTimeMillis() - startedAt);
        return TurnResult.COMPLETED;
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
        contextStore.save(sessionId, userId, working);
        log.info("[Workbench] 用户中断 session={} 已产出={}字", sessionId, partial.length());
        return TurnResult.CANCELLED;
    }

    /**
     * 主 agent 流式作答。
     * 摔了先把 working 落库再抛：用户消息此刻只在内存里，不落库的话下一轮就不知道这句问过。
     */
    private ReactLoop.Result streamMain(ChatAgentFactory.Leaves leaves, List<Message> working, long userId,
                                        String sessionId, Consumer<String> tokenSink,
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
        };
        try {
            // 会话号进 ToolContext 与闸门；中断信号直接掐模型流，点停止就在下一个检查点退出
            return leaves.main().run(working, sessionId, cancel, listener);
        } catch (RuntimeException e) {
            contextStore.save(sessionId, userId, working);
            throw e;
        }
    }
}
