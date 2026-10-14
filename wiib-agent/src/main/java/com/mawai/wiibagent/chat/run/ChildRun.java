package com.mawai.wiibagent.chat.run;

import com.mawai.wiibcommon.enums.AgentLang;

import java.util.concurrent.CompletableFuture;

/**
 * 一个子运行：主 agent 派出去、在后台异步跑、报告以消息回灌会话的那件活。
 * 固定流水线（深研判）与动态派发（delegate）都是它，区别只在任务书是代码写死还是模型现写。
 */
public final class ChildRun {

    public enum Kind { DELEGATE, DEEP_ANALYSIS }

    public enum Status { RUNNING, DONE, FAILED, CANCELLED }

    private final String id;
    private final String sessionId;
    private final long userId;
    private final Kind kind;
    /** 过程轨与报告标注里显示的名字，如"深研判 BTCUSDT" */
    private final String label;
    /** 任务书，delegate 才有 */
    private final String brief;
    private final AgentLang lang;
    private final long startedAt = System.currentTimeMillis();
    private volatile Status status = Status.RUNNING;
    /** 取消信号：/cancel、重新生成、删会话、超时都完成它；子运行的循环靠它停 */
    private final CompletableFuture<Void> cancel = new CompletableFuture<>();

    public ChildRun(String id, String sessionId, long userId, Kind kind, String label, String brief, AgentLang lang) {
        this.id = id;
        this.sessionId = sessionId;
        this.userId = userId;
        this.kind = kind;
        this.label = label;
        this.brief = brief;
        this.lang = lang;
    }

    public String id() {
        return id;
    }

    public String sessionId() {
        return sessionId;
    }

    public long userId() {
        return userId;
    }

    public Kind kind() {
        return kind;
    }

    public String label() {
        return label;
    }

    public String brief() {
        return brief;
    }

    public AgentLang lang() {
        return lang;
    }

    public long startedAt() {
        return startedAt;
    }

    public Status status() {
        return status;
    }

    void status(Status status) {
        this.status = status;
    }

    public CompletableFuture<Void> cancel() {
        return cancel;
    }

    public boolean cancelled() {
        return cancel.isDone();
    }

    /** 标记取消：状态立刻变、信号立刻发，跑着的循环在下一个检查点停 */
    void markCancelled() {
        status = Status.CANCELLED;
        cancel.complete(null);
    }
}
