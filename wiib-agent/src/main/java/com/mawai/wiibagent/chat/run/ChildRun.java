package com.mawai.wiibagent.chat.run;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import org.springframework.ai.chat.messages.AssistantMessage;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 一个子运行：主 agent 派出去、在后台异步跑、报告以消息回灌会话的那件活。
 * 固定流水线（深研判）与动态派发（delegate）都是它，区别只在任务书是代码写死还是模型现写。
 * <p>
 * 它自己记着结局和内部每一步，status 拿去给前端补过程轨。
 */
public final class ChildRun {

    public enum Kind { DELEGATE, DEEP_ANALYSIS }

    public enum Status { RUNNING, DONE, FAILED, CANCELLED }

    /** 内部的一步：kind=tool 是它调的工具（id/name/args），kind=phase 是它报的阶段文案（text）；active=做着 */
    public record Step(String kind, String text, String id, String name, ObjectNode args, boolean active) {
    }

    /** 给前端的快照（status 接口原样下发） */
    public record View(String runId, Kind kind, String label, String brief, long startedAt, Long endedAt,
                       Status status, String error, List<Step> steps) {
    }

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
    private Long endedAt;
    /** 失败原因（给用户看的那句） */
    private String error;
    /** 内部每一步，按发生先后 */
    private final List<Step> steps = new ArrayList<>();
    /** 最后一批从第几步起：还在跑时这一批算做着 */
    private int lastBatchFrom;
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

    public synchronized String error() {
        return error;
    }

    public CompletableFuture<Void> cancel() {
        return cancel;
    }

    public boolean cancelled() {
        return cancel.isDone();
    }

    /** 跑完：DONE 或 FAILED，失败带原因 */
    synchronized void end(Status status, String error) {
        this.status = status;
        this.error = error;
        this.endedAt = System.currentTimeMillis();
    }

    /** 标记取消：状态立刻变、信号立刻发，跑着的循环在下一个检查点停 */
    synchronized void markCancelled() {
        status = Status.CANCELLED;
        endedAt = System.currentTimeMillis();
        cancel.complete(null);
    }

    /** 报了一个阶段（深研判的 Bull/Bear/Judge 等） */
    synchronized void addPhase(String text) {
        lastBatchFrom = steps.size();
        steps.add(new Step("phase", text, null, null, null, false));
    }

    /** 要调一批工具 */
    synchronized void addToolCalls(List<AssistantMessage.ToolCall> calls) {
        lastBatchFrom = steps.size();
        for (AssistantMessage.ToolCall call : calls) {
            steps.add(new Step("tool", null, call.id(), call.name(), WorkbenchRunRegistry.argsOf(call), false));
        }
    }

    public synchronized View view() {
        List<Step> shown = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            shown.add(status == Status.RUNNING && i >= lastBatchFrom
                    ? new Step(s.kind(), s.text(), s.id(), s.name(), s.args(), true) : s);
        }
        return new View(id, kind, label, brief, startedAt, endedAt, status, error, shown);
    }
}
