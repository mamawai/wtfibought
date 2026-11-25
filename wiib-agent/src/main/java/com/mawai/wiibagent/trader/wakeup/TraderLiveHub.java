package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibagent.llm.SseChannel;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.handover.Intake;
import com.mawai.wiibcommon.handover.Start;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * trader 唤醒现场的发布订阅：订阅者管理、扇出、心跳。
 * 按 traderId 订阅，只有主人连得进来（准入在 controller 判）；一次唤醒一个 {@link Run} 句柄，runner 只碰它。
 * 单实例部署，进程内 Map 即可。
 * <p>
 * 进活的口是"实时页订阅"：关口时把所有订阅连接完成掉，之后来的先接下再马上完成（不回业务错误），前端退避后重连。
 */
@Slf4j
@Component
@Order(10)
public class TraderLiveHub implements Intake {

    /** 帧出口：返回 false=通道已死，hub 摘掉它 */
    public interface Sink {
        boolean send(String event, ObjectNode data);
    }

    /** nginx 默认 proxy_read_timeout 60s 会掐静默连接，20s 一帧留 3 倍余量 */
    private static final long HEARTBEAT_SECONDS = 20;
    /** 订阅 30 分钟到点，前端自动重连 */
    private static final long SUBSCRIBE_TIMEOUT_MS = 30 * 60_000L;

    /** traderId → 在跑的几轮（整盘轮和各个币的单币轮可以同时在跑），end 后摘掉 */
    private final Map<Long, Set<Run>> runs = new ConcurrentHashMap<>();
    /** traderId → 订阅者；空闲时也挂着，等下一轮 run_start */
    private final Map<Long, Set<Sink>> traderSubscribers = new ConcurrentHashMap<>();
    /** 所有 SSE 通道，心跳用 */
    private final Set<SseChannel> channels = ConcurrentHashMap.newKeySet();
    /** 订阅的口。和登记通道在同一把锁（this）里改：要么登记在前、关口时一并完成，要么看到关着当场完成 */
    private boolean open;
    /** 心跳专用：只发注释帧，单线程够所有订阅者用；虚拟线程不支持定时调度故用平台线程 */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "trader-live-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public TraderLiveHub() {
        heartbeatScheduler.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /** 上下文收尾：停掉心跳线程 */
    @PreDestroy
    public void stop() {
        heartbeatScheduler.shutdownNow();
    }

    @Override
    public synchronized void open(Start how) {
        open = true;
    }

    /** 关口：把还活着的订阅连接逐个完成掉，哪个完成时报错就跳过它接着完成下一个 */
    @Override
    public synchronized void close() {
        open = false;
        for (SseChannel channel : channels) {
            if (channel.isClosed()) {
                continue;
            }
            try {
                channel.complete();
            } catch (Exception e) {
                log.debug("[TraderLive] 关口完成订阅连接失败: {}", e.toString());
            }
        }
    }

    private void heartbeat() {
        channels.removeIf(SseChannel::isClosed);
        channels.forEach(SseChannel::heartbeat);
    }

    // ========== 运行侧 ==========

    /**
     * 一轮唤醒开始：建轨迹、登记在跑、扇出 run_start
     *
     * @param symbol 这一轮只处理的那个币；整盘轮为 null
     */
    public Run begin(AiTrader trader, String kind, String symbol, long wakeTime, long budgetSeconds,
                     BigDecimal equity, int positions, int pendingOrders) {
        Run run = new Run(new WakeTrace(trader.getId(), kind, symbol, wakeTime, budgetSeconds, equity, positions, pendingOrders));
        runs.computeIfAbsent(trader.getId(), _ -> ConcurrentHashMap.newKeySet()).add(run);
        run.started();
        return run;
    }

    /**
     * 一次唤醒的现场句柄。每个方法：轨迹变更拿帧 → 发该 trader 的订阅者。
     * 方法全同步：帧序与订阅者中途接入的回放互斥。
     * 同一个 trader 可以有几轮同时在跑，每帧都带上是哪一轮的（run），订阅者按它分开。
     */
    public final class Run {
        /** 这一轮在现场流里的编号 */
        private final String id = UUID.randomUUID().toString();
        private final WakeTrace trace;
        /** finish 收好的 run_end 帧，end 补上 decisionId 才发 */
        private WakeTrace.Frame endFrame;

        private Run(WakeTrace trace) {
            this.trace = trace;
        }

        private synchronized void started() {
            fanOut(trace.runStart());
        }

        public synchronized void prompt(String system, String instruction) {
            emit(() -> trace.prompt(system, instruction));
        }

        public synchronized void callStart() {
            emit(trace::callStart);
        }

        /** 下一段开场白追加进了历史 */
        public synchronized void stage(String instruction) {
            emit(() -> trace.stage(instruction));
        }

