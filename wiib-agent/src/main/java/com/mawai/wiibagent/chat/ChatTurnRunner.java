package com.mawai.wiibagent.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.ConversationSummarizer;
import com.mawai.wiibagent.llm.LlmErrorMessages;
import com.mawai.wiibagent.llm.ReactLoop;
import com.mawai.wiibagent.llm.SearchEvent;
import com.mawai.wiibagent.llm.SseChatModel;
import com.mawai.wiibagent.llm.ToolChoice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 一轮对话的编排：问路由 → 并行跑专家 → 汇总出答案 → 历史落库。
 * <pre>
 * 载入历史 + 本轮提问 → working
 *   ↓
 * 补答轮专属：接回让位那批在途专家，先等它们回来（普通轮跳过这步）
 *   ↓
 * 派发循环（while，最多 3 轮）
 *   ├ 有未消费授权 / 按钮意图 / 满 3 轮 / 路由 FINISH / 专家都派过了 → 出循环去汇总
 *   ├ 用户点停止 ──────────────────────────► 整轮结束：半截答案落库，不欠补答
 *   ├ 新消息到达 ──────────────────────────► 整轮结束：让位，在途批次交协调器排队
 *   └ 派新专家 → 虚拟线程并行跑 → 结论按派发顺序并入 working ↺ 回循环开头
 *        └ 这段等待是唯一的让位窗口：中断与让位都在这儿被接住
 *   ↓
 * summarizer 流式作答（token 逐帧外发）
 *   ↓
 * 终态（含压缩替换）整体覆盖会话历史，下一轮从这里起跑
 * </pre>
 * 编排用普通 Java 循环：分支就是 if、并行就是虚拟线程、回环就是 while。
 * 叶子是 {@link ReactLoop}，那里只有模型 ↔ 工具的直线循环。
 * <p>
 * <b>让位</b>（用户消息优先于专家返回）：专家等待期收到 {@link TurnYield} 的信号即让位——
 * 存档 working、把在途批次交回（{@link TurnResult}），由 {@link ChatYieldCoordinator} 排队；
 * 前端在会话空闲时发起<b>补答轮</b>（{@link #run} 的 {@code deferred} 参数）把批次接回来，
 * 之后与普通轮完全一样：可补派专家、流式作答、可中断、可再被让位。
 * 只有专家等待期可让：路由/汇总都在烧模型调用，中断只会浪费；专家等待纯粹在等 IO，
 * 让出去的只是"接着等"这件事。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatTurnRunner {

    private final ChatContextStore contextStore;
    private final ApprovalRegistry approvalRegistry;
    private final PromptCatalog prompts;
    private final LocalizedToolCallbacks localizedTools;
    /** 专家并行用。虚拟线程：专家全程阻塞在上游 HTTP 上，池大小不该成为约束 */
    private final ExecutorService expertExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** 结束派发、转去汇总的信号值，也是路由工具的结束值 */
    static final String FINISH = "FINISH";

    /**
     * 派发轮次上限，{@code while(true)} 的第二道闸。
     * <p>
     * <b>今天真正让循环停下来的是去重</b>（每轮至少吃掉一个专家名，名字用完必停）。
     * 现在正好三个专家，最坏情况（一轮派一个）恰好用满 3 轮，这个数不再有余量——
     * 再加专家就得把它一起抬，否则最后那个永远派不出去。
     * 留着它的理由是它兜的正是"去重失灵"：把 fresh 过滤删掉做变异跑，
     * 路由永远想派同一个专家，靠的就是这条闸收的场（实测）。
     */
    static final int MAX_DISPATCH_ROUNDS = 3;

    /**
     * 专家执行进度：专家走的是阻塞 invoke，token 流拿不到，改由这里主动推事件，
     * 前端据此渲染真实进度。
     *
     * @param agent 专家名
     * @param phase start=开始执行；done=完成，text 是结论原文；error=失败，text 是原因
     * @param text  phase=start 时为 null
     */
    public record ExpertProgress(String agent, String phase, String text) {
        public static final String START = "start";
        public static final String DONE = "done";
        public static final String ERROR = "error";
    }

    /**
     * 一轮的让位控制面（生产实现是 {@link ChatYieldCoordinator.TurnHandle}）：
     * runner 只声明自己需要什么，登记/信号的归属都在协调器那边，两个类不构成环。
     */
    public interface TurnYield {
        /** 进入专家等待期（让位窗口开）。返回让位信号：新消息到达时它被完成 */
        CompletableFuture<Void> enterExpertWait();

        /** 离开专家等待期（让位窗口关） */
        void exitExpertWait();

        /** 让位信号是否已发。粘滞：一旦发出，本轮后续任何检查点都立即让位 */
        boolean yieldRequested();

        /**
         * 用户点了停止。与让位的区别：让位是"这个问题稍后补答"，中断是"这个问题到此为止，不补"。
         * 同样粘滞，跑到下一个检查点就收尾。
         */
        default boolean cancelRequested() {
            return false;
        }

        /** 中断信号：专家等待期要靠它唤醒，否则点了停止得挂到整批专家跑完 */
        default CompletableFuture<Void> cancelSignal() {
            return new CompletableFuture<>();   // 永不完成＝这个调用方不支持中断
        }

        /** 无让位能力的空实现：调用方不支持让位（测试/一次性调用）时用 */
        TurnYield NONE = new TurnYield() {
            private final CompletableFuture<Void> never = new CompletableFuture<>();

            @Override
            public CompletableFuture<Void> enterExpertWait() {
                return never;
            }

            @Override
            public void exitExpertWait() {
            }

            @Override
            public boolean yieldRequested() {
                return false;
            }
        };
    }

    /**
     * 一批并行派出的专家：名字与各自的结论 future 按派发顺序对齐。
     * 让位时整批交给 {@link ChatYieldCoordinator} 排队，补答轮原样接回来接着等。
     */
    public record ExpertBatch(List<String> names, List<CompletableFuture<Message>> futures) {
        public static final ExpertBatch EMPTY = new ExpertBatch(List.of(), List.of());

        /**
         * 全部到齐的 future。<b>按派发顺序接而不是先完成先接</b>
         * 先等全部到齐，在join获取runExpert的Message结果
         */
        public CompletableFuture<List<Message>> all() {
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .thenApply(_ -> futures.stream().map(CompletableFuture::join)
                            .filter(Objects::nonNull).toList());
        }

        public boolean isDone() {
            return futures.stream().allMatch(CompletableFuture::isDone);
        }
    }

    /**
     * 一轮的结局：完整跑完，或在专家等待期让位。
     * 让位时 {@code deferredExperts} 是在途的专家批次（可能已完成），
     * 由调用方交给 {@link ChatYieldCoordinator} 排队，等补答轮接回。
     */
    public record TurnResult(ExpertBatch deferredExperts, boolean cancelled) {
        public static final TurnResult COMPLETED = new TurnResult(null, false);
        /** 用户中断：与让位不同，这一轮不欠补答，半截答案就是最终答案 */
        public static final TurnResult CANCELLED = new TurnResult(null, true);

        public boolean yielded() {
            return deferredExperts != null;
        }
    }

    /** 中断这一轮的最终文本：半截答案照留（token 已经烧掉了），一个字都没出就立块牌子。
     *  展示历史与模型上下文用同一份措辞，两边不打架 */
    static String cancelledAnswer(PromptCatalog prompts, AgentLang lang, String partial) {
        return partial == null || partial.isBlank()
                ? prompts.get(lang, "chat.cancelledEmpty")
                : partial + "\n\n" + prompts.get(lang, "chat.cancelledNote");
    }

    /**
     * 路由工具：只用来让模型**结构化地**表达"下一步给谁"，方法体永远不会被执行。
     * <p>
     * 为什么是工具而不是"让模型输出 JSON 数组再解析"：后者是我们自己发明的，四个框架没人这么做，
     * 代价已经实测过——路由指令混在文本里会泄漏给用户（["news_agent"]["news_agent"]）、
     * 会作为 AssistantMessage 进历史被模型照抄、解析还脆。alibaba 的 issue #4320/#4266
     * 记录的 routing instability + infinite loops 是同一个病。
     * 走 function calling 后参数天然结构化，且 tool_call 不进文本 token 流。
     */
    public static class RouterTool {
        // description 只是编译期兜底：真正发给模型的那份按语言取自词表 tool.route（见 LocalizedToolCallbacks）
        @Tool(name = "route", description = """
                Decide what comes next. Name the experts when real data is still needed; \
                give ["FINISH"] once the expert data is enough to answer.""")
        public String route(@ToolParam(description = """
                Where to go next: market_agent (prices/positions/liquidations/options),
                news_agent (crypto news flashes), trader_agent (the user's own AI trader:
                status/positions/decisions/plans/retrospective notes),
                or ["FINISH"] to stop dispatching and answer directly.""") List<String> next) {
            return "";
        }
    }

    /**
     * 路由工具的 callback，按语言各存一份：{@link LocalizedToolCallbacks} 拼一次要反射扫一遍工具类，
     * 一门语言建一次存着，不每轮重扫。
     */
    private final Map<AgentLang, List<ToolCallback>> routerTools = new EnumMap<>(AgentLang.class);

    private List<ToolCallback> routerTools(AgentLang lang) {
        return routerTools.computeIfAbsent(lang, l -> localizedTools.of(l, new RouterTool()));
    }

    /** 路由是轻决策，正常 3s 内返回；90s 判挂死进重试、仍失败降级 FINISH——实测上游挂死曾拖它满 10 分钟 */
    private static final Duration ROUTER_TIMEOUT = Duration.ofSeconds(90);

    /**
     * 跑完一轮对话。
     * <p>
     * 两个 sink 是这一层与 SSE 的唯一接口：调用方断连后照样让这轮跑完（答案要落历史），
     * 只是不再往通道里写——那是调用方在 sink 里自己判断的事。
     *
     * @param enrichedMessage 带时间行与提问标记的用户消息（拼法见 ChatWorkbenchController 的两个
     *                        MARKER 常量，重新生成靠它们在上下文里定位本轮提问）
     * @param intent          功能按钮直发的意图，可空（用户自己打字的普通一轮就是空）
     * @param answerTokenSink summarizer 的答案增量，逐帧
     * @param progressSink    专家的开始/完成/失败事件
     * @param searchSink      summarizer 的服务端搜索过程（模型层挂在帧 metadata 上，见 {@link SearchEvent}）
     * @param yield           让位控制面；不支持让位的调用方传 {@link TurnYield#NONE}
     * @param deferred        补答轮接回的在途专家批次（让位那轮交出去的），普通轮为 null。
     *                        这批是替 enrichedMessage 里那个问题派的，不再问路由直接接；
     *                        名字预填进去重集合，之后的路由只能补派别人
     */
    public TurnResult run(ChatAgentFactory.Leaves leaves, long userId, String sessionId, String enrichedMessage,
                          ChatIntent intent, Consumer<String> answerTokenSink,
                          Consumer<ExpertProgress> progressSink, Consumer<SearchEvent> searchSink,
                          TurnYield yield, ExpertBatch deferred) {
        long startedAt = System.currentTimeMillis();
        // 语言取自叶子：它是建叶子时按用户语言写死的，已经在叶子缓存键里，不必再查一次
        AgentLang lang = leaves.lang();
        List<Message> history = contextStore.load(sessionId);
        List<Message> working = new ArrayList<>(history);
        working.add(new UserMessage(enrichedMessage));

        Set<String> dispatched = new LinkedHashSet<>();
        int round = 0;

        // 走这里一定是触发了让位
        if (deferred != null) {
            dispatched.addAll(deferred.names());
            round++;
            log.info("[Workbench] 补答轮接回在途批次 {} session={}", deferred.names(), sessionId);
            replayProgress(deferred, progressSink);
            TurnResult ended = awaitBatch(deferred, leaves, userId, sessionId, working, yield, lang);
            if (ended != null) {
                return ended;
            }
        }

        while (true) {
            // 看是否存在未消费的深研判授权，跳过专家派发直通汇总
            if (approvalRegistry.hasApproval(sessionId)) {
                log.info("[Workbench] 存在未消费的深研判授权，跳过派发直通汇总 session={}", sessionId);
                break;
            }
            // 功能按钮直发的一轮同理直通：该做什么已经写死在意图里，跳过专家派发直通汇总
            if (intent != null) {
                log.info("[Workbench] 按钮意图 {}，跳过派发直通汇总 session={}", intent, sessionId);
                break;
            }
            // 中断压过让位：两个信号都粘滞、可能同时为真，判反了被停掉的问题会被补答轮跑完
            if (yield.cancelRequested()) {
                return cancelTurn(userId, sessionId, working, "", lang);
            }
            // 让位信号粘滞的兜底：anyOf 被批次赢了但用户消息已在门口——结论已并入 working，不再派新一轮，交空批次让位
            if (yield.yieldRequested()) {
                return yieldTurn(userId, sessionId, working, ExpertBatch.EMPTY, lang);
            }
            if (round >= MAX_DISPATCH_ROUNDS) {
                log.warn("[Workbench] 派发轮次达上限 {}，转汇总", MAX_DISPATCH_ROUNDS);
                break;
            }
            List<String> next = askRouter(leaves.light(), working, lang);
            if (next.isEmpty() || next.contains(FINISH)) {
                // FINISH 与"解析不出专家名"（空）同型不同因，事后排查靠这行分辨；路由调用失败 askRouter 已有 warn
                log.info("[Workbench] 路由结束派发 next={}（第 {} 轮后转汇总）", next, round);
                break;
            }
            // 同一专家不重复派：它取的数这一轮内不会变，再派一次只是空转烧钱，
            // 而且这正是死循环的来源（模型总觉得"再查一次说不定有新东西"）。
            // 所以需要靠代码收敛，不指望模型自觉说 FINISH
            List<String> fresh = next.stream().filter(name -> !dispatched.contains(name)).toList();
            if (fresh.isEmpty()) {
                log.info("[Workbench] {} 本轮已取过数，转汇总", next);
                break;
            }
            round++;
            dispatched.addAll(fresh);
            log.info("[Workbench] 派发 {}（第 {} 轮）", fresh, round);
            // 快照传入：让位路径会往 working 里垫占位答复，专家线程不能共享读一个正被改的列表
            ExpertBatch batch = dispatchAsync(leaves, fresh, List.copyOf(working), progressSink, lang);
            TurnResult ended = awaitBatch(batch, leaves, userId, sessionId, working, yield, lang);
            if (ended != null) {
                return ended;
            }
        }

        working.add(new UserMessage(summaryTail(lang, !dispatched.isEmpty(), intent)));
        // 中断时半截答案已经攒在这儿：token 逐帧进来，掐断在哪一帧就攒到哪一帧
        StringBuilder emitted = new StringBuilder();
        ReactLoop.Result result =
                streamSummarizer(leaves, working, userId, sessionId, chunk -> {
                    emitted.append(chunk);
                    answerTokenSink.accept(chunk);
                }, searchSink, yield);
        if (yield.cancelRequested()) {
            // 在途那条流已被中断信号掐断、循环在检查点退出了，但收尾帧没到、
            // 这次调用的 token 未知——账本就此不可信，标记让它退化成只报耗时
            leaves.deep().markAbandoned();
            leaves.light().markAbandoned();
            return cancelTurn(userId, sessionId, working, emitted.toString(), lang);
        }

        // 终态含压缩替换 + 本轮全部新消息，整体覆盖会话历史（下一轮从这里起跑）
        List<Message> finalMessages = result.messages();
        contextStore.save(sessionId, userId, finalMessages);

        log.info("[TurnMetrics] session={} rounds={} experts={} summarizerCalls={} historyIn={} "
                        + "historyOut={} estTokensIn={} durationMs={}",
                sessionId, round, dispatched.size(), result.modelCalls(),
                history.size(), finalMessages.size(),
                // 估算不是计费口径：按 CJK 1 字≈1 token 折的，只用来看"这轮喂进去多大"
                ConversationSummarizer.estimateTokens(working),
                System.currentTimeMillis() - startedAt);
        return TurnResult.COMPLETED;
    }

    /**
     * 进汇总前垫的最后一条消息，恒为用户侧——整段输入以 assistant 结尾，模型只会补一句"没什么可补充的"。
     * <ul>
     *   <li>{@code chat.expertHandoff}：给专家产出定性（取回的数据 / 取数失败 / 没有返回内容，
     *       三种形态都要覆盖）。没派专家时不垫——它指向的消息不存在。</li>
     *   <li>{@link ChatIntent#promptKey()}：按钮意图的动作指令（本轮必须调哪个工具），只有按钮直发的一轮才垫。</li>
     *   <li>{@code chat.outputLanguage}：输出语言硬收尾，派没派专家都垫。用户打的字不翻译，
     *       聊天输入随时是另一门语言，且近因权重最高，语言指令必须排在它后面。</li>
     * </ul>
     * 几句合成一条消息：它随本轮终态落进会话历史，多一条就多占后续上下文。
     */
    // 包私有非 private：输出语言硬收尾那条钉子（ChatTurnRunnerTest）要拿成文验它在末尾
    String summaryTail(AgentLang lang, boolean dispatched, ChatIntent intent) {
        StringBuilder tail = new StringBuilder();
        if (dispatched) {
            tail.append(prompts.get(lang, "chat.expertHandoff")).append('\n');
        }
        if (intent != null) {
            tail.append(prompts.get(lang, intent.promptKey())).append('\n');
        }
        return tail.append(prompts.get(lang, "chat.outputLanguage")).toString();
    }

    /**
     * 专家等待期：等批次到齐，或被让位/中断信号叫醒。让位窗口只在这段开着。
     * 普通轮派出的新批次与补答轮接回的旧批次走的都是这一段。
     *
     * @return null=批次已并入 working、本轮继续；非 null=本轮到此结束（让位或中断）
     */
    private TurnResult awaitBatch(ExpertBatch batch, ChatAgentFactory.Leaves leaves, long userId,
                                  String sessionId, List<Message> working, TurnYield yield, AgentLang lang) {
        CompletableFuture<List<Message>> all = batch.all();
        CompletableFuture<Void> signal = yield.enterExpertWait();
        CompletableFuture<Void> stop = yield.cancelSignal();
        try {
            // 让位signal唤醒处是 handle.yieldSignal.complete(null); 比如用户在同一会话又发了一条消息
            // stop唤醒处是 handle.cancelSignal.complete(null); 用户点击停止按钮
            CompletableFuture.anyOf(all, signal, stop).join();
        } finally {
            yield.exitExpertWait();
        }
        if (yield.cancelRequested()) {
            // 专家批次还在跑（阻塞 invoke 掐不断），它们的账要在读数之后才到——本轮不可信，见 markAbandoned
            leaves.deep().markAbandoned();
            leaves.light().markAbandoned();
            return cancelTurn(userId, sessionId, working, "", lang);
        }
        if (signal.isDone()) {
            // 让位优先于批次：批次恰好同刻完成也让——用户的新消息不该等一整段汇总流
            return yieldTurn(userId, sessionId, working, batch, lang);
        }
        working.addAll(all.join());
        return null;
    }

    /**
     * 补答轮，在这里用 'thenAccept' 给之前让位的专家注册回调
     * 回调的方法就是 onExpertProgress
     */
    private static void replayProgress(ExpertBatch batch, Consumer<ExpertProgress> progressSink) {
        for (int i = 0; i < batch.names().size(); i++) {
            String name = batch.names().get(i);
            progressSink.accept(new ExpertProgress(name, ExpertProgress.START, null));
            batch.futures().get(i).thenAccept(message -> progressSink.accept(new ExpertProgress(name,
                    ExpertProgress.DONE, message == null || message.getText() == null
                            ? null : stripExpertTag(message.getText()))));
        }
    }

    /**
     * 让位收尾：给原问题垫占位答复并存档 working（用户消息与已到手的专家结论都是花钱换的），
     * 在途批次原样交回——排队是 {@link ChatYieldCoordinator} 的事，接回是补答轮的事，这里只管把账记清。
     */
    private TurnResult yieldTurn(long userId, String sessionId, List<Message> working,
                                 ExpertBatch inFlight, AgentLang lang) {
        // 让位时垫进一条消息，详见 chat.yml
        working.add(new AssistantMessage(prompts.get(lang, "chat.yieldPlaceholder")));
        contextStore.save(sessionId, userId, working);
        log.info("[Workbench] 专家等待期让位 session={}", sessionId);
        return new TurnResult(inFlight, false);
    }

    /**
     * 用户中断收尾：把已产出的半截当这一轮的答复存档，续聊接得上。
     * 与让位的区别是<b>不欠补答</b>——这个问题就到此为止，用户要么接着问、要么点重新生成。
     */
    private TurnResult cancelTurn(long userId, String sessionId, List<Message> working, String partial,
                                  AgentLang lang) {
        working.add(new AssistantMessage(cancelledAnswer(prompts, lang, partial)));
        contextStore.save(sessionId, userId, working);
        log.info("[Workbench] 用户中断 session={} 已产出={}字", sessionId, partial.length());
        return TurnResult.CANCELLED;
    }

    /**
     * summarizer 叶子流式收尾。
     * 摔了先把 working 落库再抛：用户消息和专家结论此刻只在内存里，不落库的话用户重试一遍，
     * 专家全得重派重烧（market 还打真实上游配额）。
     */
    private ReactLoop.Result streamSummarizer(ChatAgentFactory.Leaves leaves,
                                              List<Message> working, long userId,
                                              String sessionId, Consumer<String> tokenSink,
                                              Consumer<SearchEvent> searchSink,
                                              TurnYield yield) {
        ReactLoop.Listener listener = new ReactLoop.Listener() {
            @Override
            public void chunk(ChatResponse frame) {
                String chunk = frame.getResults().getFirst().getOutput().getText();
                if (chunk != null && !chunk.isEmpty()) {
                    tokenSink.accept(chunk);
                }
                // 搜索过程帧是空文本帧，事件挂在这一帧的响应 metadata 上
                String json = frame.getMetadata().<String>get(SearchEvent.KEY);
                if (json != null) {
                    searchSink.accept(SearchEvent.parse(json));
                }
            }
        };
        try {
            // 会话号进 ToolContext 与闸门；中断信号直接掐模型流，点停止就在下一个检查点退出
            return leaves.summarizer().run(working, sessionId, yield.cancelSignal(), listener);
        } catch (RuntimeException e) {
            contextStore.save(sessionId, userId, working);
            throw e;
        }
    }

    /**
     * 派一批专家，各跑各的虚拟线程——等不等、等多久归调用方（让位窗口在 {@link #awaitBatch}）。
     * 出错的专家交回的是一条说明失败的消息（见 {@link #runExpert}），批次本身不会异常完成。
     */
    private ExpertBatch dispatchAsync(ChatAgentFactory.Leaves leaves, List<String> names, List<Message> input,
                                      Consumer<ExpertProgress> progressSink, AgentLang lang) {
        List<CompletableFuture<Message>> futures = names.stream()
                .map(name -> CompletableFuture.supplyAsync(
                        () -> runExpert(name, leaves.experts().get(name), input, progressSink, lang),
                        expertExecutor))
                .toList();
        return new ExpertBatch(List.copyOf(names), futures);
    }

    /** 单个专家：推进度 → （可选预取）→ 阻塞跑 → 交回带出处标注的产出。 */
    private Message runExpert(String name, ChatAgentFactory.Expert expert, List<Message> input,
                              Consumer<ExpertProgress> progressSink, AgentLang lang) {
        progressSink.accept(new ExpertProgress(name, ExpertProgress.START, null));
        try {
            List<Message> messages = new ArrayList<>(input);
            // 包装文案保持中性：怎么用这份数据（独占还是与搜索合并）由各专家的 instruction 定
            if (expert.preload() != null) {
                messages.add(new UserMessage(prompts.get(lang, "chat.preloadHeader") + "\n" + expert.preload().get()));
            }
            Message reply = expert.loop().run(messages, null, null, null).messages().getLast();
            String text = reply.getText();
            if (text == null || text.isBlank()) {
                // 空结论不当数据接：接了 summarizer 只会答"没有数据"，且无处排查
                log.warn("[Workbench] 专家 {} 没有产出任何内容", name);
                progressSink.accept(new ExpertProgress(name, ExpertProgress.ERROR,
                        prompts.get(lang, "chat.expertNoContentReason")));
                return expertMessage(prompts, lang, name, "chat.expertStatus.noContent",
                        prompts.get(lang, "chat.expertNoContentBody"));
            }
            progressSink.accept(new ExpertProgress(name, ExpertProgress.DONE, text));
            return expertMessage(prompts, lang, name, "chat.expertStatus.data", text);
        } catch (Exception e) {
            // 单个专家失败不该拖垮整轮：把失败作为一条消息交回，summarizer 自行判断要不要绕开。
            // 两个出口都过归类：原始 SDK 异常可能几百字符，喂回模型既白烧 token，
            // 又把上游细节（可能含 key）连同答案一起写进会话历史持久化
            log.warn("[Workbench] 专家 {} 执行失败", name, e);
            String reason = LlmErrorMessages.classify(e, prompts, lang);
            progressSink.accept(new ExpertProgress(name, ExpertProgress.ERROR, reason));
            return expertMessage(prompts, lang, name, "chat.expertStatus.failed", reason);
        }
    }

    /**
     * 专家产出接进上下文的统一形态：<b>带出处标注的用户侧消息</b>。
     * <p>
     * 裸 AssistantMessage 在模型眼里是"我自己刚说过的话"——既分不出哪些是取回来的数据，
     * 整段输入还会以 assistant 结尾，于是倾向于答"没有数据"（实测）。
     * 标注同时让这些消息在后续轮次里不冒充"助手以前给过的答案"。
     */
    // 包私有非 private：兜底剥标注的钉子要拿真实格式验往返
    static Message expertMessage(PromptCatalog prompts, AgentLang lang, String agent,
                                 String statusKey, String body) {
        return new UserMessage(prompts.get(lang, "chat.expertTag",
                Map.of("agent", agent, "status", prompts.get(lang, statusKey))) + "\n" + body);
    }

    /**
     * 剥掉 {@link #expertMessage} 的出处标注行，格式与它配对维护。
     * 两套括号都认（中文【】/英文 []）：标注是写入时那门语言拼的。
     */
    static String stripExpertTag(String text) {
        return text.replaceFirst("^(【[^】]*】|\\[[^]]*])\n", "");
    }

    /**
     * 问轻模型"下一步派谁"：单次调用，tool_choice 无条件 required，模型只能用 route 工具作答。
     * <p>
     * 返回本轮该派的专家名单；{@link #FINISH} 或空表示不再派发、转汇总——调用方据此出循环。
     * 超时（{@link #ROUTER_TIMEOUT}）与任何异常也返回 FINISH：路由挂了照样作答，不拖死整轮。
     * options 的派生与 tool_choice 的协议落地都归 {@link ToolChoice}。
     */
    private List<String> askRouter(ChatModel model, List<Message> history, AgentLang lang) {
        List<Message> messages = new ArrayList<>(history.size() + 1);
        messages.add(new SystemMessage(prompts.get(lang, "chat.router")));
        messages.addAll(history);
        long startedAt = System.currentTimeMillis();
        try {
            ChatOptions options = SseChatModel.withCallTimeout(
                    ToolChoice.apply(ToolChoice.withTools(model, routerTools(lang)), ToolChoice.REQUIRED),
                    ROUTER_TIMEOUT);
            ChatResponse response = model.call(new Prompt(messages, options));
            List<String> next = parseRouteCall(Objects.requireNonNull(response.getResult()).getOutput());
            // openai 协议路没有 [Responses] 那样的请求日志，路由慢在模型还是慢在别处只能靠这行分辨
            log.info("[Workbench] 路由回答 next={} 耗时={}ms", next, System.currentTimeMillis() - startedAt);
            return next;
        } catch (Exception e) {
            // 路由失败不该把整轮对话拖死：退化成"不派发直接作答"，用户至少拿得到回复
            log.warn("[Workbench] 路由调用失败，转汇总", e);
            return List.of(FINISH);
        }
    }

    /** 从 tool_call 参数里取专家名单。结构化解析，不碰自由文本。 */
    static List<String> parseRouteCall(AssistantMessage message) {
        for (AssistantMessage.ToolCall call : message.getToolCalls()) {
            if (!"route".equals(call.name())) {
                continue;
            }
            JSONArray next = JSON.parseObject(call.arguments()).getJSONArray("next");
            if (next == null || next.isEmpty()) {
                return List.of();
            }
            List<String> names = new ArrayList<>(next.size());
            for (Object item : next) {
                if (FINISH.equals(item)) {
                    return List.of(FINISH);
                }
                if (item instanceof String name && ChatAgentFactory.EXPERT_AGENTS.contains(name)) {
                    names.add(name);
                }
            }
            return names;
        }
        return List.of();
    }
}
