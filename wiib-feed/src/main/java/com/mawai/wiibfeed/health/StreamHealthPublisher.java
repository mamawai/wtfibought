package com.mawai.wiibfeed.health;

import com.alibaba.fastjson2.JSON;
import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 流健康事件发布器：作为各 WsConnection 的状态变化回调，某条流连/断/重连时取全量快照发 Redis
 * （STREAM_HEALTH 频道 → sim 中继 → 前端）。纯事件驱动，无定时轮询。
 * <p>单线程序列化发布：状态回调来自多条连接的不同线程，若并发发布，旧快照可能后到把新状态盖成 stale。
 * 交单线程按序取快照+发布，保证发布顺序=状态变化顺序，最后一帧必是最新态。
 */
@Slf4j
@Component
public class StreamHealthPublisher {

    private final WsConnectionRegistry registry;
    private final MarketBroadcaster broadcaster;
    private final ExecutorService pub = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("stream-health-pub-", 0).factory());

    public StreamHealthPublisher(WsConnectionRegistry registry, MarketBroadcaster broadcaster) {
        this.registry = registry;
        this.broadcaster = broadcaster;
    }

    /** WsConnection 状态变化回调（Runnable）：不占用 WS/调度线程，交单线程取全量快照并发布。 */
    public void publish() {
        pub.execute(() -> {
            try {
                broadcaster.broadcastStreamHealth(JSON.toJSONString(registry.snapshot()));
            } catch (Exception e) {
                log.warn("[流健康] 推送失败: {}", e.getMessage());
            }
        });
    }
}
