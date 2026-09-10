package com.mawai.wiibagent.chat;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话让位协调器：让"用户的新消息"优先于"子 agent（专家）的返回"。
 * <p>
 * 在跑的轮正处专家等待期时新消息到达：{@link #requestYield} 完成让位信号 → 那轮立即收尾
 * （见 {@link ChatTurnRunner} 的让位路径），新消息抢到名额正常应答；被让位的问题连同在途
 * 专家批次经 {@link #registerDeferred} 排队。<b>补答由前端发起</b>：status 口径含
 * {@link #hasPending}，前端在本地没有轮在跑、排队消息也发完之后请求补答轮，
 * 后端 {@link #takeDeferred} 出队头交给一个普通轮接回（{@link ChatTurnRunner#run} 的 deferred 参数）。
 * 后端自己不找空档跑补答：没有客户端在场的答案没人看，不该花钱生成。
 * <p>
 * 优先级规则就一条：<b>用户消息永远插队，专家结果永远排队</b>——补答轮撞上占线只会被拒，
 * 从不反过来挤走用户的轮。
 * <p>
 * 队列是进程内存级（与 {@link WorkbenchRunRegistry} 同哲学）：单实例部署，重启即丢，
 * 代价是让位后重启那个问题没有补答——历史里问题仍在，重问即可。
 */
@Component
public class ChatYieldCoordinator {

    /** 让位握手上限：从发信号到那轮退位还名额，正常几百毫秒（存档+发收尾事件）；超时按占线拒 */
    public static final long YIELD_HANDSHAKE_MS = 15_000;

    /** userId → 在跑轮的让位句柄（闸门每用户 1 轮，键天然唯一） */
    private final Map<Long, TurnHandle> activeTurns = new ConcurrentHashMap<>();
    /** sessionId → 待补答队列（FIFO）。一次让位欠一单，补答轮再被让位就再排一单 */
    private final Map<String, Queue<DeferredWork>> pendingBySession = new ConcurrentHashMap<>();

    /**
     * 一单欠下的补答：原问题 + 让位时交出去的在途专家批次。
     * 不存叶子：补答轮按发起时的模型配置重新建叶子，中途换过模型就用新的。
     */
    public record DeferredWork(long userId, String sessionId, String question, ChatTurnRunner.ExpertBatch batch) {
    }

    /**
     * 一轮的让位句柄：runner 经 {@link ChatTurnRunner.TurnYield} 面向它开关让位窗口，
     * 新消息的请求线程经 {@link #requestYield} 扣它的扳机。
     */
    public static final class TurnHandle implements ChatTurnRunner.TurnYield {
        private final long userId;
        /** false=这一轮不开让位窗口，新消息只能按占线拒、回落前端排队 */
        private final boolean preemptible;
        private final CompletableFuture<Void> yieldSignal = new CompletableFuture<>();
        /** 本轮完全结束（名额已还）：让位等待者以它为"可以抢名额了"的发令枪 */
        private final CompletableFuture<Void> turnDone = new CompletableFuture<>();
        /** 让位窗口开关（写：runner 线程；读：新消息的请求线程） */
        private volatile boolean yieldAble = false;
        /** 用户点了停止（写：请求线程；读：runner 线程）。粘滞，跑到下一个检查点就收尾 */
        private volatile boolean cancelled = false;
        /** 中断信号：专家等待期阻塞在 anyOf 上，只有 future 能把它叫醒，光有布尔叫不醒 */
        private final CompletableFuture<Void> cancelSignal = new CompletableFuture<>();

        private TurnHandle(long userId, boolean preemptible) {
            this.userId = userId;
            this.preemptible = preemptible;
        }

        @Override
        public CompletableFuture<Void> enterExpertWait() {
            yieldAble = preemptible;
            return yieldSignal;
        }

        @Override
        public void exitExpertWait() {
            yieldAble = false;
        }

        @Override
        public boolean yieldRequested() {
            return yieldSignal.isDone();
        }

        @Override
        public boolean cancelRequested() {
            return cancelled;
        }

        @Override
        public CompletableFuture<Void> cancelSignal() {
            return cancelSignal;
        }
    }

    /**
     * 用户请求中断在跑的那一轮。没有轮在跑（已经结束了）返回 false，让调用方如实告诉用户按钮点晚了。
     * <p>
     * 与 {@link #requestYield} 的区别：让位是"这个问题稍后补答"，中断是"到此为止不补"，
     * 所以这里不动 yieldSignal、也不排补答队列。
     */
    public boolean requestCancel(long userId) {
        TurnHandle handle = activeTurns.get(userId);
        if (handle == null) {
            return false;
        }
        handle.cancelled = true;
        handle.cancelSignal.complete(null);   // 布尔叫不醒专家等待期那一等，得靠它
        return true;
    }

    /** 一轮开跑前登记（拿到名额之后、提交执行之前）。 */
    public TurnHandle openTurn(long userId) {
        return openTurn(userId, true);
    }

    /**
     * @param preemptible false=这一轮不许被新消息挤走。重新生成轮要的就是它：
     *                    它把旧答案的位置腾了出来，被挤掉的话新答案只能由补答轮
     *                    以【补答】标头追加到会话末尾，位置错、还再也不能重新生成
     */
    public TurnHandle openTurn(long userId, boolean preemptible) {
        TurnHandle handle = new TurnHandle(userId, preemptible);
        activeTurns.put(userId, handle);
        return handle;
    }

    /**
     * 一轮完全结束。<b>必须在名额归还之后调</b>：turnDone 是让位等待者抢名额的发令枪，
     * 名额还没还就开枪，等待者抢到的必然是 USER_BUSY。
     */
    public void closeTurn(TurnHandle handle) {
        activeTurns.remove(handle.userId, handle);
        handle.turnDone.complete(null);
    }

    /**
     * 新消息撞上名额占用时的让位请求：在跑轮处于专家等待期 → 发让位信号，
     * 返回"那轮完全结束"的 future（等它再抢名额）；窗口没开（路由/汇总中）返回 null，按占线拒。
     */
    public CompletableFuture<Void> requestYield(long userId) {
        TurnHandle handle = activeTurns.get(userId);
        if (handle == null || !handle.yieldAble) {
            return null;
        }
        handle.yieldSignal.complete(null);
        return handle.turnDone;
    }

    /** 让位轮把欠的账记上：在途专家批次进队，等前端发起补答轮来取。 */
    public void registerDeferred(long userId, String sessionId, String question, ChatTurnRunner.ExpertBatch batch) {
        DeferredWork work = new DeferredWork(userId, sessionId, question, batch);
        // 入队与 takeDeferred 的"取空即删"都在 compute 里做：同一把键锁下，空队列不会在别人刚拿到引用时被摘掉
        pendingBySession.compute(sessionId, (k, queue) -> {
            Queue<DeferredWork> q = queue == null ? new ConcurrentLinkedQueue<>() : queue;
            q.add(work);
            return q;
        });
    }

    /**
     * 补答轮出队头。<b>拿到名额之后才调</b>：名额每用户 1 个，两个标签页同时请求补答
     * 只有一个拿得到名额，也就只有一个取得走这一单。
     */
    public Optional<DeferredWork> takeDeferred(String sessionId) {
        AtomicReference<DeferredWork> head = new AtomicReference<>();
        pendingBySession.computeIfPresent(sessionId, (k, queue) -> {
            head.set(queue.poll());
            return queue.isEmpty() ? null : queue;
        });
        return Optional.ofNullable(head.get());
    }

    /** 会话有没有欠着的补答（status 口径的另一半：前端据此在空闲时发起补答轮） */
    public boolean hasPending(String sessionId) {
        Queue<DeferredWork> queue = pendingBySession.get(sessionId);
        return queue != null && !queue.isEmpty();
    }

    /**
     * 这个用户还有没有跑不完的在途专家批次——有的话，用量账本上就混着不属于当前这一轮的 token。
     * <p>
     * 让位交出去的那批专家<b>没人取消</b>（{@link ChatTurnRunner} 的 dispatchAsync 是虚拟线程 fire-and-forget），
     * 它能跨过好几轮继续往同一份账本上记账。所以"这一轮的用量可不可信"要看它，
     * 而不是看"这一轮的名额是不是从谁手里抢来的"——后者只盖得住紧邻的那一轮。
     * <p>
     * 批次 {@code isDone()} 为真时账已经写完：专家走的是阻塞 invoke，模型调用在 future 完成前就返回了。
     */
    public boolean hasInFlightExperts(long userId) {
        return pendingBySession.values().stream()
                .flatMap(Queue::stream)
                .anyMatch(work -> work.userId() == userId && !work.batch().isDone());
    }
}
