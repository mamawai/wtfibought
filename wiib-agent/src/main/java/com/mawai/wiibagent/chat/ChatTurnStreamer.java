package com.mawai.wiibagent.chat;

import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.store.ChatHistoryService;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.LlmErrorMessages;
import com.mawai.wiibagent.llm.SearchEvent;
import com.mawai.wiibagent.llm.SseChannel;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 一轮对话在 SSE 通道上的完整过程：登记运行、喂心跳、跑 {@link ChatTurnRunner}、按结局落库并发 done 帧。
 * /chat、/regenerate、/deferred 在 {@link ChatWorkbenchController} 做完准入后都交到 {@link #run}；
 * 名额与让位句柄的开闭归调用方。
 * <p>
 * 一轮三种结局，各自一个 finish 方法（见 {@link Turn}）：用户点停止——半截答案落库、不欠补答；
 * 专家等待期让位——答案欠着、在途批次交协调器排队；正常作答——可能先弹一张 HITL 确认卡。
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

    /** 深研判期间 SSE 通道会静默数分钟，nginx 默认 proxy_read_timeout 60s 会掐断——20s 一帧留 3 倍余量 */
    private static final long HEARTBEAT_SECONDS = 20;

    private final ChatTurnRunner turnRunner;
    private final ChatHistoryService chatHistoryService;
    private final WorkbenchRunRegistry runRegistry;
    private final ChatYieldCoordinator yieldCoordinator;
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
     * 跑一轮到底。
     *
     * @param replacedAnswerId null=普通轮；非空=重新生成轮，它在顶替这条旧答案——提问已在库里不再落一遍
     * @param deferred         非空=补答轮，message 是被让位的原问题，这批是替它派的专家
     */
    public void run(SseChannel channel, long userId, String sessionId, String message,
                    ChatAgentFactory.Leaves leaves, ChatYieldCoordinator.TurnHandle handle, Long replacedAnswerId,
                    ChatIntent intent, ChatTurnRunner.ExpertBatch deferred) {
        new Turn(channel, userId, sessionId, message, leaves, handle, replacedAnswerId, intent, deferred).run();
    }

    /** 一轮的执行现场：入参与跑出来的中间量都在这儿，各种结局收尾时直接取用。 */
    @RequiredArgsConstructor
    private final class Turn {
        private final SseChannel channel;
        private final long userId;
        private final String sessionId;
        private final String message;
        private final ChatAgentFactory.Leaves leaves;
        private final ChatYieldCoordinator.TurnHandle handle;
        private final Long replacedAnswerId;
        private final ChatIntent intent;
        private final ChatTurnRunner.ExpertBatch deferred;

        private final long startedAt = System.currentTimeMillis();
        /** 轮开始时的确认卡登记水位：只发本轮新登记的卡。序号不是墙钟，毫秒粒度挤不出误判 */
        private final long approvalSeqAtStart = approvalRegistry.currentSeq();
        // 答案流/过程流分离：专家的结论是"工作过程"（前端折叠展示、不落历史），只有 summarizer 的汇总才是答案
        private final StringBuilder answer = new StringBuilder();
        private final StringBuilder expertLog = new StringBuilder();
        /** 补答标头：随首个答案 chunk 推出，落库时拼在答案前面；普通轮为空串 */
        private String prefix = "";
        /** 账本里混着别轮在途专家的账：本轮只报耗时不报 token */
        private boolean dirtyBook;
        /** 本轮搜到/引用到的来源，按 url 去重保序：随 done 下发并落库，刷新后答案底部还在 */
        private final Map<String, SearchEvent.Source> sources = new LinkedHashMap<>();

        void run() {
            ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleWithFixedDelay(
                    channel::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
            try {
                // 运行中标记：status 接口、拒删会话都看它
                // 事件出口：工具手里只有 sessionId，进度/表单卡经这转成 SSE 帧
                runRegistry.start(sessionId, (event, data) -> {
                    if (channel.isClosed()) {
                        return false;
                    }
                    channel.send(event, data);
                    return true;
                });
                channel.send("session", new JSONObject().fluentPut("sessionId", sessionId));
                // 用户重新生成和补答不落库
                if (replacedAnswerId == null && deferred == null) {
                    chatHistoryService.append(sessionId, userId, "user", message);
                }

                // 补充时间信息。轮起始标记按语言取自词表（chat.turn.*）：重新生成靠它从尾部找到
                // "本轮提问"那条，认的一侧在 ChatTurnRewinder.cutAt，遍历全部语言
                String enriched = prompts.get(leaves.lang(), "chat.turn.timeMark",
                        Map.of("time", TIME_FMT.format(Instant.now()))) + "\n"
                        + (deferred == null
                        ? prompts.get(leaves.lang(), "chat.turn.questionPrefix") + message
                        : prompts.get(leaves.lang(), "chat.deferred.instruction", Map.of("question", message)));

                if (deferred != null) {
                    prefix = deferredHeader(leaves.lang(), message);
                }

                // dirtyBook = 有没有停不下来、会往这份账本上继续加 token 的在途专家，如果有就不报token计数
                dirtyBook = yieldCoordinator.hasInFlightExperts(userId) || (deferred != null && !deferred.isDone());
                leaves.resetUsage();

                ChatTurnRunner.TurnResult result = turnRunner.run(leaves, userId, sessionId, enriched, intent,
                        this::onAnswerChunk, this::onExpertProgress, this::onSearch, handle, deferred);

                if (result.cancelled()) {
                    finishCancelled();
                } else if (result.yielded()) {
                    finishYielded(result.deferredExperts());
                } else {
                    sendHitlCardIfAny();
                    finishAnswered();
                }
            } catch (Exception e) {
                log.error("[Workbench] 对话失败 sessionId={}", sessionId, e);
                if (!channel.isClosed()) {
                    channel.send("error", new JSONObject()
                            .fluentPut("message", LlmErrorMessages.classify(e, prompts, leaves.lang())));
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
            if (!channel.isClosed()) {
                channel.send("search", event.toJsonObject());
            }
        }

        /**
         * 给前端发一帧专家的进度
         */
        private void onExpertProgress(ChatTurnRunner.ExpertProgress event) {
            switch (event.phase()) {
                case ChatTurnRunner.ExpertProgress.START -> channel.send("agent_start", new JSONObject()
                        .fluentPut("node", event.agent())
                        .fluentPut("agent", event.agent()));
                case ChatTurnRunner.ExpertProgress.DONE -> {
                    if (event.text() != null && !event.text().isBlank()) {
                        expertLog.append(event.text());
                        channel.send("token", new JSONObject()
                                .fluentPut("text", event.text())
                                .fluentPut("agent", event.agent())
                                .fluentPut("role", "process"));
                    }
                }
                case ChatTurnRunner.ExpertProgress.ERROR -> channel.send("progress", new JSONObject()
                        .fluentPut("text", prompts.get(leaves.lang(), "chat.progress.expertFailed",
                                Map.of("agent", event.agent(), "reason", event.text()))));
                default -> log.warn("[Workbench] 未知专家进度阶段 {}", event.phase());
            }
        }

        /**
         * 用户点了停止：半截答案照落库（token 已经烧掉了，屏幕上那段也该留得住）。
         * 与让位不同，这一轮不欠补答，done 收尾即完结。
         */
        private void finishCancelled() {
            String stopped = ChatTurnRunner.cancelledAnswer(prompts, leaves.lang(), prefix + answer);
            ChatHistoryService.TurnMeta meta = turnMeta();
            boolean saved = chatHistoryService.append(sessionId, userId, "assistant", stopped, meta, sourceList());
            // 重生成轮：出了半截才顶掉旧答案，半截也是这一次重生成的产物，留着旧的同一个提问下
            // 就是两条 assistant 行。一个字都没出（点得快）就别删——那等于拿一行"未作答"
            // 换掉用户原来那条好答案，而且不可恢复
            if (replacedAnswerId != null && saved && !answer.isEmpty()) {
                chatHistoryService.deleteMessage(replacedAnswerId);
            }
            sendDone(new JSONObject()
                    .fluentPut("sessionId", sessionId)
                    .fluentPut("answer", stopped)
                    .fluentPut("cancelled", true)
                    .fluentPut("pending", yieldCoordinator.hasPending(sessionId))
                    .fluentPut("meta", metaJson(meta))
                    .fluentPut("sources", SearchEvent.Source.toJson(sourceList())));
        }

        /**
         * 让位收尾：答案欠着（记账给协调器排队），本轮不落 assistant 历史——补答轮会补齐。
         * 补答轮自己再被让位时 message 仍是原问题，再排的还是原问题那一单。
         * registerDeferred 必须在本轮结束（runRegistry.finish）之前：status 是 running/pending 两个口径，
         * 先摘运行标记再记账会闪出两者皆假的空窗，轮询端误判"已结束且不欠账"。
         * done 带 deferred 标记：前端据此记下欠账、空闲时发起补答轮，answer 只是过渡话术不进历史；
         * question 是被让位的原问题，前端按它把"稍后补答"的说明行挂到正确的提问名下。
         * 每种 done 都带 pending（此刻会话还欠不欠补答）：补答轮跑完后队列里可能还排着下一单。
         */
        private void finishYielded(ChatTurnRunner.ExpertBatch inFlight) {
            yieldCoordinator.registerDeferred(userId, sessionId, message, inFlight);
            sendDone(new JSONObject()
                    .fluentPut("sessionId", sessionId)
                    .fluentPut("deferred", true)
                    .fluentPut("question", message)
                    .fluentPut("pending", true)
                    .fluentPut("answer", prompts.get(leaves.lang(), "chat.yieldDoneAnswer")));
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
                    .ifPresent(pendingRequest -> channel.send("hitl_request", new JSONObject()
                            .fluentPut("sessionId", sessionId)
                            .fluentPut("symbol", pendingRequest.symbol())
                            .fluentPut("reason", pendingRequest.reason())
                            .fluentPut("requestId", pendingRequest.requestId())
                            .fluentPut("resumeMessage",
                                    prompts.get(leaves.lang(), "chat.hitl.resumeMessage"))));
        }

        /** 正常作答收尾：答案落库、顶掉旧答案（重生成轮）、发 done。 */
        private void finishAnswered() {
            // 极端场景（调用上限截停等）summarizer 没产出汇总，退专家结论，答案不至于丢
            String finalAnswer = prefix + (!answer.isEmpty() ? answer : expertLog);
            ChatHistoryService.TurnMeta meta = turnMeta();
            // 历史不看连接死活：切页断连后这一轮照跑完，答案必须落库（前端回来靠 status+历史补）。
            // 且必须在 finally 摘运行标记之前写完——轮询端不能出现"已结束但查不到答案"的空窗
            boolean saved = chatHistoryService.append(sessionId, userId, "assistant", finalAnswer, meta, sourceList());
            // 旧答案留到新答案确实落库之后才删（append 落没落库看返回值，空产出压根不落行）：
            // 重跑抛异常、产出为空、insert 失败、中途被让位的任何一条路上，用户至少还留着原来那条，
            // 也还能再点一次重新生成——两条都没了的话末尾是 user 行，连重新生成都点不了
            if (replacedAnswerId != null && saved) {
                chatHistoryService.deleteMessage(replacedAnswerId);
            }
            sendDone(new JSONObject()
                    .fluentPut("sessionId", sessionId)
                    .fluentPut("answer", finalAnswer)
                    .fluentPut("pending", yieldCoordinator.hasPending(sessionId))
                    .fluentPut("meta", metaJson(meta))
                    .fluentPut("sources", SearchEvent.Source.toJson(sourceList())));
        }

        /** done 是本轮最后一帧，发完就收口通道；断掉的通道什么都不写 */
        private void sendDone(JSONObject done) {
            if (channel.isClosed()) {
                return;
            }
            channel.send("done", done);
            channel.complete();
        }

        private List<SearchEvent.Source> sourceList() {
            return new ArrayList<>(sources.values());
        }

        /**
         * 本轮读数。账本被别轮的在途专家写脏时只报耗时：读到的数混着别人的账——
         * 宁可不报，也不能报个错的（null=没报，与全站 token 语义一致）。
         */
        private ChatHistoryService.TurnMeta turnMeta() {
            int latencyMs = (int) (System.currentTimeMillis() - startedAt);
            // usageUntrusted：中断丢下的在途流会在读数之后才入账，这一轮和下一轮的数都不可信
            return dirtyBook || leaves.usageUntrusted()
                    ? ChatHistoryService.TurnMeta.latencyOnly(leaves.modelLabel(), latencyMs)
                    : ChatHistoryService.TurnMeta.of(leaves.modelLabel(), leaves.usageSnapshot(), latencyMs);
        }
    }

    /**
     * 读数 → SSE 字段，字段名与历史回放的 meta 一一对应。
     * 形状有一处不同：fastjson2 默认不输出 null，所以没报的项在这里是<b>缺席</b>，
     * 而历史接口走 Jackson 会输出 {@code null}——前端两边都按"取不到值=没报"判，不要判 0。
     */
    private static JSONObject metaJson(ChatHistoryService.TurnMeta meta) {
        return new JSONObject()
                .fluentPut("modelLabel", meta.modelLabel())
                .fluentPut("modelCalls", meta.modelCalls())
                .fluentPut("promptTokens", meta.promptTokens())
                .fluentPut("completionTokens", meta.completionTokens())
                .fluentPut("totalTokens", meta.totalTokens())
                .fluentPut("latencyMs", meta.latencyMs());
    }

    /** 答案流的一帧。agent=supervisor / role=answer 是既有前端事件契约 */
    private static JSONObject answerToken(String text) {
        return new JSONObject()
                .fluentPut("text", text)
                .fluentPut("agent", "supervisor")
                .fluentPut("role", "answer");
    }

    /** 补答标头：问题摘要截 40 字；与 chat.deferred.prefix 同源，前端按前缀认出补答行（不给重新生成） */
    private String deferredHeader(AgentLang lang, String question) {
        String q = question.strip().replaceAll("\\s+", " ");
        if (q.length() > 40) {
            q = q.substring(0, 40) + "…";
        }
        return prompts.get(lang, "chat.deferred.header", Map.of("question", q)) + "\n\n";
    }
}