        public synchronized void token(String text) {
            emit(() -> trace.token(text));
        }

        public synchronized void callEnd(String text, List<AssistantMessage.ToolCall> toolCalls) {
            emit(() -> trace.callEnd(text, toolCalls));
        }

        public synchronized void toolResult(String id, String name, String responseData) {
            emit(() -> trace.toolResult(id, name, responseData));
        }

        /** 保险丝补的占位回执：这次调用没执行 */
        public synchronized void toolSkipped(String id, String name, String responseData) {
            emit(() -> trace.toolSkipped(id, name, responseData));
        }

        /** 过程帧的统一出口：已 finish 的一轮不再收帧（超时后循环线程还会晚推几帧） */
        private void emit(Supplier<WakeTrace.Frame> change) {
            if (endFrame != null) {
                return;
            }
            fanOut(change.get());
        }

        /** 收尾写进轨迹，返回落库 JSON；run_end 帧先攒着，决策行落库拿到 id 再发 */
        public synchronized String finish(String status, String error, BigDecimal equity, Integer latencyMs,
                                          Integer modelCalls, Long totalTokens) {
            endFrame = trace.end(status, error, equity, latencyMs, modelCalls, totalTokens);
            return trace.toJson();
        }

        /** 决策行已落库：从在跑表摘掉、发 run_end（带 decisionId） */
        public synchronized void end(Long decisionId) {
            Set<Run> mine = runs.get(trace.traderId);
            if (mine != null) {
                mine.remove(this);
            }
            endFrame.data().put("decisionId", decisionId);
            fanOut(endFrame);
        }

        /** 中途接入：登记与回放在同一把锁里，实时帧插不进两者之间 */
        private synchronized void attachAndReplay(Sink sink) {
            subscribers(trace.traderId).add(sink);
            for (WakeTrace.Frame frame : trace.replay()) {
                if (notDeliver(sink, frame)) {
                    return;
                }
            }
        }

        private void fanOut(WakeTrace.Frame frame) {
            Set<Sink> subs = subscribers(trace.traderId);
            subs.removeIf(sink -> notDeliver(sink, frame));
        }

        private boolean notDeliver(Sink sink, WakeTrace.Frame frame) {
            return !sink.send(frame.event(), frame.data().put("run", id));
        }
    }

    // ========== 订阅侧 ==========

    /** 现场流：登记后立刻回放在跑的每一轮；空闲时什么都不发，只有心跳。口关着就当场完成 */
    public SseEmitter subscribeTrader(long traderId) {
        SseEmitter emitter = new SseEmitter(SUBSCRIBE_TIMEOUT_MS);
        SseChannel channel = new SseChannel(emitter);
        Sink sink = sinkOf(channel);
        synchronized (this) {
            if (!open) {
                emitter.complete();
                return emitter;
            }
            attach(emitter, channel, () -> subscribers(traderId).remove(sink));
        }
        subscribeTrader(traderId, sink);
        return emitter;
    }

    /** 包私有：测试挂收集器用 */
    void subscribeTrader(long traderId, Sink sink) {
        // 先挂上：空闲就等下一轮 run_start。在跑的每一轮进它的 Run 锁里回放，
        // 回放以 run_start 开头，订阅者据此把这一轮从头重建，回放之前零星收到的它的帧不作数
        subscribers(traderId).add(sink);
        runs.getOrDefault(traderId, Set.of()).forEach(run -> run.attachAndReplay(sink));
    }

    private Set<Sink> subscribers(long traderId) {
        return traderSubscribers.computeIfAbsent(traderId, _ -> ConcurrentHashMap.newKeySet());
    }

    /** trader 已删：摘掉这两份登记。已连着的 SSE 不主动掐，收不到新帧、30 分钟超时自己走 */
    public void forget(long traderId) {
        runs.remove(traderId);
        traderSubscribers.remove(traderId);
    }

    /** SseChannel 适配成 Sink：写失败 SseChannel 自己标关，下次就返回 false */
    private static Sink sinkOf(SseChannel channel) {
        return (event, data) -> {
            if (channel.isClosed()) {
                return false;
            }
            channel.send(event, data);
            return !channel.isClosed();
        };
    }

    /** emitter 生命周期：完成/超时/出错都标关、摘心跳、摘订阅 */
    private void attach(SseEmitter emitter, SseChannel channel, Runnable unsubscribe) {
        channels.add(channel);
        Runnable close = () -> {
            channel.markClosed();
            channels.remove(channel);
            unsubscribe.run();
        };
        emitter.onCompletion(close);
        emitter.onTimeout(() -> {
            close.run();
            emitter.complete();
        });
        emitter.onError(_ -> close.run());
    }
}
