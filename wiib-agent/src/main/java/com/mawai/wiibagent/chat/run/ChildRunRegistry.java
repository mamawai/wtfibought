package com.mawai.wiibagent.chat.run;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.LlmErrorMessages;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 子运行注册表：会话里有哪些子运行在跑、报告回来往哪儿放、谁来唤醒。
 * <p>
 * 报告回灌只有一条规则（{@link #deliver}）：会话正挂着一轮（{@link #attachTurn}）就塞进那轮的收件箱，
 * 下一次调模型前排进上下文；没挂就直接追加进会话上下文并标 {@code pending}，前端空闲时发起唤醒轮。
 * 分这两条路和轮结束的保存（{@link #settle}）都在会话锁里：{@link ChatContextStore#save} 是整体覆盖，
 * 报告在锁外写库会被轮结束时的保存抹掉。
 * <p>
 * 进程内存级，与 {@link WorkbenchRunRegistry} 同哲学：重启丢在跑的子运行与 pending 标记，已落库的报告还在上下文里。
 */
@Slf4j
@Component
public class ChildRunRegistry {

    /** 派发请求 */
    public record Spec(String sessionId, long userId, ChildRun.Kind kind, String label, String brief, AgentLang lang) {
    }

    /** 派发结果：起了就有 run；拒了就有拒因（原样回给模型） */
    public record Spawn(ChildRun run, String refusal) {
        public boolean started() {
            return run != null;
        }
    }

    /** 会话的子运行状况：在跑几个、有没有没答过的报告 */
    public record SessionState(int running, boolean pending) {
    }

    /** 一个会话的现场 */
    private static final class Session {
        /** 在跑的子运行，id → run；结束的立刻摘掉。并发表：数在跑数不拿会话锁 */
        final Map<String, ChildRun> running = new ConcurrentHashMap<>();
        /** 非空=有轮挂着，报告进它 */
        Deque<Message> inbox;
        /** 有报告落在上下文里、还没有哪一轮答过 */
        boolean pending;
    }

    /** 子运行跑在上面；主体阻塞在模型与工具的 IO 上 */
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ChatContextStore contextStore;
    private final WorkbenchRunRegistry runRegistry;
    private final WorkbenchPrefService prefs;
    private final PromptCatalog prompts;
    /** 全站同时在跑的子运行上限：行情配额按出口 IP 算，子运行不占对话名额，得另有一道闸 */
    private final Semaphore globalSlots;
    private final long timeoutSeconds;
    /** 报告正文回灌主 agent 的字数上限 */
    private final int reportMaxChars;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    public ChildRunRegistry(ChatContextStore contextStore, WorkbenchRunRegistry runRegistry,
                            WorkbenchPrefService prefs, PromptCatalog prompts,
                            @Value("${agent.workbench.max-concurrent-children:20}") int maxConcurrentChildren,
                            @Value("${agent.workbench.child-timeout-seconds:300}") long timeoutSeconds,
                            @Value("${agent.workbench.child-report-max-chars:4000}") int reportMaxChars) {
        this.contextStore = contextStore;
        this.runRegistry = runRegistry;
        this.prefs = prefs;
        this.prompts = prompts;
        this.globalSlots = new Semaphore(maxConcurrentChildren);
        this.timeoutSeconds = timeoutSeconds;
        this.reportMaxChars = reportMaxChars;
    }

    /**
     * 派一个子运行。三道检查按序：用户开关（只管 delegate，深研判是用户点头过的）→ 单用户在跑数 → 全局信号量；
     * 过了就登记并立即提交执行，调用方马上拿到 run。
     *
     * @param body 子运行主体，返回报告正文；抛出即失败。主体要看 {@link ChildRun#cancel()}，取消了就早点退出
     */
    public Spawn spawn(Spec spec, Function<ChildRun, String> body) {
        Objects.requireNonNull(spec.sessionId(), "子运行必须挂在会话上");
        WorkbenchPrefService.Prefs userPrefs = prefs.of(spec.userId());
        if (spec.kind() == ChildRun.Kind.DELEGATE && !userPrefs.subAgentsEnabled()) {
            return new Spawn(null, prompts.get(spec.lang(), "chat.childRun.refused.disabled"));
        }
        ChildRun run;
        // 数在跑的和登记一气做完：同一批 tool_call 并发派几个，不然都数到旧值一起过闸
        synchronized (this) {
            if (runningOf(spec.userId()) >= userPrefs.subAgentLimit()) {
                return new Spawn(null, prompts.get(spec.lang(), "chat.childRun.refused.limit",
                        Map.of("limit", userPrefs.subAgentLimit())));
            }
            if (!globalSlots.tryAcquire()) {
                return new Spawn(null, prompts.get(spec.lang(), "chat.childRun.refused.busy"));
            }
            run = new ChildRun(String.valueOf(seq.incrementAndGet()), spec.sessionId(), spec.userId(),
                    spec.kind(), spec.label(), spec.brief(), spec.lang());
            Session session = sessions.computeIfAbsent(spec.sessionId(), k -> new Session());
            synchronized (session) {
                session.running.put(run.id(), run);
            }
        }
        log.info("[ChildRun] 派发 #{} session={} kind={} label={}", run.id(), run.sessionId(), run.kind(), run.label());
        runRegistry.publishChildStart(run.sessionId(), run);
        CompletableFuture.supplyAsync(() -> body.apply(run), EXECUTOR)
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .whenComplete((text, err) -> finish(run, text, err));
        return new Spawn(run, null);
    }

    /** 一轮开跑：挂上收件箱。要在读历史<b>之前</b>挂——之后到的报告进收件箱，之前到的已经在库里、随历史读进来 */
    public void attachTurn(String sessionId, Deque<Message> inbox) {
        Session session = sessions.computeIfAbsent(sessionId, k -> new Session());
        synchronized (session) {
            session.inbox = inbox;
            session.pending = false;    // 落库的报告这一轮读得到，不再欠唤醒
        }
    }

    /**
     * 一轮结束（每条出路都走这里）：摘掉收件箱，剩余报告并进终态消息，整体覆盖会话历史。
     * 摘和存在同一把锁里：这当口到的报告要么还赶上进收件箱随这次一起落库，要么排在这次之后自己落库。
     *
     * @return 剩余报告非空——这些报告这轮没答过，欠一次唤醒
     */
    public boolean settle(String sessionId, long userId, List<Message> messages) {
        Session session = sessions.computeIfAbsent(sessionId, k -> new Session());
        synchronized (session) {
            List<Message> leftover = session.inbox == null ? List.of() : List.copyOf(session.inbox);
            session.inbox = null;
            messages.addAll(leftover);
            contextStore.save(sessionId, userId, messages);
            session.pending = !leftover.isEmpty();
            return session.pending;
        }
    }

    public SessionState state(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            return new SessionState(0, false);
        }
        synchronized (session) {
            return new SessionState(session.running.size(), session.pending);
        }
    }

    /** 停掉这个会话全部在跑的子运行（停止按钮、重新生成）；它们的报告不再回灌 */
    public void cancelAll(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        synchronized (session) {
            session.running.values().forEach(ChildRun::markCancelled);
            session.running.clear();
        }
    }

    /** 删会话：停掉在跑的，现场一并清 */
    public void purge(String sessionId) {
        cancelAll(sessionId);
        sessions.remove(sessionId);
    }

    /** 不拿会话锁：会话锁里有落库，一个会话的慢保存不该卡住全站的派发。刚结束还没摘的多数一个无妨 */
    private int runningOf(long userId) {
        int n = 0;
        for (Session session : sessions.values()) {
            for (ChildRun run : session.running.values()) {
                if (run.userId() == userId) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 主体跑完：取消的直接丢；否则拼报告回灌，再推 child_done */
    private void finish(ChildRun run, String text, Throwable err) {
        globalSlots.release();
        Session session = sessions.get(run.sessionId());
        if (run.status() == ChildRun.Status.CANCELLED) {
            runRegistry.publishChildDone(run.sessionId(), run, null);
            return;
        }
        Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
        boolean failed = cause != null;
        if (cause instanceof TimeoutException) {
            run.cancel().complete(null);    // 主体还在跑的话让它停下来
        }
        String body = failed ? failureText(run, cause) : clip(text);
        String status = prompts.get(run.lang(), failed ? "chat.childRun.status.failed" : "chat.childRun.status.done");
        Message report = new UserMessage(prompts.get(run.lang(), "chat.childRun.tag",
                Map.of("id", run.id(), "label", run.label(), "status", status)) + "\n" + body);
        if (failed) {
            log.warn("[ChildRun] #{} 失败 session={} label={}", run.id(), run.sessionId(), run.label(), cause);
        } else {
            log.info("[ChildRun] #{} 完成 session={} label={} 报告{}字", run.id(), run.sessionId(), run.label(), body.length());
        }
        deliver(session, run, report);
        run.status(failed ? ChildRun.Status.FAILED : ChildRun.Status.DONE);
        synchronized (session) {
            session.running.remove(run.id());
        }
        runRegistry.publishChildDone(run.sessionId(), run, failed ? body : null);
    }

    private void deliver(Session session, ChildRun run, Message report) {
        synchronized (session) {
            if (session.inbox != null) {
                session.inbox.add(report);
                return;
            }
            // 没轮在跑：直接追加进会话上下文。整体覆盖式存储，读-加-写要在锁里
            List<Message> messages = new ArrayList<>(contextStore.load(run.sessionId()));
            messages.add(report);
            contextStore.save(run.sessionId(), run.userId(), messages);
            session.pending = true;
        }
    }

    private String failureText(ChildRun run, Throwable cause) {
        return cause instanceof TimeoutException
                ? prompts.get(run.lang(), "chat.childRun.timeout")
                : LlmErrorMessages.classify(cause, prompts, run.lang());
    }

    private String clip(String text) {
        String body = text == null ? "" : text;
        return body.length() <= reportMaxChars ? body : body.substring(0, reportMaxChars) + "…";
    }

    /** 把收件箱全部取出（给 ReactLoop 的 Listener.inbox 用） */
    public static List<Message> drain(Deque<Message> inbox) {
        List<Message> arrived = new ArrayList<>();
        for (Message m; (m = inbox.poll()) != null; ) {
            arrived.add(m);
        }
        return arrived;
    }
}
