package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.store.ChatHistoryService;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.ContextUsage;
import com.mawai.wiibagent.llm.LlmErrorMessages;
import com.mawai.wiibagent.llm.SearchEvent;
import com.mawai.wiibagent.llm.SseChannel;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 一轮对话在 SSE 通道上的完整过程：登记运行、喂心跳、跑 {@link ChatTurnRunner}、按结局落库并发 done 帧。
 * /chat、/regenerate、/wake 在 {@link ChatWorkbenchController} 做完准入后都交到 {@link #run}；名额的开闭归调用方。
 * <p>
 * 一轮两种结局，各自一个 finish 方法（见 {@link Turn}）：用户点停止——半截答案落库；
 * 正常作答——可能先弹一张 HITL 确认卡。两种 done 都带 pending：最后一次模型调用之后又到了子运行报告，前端空闲时发起唤醒轮。
 * <p>
 * 唤醒轮（wake=true）：没有用户提问，用户侧消息是唤醒指令，答案带【补充回答】标头、不落 user 行。
 * <p>
 * 断连不中止本轮：通道关了照样跑完并落历史，只是不再往通道里写帧，前端回来靠 status 接口+历史回放补。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatTurnStreamer {

    /** 注入用户消息的当前时间。带年份不随仓里 MM-dd 惯例：模型没有时钟，年份是它最容易错的一位 */
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** 模型思考期间 SSE 通道可能静默较久，nginx 默认 proxy_read_timeout 60s 会掐断——20s 一帧留 3 倍余量 */
    private static final long HEARTBEAT_SECONDS = 20;

    private final ChatTurnRunner turnRunner;
    private final ChatHistoryService chatHistoryService;
    private final WorkbenchRunRegistry runRegistry;
    private final ApprovalRegistry approvalRegistry;
    private final PromptCatalog prompts;
    /** 心跳专用：只发注释帧(微秒级)，单线程够所有会话用；虚拟线程不支持定时调度故用平台线程 */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    /** 上下文收尾：停掉心跳线程 */
    @PreDestroy
    public void stop() {
        heartbeatScheduler.shutdownNow();
    }

    /**
     * 带轮起始标记的用户消息是哪门语言拼的（拼法见 {@link Turn#run}），不是就 empty。
     * 认全部语言：切语言后旧轮还是旧语言。重新生成定位本轮提问、整理上下文找上一问都用它
     */
    static Optional<AgentLang> turnLang(Message message, PromptCatalog prompts) {
        if (message instanceof UserMessage) {
            for (AgentLang lang : AgentLang.values()) {
                if (message.getText().startsWith(prompts.get(lang, "chat.turn.timePrefix"))) {
                    return Optional.of(lang);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 跑一轮到底。
     *
     * @param message          用户提问；唤醒轮为 null
     * @param replacedAnswerId null=普通轮；非空=重新生成轮，它在顶替这条旧答案——提问已在库里不再落一遍
     * @param wake             唤醒轮：子运行报告已落进上下文，这一轮据此补充回答
     */
    public void run(SseChannel channel, long userId, String sessionId, String message,
                    ChatAgentFactory.Leaves leaves, Long replacedAnswerId, ChatIntent intent, boolean wake) {
        new Turn(channel, userId, sessionId, message, leaves, replacedAnswerId, intent, wake).run();
    }

    /** 一轮的执行现场：入参与跑出来的中间量都在这儿，各种结局收尾时直接取用。 */
    @RequiredArgsConstructor
    private final class Turn {
        private final SseChannel channel;
        private final long userId;
        private final String sessionId;
        private final String message;
        private final ChatAgentFactory.Leaves leaves;
        private final Long replacedAnswerId;
        private final ChatIntent intent;
        private final boolean wake;

        private final long startedAt = System.currentTimeMillis();
        /** 轮开始时的确认卡登记水位：只发本轮新登记的卡。序号不是墙钟，毫秒粒度挤不出误判 */
        private final long approvalSeqAtStart = approvalRegistry.currentSeq();
        /** 中断信号：登记进运行注册表，/cancel 完成它；runner 在检查点看它、ReactLoop 用它掐在途流 */
        private final CompletableFuture<Void> cancel = new CompletableFuture<>();
        private final StringBuilder answer = new StringBuilder();
        /** 唤醒轮的答案标头：随首个答案 chunk 推出，落库时拼在答案前面；普通轮为空串 */
        private String prefix = "";
        /** 本轮搜到/引用到的来源，按 url 去重保序：随 done 下发并落库，刷新后答案底部还在 */
        private final Map<String, SearchEvent.Source> sources = new LinkedHashMap<>();
        /** 经事件出口的过程事件里随答案落库的几种（搜索另从 onSearch 进）；前端重开历史时据它重建过程轨 */
        private static final Set<String> STEP_EVENTS =
                Set.of("progress", "tool_calls", "child_start", "child_progress", "child_done");
        /** 本轮的过程事件，和推给前端的 SSE 帧同形，多一个 type */
        private final ArrayNode steps = MAPPER.createArrayNode();

        void run() {
            ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleWithFixedDelay(
                    channel::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
            try {
                // 运行中标记：status 接口、拒删会话都看它
                // 事件出口：工具手里只有 sessionId，进度/表单卡/子运行事件经这转成 SSE 帧
                runRegistry.start(sessionId, (event, data) -> {
                    // 落库不看连接死活：断连后这轮照跑完，过程也要留在历史里
                    if (STEP_EVENTS.contains(event)) {
                        steps.add(data.deepCopy().put("type", event));
                    }
                    if (channel.isClosed()) {
                        return false;
                    }
                    channel.send(event, data);
                    return true;
                }, cancel);
                channel.send("session", MAPPER.createObjectNode().put("sessionId", sessionId));
                // 重新生成不落库：提问行已经在了；唤醒轮没有提问
                if (replacedAnswerId == null && !wake) {
                    chatHistoryService.append(sessionId, userId, "user", message);
                }

                String enriched;
                if (wake) {
                    enriched = prompts.get(leaves.lang(), "chat.wake.instruction");
                    prefix = prompts.get(leaves.lang(), "chat.wake.header") + "\n\n";
                } else {
                    // 补充时间信息。轮起始标记按语言取自词表（chat.turn.*），认的一侧在 turnLang
                    enriched = prompts.get(leaves.lang(), "chat.turn.timeMark",
                            Map.of("time", TIME_FMT.format(Instant.now()))) + "\n"
                            + prompts.get(leaves.lang(), "chat.turn.questionPrefix") + message;
                }

                // 轮开始清零，划出本轮账本的起点
                leaves.deep().reset();
                ChatTurnRunner.TurnResult result = turnRunner.run(leaves, userId, sessionId, enriched, intent,
                        this::onAnswerChunk, this::onSearch, cancel);

                if (result.cancelled()) {
                    finishCancelled(result.pending());
                } else {
                    sendHitlCardIfAny();
                    finishAnswered(result.pending(), result.context());
                }
            } catch (Exception e) {
                log.error("[Workbench] 对话失败 sessionId={}", sessionId, e);
                if (!channel.isClosed()) {
                    channel.send("error", MAPPER.createObjectNode()
                            .put("message", LlmErrorMessages.classify(e, prompts, leaves.lang())));
                    // 正常收尾而非 completeWithError：原因已随上面的 error 事件发出去了，
                    // 再把异常抛回 MVC 只会让 GlobalExceptionHandler 往 event-stream 里写 JSON，
                    // 撞 HttpMessageNotWritableException，反而把真实错误盖掉
                    channel.complete();
                }
            } finally {
                heartbeat.cancel(false);
                runRegistry.finish(sessionId);
            }
        }

        /** 攒答案在断连判断之外：断连后这轮照跑完，答案仍要进历史，只是不再往已经断掉的通道里写帧 */
        private void onAnswerChunk(String chunk) {
            if (answer.isEmpty() && !prefix.isEmpty() && !channel.isClosed()) {
                channel.send("token", answerToken(prefix));
            }
            answer.append(chunk);
            if (!channel.isClosed()) {
                channel.send("token", answerToken(chunk));
            }
        }

        /** 搜索过程逐条外发（过程轨画"正在搜索/搜索了 N 个网站"），来源攒起来 */
        private void onSearch(SearchEvent event) {
            for (SearchEvent.Source source : event.sources()) {
                if (source.url() != null) {
                    sources.putIfAbsent(source.url(), source);
                }
            }
            ObjectNode json = event.toJsonObject();
            // 引用只并入来源，不是过程轨的一步
            if (!"cited".equals(json.path("phase").asString(null))) {
                steps.add(json.deepCopy().put("type", "search"));
            }
            if (!channel.isClosed()) {
                channel.send("search", json);
            }
        }

        /** 答案全文：唤醒轮带标头，一个字没出就不带（空产出不落行） */
        private String fullAnswer() {
            return answer.isEmpty() ? "" : prefix + answer;
        }

        /**
         * 用户点了停止：半截答案照落库（token 已经烧掉了，屏幕上那段也该留得住），done 收尾即完结。
         */
        private void finishCancelled(boolean pending) {
            String stopped = ChatTurnRunner.cancelledAnswer(prompts, leaves.lang(), fullAnswer());
            // 中断的轮不报上下文用量：工具过程没落库，轮中途测到的偏大
            ChatHistoryService.TurnMeta meta = turnMeta(null);
            boolean saved = chatHistoryService.append(sessionId, userId, "assistant", stopped, meta, sourceList(), stepsOrNull());
            // 重生成轮：出了半截才顶掉旧答案，半截也是这一次重生成的产物，留着旧的同一个提问下
            // 就是两条 assistant 行。一个字都没出（点得快）就别删——那等于拿一行"未作答"
            // 换掉用户原来那条好答案，而且不可恢复
            if (replacedAnswerId != null && saved && !answer.isEmpty()) {
                chatHistoryService.deleteMessage(replacedAnswerId);
            }
            sendDone(MAPPER.createObjectNode()
                    .put("sessionId", sessionId)
                    .put("answer", stopped)
                    .put("cancelled", true)
                    .put("pending", pending)
                    .set("meta", metaJson(meta))
                    .set("sources", SearchEvent.Source.toJson(sourceList())));
        }

        /**
         * HITL：本轮 agent 触发了贵操作待确认 → 弹确认卡（approve 后前端自动补发继续指令）。
         * 只发本轮新登记的那张：pending 是 approve/reject 才摘的，用户不点、接着问下一个问题的话
         * 它会一直躺在那儿——不筛的话每轮结束都再弹一遍同一张卡。
         * 筛"本轮新登记"而不是"发完就删"：删了用户回头点那张旧卡就成了"已失效"，
         * 而他点的其实是唯一还在服务端挂着的那条请求，照批是对的。
         */
        private void sendHitlCardIfAny() {
            approvalRegistry.peekPending(sessionId)
                    .filter(pendingRequest -> pendingRequest.seq() > approvalSeqAtStart)
                    .ifPresent(pendingRequest -> channel.send("hitl_request", MAPPER.createObjectNode()
                            .put("sessionId", sessionId)
                            .put("symbol", pendingRequest.symbol())
                            .put("reason", pendingRequest.reason())
                            .put("requestId", pendingRequest.requestId())
                            .put("resumeMessage",
                                    prompts.get(leaves.lang(), "chat.hitl.resumeMessage"))));
        }

        /** 正常作答收尾：答案落库、顶掉旧答案（重生成轮）、发 done。 */
        private void finishAnswered(boolean pending, ContextUsage context) {
            String finalAnswer = fullAnswer();
            ChatHistoryService.TurnMeta meta = turnMeta(context);
            // 历史不看连接死活：切页断连后这一轮照跑完，答案必须落库（前端回来靠 status+历史补）。
            // 且必须在 finally 摘运行标记之前写完——轮询端不能出现"已结束但查不到答案"的空窗
            boolean saved = chatHistoryService.append(sessionId, userId, "assistant", finalAnswer, meta, sourceList(), stepsOrNull());
            // 旧答案留到新答案确实落库之后才删（append 落没落库看返回值，空产出压根不落行）：
            // 重跑抛异常、产出为空、insert 失败的任何一条路上，用户至少还留着原来那条，
            // 也还能再点一次重新生成——两条都没了的话末尾是 user 行，连重新生成都点不了
            if (replacedAnswerId != null && saved) {
                chatHistoryService.deleteMessage(replacedAnswerId);
            }
            sendDone(MAPPER.createObjectNode()
                    .put("sessionId", sessionId)
                    .put("answer", finalAnswer)
                    .put("pending", pending)
                    .set("meta", metaJson(meta))
                    .set("sources", SearchEvent.Source.toJson(sourceList())));
        }

        /** done 是本轮最后一帧，发完就收口通道；断掉的通道什么都不写 */
        private void sendDone(ObjectNode done) {
            if (channel.isClosed()) {
                return;
            }
            channel.send("done", done);
            channel.complete();
        }

        private List<SearchEvent.Source> sourceList() {
            return new ArrayList<>(sources.values());
        }

        /** 一步过程都没有的轮不落这一列 */
        private ArrayNode stepsOrNull() {
            return steps.isEmpty() ? null : steps;
        }

        /** 本轮读数。中断丢下的在途流会在读数之后才入账，账本不可信时只报耗时（null=没报，与全站 token 语义一致） */
        private ChatHistoryService.TurnMeta turnMeta(ContextUsage context) {
            int latencyMs = (int) (System.currentTimeMillis() - startedAt);
            return leaves.deep().untrusted()
                    ? ChatHistoryService.TurnMeta.latencyOnly(leaves.modelLabel(), latencyMs, context)
                    : ChatHistoryService.TurnMeta.of(leaves.modelLabel(), leaves.deep().snapshot(), latencyMs, context);
        }
    }

    /**
     * 读数 → SSE 字段，字段名与历史回放的 meta 一一对应。
     * 形状有一处不同：这里走 JsonUtils.MAPPER 不输出 null，没报的项是<b>缺席</b>，
     * 而历史接口走 Spring 的 HTTP 出站会输出 {@code null}——前端两边都按"取不到值=没报"判，不要判 0。
     */
    private static ObjectNode metaJson(ChatHistoryService.TurnMeta meta) {
        ObjectNode json = MAPPER.createObjectNode()
                .put("modelLabel", meta.modelLabel())
                .put("modelCalls", meta.modelCalls())
                .put("promptTokens", meta.promptTokens())
                .put("completionTokens", meta.completionTokens())
                .put("totalTokens", meta.totalTokens())
                .put("latencyMs", meta.latencyMs());
        if (meta.context() != null) {
            json.set("context", meta.context().toJson());
        }
        return json;
    }

    /** 答案流的一帧。agent=supervisor / role=answer 是既有前端事件契约 */
    private static ObjectNode answerToken(String text) {
        return MAPPER.createObjectNode()
                .put("text", text)
                .put("agent", "supervisor")
                .put("role", "answer");
    }
}
