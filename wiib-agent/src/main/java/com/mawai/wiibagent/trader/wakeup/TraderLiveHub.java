package com.mawai.wiibagent.trader.wakeup;

import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibagent.llm.SseChannel;
import com.mawai.wiibcommon.entity.AiTrader;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * trader 唤醒现场的发布订阅：订阅者管理、扇出、心跳。
 * 按 traderId 订阅，只有主人连得进来（准入在 controller 判）；一次唤醒一个 {@link Run} 句柄，runner 只碰它。
 * 单实例部署，进程内 Map 即可。不认识 langgraph。
 */
@Component
public class TraderLiveHub {

    /** 帧出口：返回 false=通道已死，hub 摘掉它 */
    public interface Sink {
        boolean send(String event, JSONObject data);
    }

    /** nginx 默认 proxy_read_timeout 60s 会掐静默连接，20s 一帧留 3 倍余量 */
    private static final long HEARTBEAT_SECONDS = 20;
    /** 订阅 30 分钟到点，前端自动重连 */
    private static final long SUBSCRIBE_TIMEOUT_MS = 30 * 60_000L;

    /** traderId → 在跑的一轮，end 后摘掉 */
    private final Map<Long, Run> runs = new ConcurrentHashMap<>();
    /** traderId → 订阅者；空闲时也挂着，等下一轮 run_start */
    private final Map<Long, Set<Sink>> traderSubscribers = new ConcurrentHashMap<>();
    /** 所有 SSE 通道，心跳用 */
    private final Set<SseChannel> channels = ConcurrentHashMap.newKeySet();
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

    private void heartbeat() {
        channels.removeIf(SseChannel::isClosed);
        channels.forEach(SseChannel::heartbeat);
    }

    // ========== 运行侧 ==========

    /** 一轮唤醒开始：建轨迹、登记在跑、扇出 run_start */
    public Run begin(AiTrader trader, String kind, long wakeTime, long budgetSeconds,
                     BigDecimal equity, int positions, int pendingOrders) {
        Run run = new Run(new WakeTrace(trader.getId(), kind, wakeTime, budgetSeconds, equity, positions, pendingOrders));
        runs.put(trader.getId(), run);
        run.started();
        return run;
    }

    /**
     * 一次唤醒的现场句柄。每个方法：轨迹变更拿帧 → 发该 trader 的订阅者。
     * 方法全同步：帧序与订阅者中途接入的回放互斥。
     */
    public final class Run {
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

        public synchronized void token(String text) {
            emit(() -> trace.token(text));
        }

        public synchronized void callEnd(String text, List<AssistantMessage.ToolCall> toolCalls) {
            emit(() -> trace.callEnd(text, toolCalls));
        }

        public synchronized void toolResult(String id, String name, String responseData) {
            emit(() -> trace.toolResult(id, name, responseData));
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
            runs.remove(trace.traderId, this);
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
            return !sink.send(frame.event(), frame.data());
        }
    }

    // ========== 订阅侧 ==========

    /** 现场流：登记后立刻回放在跑的一轮；空闲时什么都不发，只有心跳 */
    public SseEmitter subscribeTrader(long traderId) {
        SseEmitter emitter = new SseEmitter(SUBSCRIBE_TIMEOUT_MS);
        SseChannel channel = new SseChannel(emitter);
        Sink sink = sinkOf(channel);
        attach(emitter, channel, () -> subscribers(traderId).remove(sink));
        subscribeTrader(traderId, sink);
        return emitter;
    }

    /** 包私有：测试挂收集器用 */
    void subscribeTrader(long traderId, Sink sink) {
        Run run = runs.get(traderId);
        // 空闲：挂上等下一轮 run_start；在跑：进 Run 锁里登记 + 回放
        if (run == null) {
            subscribers(traderId).add(sink);
        } else {
            run.attachAndReplay(sink);
        }
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
